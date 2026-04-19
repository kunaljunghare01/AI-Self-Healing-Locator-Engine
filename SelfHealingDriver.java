// src/main/java/com/selfhealing/core/SelfHealingDriver.java
// ─────────────────────────────────────────────────────────────────────────────
// SELF-HEALING DRIVER — A Playwright Page wrapper with transparent auto-healing
//
// This is the PUBLIC API that test code uses.
// Instead of calling page.locator("...").click() directly,
// tests call driver.click("...", "description") and healing happens automatically.
//
// The test code NEVER changes. When locators break:
//   Before: test fails with "Element not found"
//   After:  test pauses, heals, retries, passes ✅
//
// Design Pattern: DECORATOR
//   SelfHealingDriver WRAPS the Playwright Page object.
//   All normal Playwright methods still work.
//   Healing methods intercept failures and delegate to LocatorHealer.
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.core;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.qameta.allure.Allure;
import io.qameta.allure.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SelfHealingDriver — Drop-in replacement for Playwright Page interactions.
 *
 * Usage in test classes:
 *   SelfHealingDriver driver = new SelfHealingDriver(page, apiKey, true);
 *   driver.click("[data-test='login-btn']", "Login submit button");
 *   driver.fill("[data-test='username']", "standard_user", "Username input field");
 */
public class SelfHealingDriver {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingDriver.class);

    private final Page          page;    // The real Playwright page
    private final LocatorHealer healer;  // The healing engine

    /**
     * @param page    Active Playwright browser page
     * @param apiKey  Anthropic API key for LLM healing
     * @param useMCP  true = MCP agentic mode (accurate), false = single-shot (fast)
     */
    public SelfHealingDriver(Page page, String apiKey, boolean useMCP) {
        this.page   = page;
        this.healer = new LocatorHealer(apiKey, useMCP);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // click() — Click an element; heal locator automatically if not found
    //
    // @param locator             CSS/XPath selector for the element
    // @param elementDescription  What this element does (used in healing prompt)
    // ─────────────────────────────────────────────────────────────────────────
    @Step("Click: {elementDescription}")
    public void click(String locator, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        log.info("Clicking '{}' using locator: '{}'", elementDescription, activeLocator);
        page.locator(activeLocator).click();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fill() — Type text into an input; heal if not found
    // ─────────────────────────────────────────────────────────────────────────
    @Step("Fill '{elementDescription}' with value")
    public void fill(String locator, String value, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        log.info("Filling '{}' using locator: '{}'", elementDescription, activeLocator);
        page.locator(activeLocator).fill(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getText() — Get text content; heal if not found
    // ─────────────────────────────────────────────────────────────────────────
    @Step("Get text from: {elementDescription}")
    public String getText(String locator, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        return page.locator(activeLocator).textContent();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // isVisible() — Check element visibility; heal if locator broken
    // ─────────────────────────────────────────────────────────────────────────
    @Step("Check visibility of: {elementDescription}")
    public boolean isVisible(String locator, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        return page.locator(activeLocator).isVisible();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // selectOption() — Select from dropdown; heal if not found
    // ─────────────────────────────────────────────────────────────────────────
    @Step("Select '{value}' from: {elementDescription}")
    public void selectOption(String locator, String value, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        page.locator(activeLocator).selectOption(value);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getLocator() — Returns the (possibly healed) Playwright Locator object
    // Use this when you need full Playwright Locator API access
    // ─────────────────────────────────────────────────────────────────────────
    public Locator getLocator(String locator, String elementDescription) {
        String activeLocator = resolveLocator(locator, elementDescription);
        return page.locator(activeLocator);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // navigate() — Navigate to a URL (no healing needed, direct delegation)
    // ─────────────────────────────────────────────────────────────────────────
    public void navigate(String url) {
        page.navigate(url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getPage() — Expose the underlying Playwright Page for direct access
    // Use sparingly — prefer driver methods for healable interactions
    // ─────────────────────────────────────────────────────────────────────────
    public Page getPage() {
        return page;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // resolveLocator() — CORE METHOD: the transparent healing interceptor
    //
    // This is what makes the driver "self-healing":
    //   1. Check if the locator works RIGHT NOW (fast, no LLM)
    //   2. If not → trigger healing (LocatorHealer)
    //   3. Return the working locator (original or healed)
    //
    // Called by every action method above (click, fill, getText, etc.)
    // ─────────────────────────────────────────────────────────────────────────
    private String resolveLocator(String locator, String elementDescription) {
        // Quick check: does the locator work right now?
        try {
            int count = page.locator(locator).count();
            if (count > 0 && page.locator(locator).first().isVisible()) {
                return locator; // Locator works — use it directly, no healing needed
            }
        } catch (Exception e) {
            log.debug("Initial locator check failed: {}", e.getMessage());
        }

        // Locator doesn't work → trigger self-healing
        log.warn("Locator broken! Triggering self-healing for: '{}'", locator);
        HealingResult result = healer.heal(page, locator, elementDescription);

        if (result.isSuccess()) {
            // Attach healing event to Allure report for visibility
            attachHealingToReport(result);
            return result.getHealedLocator();
        }

        // Healing failed — throw a descriptive exception
        throw new RuntimeException(
            String.format(
                "Element not found and self-healing FAILED.\n" +
                "  Broken locator    : '%s'\n" +
                "  Element description: '%s'\n" +
                "  Healing reason     : %s\n" +
                "  Page URL           : %s",
                locator, elementDescription,
                result.getReason(), page.url()
            )
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // attachHealingToReport() — Adds a note to the Allure report
    // Shows: which locator broke, what it was healed to, strategy used
    // ─────────────────────────────────────────────────────────────────────────
    private void attachHealingToReport(HealingResult result) {
        String report = String.format(
            "🔧 SELF-HEALING TRIGGERED\n" +
            "Original locator : %s\n" +
            "Healed locator   : %s\n" +
            "Strategy         : %s\n" +
            "Time taken       : %dms\n",
            result.getOriginalLocator(),
            result.getHealedLocator(),
            result.getStrategy(),
            result.getHealingTimeMs()
        );

        // Allure.addAttachment — attaches text to the current test step
        Allure.addAttachment("Self-Healing Event", report);
        log.info("Self-healing note attached to Allure report");
    }
}
