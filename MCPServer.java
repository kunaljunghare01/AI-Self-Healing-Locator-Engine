// src/main/java/com/selfhealing/mcp/MCPServer.java
// ─────────────────────────────────────────────────────────────────────────────
// MCP SERVER — Executes the tool calls that Claude requests
//
// MCP Flow (agentic loop):
//
//   1. We send Claude: system prompt + user message + list of available tools
//   2. Claude responds with a tool_use block (e.g. "call get_dom_context")
//   3. We EXECUTE the tool (e.g. extract DOM from Playwright page)
//   4. We send the tool RESULT back to Claude
//   5. Claude may call another tool, or call report_healed() with the answer
//   6. We repeat until Claude calls report_healed() — the loop ends
//
// This is called an "agentic loop" — Claude drives the investigation itself.
// It's more powerful than a single-shot prompt because:
//   - Claude can inspect the DOM, validate a candidate, and iterate
//   - Like a real QA engineer: look → try → verify → report
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.selfhealing.utils.DOMExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * MCPServer — Runs the MCP agentic tool-use loop with Claude.
 *
 * This is the most sophisticated part of the self-healing engine.
 * Claude decides WHICH tools to call and in WHAT ORDER.
 * We just execute them and return the results.
 */
public class MCPServer {

    private static final Logger log = LoggerFactory.getLogger(MCPServer.class);

    private static final String API_URL     = "https://api.anthropic.com/v1/messages";
    private static final String MODEL       = "claude-3-5-sonnet-20241022"; // Sonnet for MCP — better reasoning
    private static final String API_VERSION = "2023-06-01";
    private static final int    MAX_TOKENS  = 1024;
    private static final int    MAX_LOOPS   = 5; // Safety limit: max 5 tool calls per healing session

    private final String      apiKey;
    private final Page        page;          // Live Playwright browser page
    private final ObjectMapper mapper;
    private final HttpClient  httpClient;

    public MCPServer(String apiKey, Page page) {
        this.apiKey     = apiKey;
        this.page       = page;
        this.mapper     = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // runHealingSession() — Entry point for an MCP-driven healing session
    //
    // @param brokenLocator      The locator that failed
    // @param elementDescription What the element does (from test annotation)
    // @return                   The healed locator, or "HEALING_FAILED"
    // ─────────────────────────────────────────────────────────────────────────
    public String runHealingSession(String brokenLocator, String elementDescription) {
        log.info("Starting MCP healing session for: '{}'", brokenLocator);

        try {
            // ── Build the initial messages array ──────────────────────────
            // The conversation starts with the user's healing request
            ArrayNode messages = mapper.createArrayNode();

            ObjectNode userMessage = mapper.createObjectNode();
            userMessage.put("role", "user");
            userMessage.put("content",
                "A Playwright test locator has stopped working after a UI change.\n\n" +
                "BROKEN LOCATOR: " + brokenLocator + "\n" +
                "ELEMENT DESCRIPTION: " + elementDescription + "\n" +
                "PAGE URL: " + DOMExtractor.extractPageUrl(page) + "\n\n" +
                "Please use the available tools to:\n" +
                "1. Get the current DOM to inspect the page\n" +
                "2. Find the element using the description\n" +
                "3. Validate your candidate locator\n" +
                "4. Report the healed locator using report_healed tool\n\n" +
                "If the element is completely gone, call report_healed with HEALING_FAILED."
            );
            messages.add(userMessage);

            // ── Get tool schemas ───────────────────────────────────────────
            List<ObjectNode> tools = MCPTools.getAllTools();
            ArrayNode toolsArray = mapper.createArrayNode();
            tools.forEach(toolsArray::add);

            // ── Agentic loop ───────────────────────────────────────────────
            for (int iteration = 0; iteration < MAX_LOOPS; iteration++) {
                log.debug("MCP loop iteration {}", iteration + 1);

                // ── Call Claude with current messages + tools ──────────────
                JsonNode response = callClaudeWithTools(messages, toolsArray);
                if (response == null) return "HEALING_FAILED";

                // ── Add Claude's response to the conversation history ──────
                ObjectNode assistantMsg = mapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.set("content", response.get("content"));
                messages.add(assistantMsg);

                // ── Process each content block in the response ─────────────
                String healedLocator = processResponseContent(
                    response.get("content"), messages
                );

                // If Claude called report_healed, we're done
                if (healedLocator != null) {
                    return healedLocator;
                }

                // Check stop_reason: if "end_turn" without tool call, something went wrong
                String stopReason = response.get("stop_reason").asText();
                if ("end_turn".equals(stopReason)) {
                    log.warn("Claude stopped without calling report_healed");
                    break;
                }
            }

        } catch (Exception e) {
            log.error("MCP healing session failed: {}", e.getMessage(), e);
        }

        return "HEALING_FAILED";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // processResponseContent() — Handles Claude's response content blocks
    //
    // Claude can return:
    //   - "text" blocks: Claude's reasoning (we log but don't act on)
    //   - "tool_use" blocks: Claude wants to call a tool
    //
    // Returns: healed locator string if report_healed was called, else null
    // ─────────────────────────────────────────────────────────────────────────
    private String processResponseContent(JsonNode contentBlocks, ArrayNode messages) throws Exception {
        if (contentBlocks == null || !contentBlocks.isArray()) return null;

        // Collect all tool results to send back in one message
        ArrayNode toolResults = mapper.createArrayNode();
        String healedLocator = null;

        for (JsonNode block : contentBlocks) {
            String blockType = block.get("type").asText();

            if ("text".equals(blockType)) {
                // Claude is thinking out loud — log it for debugging
                log.debug("Claude reasoning: {}", block.get("text").asText());

            } else if ("tool_use".equals(blockType)) {
                // Claude wants to call a tool
                String toolName = block.get("name").asText();
                String toolUseId = block.get("id").asText();
                JsonNode input = block.get("input");

                log.info("Claude calling tool: '{}' with input: {}", toolName, input);

                // ── Execute the requested tool ─────────────────────────────
                String toolResult;

                if (MCPTools.REPORT_HEALED.equals(toolName)) {
                    // Special case: report_healed = Claude is done
                    healedLocator = input.get("healed_locator").asText();
                    String confidence = input.has("confidence") ? input.get("confidence").asText() : "UNKNOWN";
                    log.info("MCP healing complete! Locator: '{}' | Confidence: {}", healedLocator, confidence);
                    toolResult = "Locator recorded successfully.";

                } else {
                    // Execute one of the inspection tools
                    toolResult = executeTool(toolName, input);
                }

                // ── Build the tool_result block ────────────────────────────
                // This is what we send back to Claude so it knows what the tool returned
                ObjectNode resultBlock = mapper.createObjectNode();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", toolUseId);  // Must match the tool_use id
                resultBlock.put("content", toolResult);
                toolResults.add(resultBlock);
            }
        }

        // ── Send all tool results back to Claude ───────────────────────────
        if (!toolResults.isEmpty()) {
            ObjectNode toolResultMessage = mapper.createObjectNode();
            toolResultMessage.put("role", "user");
            toolResultMessage.set("content", toolResults);
            messages.add(toolResultMessage);
        }

        return healedLocator; // null if not done yet, locator string if done
    }

    // ─────────────────────────────────────────────────────────────────────────
    // executeTool() — Runs the actual tool implementation
    // Each tool maps to real Playwright/DOM operations
    // ─────────────────────────────────────────────────────────────────────────
    private String executeTool(String toolName, JsonNode input) {
        try {
            switch (toolName) {

                case MCPTools.GET_DOM_CONTEXT: {
                    // Get live DOM from Playwright page
                    String sectionSelector = input.has("section_selector")
                        ? input.get("section_selector").asText() : null;

                    String dom;
                    if (sectionSelector != null && !sectionSelector.isEmpty()) {
                        // Extract only the specified section (more focused)
                        dom = (String) page.evaluate(
                            "([sel]) => { const el = document.querySelector(sel); " +
                            "return el ? el.outerHTML : 'Section not found: ' + sel; }",
                            new Object[]{sectionSelector}
                        );
                    } else {
                        // Full body extraction
                        dom = DOMExtractor.extractRelevantDOM(page, "");
                    }
                    return dom;
                }

                case MCPTools.VALIDATE_LOCATOR: {
                    // Check if the given locator finds an element
                    String locator = input.get("locator").asText();
                    try {
                        // count() returns how many elements match — does NOT throw
                        int count = page.locator(locator).count();
                        if (count == 0) {
                            return "RESULT: 0 elements found. This locator does not match anything.";
                        }
                        // Get the outer HTML of the first match
                        String outerHtml = (String) page.locator(locator).first().evaluate("el => el.outerHTML");
                        return String.format(
                            "RESULT: %d element(s) found.\nFirst match:\n%s",
                            count, outerHtml
                        );
                    } catch (Exception e) {
                        return "RESULT: Invalid locator syntax — " + e.getMessage();
                    }
                }

                case MCPTools.FIND_BY_DESCRIPTION: {
                    // Search DOM for elements semantically matching the description
                    String description = input.get("description").asText().toLowerCase();
                    String elementType = input.has("element_type")
                        ? input.get("element_type").asText() : "";

                    // Use JavaScript to search by text content, aria-label, placeholder, title
                    String results = (String) page.evaluate(
                        "([desc, elType]) => {" +
                        "  const tag = elType || '*';" +
                        "  const els = Array.from(document.querySelectorAll(tag));" +
                        "  const matches = els.filter(el => {" +
                        "    const text = (el.textContent || '').toLowerCase();" +
                        "    const aria = (el.getAttribute('aria-label') || '').toLowerCase();" +
                        "    const ph   = (el.getAttribute('placeholder') || '').toLowerCase();" +
                        "    const title= (el.getAttribute('title') || '').toLowerCase();" +
                        "    return text.includes(desc) || aria.includes(desc) " +
                        "           || ph.includes(desc) || title.includes(desc);" +
                        "  }).slice(0, 5);" + // Return max 5 matches
                        "  return matches.map(el => ({" +
                        "    tag: el.tagName.toLowerCase()," +
                        "    id: el.id," +
                        "    classes: el.className," +
                        "    'data-test': el.getAttribute('data-test')," +
                        "    'aria-label': el.getAttribute('aria-label')," +
                        "    text: el.textContent.trim().substring(0, 50)," +
                        "    outerHTML: el.outerHTML.substring(0, 200)" +
                        "  }));" +
                        "}",
                        new Object[]{description, elementType}
                    );
                    return results != null ? results.toString() : "No elements found matching: " + description;
                }

                default:
                    return "Unknown tool: " + toolName;
            }
        } catch (Exception e) {
            log.error("Tool '{}' execution failed: {}", toolName, e.getMessage());
            return "Tool execution error: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // callClaudeWithTools() — Makes the actual API call with tool_choice
    // ─────────────────────────────────────────────────────────────────────────
    private JsonNode callClaudeWithTools(ArrayNode messages, ArrayNode tools) {
        try {
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", MODEL);
            requestBody.put("max_tokens", MAX_TOKENS);
            requestBody.put("system",
                "You are a test automation expert. Your job is to find a working " +
                "replacement for a broken Playwright locator. Use the tools to " +
                "inspect the live page, validate candidates, and report the best locator. " +
                "Prefer data-test > aria-label > id > CSS. Avoid positional selectors."
            );
            requestBody.set("messages", messages);
            requestBody.set("tools", tools);

            // tool_choice: "auto" = Claude decides when to call tools
            ObjectNode toolChoice = mapper.createObjectNode();
            toolChoice.put("type", "auto");
            requestBody.set("tool_choice", toolChoice);

            String jsonBody = mapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(60)) // MCP sessions can take longer
                .header("Content-Type",      "application/json")
                .header("x-api-key",         apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Claude API error {}: {}", response.statusCode(), response.body());
                return null;
            }

            return mapper.readTree(response.body());

        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage(), e);
            return null;
        }
    }
}
