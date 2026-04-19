// src/main/java/com/selfhealing/llm/ClaudeClient.java
// ─────────────────────────────────────────────────────────────────────────────
// CLAUDE API CLIENT — Sends prompts to Claude and returns the LLM's response
//
// This is the LLM layer of the self-healing engine.
// It uses Java's built-in HttpClient (Java 11+) — no extra HTTP library needed.
//
// Flow:
//   1. Build a JSON request body (model, messages, max_tokens)
//   2. POST to https://api.anthropic.com/v1/messages
//   3. Parse the JSON response and extract the text content
//   4. Return the raw text (locator string or "HEALING_FAILED")
//
// API used: Anthropic Claude Messages API
// Docs: https://docs.anthropic.com/en/api/messages
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;   // Java 11 built-in HTTP client
import java.net.http.HttpRequest;  // Immutable HTTP request builder
import java.net.http.HttpResponse; // HTTP response with body
import java.time.Duration;

/**
 * ClaudeClient — Communicates with Anthropic's Claude API.
 *
 * Thread-safe: HttpClient and ObjectMapper are both thread-safe.
 * Can be shared across multiple simultaneous healing requests.
 */
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    // ── Claude API constants ──────────────────────────────────────────────
    private static final String API_URL   = "https://api.anthropic.com/v1/messages";
    // claude-3-5-haiku: fast and cheap — ideal for locator suggestions
    // Use claude-sonnet for higher accuracy (more expensive, slower)
    private static final String MODEL     = "claude-3-5-haiku-20241022";
    private static final int    MAX_TOKENS = 200; // Locators are short — 200 tokens is plenty
    private static final String API_VERSION = "2023-06-01"; // Required header by Anthropic

    // ── Instance fields ───────────────────────────────────────────────────
    private final String     apiKey;     // Anthropic API key (from env variable)
    private final HttpClient httpClient; // Java 11 built-in HTTP client
    private final ObjectMapper mapper;   // Jackson JSON serialiser/deserialiser

    // ── Constructor ───────────────────────────────────────────────────────
    public ClaudeClient(String apiKey) {
        this.apiKey = apiKey;

        // HttpClient.newBuilder() — creates a configured HTTP client
        // connectTimeout: fail fast if server is unreachable
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        // ObjectMapper — reuse instance (expensive to create)
        this.mapper = new ObjectMapper();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // suggestLocator() — Main method: asks Claude to suggest a healed locator
    //
    // @param systemPrompt  Defines Claude's role and rules (from PromptBuilder)
    // @param userPrompt    The specific healing request (broken locator + DOM)
    // @return              The locator string Claude suggests, or "HEALING_FAILED"
    // ─────────────────────────────────────────────────────────────────────────
    public String suggestLocator(String systemPrompt, String userPrompt) {
        try {
            // ── Step 1: Build the JSON request body ────────────────────────
            // Claude API expects:
            // {
            //   "model": "claude-3-5-haiku-20241022",
            //   "max_tokens": 200,
            //   "system": "...",
            //   "messages": [{"role": "user", "content": "..."}]
            // }
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", MODEL);
            requestBody.put("max_tokens", MAX_TOKENS);
            requestBody.put("system", systemPrompt); // System prompt goes at root level

            // messages array — user turn
            ArrayNode messages = mapper.createArrayNode();
            ObjectNode userMessage = mapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);
            requestBody.set("messages", messages);

            // Convert ObjectNode → JSON string
            String jsonBody = mapper.writeValueAsString(requestBody);
            log.debug("Sending request to Claude API ({} chars)", jsonBody.length());

            // ── Step 2: Build the HTTP POST request ────────────────────────
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(30))     // Response timeout (LLM can be slow)

                // Required headers for Anthropic API
                .header("Content-Type",     "application/json")
                .header("x-api-key",        apiKey)             // Auth header
                .header("anthropic-version", API_VERSION)       // API version header

                // POST body — BodyPublishers.ofString converts String to request body
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            // ── Step 3: Send the request and get the response ─────────────
            // send() is synchronous — blocks until response received
            // BodyHandlers.ofString() reads the response body as a String
            HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            // ── Step 4: Check HTTP status ─────────────────────────────────
            if (response.statusCode() != 200) {
                log.error("Claude API error {}: {}", response.statusCode(), response.body());
                return "HEALING_FAILED";
            }

            // ── Step 5: Parse the JSON response ───────────────────────────
            // Claude API response structure:
            // {
            //   "content": [
            //     { "type": "text", "text": "[data-test='login-btn']" }
            //   ],
            //   "usage": { "input_tokens": 150, "output_tokens": 12 }
            // }
            JsonNode responseJson = mapper.readTree(response.body());

            // Navigate: root → "content" array → first element → "text" field
            JsonNode contentArray = responseJson.get("content");
            if (contentArray == null || !contentArray.isArray() || contentArray.isEmpty()) {
                log.error("Unexpected Claude response structure: {}", response.body());
                return "HEALING_FAILED";
            }

            String suggestedLocator = contentArray.get(0).get("text").asText().trim();

            // Log token usage for cost monitoring
            JsonNode usage = responseJson.get("usage");
            if (usage != null) {
                log.info("Claude API usage — input: {} tokens, output: {} tokens",
                    usage.get("input_tokens").asInt(),
                    usage.get("output_tokens").asInt());
            }

            log.info("Claude suggested locator: '{}'", suggestedLocator);
            return suggestedLocator;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupted status
            log.error("Claude API call interrupted: {}", e.getMessage());
            return "HEALING_FAILED";
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage(), e);
            return "HEALING_FAILED";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // validateLocator() — Secondary check: confirm the suggested locator is correct
    //
    // Uses a yes/no prompt to verify the LLM's own suggestion.
    // Double-checking reduces false positives significantly.
    // ─────────────────────────────────────────────────────────────────────────
    public boolean validateLocator(String candidateLocator, String elementDescription, String domContext) {
        String validationPrompt = PromptBuilder.buildValidationPrompt(
            candidateLocator, elementDescription, domContext
        );

        // Use a simple yes/no system for validation
        String answer = suggestLocator(
            "Answer ONLY with YES or NO. Nothing else.",
            validationPrompt
        );

        return answer.trim().toUpperCase().startsWith("YES");
    }
}
