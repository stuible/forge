package forge.decktester;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes top decks from MTGGoldfish and converts them to Forge deck format.
 * Includes intelligent caching to avoid re-downloading decks unnecessarily.
 */
public class MTGGoldfishScraper {
    private static final String METAGAME_URL = "https://www.mtggoldfish.com/metagame/standard/full#paper";
    private static final String DECK_URL_BASE = "https://www.mtggoldfish.com";
    private static final int MAX_DECKS = 100;
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36";
    private static final String CACHE_INFO_FILE = ".cache_info.txt";
    private static final int CACHE_EXPIRY_DAYS = 7; // Cache expires after 7 days

    private final Path outputDir;
    private final Path cacheInfoPath;

    public MTGGoldfishScraper(String outputDirectory) {
        this.outputDir = Paths.get(outputDirectory);
        this.cacheInfoPath = outputDir.resolve(CACHE_INFO_FILE);
    }

    /**
     * Fetch and save top decks from MTGGoldfish with intelligent caching.
     */
    public List<DeckInfo> fetchTopDecks(int maxDecks) throws IOException {
        return fetchTopDecks(maxDecks, false);
    }

    /**
     * Fetch and save top decks from MTGGoldfish with intelligent caching.
     * @param maxDecks Maximum number of decks to fetch
     * @param forceRefresh If true, ignore cache and re-download all decks
     */
    public List<DeckInfo> fetchTopDecks(int maxDecks, boolean forceRefresh) throws IOException {
        // Create output directory
        Files.createDirectories(outputDir);

        // Check cache validity
        if (!forceRefresh && isCacheValid()) {
            System.out.println("Using cached decks (cache is valid)...");
            List<DeckInfo> cachedDecks = loadFromCache();
            if (!cachedDecks.isEmpty()) {
                System.out.printf("Loaded %d decks from cache%n", cachedDecks.size());
                return cachedDecks.subList(0, Math.min(maxDecks, cachedDecks.size()));
            }
        }

        if (forceRefresh) {
            System.out.println("Force refresh requested - re-downloading decks...");
        } else {
            System.out.println("Cache invalid or empty - fetching fresh decks from MTGGoldfish...");
        }

        // Fetch metagame page
        String metaGameHtml = fetchUrl(METAGAME_URL);

        // Parse deck URLs
        List<DeckInfo> deckList = parseMetagamePage(metaGameHtml, Math.min(maxDecks, MAX_DECKS));

        // Download each deck
        int count = 0;
        for (DeckInfo deckInfo : deckList) {
            count++;
            System.out.printf("Downloading deck %d/%d: %s%n", count, deckList.size(), deckInfo.name);

            try {
                String deckHtml = fetchUrl(DECK_URL_BASE + deckInfo.url);
                String deckText = parseDeckPage(deckHtml);
                String forgeDeck = convertToForgeDeck(deckInfo.name, deckText);

                // Save to file
                Path deckFile = outputDir.resolve(sanitizeFilename(deckInfo.name) + ".dck");
                Files.writeString(deckFile, forgeDeck);
                deckInfo.filePath = deckFile.toString();

                // Be respectful to the server
                Thread.sleep(500);
            } catch (Exception e) {
                System.err.println("Failed to download deck: " + deckInfo.name + " - " + e.getMessage());
            }
        }

        System.out.printf("Successfully downloaded %d decks%n", count);

        // Save cache info
        saveCacheInfo(deckList);

        return deckList;
    }

    /**
     * Check if the cache is valid (exists and not expired).
     */
    private boolean isCacheValid() {
        try {
            if (!Files.exists(cacheInfoPath)) {
                return false;
            }

            // Check if cache file is older than CACHE_EXPIRY_DAYS
            FileTime cacheTime = Files.getLastModifiedTime(cacheInfoPath);
            Instant cacheInstant = cacheTime.toInstant();
            Instant expiryInstant = Instant.now().minus(CACHE_EXPIRY_DAYS, ChronoUnit.DAYS);

            if (cacheInstant.isBefore(expiryInstant)) {
                System.out.println("Cache expired (older than " + CACHE_EXPIRY_DAYS + " days)");
                return false;
            }

            // Check if we have any deck files
            long deckCount = Files.list(outputDir)
                    .filter(p -> p.toString().endsWith(".dck"))
                    .count();

            return deckCount > 0;

        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Load deck info from cache.
     */
    private List<DeckInfo> loadFromCache() throws IOException {
        List<DeckInfo> decks = new ArrayList<>();

        if (!Files.exists(cacheInfoPath)) {
            return decks;
        }

        List<String> lines = Files.readAllLines(cacheInfoPath);
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }

            // Format: name|url|filepath
            String[] parts = line.split("\\|", 3);
            if (parts.length >= 3) {
                DeckInfo info = new DeckInfo(parts[0], parts[1]);
                info.filePath = parts[2];

                // Verify file still exists
                if (Files.exists(Paths.get(info.filePath))) {
                    decks.add(info);
                }
            }
        }

        return decks;
    }

    /**
     * Save cache information.
     */
    private void saveCacheInfo(List<DeckInfo> decks) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(cacheInfoPath.toFile()))) {
            writer.println("# MTGGoldfish Deck Cache");
            writer.println("# Generated: " + Instant.now());
            writer.println("# Format: name|url|filepath");
            writer.println();

            for (DeckInfo deck : decks) {
                if (deck.filePath != null) {
                    writer.printf("%s|%s|%s%n", deck.name, deck.url, deck.filePath);
                }
            }
        }
    }

    /**
     * Clear the cache.
     */
    public void clearCache() throws IOException {
        if (Files.exists(cacheInfoPath)) {
            Files.delete(cacheInfoPath);
            System.out.println("Cache cleared");
        }

        // Optionally delete all .dck files
        Files.list(outputDir)
                .filter(p -> p.toString().endsWith(".dck"))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        System.err.println("Failed to delete: " + p);
                    }
                });
    }

    /**
     * Parse metagame page to extract deck URLs.
     */
    private List<DeckInfo> parseMetagamePage(String html, int maxDecks) {
        List<DeckInfo> decks = new ArrayList<>();

        // Pattern to match deck archetype links - multiple patterns for robustness
        Pattern[] patterns = {
            Pattern.compile("<a[^>]+href=\"(/archetype/[^\"]+)\"[^>]*>\\s*<span[^>]*>([^<]+)</span>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<a[^>]+href=\"(/archetype/[^\"]+)\"[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE),
            Pattern.compile("href=\"(/archetype/[^\"]+)\"[^>]*>([^<]+)", Pattern.CASE_INSENSITIVE)
        };

        Set<String> seenUrls = new HashSet<>();

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            while (matcher.find() && decks.size() < maxDecks) {
                String url = matcher.group(1);
                String name = matcher.group(2).trim().replaceAll("<[^>]+>", "");

                // Avoid duplicates
                if (!seenUrls.contains(url) && !name.isEmpty()) {
                    seenUrls.add(url);
                    decks.add(new DeckInfo(name, url));
                }
            }
        }

        // If we didn't find enough decks, print a warning
        if (decks.isEmpty()) {
            System.err.println("Warning: Could not parse any decks from MTGGoldfish. The site structure may have changed.");
        }

        return decks;
    }

    /**
     * Parse deck page to extract deck list.
     */
    private String parseDeckPage(String html) {
        // Look for deck export in text format
        Pattern pattern = Pattern.compile(
            "<textarea[^>]*class=\"deck-textarea\"[^>]*>([^<]+)</textarea>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher matcher = pattern.matcher(html);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return "";
    }

    /**
     * Convert MTGGoldfish deck format to Forge .dck format.
     */
    private String convertToForgeDeck(String deckName, String deckText) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("[metadata]\n");
        sb.append("Name=").append(deckName).append("\n");
        sb.append("Format=Standard\n");
        sb.append("[Main]\n");

        boolean inSideboard = false;

        for (String line : deckText.split("\n")) {
            line = line.trim();

            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            // Check for sideboard section
            if (line.toLowerCase().contains("sideboard")) {
                if (!inSideboard) {
                    sb.append("\n[Sideboard]\n");
                    inSideboard = true;
                }
                continue;
            }

            // Parse card line: "4 Lightning Bolt" or "4x Lightning Bolt"
            Pattern cardPattern = Pattern.compile("^(\\d+)x?\\s+(.+)$");
            Matcher matcher = cardPattern.matcher(line);

            if (matcher.matches()) {
                String quantity = matcher.group(1);
                String cardName = matcher.group(2).trim();

                // Forge format: "quantity cardname"
                sb.append(quantity).append(" ").append(cardName).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Fetch URL content with retry logic.
     */
    private String fetchUrl(String urlString) throws IOException {
        int retries = 3;
        IOException lastException = null;

        for (int i = 0; i < retries; i++) {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line).append("\n");
                        }
                        return response.toString();
                    }
                } else {
                    throw new IOException("HTTP error code: " + responseCode);
                }
            } catch (IOException e) {
                lastException = e;
                if (i < retries - 1) {
                    try {
                        Thread.sleep(1000 * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }
        }

        throw lastException;
    }

    /**
     * Sanitize filename for filesystem.
     */
    private String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9-_\\s]", "")
                   .replaceAll("\\s+", "_")
                   .toLowerCase();
    }

    /**
     * Deck information holder.
     */
    public static class DeckInfo {
        public final String name;
        public final String url;
        public String filePath;

        public DeckInfo(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return name + " (" + url + ")";
        }
    }
}
