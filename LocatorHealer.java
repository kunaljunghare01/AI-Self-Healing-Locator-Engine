// src/main/java/com/selfhealing/core/LocatorHealer.java
// ─────────────────────────────────────────────────────────────────────────────
// LOCATOR HEALER — The core orchestrator of the self-healing engine
//
// This is the brain that coordinates ALL the components:
//   1. Check the cache first  (LocatorRepository)
//   2. Extract DOM context    (DOMExtractor)
//   3. Call Claude via MCP    (MCPServer — agentic, multi-tool)
//     OR direct Claude API    (ClaudeClient — single-shot, faster)
//   4. Validate the result    (Playwright — does it actually find an element?)
//   5. Save to cache          (LocatorRepository)
//   6. Return HealingResult   (success/failed/skipped)
//
// The healing is TRANSPARENT to the test code.
// Tests call: healer.findElement(page, "[data-test='old-btn']", "Login button")
// If the locator works → return it immediately
// If it fails → heal it automatically → return the fixed locator
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.core;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.selfhealing.llm.ClaudeClient;
import com.selfhealing.llm.PromptBuilder;
import com.selfhealing.mcp.MCPServer;
import com.selfhealing.storage.LocatorRepository;
import com.selfhealing.utils.DOMExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LocatorHealer — The main entry point for the self-healing engine.
 *
 * Usage from test code:
 *   LocatorHealer healer = new LocatorHealer(apiKey, useMCP: true);
 *   HealingResult result = healer.heal(page, "[data-test='old-btn']", "Login button");
 *   if (result.isSuccess()) {
 *       page.locator(result.getHealedLocator()).click();
 *   }
 */
public class LocatorHealer {

    private static final Logger log = LoggerFactory.getLogger(LocatorHealer.class);

    private final ClaudeClient       claudeClient;
    private final LocatorRepository  repository;
    private final boolean            useMCP;  // true = MCP agentic mode, false = single-shot

    /**
     * Constructor
     * @param apiKey  Anthropic API key (set via CLAUDE_API_KEY env variable)
     * @param useMCP  true = use MCP agentic loop (more accurate, slower)
     *                false = use single-shot Claude call (faster, less accurate)
     */
    public LocatorHealer(String apiKey, boolean useMCP) {
        this.claudeClient = new ClaudeClient(apiKey);
        this.repository   = LocatorRepository.getInstance();
        this.useMCP       = useMCP;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // heal() — Main method: attempts to heal a broken locator
    //
    // Full healing pipeline:
    //   STEP 1 → Check Playwright: does the locator still work? (avoid LLM if possible)
    //   STEP 2 → Check cache: was this locator healed before? (avoid LLM if cached)
    //   STEP 3 → Heal: call LLM (MCP or single-shot)
    //   STEP 4 → Validate: does the healed locator find an element?
    //   STEP 5 → Save to cache
    //
    // @param page               Live Playwright browser page
    // @param brokenLocator      The CSS/XPath locator that threw an exception
    // @param elementDescription What the element does (e.g. "Login submit button")
    // @return                   HealingResult with status + healed locator
    // ─────────────────────────────────────────────────────────────────────────
    public HealingResult heal(Page page, String brokenLocator, String elementDescription) {
        long startTime = System.currentTimeMillis();

        log.info("=== Starting self-healing for: '{}' ===", brokenLocator);

        // ── STEP 1: Quick check — does the locator still work? ─────────────
        // UI changes often only move elements slightly.
        // The locator might still resolve if we just wait a moment.
        if (isLocatorWorking(page, brokenLocator)) {
            log.info("Locator '{}' is actually still working — no healing needed", brokenLocator);
            return HealingResult.skipped(brokenLocator, "Locator is still working");
        }

        // ── STEP 2: Cache check ────────────────────────────────────────────
        // Was this exact locator healed in a previous run?
        String cachedHealed = repository.getCachedLocator(brokenLocator);
        if (cachedHealed != null) {
            // Verify the cached locator still works (the UI might have changed again)
            if (isLocatorWorking(page, cachedHealed)) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Used cached healed locator: '{}' ({}ms)", cachedHealed, elapsed);
                return HealingResult.success(brokenLocator, cachedHealed, "cache", elapsed);
            } else {
                // Cached locator is also broken — need fresh LLM healing
                log.warn("Cached locator '{}' is also broken — re-healing", cachedHealed);
            }
        }

        // ── STEP 3: LLM Healing ────────────────────────────────────────────
        String healedLocator;

        if (useMCP) {
            // MCP AGENTIC MODE:
            // Claude calls tools iteratively: get_dom → validate_locator → report_healed
            // More accurate because Claude can verify its own suggestions
            log.info("Using MCP agentic mode for healing...");
            MCPServer mcpServer = new MCPServer(getApiKey(), page);
            healedLocator = mcpServer.runHealingSession(brokenLocator, elementDescription);
        } else {
            // SINGLE-SHOT MODE:
            // One prompt with DOM context → one response with locator
            // Faster but less accurate (no self-verification)
            log.info("Using single-shot Claude mode for healing...");
            String domContext  = DOMExtractor.extractRelevantDOM(page, brokenLocator);
            String pageUrl     = DOMExtractor.extractPageUrl(page);
            String pageTitle   = DOMExtractor.extractPageTitle(page);

            String userPrompt  = PromptBuilder.buildHealingPrompt(
                brokenLocator, elementDescription, domContext, pageUrl, pageTitle
            );
            healedLocator = claudeClient.suggestLocator(
                PromptBuilder.buildSystemPrompt(), userPrompt
            );
        }

        // ── STEP 4: Validate the LLM's suggestion ─────────────────────────
        // "HEALING_FAILED" is our sentinel string returned by ClaudeClient/MCPServer
        if ("HEALING_FAILED".equals(healedLocator) || healedLocator == null) {
            log.error("Healing FAILED for '{}' — LLM could not find replacement", brokenLocator);
            return HealingResult.failed(brokenLocator,
                "LLM could not find a replacement element in the DOM");
        }

        // Try the suggested locator in the actual browser
        if (!isLocatorWorking(page, healedLocator)) {
            log.error("LLM suggested '{}' but it doesn't work in browser", healedLocator);
            return HealingResult.failed(brokenLocator,
                "LLM suggestion '" + healedLocator + "' failed Playwright validation");
        }

        // ── STEP 5: Save to cache ──────────────────────────────────────────
        repository.saveHealedLocator(
            brokenLocator, healedLocator, elementDescription,
            DOMExtractor.extractPageUrl(page)
        );

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("✅ Healing SUCCESS: '{}' → '{}' in {}ms", brokenLocator, healedLocator, elapsed);

        return HealingResult.success(
            brokenLocator, healedLocator,
            useMCP ? "llm-mcp" : "llm-claude",
            elapsed
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isLocatorWorking() — Checks if a locator resolves to at least one element
    //
    // Uses Playwright's count() method — returns 0 if not found, no exception.
    // We also check isVisible() to ensure the element is actually interactable.
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isLocatorWorking(Page page, String locator) {
        try {
            // locator().count() — returns the number of matching elements
            // Does NOT throw if nothing is found (unlike waitForSelector)
            int count = page.locator(locator).count();
            if (count == 0) return false;

            // Also verify at least one match is visible (not hidden in DOM)
            return page.locator(locator).first().isVisible();
        } catch (Exception e) {
            // Invalid locator syntax, or page navigated away
            log.debug("Locator check exception for '{}': {}", locator, e.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getApiKey() — Reads the API key from environment variable
    // Never hardcode API keys in source code — always use env vars
    // ─────────────────────────────────────────────────────────────────────────
    private String getApiKey() {
        String key = System.getenv("CLAUDE_API_KEY");
        if (key == null || key.isEmpty()) {
            // Try JVM system property as fallback (-DCLAUDE_API_KEY=sk-...)
            key = System.getProperty("CLAUDE_API_KEY", "");
        }
        if (key.isEmpty()) {
            throw new IllegalStateException(
                "CLAUDE_API_KEY environment variable is not set. " +
                "Set it with: export CLAUDE_API_KEY=sk-ant-..."
            );
        }
        return key;
    }
}
