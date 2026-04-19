// src/main/java/com/selfhealing/storage/LocatorRepository.java
// ─────────────────────────────────────────────────────────────────────────────
// LOCATOR REPOSITORY — Persists healed locator mappings to disk (JSON file)
//
// Why persist healed locators?
//   The LLM call takes 1–3 seconds. If the same locator breaks in every test run,
//   calling the LLM every time is wasteful and slow.
//
//   Solution: Cache the healed mapping locally.
//   Next run: broken locator → lookup in cache → use healed locator immediately.
//   No LLM call needed. Zero latency. Free.
//
// Storage format (healed-locators.json):
//   {
//     "[data-test='old-login-btn']": {
//       "healedLocator": "[data-test='login-button']",
//       "elementDescription": "Login submit button",
//       "pageUrl": "https://app.com/login",
//       "healedAt": "2024-05-01T10:30:00",
//       "timesUsed": 3
//     }
//   }
// ─────────────────────────────────────────────────────────────────────────────

package com.selfhealing.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap; // Thread-safe HashMap

/**
 * LocatorRepository — Read/write healed locator mappings.
 *
 * Thread-safe: uses ConcurrentHashMap for in-memory store.
 * Singleton pattern: one shared instance across the entire test run.
 */
public class LocatorRepository {

    private static final Logger log = LoggerFactory.getLogger(LocatorRepository.class);

    // ── Singleton instance ────────────────────────────────────────────────
    private static LocatorRepository instance;

    // ── Storage file path ─────────────────────────────────────────────────
    // Stored at project root — committed to Git so the team shares healed locators
    private static final String STORAGE_FILE = "locator-mappings/healed-locators.json";

    // ── In-memory cache ───────────────────────────────────────────────────
    // ConcurrentHashMap: thread-safe for parallel test execution
    // Key: original broken locator string
    // Value: ObjectNode containing healed locator + metadata
    private final ConcurrentHashMap<String, ObjectNode> cache;

    private final ObjectMapper mapper;
    private final File storageFile;

    // ── Private constructor (Singleton) ───────────────────────────────────
    private LocatorRepository() {
        this.mapper      = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.storageFile = new File(STORAGE_FILE);
        this.cache       = new ConcurrentHashMap<>();
        loadFromDisk();    // Load existing healed locators on startup
    }

    /**
     * getInstance() — Returns the single shared LocatorRepository.
     * Synchronized to prevent duplicate instances in parallel test startup.
     */
    public static synchronized LocatorRepository getInstance() {
        if (instance == null) {
            instance = new LocatorRepository();
        }
        return instance;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getCachedLocator() — Check if a healed locator already exists in cache
    //
    // Called BEFORE making any LLM request.
    // If a cached result exists, skip the LLM call entirely.
    //
    // @param brokenLocator  The locator that failed in the current test run
    // @return               The previously healed locator, or null if not cached
    // ─────────────────────────────────────────────────────────────────────────
    public String getCachedLocator(String brokenLocator) {
        ObjectNode entry = cache.get(brokenLocator);
        if (entry != null) {
            // Increment the "timesUsed" counter — useful for reporting
            int timesUsed = entry.get("timesUsed").asInt(0) + 1;
            entry.put("timesUsed", timesUsed);

            String healed = entry.get("healedLocator").asText();
            log.info("Cache HIT for '{}' → '{}' (used {} times)", brokenLocator, healed, timesUsed);
            return healed;
        }
        log.debug("Cache MISS for '{}'", brokenLocator);
        return null; // null signals "not cached" to the caller
    }

    // ─────────────────────────────────────────────────────────────────────────
    // saveHealedLocator() — Persist a newly healed locator to disk
    //
    // Called after the LLM successfully suggests a replacement locator.
    // Saves to both in-memory cache (fast) and JSON file (persistent across runs).
    //
    // @param brokenLocator       The locator that broke
    // @param healedLocator       The LLM's suggested replacement
    // @param elementDescription  Human description (e.g. "Login submit button")
    // @param pageUrl             URL where the break was detected
    // ─────────────────────────────────────────────────────────────────────────
    public void saveHealedLocator(String brokenLocator, String healedLocator,
                                   String elementDescription, String pageUrl) {
        // ── Build the cache entry ──────────────────────────────────────────
        ObjectNode entry = mapper.createObjectNode();
        entry.put("healedLocator",       healedLocator);
        entry.put("elementDescription",  elementDescription);
        entry.put("pageUrl",             pageUrl);
        entry.put("healedAt",            LocalDateTime.now().toString());
        entry.put("timesUsed",           1);

        // ── Write to in-memory cache ───────────────────────────────────────
        cache.put(brokenLocator, entry);

        // ── Persist to JSON file on disk ───────────────────────────────────
        saveToDisk();

        log.info("Saved healing: '{}' → '{}'", brokenLocator, healedLocator);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // loadFromDisk() — Read previously healed locators from JSON file on startup
    // ─────────────────────────────────────────────────────────────────────────
    private void loadFromDisk() {
        if (!storageFile.exists()) {
            log.info("No existing locator cache found at '{}' — starting fresh", STORAGE_FILE);
            return;
        }

        try {
            // Read the JSON file into a top-level ObjectNode
            ObjectNode root = (ObjectNode) mapper.readTree(storageFile);

            // Iterate over all keys (broken locators) and load into cache
            root.fields().forEachRemaining(entry ->
                cache.put(entry.getKey(), (ObjectNode) entry.getValue())
            );

            log.info("Loaded {} healed locators from cache", cache.size());
        } catch (IOException e) {
            log.error("Failed to load locator cache: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // saveToDisk() — Write the current in-memory cache to the JSON file
    // Called after every new healing to keep the file in sync
    // ─────────────────────────────────────────────────────────────────────────
    private synchronized void saveToDisk() {
        // synchronized: prevents concurrent writes from parallel tests
        try {
            // Ensure the directory exists before writing
            storageFile.getParentFile().mkdirs();

            // Build the root ObjectNode from the cache map
            ObjectNode root = mapper.createObjectNode();
            cache.forEach(root::set);

            // Write to file with pretty printing (INDENT_OUTPUT enabled above)
            mapper.writeValue(storageFile, root);
        } catch (IOException e) {
            log.error("Failed to save locator cache: {}", e.getMessage());
        }
    }

    /** Returns total number of healed locators in cache */
    public int getCacheSize() { return cache.size(); }
}
