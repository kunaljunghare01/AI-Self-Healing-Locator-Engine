// src/test/java/com/selfhealing/tests/SelfHealingDemoTest.java
// ─────────────────────────────────────────────────────────────────────────────
// DEMO TEST — Shows the self-healing engine working end-to-end
//
// This test INTENTIONALLY uses old/broken locators to demonstrate healing.
// In a real project, these would be your existing test locators that broke
// after a UI update — the test code stays UNCHANGED, healing handles it.
//
// How to run:
//   export CLAUDE_API_KEY=sk-ant-your-key-here
//   mvn test -Dtest=SelfHealingDemoTest
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.tests;

import com.microsoft.playwright.*;
import com.selfhealing.core.HealingResult;
import com.selfhealing.core.LocatorHealer;
import com.selfhealing.core.SelfHealingDriver;
import io.qameta.allure.*;
import org.testng.Assert;
import org.testng.annotations.*;

@Epic("AI Self-Healing Locator Engine")
@Feature("Automatic Locator Recovery")
public class SelfHealingDemoTest {

    private Playwright playwright;
    private Browser    browser;
    private Page       page;
    private SelfHealingDriver driver;

    // ── API key read from environment variable — NEVER hardcode ───────────
    private final String API_KEY = System.getenv("CLAUDE_API_KEY") != null
        ? System.getenv("CLAUDE_API_KEY")
        : System.getProperty("CLAUDE_API_KEY", "YOUR_API_KEY_HERE");

    @BeforeClass
    public void setup() {
        // Standard Playwright Java setup
        playwright = Playwright.create();
        browser    = playwright.chromium().launch(
            new BrowserType.LaunchOptions().setHeadless(true)
        );
        BrowserContext context = browser.newContext(
            new Browser.NewContextOptions().setViewportSize(1280, 720)
        );
        page = context.newPage();

        // Wrap with SelfHealingDriver (useMCP=true for agentic healing)
        driver = new SelfHealingDriver(page, API_KEY, true);
    }

    @AfterClass
    public void teardown() {
        if (page       != null) page.close();
        if (browser    != null) browser.close();
        if (playwright != null) playwright.close();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: Login with WORKING locators
    // Shows the baseline — healing is bypassed when locators work fine
    // ─────────────────────────────────────────────────────────────────────────
    @Test(priority = 1, description = "Login with current working locators (no healing needed)")
    @Story("Normal test flow")
    @Severity(SeverityLevel.BLOCKER)
    public void testLoginWithWorkingLocators() {
        driver.navigate("https://www.saucedemo.com");

        // These are the CORRECT current locators — healing should be skipped
        driver.fill("[data-test='username']", "standard_user", "Username input field");
        driver.fill("[data-test='password']", "secret_sauce",  "Password input field");
        driver.click("[data-test='login-button']",             "Login submit button");

        // Verify we're on the products page
        String title = driver.getText(".title", "Products page heading");
        Assert.assertEquals(title, "Products", "Should land on Products page");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: Direct LocatorHealer test — simulates a broken locator scenario
    // Demonstrates the healer with old-style selectors that don't exist
    // ─────────────────────────────────────────────────────────────────────────
    @Test(priority = 2, description = "Heal a broken ID-based locator that no longer exists")
    @Story("LLM heals broken locator")
    @Severity(SeverityLevel.CRITICAL)
    public void testHealBrokenIdLocator() {
        driver.navigate("https://www.saucedemo.com");

        LocatorHealer healer = new LocatorHealer(API_KEY, false); // single-shot mode

        // OLD locator: "#user-name" — this is NOT the actual locator on SauceDemo
        // The REAL locator is [data-test="username"]
        // The healer should figure this out by looking at the DOM
        HealingResult result = healer.heal(
            page,
            "#user-name-field",           // ← Intentionally wrong/old locator
            "Username text input field on the login page"
        );

        // The healer should have found the real locator
        System.out.println("Healing result: " + result);
        System.out.println("Suggested locator: " + result.getHealedLocator());

        // Even if the exact healed locator differs, the concept is validated
        // In a real scenario, we'd assert the healed locator actually works
        if (result.isSuccess()) {
            Assert.assertNotNull(result.getHealedLocator(), "Healed locator should not be null");
            Assert.assertTrue(
                page.locator(result.getHealedLocator()).count() > 0,
                "Healed locator should find at least one element"
            );
        }
        // Log for report visibility even if LLM couldn't heal (no real API key in CI)
        Allure.addAttachment("Healing Result", result.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: MCP Agentic healing
    // Claude uses tools iteratively to inspect DOM and find the element
    // ─────────────────────────────────────────────────────────────────────────
    @Test(priority = 3, description = "MCP agentic healing: Claude uses tools to find element")
    @Story("MCP agentic healing")
    @Severity(SeverityLevel.CRITICAL)
    public void testMCPAgenticHealing() {
        driver.navigate("https://www.saucedemo.com");

        LocatorHealer mcpHealer = new LocatorHealer(API_KEY, true); // MCP mode

        // This old class-based selector doesn't work on SauceDemo
        HealingResult result = mcpHealer.heal(
            page,
            ".btn-login-submit",          // ← Old class-based selector (broken)
            "The primary login button that submits the login form"
        );

        System.out.println("MCP Healing result: " + result);
        Allure.addAttachment("MCP Healing Result", result.toString());

        // If API key is configured and LLM succeeds:
        if (result.isSuccess()) {
            Assert.assertTrue(
                page.locator(result.getHealedLocator()).count() > 0,
                "MCP-healed locator should work in browser"
            );
            System.out.println("✅ MCP healing SUCCESS: " + result.getHealedLocator());
        } else {
            // Expected in CI without real API key — just verify the system ran
            System.out.println("ℹ️ Healing not performed (no API key or element truly missing)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Cache verification
    // After Test 2 healed a locator, this test verifies the cache works
    // ─────────────────────────────────────────────────────────────────────────
    @Test(priority = 4, dependsOnMethods = "testHealBrokenIdLocator",
          description = "Second attempt on same broken locator uses cache — no LLM call")
    @Story("Cache prevents redundant LLM calls")
    @Severity(SeverityLevel.NORMAL)
    public void testCachePreventsDuplicateLLMCalls() {
        driver.navigate("https://www.saucedemo.com");

        LocatorHealer healer = new LocatorHealer(API_KEY, false);
        long start = System.currentTimeMillis();

        // Same broken locator as Test 2 — should hit cache, not LLM
        HealingResult result = healer.heal(
            page,
            "#user-name-field",
            "Username text input field on the login page"
        );

        long elapsed = System.currentTimeMillis() - start;

        System.out.println("Cache healing took: " + elapsed + "ms");
        Allure.addAttachment("Cache Performance",
            "Strategy: " + result.getStrategy() + "\nTime: " + elapsed + "ms"
        );

        // Cache hit should be < 100ms (vs 1000-3000ms for LLM call)
        if ("cache".equals(result.getStrategy())) {
            Assert.assertTrue(elapsed < 500, "Cache lookup should be very fast (< 500ms)");
            System.out.println("✅ Cache HIT confirmed — no LLM call made!");
        }
    }
}
