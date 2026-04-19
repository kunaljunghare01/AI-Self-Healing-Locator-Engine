// src/main/java/com/selfhealing/core/HealingResult.java
// ─────────────────────────────────────────────────────────────────────────────
// HEALING RESULT — Value object returned after every locator healing attempt.
//
// Why a dedicated result class?
//   When the LLM tries to heal a broken locator, many things can happen:
//   SUCCESS  → LLM found a valid replacement, test can continue
//   FAILED   → LLM could not find a match, test should fail gracefully
//   SKIPPED  → Healing was disabled, or a cached result was already available
//
//   Wrapping all outcomes in one object makes the caller's logic clean:
//     HealingResult result = healer.heal(brokenLocator, page);
//     if (result.isSuccess()) { use result.getHealedLocator(); }
//     else { log result.getReason(); fail the test; }
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.core;

/**
 * Immutable value object — all fields set at construction, no setters.
 * Immutability prevents accidental mutation after healing completes.
 */
public class HealingResult {

    // ── Enum: possible outcomes of a healing attempt ──────────────────────
    public enum Status {
        SUCCESS,   // LLM found and validated a working replacement locator
        FAILED,    // LLM could not find any replacement (element may be gone)
        SKIPPED    // Healing not attempted (feature disabled or cache hit)
    }

    private final Status  status;          // Outcome of this healing attempt
    private final String  originalLocator; // The locator that broke (e.g. "#old-btn")
    private final String  healedLocator;   // The LLM-suggested replacement (null if FAILED)
    private final String  strategy;        // How it was found: "llm", "cache", "fallback"
    private final String  reason;          // Human-readable explanation (for reports/logs)
    private final long    healingTimeMs;   // How long the healing took (for performance tracking)

    // ── Private constructor — callers use static factory methods below ─────
    private HealingResult(Status status, String original, String healed,
                          String strategy, String reason, long timeMs) {
        this.status          = status;
        this.originalLocator = original;
        this.healedLocator   = healed;
        this.strategy        = strategy;
        this.reason          = reason;
        this.healingTimeMs   = timeMs;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATIC FACTORY METHODS — readable, self-documenting result construction
    // Pattern: HealingResult.success(...) is clearer than new HealingResult(SUCCESS,...)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * success() — LLM found a valid replacement locator.
     * @param original     The broken locator (e.g. "[data-test='old-login-btn']")
     * @param healed       The new working locator suggested by LLM
     * @param strategy     How it was found: "llm-claude", "cache", etc.
     * @param healingTimeMs How many milliseconds the healing process took
     */
    public static HealingResult success(String original, String healed,
                                        String strategy, long healingTimeMs) {
        return new HealingResult(
            Status.SUCCESS, original, healed, strategy,
            "Healed successfully using " + strategy + " in " + healingTimeMs + "ms",
            healingTimeMs
        );
    }

    /**
     * failed() — LLM could not find any valid replacement.
     * @param original  The broken locator
     * @param reason    Why healing failed (e.g. "Element not found in DOM")
     */
    public static HealingResult failed(String original, String reason) {
        return new HealingResult(Status.FAILED, original, null, "none", reason, 0L);
    }

    /**
     * skipped() — Healing was not attempted (feature disabled or cache hit).
     * @param original  The original locator
     * @param reason    Why healing was skipped
     */
    public static HealingResult skipped(String original, String reason) {
        return new HealingResult(Status.SKIPPED, original, original, "cache", reason, 0L);
    }

    // ── Accessors ──────────────────────────────────────────────────────────
    public boolean isSuccess()         { return status == Status.SUCCESS; }
    public Status  getStatus()         { return status; }
    public String  getOriginalLocator(){ return originalLocator; }
    public String  getHealedLocator()  { return healedLocator; }
    public String  getStrategy()       { return strategy; }
    public String  getReason()         { return reason; }
    public long    getHealingTimeMs()  { return healingTimeMs; }

    @Override
    public String toString() {
        return String.format("HealingResult{status=%s, original='%s', healed='%s', time=%dms}",
            status, originalLocator, healedLocator, healingTimeMs);
    }
}
