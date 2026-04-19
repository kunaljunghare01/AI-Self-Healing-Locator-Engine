// src/main/java/com/selfhealing/llm/PromptBuilder.java
// ─────────────────────────────────────────────────────────────────────────────
// PROMPT BUILDER — Constructs the exact prompts sent to the LLM (Claude)
//
// Why a dedicated PromptBuilder?
//   The quality of the LLM's response depends entirely on prompt quality.
//   A poorly worded prompt → vague or wrong locator suggestions.
//   A well-structured prompt → precise, working locator with reasoning.
//
//   Separating prompt construction from the API client means:
//   - Prompts can be tuned/improved without touching API logic
//   - Prompts are testable (you can print and inspect them)
//   - Different strategies can have different prompt templates
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.llm;

/**
 * PromptBuilder — All prompt templates for locator healing.
 * Static utility — no instantiation needed.
 */
public class PromptBuilder {

    private PromptBuilder() {}

    // ─────────────────────────────────────────────────────────────────────────
    // buildSystemPrompt() — The SYSTEM message sent to Claude
    //
    // The system prompt defines Claude's ROLE and RULES.
    // It's sent once per conversation and persists across turns.
    //
    // Key rules we enforce:
    //   1. Return ONLY the locator — no explanation wrapped around it
    //   2. Prefer data-test > aria-label > id > CSS > XPath (stability order)
    //   3. Avoid brittle selectors (nth-child, long XPath chains)
    //   4. If nothing found, return exactly: HEALING_FAILED
    // ─────────────────────────────────────────────────────────────────────────
    public static String buildSystemPrompt() {
        return "You are an expert test automation engineer specialising in web element locators.\n\n" +

               "Your job:\n" +
               "Given a BROKEN locator that no longer finds its element on the page, " +
               "and the current page HTML, find the BEST replacement locator.\n\n" +

               "LOCATOR PRIORITY ORDER (use the highest available):\n" +
               "1. data-test or data-testid attribute  → e.g. [data-test='login-btn']\n" +
               "2. aria-label attribute                → e.g. [aria-label='Submit']\n" +
               "3. Unique id attribute                 → e.g. #login-button\n" +
               "4. Stable CSS class + tag combination  → e.g. button.login-btn\n" +
               "5. text content (only if unique)       → e.g. text=Sign In\n" +
               "6. XPath (last resort only)            → e.g. //button[@type='submit']\n\n" +

               "RULES:\n" +
               "- Return ONLY the locator string. No explanation. No markdown. No quotes.\n" +
               "- The locator must uniquely identify one element.\n" +
               "- Never use nth-child(), nth-of-type(), or positional XPath like [1], [2].\n" +
               "- Never use auto-generated class names (e.g. sc-abc123, css-xyz).\n" +
               "- If the element is clearly absent from the DOM, return exactly: HEALING_FAILED\n\n" +

               "EXAMPLES OF GOOD OUTPUT:\n" +
               "[data-test='username-input']\n" +
               "#submit-button\n" +
               "button[aria-label='Close dialog']\n\n" +

               "EXAMPLES OF BAD OUTPUT (never do this):\n" +
               "Here is the locator: [data-test='btn']   ← Don't add prose\n" +
               "div:nth-child(3) > button                ← Positional, fragile\n" +
               ".sc-1a2b3c4d                             ← Auto-generated class\n";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildHealingPrompt() — The USER message containing the specific broken case
    //
    // We provide full context:
    //   - The page being tested (URL + title)
    //   - The ORIGINAL locator that USED TO work
    //   - The ELEMENT DESCRIPTION (what the element does — from test code)
    //   - The CURRENT DOM HTML (what the page looks like NOW)
    //
    // @param brokenLocator      The CSS/XPath/text locator that stopped working
    // @param elementDescription Human description of the element (e.g. "Login button")
    // @param domContext         The cleaned, relevant HTML from DOMExtractor
    // @param pageUrl            Current page URL for context
    // @param pageTitle          Current page title for context
    // ─────────────────────────────────────────────────────────────────────────
    public static String buildHealingPrompt(
            String brokenLocator,
            String elementDescription,
            String domContext,
            String pageUrl,
            String pageTitle) {

        // StringBuilder is more efficient than String + for multi-line construction
        StringBuilder prompt = new StringBuilder();

        prompt.append("=== LOCATOR HEALING REQUEST ===\n\n");

        // ── Context: which page are we on? ────────────────────────────────
        prompt.append("PAGE CONTEXT:\n");
        prompt.append("  URL   : ").append(pageUrl).append("\n");
        prompt.append("  Title : ").append(pageTitle).append("\n\n");

        // ── The broken locator ────────────────────────────────────────────
        prompt.append("BROKEN LOCATOR (no longer works):\n");
        prompt.append("  ").append(brokenLocator).append("\n\n");

        // ── What the element is supposed to be ───────────────────────────
        // This is crucial — it tells the LLM WHAT to look for, not just what
        // the old selector was. Even if the ID changed, the element's purpose didn't.
        prompt.append("ELEMENT DESCRIPTION (what this element does):\n");
        prompt.append("  ").append(elementDescription).append("\n\n");

        // ── The live DOM ──────────────────────────────────────────────────
        prompt.append("CURRENT PAGE HTML (find the element here):\n");
        prompt.append("```html\n");
        prompt.append(domContext);
        prompt.append("\n```\n\n");

        // ── The ask ───────────────────────────────────────────────────────
        prompt.append("Provide the best replacement locator for this element.\n");
        prompt.append("Return ONLY the locator string (no explanation).\n");
        prompt.append("If not found, return: HEALING_FAILED");

        return prompt.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildValidationPrompt() — Asks LLM to CONFIRM a candidate locator
    //
    // After healing, we run a second LLM call to cross-verify:
    // "Does this healed locator actually find the right element?"
    // This double-check reduces false positives.
    // ─────────────────────────────────────────────────────────────────────────
    public static String buildValidationPrompt(String candidateLocator, String elementDescription, String domContext) {
        return "Does this locator: `" + candidateLocator + "`\n" +
               "correctly identify an element that is: " + elementDescription + "\n" +
               "in this HTML:\n```html\n" + domContext + "\n```\n\n" +
               "Answer with only: YES or NO";
    }
}
