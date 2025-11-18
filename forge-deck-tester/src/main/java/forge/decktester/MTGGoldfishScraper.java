package forge.decktester;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
    private static final String DEFAULT_CACHE_DIR = ".cache/mtggoldfish_decks";

    private final Path outputDir;
    private final Path cacheInfoPath;

    public MTGGoldfishScraper() {
        this(DEFAULT_CACHE_DIR);
    }

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
            List<DeckInfo> cachedDecks = loadFromCache();
            if (!cachedDecks.isEmpty()) {
                // If cache has enough decks, use it
                if (cachedDecks.size() >= maxDecks) {
                    System.out.printf("Using cached decks (%d decks in cache)%n", cachedDecks.size());
                    return cachedDecks.subList(0, maxDecks);
                } else {
                    // Cache doesn't have enough decks, need to re-download
                    System.out.printf("Cache only has %d decks but %d requested - re-downloading...%n",
                                     cachedDecks.size(), maxDecks);
                }
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

                // Debug: Save raw HTML if deck parsing fails
                if (deckText.isEmpty()) {
                    Path debugPath = outputDir.resolve(sanitizeFilename(deckInfo.name) + "_debug.html");
                    Files.writeString(debugPath, deckHtml);
                    System.err.println("Warning: Could not parse deck list for '" + deckInfo.name + "'. HTML saved to " + debugPath + " for debugging.");
                }

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

        Set<String> seenArchetypes = new HashSet<>();

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(html);
            while (matcher.find() && decks.size() < maxDecks) {
                String url = matcher.group(1);
                String name = matcher.group(2).trim().replaceAll("<[^>]+>", "");

                // Normalize URL by removing fragment (#online, #paper)
                // This prevents downloading the same archetype twice
                String baseUrl = url.split("#")[0];

                // Avoid duplicates based on archetype (ignoring #online vs #paper)
                if (!seenArchetypes.contains(baseUrl) && !name.isEmpty()) {
                    seenArchetypes.add(baseUrl);
                    // Keep the first URL variant we see
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
     * For archetype pages, extracts from JavaScript initializeDeckComponents.
     * For individual deck pages, extracts from textarea.
     */
    private String parseDeckPage(String html) {
        try {
            // Method 1: Extract from JavaScript initializeDeckComponents (for archetype pages)
            // Pattern: initializeDeckComponents('...', '...', "1%20Card%0A2%20Another%0A...", '...')
            Pattern jsPattern = Pattern.compile(
                "initializeDeckComponents\\([^,]+,\\s*[^,]+,\\s*\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            );
            Matcher jsMatcher = jsPattern.matcher(html);
            if (jsMatcher.find()) {
                String urlEncodedDeck = jsMatcher.group(1);
                try {
                    // URL decode the deck list
                    String decoded = java.net.URLDecoder.decode(urlEncodedDeck, "UTF-8");
                    if (!decoded.isEmpty()) {
                        return decoded;
                    }
                } catch (Exception e) {
                    System.err.println("Failed to decode deck list: " + e.getMessage());
                }
            }

            // Method 2: Textarea (for individual deck pages)
            Document doc = Jsoup.parse(html);
            Elements textareas = doc.select("textarea.deck-textarea");
            if (!textareas.isEmpty()) {
                return textareas.first().text();
            }

            // Method 3: Parse HTML deck tables (fallback)
            StringBuilder deckText = new StringBuilder();
            Elements deckTables = doc.select("table.deck-view-deck-table, div.archetype-deck table, div.deck-view table");

            if (!deckTables.isEmpty()) {
                for (Element table : deckTables) {
                    Elements rows = table.select("tr");
                    for (Element row : rows) {
                        Elements qtyCells = row.select("td.deck-col-qty, td:first-child");
                        Elements cardCells = row.select("td.deck-col-card, td:nth-child(2)");

                        if (!qtyCells.isEmpty() && !cardCells.isEmpty()) {
                            String quantity = qtyCells.first().text().trim();
                            String cardName = cardCells.first().text().trim();

                            Element link = cardCells.first().selectFirst("a");
                            if (link != null) {
                                cardName = link.text().trim();
                            }

                            if (!quantity.isEmpty() && !cardName.isEmpty() && quantity.matches("\\d+")) {
                                deckText.append(quantity).append(" ").append(cardName).append("\n");
                            }
                        }
                    }
                }
            }

            String result = deckText.toString().trim();
            if (!result.isEmpty()) {
                return result;
            }

            // Method 4: Look for any table rows with card data
            Elements allTables = doc.select("table");
            for (Element table : allTables) {
                Elements rows = table.select("tr");
                for (Element row : rows) {
                    Elements cells = row.select("td");
                    if (cells.size() >= 2) {
                        String first = cells.get(0).text().trim();
                        String second = cells.get(1).text().trim();

                        if (first.matches("\\d+") && !second.isEmpty()) {
                            deckText.append(first).append(" ").append(second).append("\n");
                        }
                    }
                }
            }

            return deckText.toString().trim();

        } catch (Exception e) {
            System.err.println("Error parsing deck page: " + e.getMessage());
            return "";
        }
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
