// src/main/java/com/selfhealing/mcp/MCPTools.java
// ─────────────────────────────────────────────────────────────────────────────
// MCP TOOLS — Model Context Protocol tool definitions
//
// What is MCP (Model Context Protocol)?
//   MCP is Anthropic's open standard that allows LLMs to call external "tools"
//   during a conversation. Instead of just generating text, the LLM can invoke
//   structured functions (tools) and receive real data back.
//
//   Think of it like function calling in OpenAI's API — but standardised.
//
// How MCP works in this project:
//   Instead of one big prompt + one big DOM dump, we give Claude TOOLS it can call:
//   
//   Tool 1: get_dom_context     → Returns live HTML from the browser
//   Tool 2: validate_locator    → Checks if a given locator exists on the page
//   Tool 3: find_by_description → Searches DOM for element matching a description
//   Tool 4: report_healed       → Records the final healed locator
//
//   Claude calls these tools iteratively — inspecting, validating, iterating —
//   just like a real SDET would investigate a broken locator manually.
//
// This class defines the tool SCHEMAS (what each tool accepts and returns).
// MCPServer.java handles the actual execution of tool calls.
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;

/**
 * MCPTools — Tool schema definitions for the MCP server.
 *
 * Each tool definition is a JSON object that tells Claude:
 *   - name:        what to call it (used in tool_use blocks)
 *   - description: when and why to use it (the LLM reads this)
 *   - input_schema: what parameters the tool accepts (JSON Schema format)
 */
public class MCPTools {

    private static final ObjectMapper mapper = new ObjectMapper();

    // ── Tool Names (constants prevent typos across the codebase) ──────────
    public static final String GET_DOM_CONTEXT      = "get_dom_context";
    public static final String VALIDATE_LOCATOR     = "validate_locator";
    public static final String FIND_BY_DESCRIPTION  = "find_by_description";
    public static final String REPORT_HEALED        = "report_healed";

    // ─────────────────────────────────────────────────────────────────────────
    // getAllTools() — Returns the complete list of tool definitions
    // Sent to Claude in every API call that uses tool_choice
    // ─────────────────────────────────────────────────────────────────────────
    public static List<ObjectNode> getAllTools() {
        return Arrays.asList(
            buildGetDomContextTool(),
            buildValidateLocatorTool(),
            buildFindByDescriptionTool(),
            buildReportHealedTool()
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 1: get_dom_context
    // Purpose: Get the live HTML of the page (or a specific section)
    // Claude calls this when it wants to inspect the current page structure
    // ─────────────────────────────────────────────────────────────────────────
    private static ObjectNode buildGetDomContextTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", GET_DOM_CONTEXT);
        tool.put("description",
            "Get the current HTML DOM from the browser page. " +
            "Use this to inspect the live page structure when trying to find " +
            "a replacement for a broken locator. You can optionally pass a CSS " +
            "selector to get only a specific section of the page (more efficient)."
        );

        // input_schema defines what parameters this tool accepts
        // Follows JSON Schema draft-07 format
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        // Optional: section_selector — narrow down the DOM extraction
        ObjectNode sectionSelector = mapper.createObjectNode();
        sectionSelector.put("type", "string");
        sectionSelector.put("description",
            "Optional CSS selector to extract only a specific section of the page. " +
            "E.g., 'form' to get only form elements, 'main' for main content. " +
            "Leave empty to get the full body."
        );
        properties.set("section_selector", sectionSelector);

        schema.set("properties", properties);
        // No required fields — all parameters are optional for this tool
        schema.set("required", mapper.createArrayNode());

        tool.set("input_schema", schema);
        return tool;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 2: validate_locator
    // Purpose: Check whether a given locator actually finds an element on the page
    // Claude calls this AFTER suggesting a replacement to verify it works
    // ─────────────────────────────────────────────────────────────────────────
    private static ObjectNode buildValidateLocatorTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", VALIDATE_LOCATOR);
        tool.put("description",
            "Check whether a CSS or XPath locator finds an element on the current page. " +
            "Returns the count of matching elements and the outerHTML of the first match. " +
            "Use this to verify your candidate locator before reporting it as healed."
        );

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        // Required: the locator to validate
        ObjectNode locator = mapper.createObjectNode();
        locator.put("type", "string");
        locator.put("description", "The CSS selector or XPath expression to validate on the current page.");
        properties.set("locator", locator);

        schema.set("properties", properties);

        // Required fields array
        ArrayNode required = mapper.createArrayNode();
        required.add("locator");
        schema.set("required", required);

        tool.set("input_schema", schema);
        return tool;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 3: find_by_description
    // Purpose: Let Claude describe what it's looking for and search the DOM
    // Useful when the element's purpose is known but the locator is unclear
    // ─────────────────────────────────────────────────────────────────────────
    private static ObjectNode buildFindByDescriptionTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", FIND_BY_DESCRIPTION);
        tool.put("description",
            "Search the page DOM for an element matching a semantic description. " +
            "Pass a description like 'submit button in the login form' or " +
            "'email input field'. Returns matching elements with their attributes " +
            "and suggested locators."
        );

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode description = mapper.createObjectNode();
        description.put("type", "string");
        description.put("description", "Semantic description of the element to find. E.g., 'login button', 'email input'.");
        properties.set("description", description);

        ObjectNode elementType = mapper.createObjectNode();
        elementType.put("type", "string");
        elementType.put("description", "Optional HTML element type hint: 'button', 'input', 'a', 'div', etc.");
        properties.set("element_type", elementType);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("description");
        schema.set("required", required);

        tool.set("input_schema", schema);
        return tool;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool 4: report_healed
    // Purpose: Claude calls this to report its final healed locator
    // This is how the LLM "returns" its answer in the MCP flow
    // ─────────────────────────────────────────────────────────────────────────
    private static ObjectNode buildReportHealedTool() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", REPORT_HEALED);
        tool.put("description",
            "Report the final healed locator once you have validated it works. " +
            "Call this tool with the best locator you found after inspecting the DOM " +
            "and validating the locator. Include your confidence level and reasoning."
        );

        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = mapper.createObjectNode();

        ObjectNode healedLocator = mapper.createObjectNode();
        healedLocator.put("type", "string");
        healedLocator.put("description", "The validated replacement locator (CSS or XPath).");
        properties.set("healed_locator", healedLocator);

        ObjectNode confidence = mapper.createObjectNode();
        confidence.put("type", "string");
        ArrayNode confidenceEnum = mapper.createArrayNode();
        confidenceEnum.add("HIGH");
        confidenceEnum.add("MEDIUM");
        confidenceEnum.add("LOW");
        confidence.set("enum", confidenceEnum);
        confidence.put("description", "Confidence level: HIGH (unique match), MEDIUM (partial), LOW (guessing).");
        properties.set("confidence", confidence);

        ObjectNode reasoning = mapper.createObjectNode();
        reasoning.put("type", "string");
        reasoning.put("description", "Brief explanation of why this locator was chosen.");
        properties.set("reasoning", reasoning);

        schema.set("properties", properties);

        ArrayNode required = mapper.createArrayNode();
        required.add("healed_locator");
        required.add("confidence");
        schema.set("required", required);

        tool.set("input_schema", schema);
        return tool;
    }
}
