// src/main/java/com/selfhealing/utils/DOMExtractor.java
// ─────────────────────────────────────────────────────────────────────────────
// DOM EXTRACTOR — Extracts a focused, relevant slice of the live page HTML
//
// Why not send the entire page HTML to the LLM?
//   A real page can have 50,000+ characters of HTML.
//   Sending everything to the LLM is:
//     - Expensive (more tokens = more cost)
//     - Slow (larger payload = slower response)
//     - Noisy (nav bars, footers, scripts pollute the context)
//
//   Instead, we extract ONLY the semantically relevant section of the DOM
//   near the area where the broken element is expected to be.
//   This gives the LLM a clean, focused context to reason about.
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.utils;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DOMExtractor — Pulls focused HTML from the live browser page.
 * Static utility class — no instantiation needed.
 */
public class DOMExtractor {

    private static final Logger log = LoggerFactory.getLogger(DOMExtractor.class);

    // Maximum characters to send to LLM — prevents token limit issues
    private static final int MAX_DOM_LENGTH = 8000;

    private DOMExtractor() {} // Utility class — no instantiation

    // ─────────────────────────────────────────────────────────────────────────
    // extractRelevantDOM() — Main method: gets focused HTML around broken element
    //
    // Strategy:
    //   1. Try to find the PARENT container of the broken element (e.g., a form, section)
    //   2. Extract only that subtree — smaller but contextually complete
    //   3. Fall back to body HTML if no container found
    //   4. Truncate if still too large
    //
    // @param page            The live Playwright page object (active browser tab)
    // @param brokenLocator   The locator that failed (e.g. "[data-test='login-btn']")
    // @return                Cleaned, truncated HTML string for the LLM prompt
    // ─────────────────────────────────────────────────────────────────────────
    public static String extractRelevantDOM(Page page, String brokenLocator) {
        try {
            // ── Step 1: Execute JavaScript inside the browser ──────────────
            // page.evaluate() runs a JS function in the live browser context
            // and returns the result back to Java.
            // We pass brokenLocator as the argument to the JS function.
            String domContext = (String) page.evaluate(
                "([locator]) => {" +

                // ── Try to find the element using the broken locator ────────
                // If it still exists (maybe just changed slightly), use its parent
                "  let el = null;" +
                "  try {" +
                "    if (locator.startsWith('//') || locator.startsWith('(//')) {" +
                // XPath locator — use document.evaluate
                "      let result = document.evaluate(locator, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null);" +
                "      el = result.singleNodeValue;" +
                "    } else {" +
                // CSS locator — use querySelector
                "      el = document.querySelector(locator);" +
                "    }" +
                "  } catch(e) {}" +

                // ── Walk UP the DOM to find a meaningful parent container ───
                // Meaningful = a form, section, div with id/class, main, article
                // This gives us the "neighbourhood" of the broken element
                "  if (el) {" +
                "    let parent = el.parentElement;" +
                "    for (let i = 0; i < 4; i++) {" + // Walk up max 4 levels
                "      if (!parent) break;" +
                "      let tag = parent.tagName.toLowerCase();" +
                "      if (['form','section','main','article','[role]'].includes(tag)" +
                "          || parent.id || parent.className) {" +
                "        return parent.outerHTML;" + // Found a good container
                "      }" +
                "      parent = parent.parentElement;" +
                "    }" +
                "  }" +

                // ── Fallback: use the full <body> HTML ──────────────────────
                // If element not found at all (truly broken), send the full body
                // so LLM has the whole page context to search
                "  return document.body ? document.body.innerHTML : document.documentElement.outerHTML;" +
                "}",
                new Object[]{brokenLocator} // Pass locator as argument to JS function
            );

            // ── Step 2: Clean up the HTML ──────────────────────────────────
            String cleaned = cleanDom(domContext);

            // ── Step 3: Truncate if too long ───────────────────────────────
            if (cleaned.length() > MAX_DOM_LENGTH) {
                log.warn("DOM context truncated from {} to {} chars", cleaned.length(), MAX_DOM_LENGTH);
                cleaned = cleaned.substring(0, MAX_DOM_LENGTH) + "\n... [truncated]";
            }

            log.debug("Extracted DOM context ({} chars) for locator: {}", cleaned.length(), brokenLocator);
            return cleaned;

        } catch (Exception e) {
            log.error("Failed to extract DOM context: {}", e.getMessage());
            // Return a minimal fallback so the LLM can still attempt healing
            return "<body>DOM extraction failed: " + e.getMessage() + "</body>";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // cleanDom() — Removes noise from the HTML before sending to LLM
    //
    // We strip:
    //   - <script> tags (JavaScript code — useless for locator identification)
    //   - <style> tags (CSS rules — not needed)
    //   - HTML comments <!-- ... -->
    //   - SVG content (large, rarely relevant for locators)
    //   - Extra whitespace (compresses token count)
    // ─────────────────────────────────────────────────────────────────────────
    private static String cleanDom(String html) {
        if (html == null) return "";

        return html
            // Remove <script>...</script> blocks (including multiline)
            .replaceAll("(?s)<script[^>]*>.*?</script>", "")
            // Remove <style>...</style> blocks
            .replaceAll("(?s)<style[^>]*>.*?</style>", "")
            // Remove HTML comments
            .replaceAll("<!--.*?-->", "")
            // Remove <svg>...</svg> blocks (large and irrelevant)
            .replaceAll("(?s)<svg[^>]*>.*?</svg>", "<svg/>")
            // Collapse multiple spaces/newlines into single space
            .replaceAll("\\s{2,}", " ")
            // Trim leading/trailing whitespace
            .trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractPageTitle() — Gets the page <title> for additional LLM context
    // Helps the LLM understand WHICH page it's looking at
    // ─────────────────────────────────────────────────────────────────────────
    public static String extractPageTitle(Page page) {
        try {
            return (String) page.evaluate("() => document.title");
        } catch (Exception e) {
            return "Unknown Page";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // extractPageUrl() — Gets the current URL for context
    // ─────────────────────────────────────────────────────────────────────────
    public static String extractPageUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
