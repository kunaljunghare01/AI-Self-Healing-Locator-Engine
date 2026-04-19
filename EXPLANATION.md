# 📖 PROJECT 3: AI Self-Healing Locator Engine — Complete Explanation

> The most advanced project on your resume. Study this file before every interview.

---

## 🔹 What is this project in ONE sentence?

When a UI change breaks a test locator, this engine **automatically detects the failure, calls Claude AI via MCP tools, inspects the live browser DOM, and heals the locator — without any human intervention.**

---

## 🔹 The Problem It Solves

Modern applications release multiple times per day. Every release can change:
- Element IDs (`#login-btn` → `#submit-btn`)
- CSS classes (restyling)
- HTML structure (component refactors)
- `data-test` attribute names

**Without self-healing:** Every UI change breaks 5–20 locators. An SDET spends 2–3 hours per release fixing tests manually.

**With self-healing:** Locators auto-repair at runtime. Tests pass. The SDET reviews the healing log later instead of firefighting.

---

## 🔹 System Architecture Diagram

```
Test Code (TestNG)
      │
      ▼
SelfHealingDriver.java        ← Wraps Playwright Page
      │  (locator fails)
      ▼
LocatorHealer.java            ← ORCHESTRATOR (the brain)
      │
      ├─► LocatorRepository    ← Check cache first (0ms, no LLM)
      │         │ (cache miss)
      │         ▼
      ├─► DOMExtractor         ← Extract clean HTML from Playwright page
      │
      ├─► [MCP MODE] MCPServer ← Agentic loop: Claude calls tools iteratively
      │         │
      │         ├─ Tool: get_dom_context     → Returns live page HTML
      │         ├─ Tool: validate_locator    → Checks candidate in browser
      │         ├─ Tool: find_by_description → Semantic DOM search
      │         └─ Tool: report_healed       → Claude reports final answer
      │
      ├─► [SIMPLE MODE] ClaudeClient ← Single HTTP call to Claude API
      │         │
      │         └─ PromptBuilder → Crafts structured prompt with DOM
      │
      ▼
HealingResult.java            ← SUCCESS / FAILED / SKIPPED
      │
      ▼
LocatorRepository             ← Save healed mapping to JSON cache
      │
      ▼
Test continues with healed locator ✅
```

---

## 🔹 What is MCP (Model Context Protocol)?

**MCP = Model Context Protocol** — an open standard by Anthropic that lets LLMs call external tools during a conversation.

**Without MCP (single-shot):**
```
You → Claude: "Here's a broken locator and 8000 chars of HTML. Fix it."
Claude → You: "[data-test='login-btn']"
```
One shot. If Claude's first guess is wrong, you don't know.

**With MCP (agentic):**
```
You → Claude: "Fix this broken locator. Here are tools you can use."
Claude → You: "call get_dom_context(section='form')"    ← Claude inspects
You → Claude: "<form>...</form>" (tool result)
Claude → You: "call validate_locator('[data-test=login-button]')"  ← Claude validates
You → Claude: "1 element found, outerHTML: <button ...>" (tool result)
Claude → You: "call report_healed('[data-test=login-button]', confidence=HIGH)"
```
Claude **investigates iteratively**, like a real QA engineer debugging a broken test.

---

## 🔹 File-by-File Deep Dive

### `MCPTools.java` — Tool Definitions

Defines 4 tools Claude can call:

| Tool | What it does | When Claude calls it |
|---|---|---|
| `get_dom_context` | Returns live HTML from browser | First — to see current page |
| `validate_locator` | Checks if a locator finds an element | To verify a candidate |
| `find_by_description` | Semantic search of DOM | When locator is completely different |
| `report_healed` | Reports the final answer | Last — when Claude is confident |

Each tool has:
- `name` — what Claude types to call it
- `description` — when/why to use it (Claude reads this to decide)
- `input_schema` — JSON Schema of accepted parameters

---

### `MCPServer.java` — The Agentic Loop

The agentic loop is the most important concept:

```java
for (int iteration = 0; iteration < MAX_LOOPS; iteration++) {
    // 1. Call Claude with current conversation history + tools
    JsonNode response = callClaudeWithTools(messages, toolsArray);

    // 2. Add Claude's response to history
    messages.add(assistantMessage(response));

    // 3. Execute tool calls Claude requested
    //    → add results back to messages
    String healedLocator = processResponseContent(response, messages);

    // 4. If Claude called report_healed → done!
    if (healedLocator != null) return healedLocator;
}
```

Key design decisions:
- `MAX_LOOPS = 5` — prevents infinite loops if Claude keeps asking for tools
- `stop_reason == "end_turn"` without tool call → something went wrong, exit
- Full conversation history is sent every call (LLMs are stateless)

---

### `SelfHealingDriver.java` — The Decorator Pattern

```java
// WITHOUT self-healing (old approach):
page.locator("[data-test='old-btn']").click(); // throws if locator broken

// WITH self-healing (new approach):
driver.click("[data-test='old-btn']", "Login button"); // heals automatically
```

The `resolveLocator()` method is the key:
1. Try the locator → works? Return it immediately
2. Doesn't work → call `healer.heal()` → returns healed locator
3. Healed locator also fails → throw descriptive exception

Test code is **completely unchanged** — only import the driver instead of raw `page`.

---

### `LocatorRepository.java` — The Cache

**Why it matters for the 35% reduction claim:**

Without cache: Every broken locator = 1-3 second LLM call, every test run.
With cache: First healing = 1-3s. All subsequent runs = <10ms from JSON file.

If you have 100 broken locators and run tests 10 times: 
- Without cache: 1000 LLM calls, ~1000 seconds cost
- With cache: 100 LLM calls, ~100 seconds total

The cache is **committed to Git** — the whole team shares healed locators.

---

### `PromptBuilder.java` — Prompt Engineering

The system prompt enforces strict output format:
```
"Return ONLY the locator string. No explanation. No markdown. No quotes."
```

This is critical — if Claude returns "Here is the locator: [data-test='btn']",
the code gets the full sentence instead of just the locator, which would break.

Locator priority order in the prompt:
```
1. data-test / data-testid   ← Most stable (made for automation)
2. aria-label                ← Semantic, stable
3. Unique ID                 ← Stable if not auto-generated
4. CSS class + tag           ← Moderate stability
5. Text content              ← Changes when copy changes
6. XPath                     ← Last resort, fragile
```

---

## 🔹 Interview Questions

**Q: What is self-healing locators?**

*"When UI changes break test locators, instead of failing and requiring manual fixes, the engine catches the locator exception, sends the live page DOM to an LLM, and gets a replacement locator back — all at runtime. The healed locator is cached so subsequent runs don't need LLM calls."*

**Q: What is MCP and why did you use it?**

*"MCP is Anthropic's Model Context Protocol — it lets Claude call external tools during a conversation. Instead of sending one big prompt and hoping for a good answer, Claude can call `get_dom_context` to inspect the page, `validate_locator` to verify a candidate, and iterate until it's confident. This agentic approach is significantly more accurate than single-shot prompting because Claude self-verifies before reporting."*

**Q: How does it achieve 35% maintenance overhead reduction?**

*"Three factors: First, broken locators heal automatically instead of needing manual fixes. Second, the cache means a locator is healed once and reused indefinitely — no repeated LLM calls for the same break. Third, the `healed-locators.json` is committed to Git so the entire team shares healed mappings — if one engineer's run heals a locator, everyone benefits immediately."*

**Q: What is the Decorator design pattern and why did you use it?**

*"The Decorator pattern wraps an existing object to add new behaviour without changing its interface. `SelfHealingDriver` wraps Playwright's `Page` — it adds automatic healing to `click()`, `fill()`, etc. The test code doesn't know or care that healing is happening. This is the Open/Closed principle: we extended behaviour without modifying existing test code."*

**Q: How does the engine know WHAT the element is supposed to be?**

*"Each element interaction in the driver requires an `elementDescription` parameter — e.g., 'Login submit button'. Even if the CSS selector changes completely, the LLM knows it's looking for a submit button on a login form. This semantic description is the most important context we give the LLM — more important than the broken locator itself."*

**Q: What happens when healing fails?**

*"If the LLM returns 'HEALING_FAILED' or suggests a locator that Playwright validates as not working, we throw a `RuntimeException` with a detailed message: the broken locator, element description, page URL, and reason. This gives the SDET full context to investigate manually. We never silently pass a test with a failed heal — that would be a false positive."*

**Q: How do you prevent the LLM from suggesting positional selectors like nth-child?**

*"The system prompt explicitly prohibits it: 'Never use nth-child(), nth-of-type(), or positional XPath like [1], [2].' Positional selectors break if the page adds one element above the target. The prompt also prohibits auto-generated class names (e.g., sc-abc123) which change on every build."*

---

## 🔹 Tech Stack Summary

| Component | Technology | Why |
|---|---|---|
| Browser automation | Playwright Java | Fast, reliable, Java-native |
| LLM (single-shot) | Claude Haiku via HTTP | Fast, cheap, sufficient for simple locators |
| LLM (agentic) | Claude Sonnet + MCP | Better reasoning for complex DOM |
| HTTP client | Java 11 HttpClient | Built-in, no extra dependency |
| JSON handling | Jackson | Industry standard, fast |
| Cache storage | JSON file (Jackson) | Simple, human-readable, Git-committable |
| Test runner | TestNG | Parallel execution support |
| Reports | Allure | Shows healing events inline with test steps |
| CI/CD | GitHub Actions | Runs nightly, saves cache between runs |
