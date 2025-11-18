package forge.decktester;

import forge.deck.Deck;
import forge.decktester.DeckTester.MatchupResult;
import forge.decktester.DeckTester.TestResults;
import forge.decktester.MTGGoldfishScraper.DeckInfo;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Command-line interface for automated deck testing.
 */
public class DeckTesterCLI {
    private static final String VERSION = "1.0.0";
    private static final int DEFAULT_GAMES = 1000;
    private static final int DEFAULT_TOP_DECKS = 100;

    public static void main(String[] args) {
        try {
            Options options = parseArgs(args);

            if (options.showHelp) {
                printHelp();
                return;
            }

            if (options.showVersion) {
                System.out.println("Forge Deck Tester v" + VERSION);
                return;
            }

            runDeckTesting(options);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Run the deck testing process.
     */
    private static void runDeckTesting(Options options) throws Exception {
        printBanner();

        // Suppress stderr and filter stdout unless verbose mode is enabled
        PrintStream originalErr = System.err;
        PrintStream originalOut = System.out;

        if (!options.verbose) {
            // Suppress all stderr
            System.setErr(new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                    // Discard all output
                }
            }));

            // Filter stdout to remove Forge debug messages
            System.setOut(new PrintStream(new OutputStream() {
                private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

                @Override
                public void write(int b) throws IOException {
                    if (b == '\n') {
                        String line = buffer.toString();
                        // Only print if it's not a Forge debug message
                        if (!isForgeDebugMessage(line)) {
                            originalOut.println(line);
                        }
                        buffer.reset();
                    } else {
                        buffer.write(b);
                    }
                }

                @Override
                public void flush() throws IOException {
                    originalOut.flush();
                }
            }));
        }

        DeckTester tester = new DeckTester();

        try {
            // Step 1: Initialize Forge first
            tester.initialize();

            // Step 2: Load test deck to detect format
            System.out.println("Loading test deck: " + options.testDeckPath);
            Deck testDeck = tester.loadDeck(options.testDeckPath);

            // Detect if it's a Commander deck
            boolean isCommander = testDeck.has(forge.deck.DeckSection.Commander) ||
                                 testDeck.getAllCardsInASinglePool().countAll() == 100;

            int cardCount = isCommander ? testDeck.getAllCardsInASinglePool().countAll() : testDeck.getMain().countAll();
            String format = isCommander ? "Commander" : "Standard";

            // Validate deck legality
            validateDeck(testDeck, isCommander);

            System.out.printf("Test deck loaded: %s (%d cards, %s format)%n",
                    testDeck.getName(), cardCount, format);

            if (isCommander && options.commanderOpponents > 1) {
                System.out.printf("Commander mode: Playing against %d opponents (%d total players)%n",
                        options.commanderOpponents, options.commanderOpponents + 1);
            }
            System.out.println();

            // Step 3: Get or download opponent decks (using detected format)
            List<Deck> opponentDecks = getOpponentDecks(tester, options, isCommander);

            if (opponentDecks.isEmpty()) {
                System.err.println("No opponent decks found!");
                return;
            }

            System.out.printf("Loaded %d opponent decks%n%n", opponentDecks.size());

            // Step 4: Run tests
            System.out.println("AI Profile: " + options.aiProfile);
            if (options.showLiveProgress) {
                System.out.println("Live Progress: Enabled");
            }
            System.out.println();

            long startTime = System.currentTimeMillis();

            tester.setAiProfile(options.aiProfile);
            tester.setShowLiveProgress(options.showLiveProgress);
            tester.setCommanderOpponents(options.commanderOpponents);
            // Pass original stdout for live display (bypasses filtering)
            if (options.showLiveProgress) {
                tester.setDirectOutputStream(originalOut);
            }
            Map<String, MatchupResult> results = tester.testDeck(
                    testDeck,
                    opponentDecks,
                    options.gamesPerMatchup
            );

            long endTime = System.currentTimeMillis();
            long duration = (endTime - startTime) / 1000;

            // Step 4: Print results
            TestResults testResults = new TestResults(testDeck.getName(), results);
            printResults(testResults, options, duration);

            // Step 5: Save results if requested
            if (options.outputFile != null) {
                saveResults(testResults, options);
            }

        } finally {
            tester.shutdown();
            // Restore stderr and stdout
            if (!options.verbose) {
                System.setErr(originalErr);
                System.setOut(originalOut);
            }
        }
    }

    /**
     * Sanitize filename for deck caching.
     */
    private static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-\\s]", "")
                   .replaceAll("\\s+", "_");
    }

    /**
     * Validate deck legality for the detected format.
     */
    private static void validateDeck(Deck deck, boolean isCommander) {
        if (isCommander) {
            // Commander deck validation
            int totalCards = deck.getAllCardsInASinglePool().countAll();
            if (totalCards != 100) {
                System.err.println("WARNING: Commander deck should have exactly 100 cards, found " + totalCards);
            }

            if (!deck.has(forge.deck.DeckSection.Commander)) {
                System.err.println("WARNING: Deck does not have a Commander section");
            } else if (deck.get(forge.deck.DeckSection.Commander).countAll() == 0) {
                System.err.println("WARNING: Commander section is empty");
            }
        } else {
            // Standard deck validation
            int mainDeckSize = deck.getMain().countAll();
            if (mainDeckSize < 60) {
                System.err.println("WARNING: Standard deck should have at least 60 cards in main deck, found " + mainDeckSize);
            }

            // Check for more than 4 copies of any card (except basic lands)
            Map<String, Integer> cardCounts = new HashMap<>();
            for (forge.item.PaperCard card : deck.getMain().toFlatList()) {
                String cardName = card.getName();
                if (!card.getRules().getType().isBasicLand()) {
                    cardCounts.put(cardName, cardCounts.getOrDefault(cardName, 0) + 1);
                    if (cardCounts.get(cardName) > 4) {
                        System.err.println("WARNING: More than 4 copies of non-basic land: " + cardName + " (" + cardCounts.get(cardName) + " copies)");
                    }
                }
            }
        }
    }

    /**
     * Check if a line is a Forge internal debug message that should be filtered.
     */
    private static boolean isForgeDebugMessage(String line) {
        if (line == null) {
            return false;
        }

        // Filter common Forge debug messages
        return line.contains("Did not have activator set") ||
               line.contains("SpellAbilityRestriction.canPlay()") ||
               line.contains("Overriding AI confirmAction") ||
               line.contains("Couldn't add to stack") ||
               line.contains("default (ie. inherited from base class) implementation") ||
               line.contains("Consider declaring an overloaded method");
    }

    /**
     * Get opponent decks (download or load from directory).
     */
    private static List<Deck> getOpponentDecks(DeckTester tester, Options options, boolean isCommanderDeck) throws Exception {

        if (options.downloadDecks) {
            // Determine which cache directory and metagame to use based on format
            boolean isCommander = isCommanderDeck;
            MTGGoldfishScraper scraper = new MTGGoldfishScraper(isCommander);
            String cacheDir = isCommander ? MTGGoldfishScraper.COMMANDER_CACHE_DIR : MTGGoldfishScraper.DEFAULT_CACHE_DIR;
            String format = isCommander ? "Commander" : "Standard";

            System.out.println("Detected format: " + format);
            System.out.println("Downloading " + format + " decks from MTGGoldfish...");
            System.out.println();

            // Handle cache clearing
            if (options.clearCache) {
                System.out.println("Clearing deck cache...");
                scraper.clearCache();
                System.out.println();
            }

            // Fetch decks (with caching)
            List<DeckInfo> deckInfos = scraper.fetchTopDecks(
                    options.topDecksCount,
                    options.forceRefresh
            );

            System.out.println();

            // Load only the specific decks that were fetched (by name)
            List<Deck> decks = new ArrayList<>();
            for (DeckInfo deckInfo : deckInfos) {
                String filename = sanitizeFilename(deckInfo.name) + ".dck";
                File deckFile = new File(cacheDir, filename);
                if (deckFile.exists()) {
                    try {
                        Deck deck = tester.loadDeck(deckFile.getAbsolutePath());
                        decks.add(deck);
                    } catch (Exception e) {
                        System.err.println("Failed to load deck: " + deckInfo.name);
                    }
                }
            }
            return decks;

        } else if (options.deckDirectory != null) {
            System.out.println("Loading decks from: " + options.deckDirectory);
            return tester.loadDecksFromDirectory(options.deckDirectory);

        } else {
            throw new IllegalArgumentException(
                    "Must specify either --download or --deck-dir");
        }
    }

    /**
     * Print test results.
     */
    private static void printResults(TestResults results, Options options, long durationSeconds) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  TEST RESULTS: " + results.deckName);
        System.out.println("=".repeat(80) + "\n");

        // Overall statistics
        int totalGames = results.matchups.values().stream()
                .mapToInt(MatchupResult::getTotalGames)
                .sum();

        int totalWins = results.matchups.values().stream()
                .mapToInt(m -> m.wins)
                .sum();

        int totalLosses = results.matchups.values().stream()
                .mapToInt(m -> m.losses)
                .sum();

        int totalDraws = results.matchups.values().stream()
                .mapToInt(m -> m.draws)
                .sum();

        int totalErrors = results.matchups.values().stream()
                .mapToInt(m -> m.errors)
                .sum();

        int validGames = totalWins + totalLosses + totalDraws;

        System.out.println("OVERALL PERFORMANCE");
        System.out.println("-".repeat(80));
        System.out.printf("Total Games:        %d%n", totalGames);
        System.out.printf("Valid Games:        %d%n", validGames);
        System.out.printf("Total Wins:         %d (%.1f%%)%n", totalWins,
                validGames > 0 ? (double) totalWins / validGames * 100 : 0);
        System.out.printf("Total Losses:       %d (%.1f%%)%n", totalLosses,
                validGames > 0 ? (double) totalLosses / validGames * 100 : 0);
        System.out.printf("Total Draws:        %d (%.1f%%)%n", totalDraws,
                validGames > 0 ? (double) totalDraws / validGames * 100 : 0);
        System.out.printf("Total Errors:       %d (timeouts/crashes)%n", totalErrors);
        System.out.printf("Overall Win Rate:   %.2f%%%n%n", results.getOverallWinRate() * 100);

        // Best matchups
        System.out.println("BEST MATCHUPS (Top 10)");
        System.out.println("-".repeat(80));
        System.out.printf("%-40s %12s %10s %10s%n", "Opponent", "W-L-D-E", "Win Rate", "Avg Turns");
        System.out.println("-".repeat(80));

        List<MatchupResult> bestMatchups = results.getBestMatchups(10);
        for (MatchupResult matchup : bestMatchups) {
            System.out.printf("%-40s %3d-%3d-%2d-%2d  %9.1f%%  %9.1f%n",
                    truncate(matchup.opponentName, 40),
                    matchup.wins,
                    matchup.losses,
                    matchup.draws,
                    matchup.errors,
                    matchup.getWinRate() * 100,
                    matchup.getAverageTurns());
        }

        // Worst matchups
        System.out.println("\nWORST MATCHUPS (Bottom 10)");
        System.out.println("-".repeat(80));
        System.out.printf("%-40s %12s %10s %10s%n", "Opponent", "W-L-D-E", "Win Rate", "Avg Turns");
        System.out.println("-".repeat(80));

        List<MatchupResult> worstMatchups = results.getWorstMatchups(10);
        for (MatchupResult matchup : worstMatchups) {
            System.out.printf("%-40s %3d-%3d-%2d-%2d  %9.1f%%  %9.1f%n",
                    truncate(matchup.opponentName, 40),
                    matchup.wins,
                    matchup.losses,
                    matchup.draws,
                    matchup.errors,
                    matchup.getWinRate() * 100,
                    matchup.getAverageTurns());
        }

        // Performance metrics
        System.out.println("\nPERFORMANCE METRICS");
        System.out.println("-".repeat(80));
        System.out.printf("Total Test Duration:  %02d:%02d:%02d%n",
                durationSeconds / 3600,
                (durationSeconds % 3600) / 60,
                durationSeconds % 60);
        System.out.printf("Games per Second:     %.2f%n",
                (double) totalGames / durationSeconds);
        System.out.printf("Matchups Tested:      %d%n", results.matchups.size());

        System.out.println("\n" + "=".repeat(80) + "\n");
    }

    /**
     * Save results to file.
     */
    private static void saveResults(TestResults results, Options options) throws IOException {
        System.out.println("Saving results to: " + options.outputFile);

        try (PrintWriter writer = new PrintWriter(new FileWriter(options.outputFile))) {
            // Header
            writer.println("Forge Deck Tester Results");
            writer.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            writer.println("Test Deck: " + results.deckName);
            writer.println("Overall Win Rate: " + String.format("%.2f%%", results.getOverallWinRate() * 100));
            writer.println();

            // Detailed matchup results
            writer.println("Matchup,Wins,Losses,Draws,WinRate,AvgTurns");

            List<MatchupResult> sortedResults = results.matchups.values().stream()
                    .sorted(Comparator.comparingDouble(MatchupResult::getWinRate).reversed())
                    .collect(Collectors.toList());

            for (MatchupResult matchup : sortedResults) {
                writer.printf("%s,%d,%d,%d,%.4f,%.2f%n",
                        matchup.opponentName.replaceAll(",", ";"),
                        matchup.wins,
                        matchup.losses,
                        matchup.draws,
                        matchup.getWinRate(),
                        matchup.getAverageTurns());
            }
        }

        System.out.println("Results saved successfully");
    }

    /**
     * Parse command-line arguments.
     */
    private static Options parseArgs(String[] args) {
        Options options = new Options();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-h":
                case "--help":
                    options.showHelp = true;
                    break;

                case "-v":
                case "--version":
                    options.showVersion = true;
                    break;

                case "-d":
                case "--deck":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing deck path after " + arg);
                    }
                    options.testDeckPath = args[++i];
                    break;

                case "--deck-dir":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing directory path after " + arg);
                    }
                    options.deckDirectory = args[++i];
                    break;

                case "--download":
                    options.downloadDecks = true;
                    break;

                case "--clear-cache":
                    options.clearCache = true;
                    break;

                case "--force-refresh":
                    options.forceRefresh = true;
                    break;

                case "-n":
                case "--games":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing number after " + arg);
                    }
                    options.gamesPerMatchup = Integer.parseInt(args[++i]);
                    break;

                case "--top":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing number after " + arg);
                    }
                    options.topDecksCount = Integer.parseInt(args[++i]);
                    break;

                case "-o":
                case "--output":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing output file after " + arg);
                    }
                    options.outputFile = args[++i];
                    break;

                case "--ai-profile":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing AI profile name after " + arg);
                    }
                    String profile = args[++i];
                    if (!profile.equals("Default") && !profile.equals("Cautious") &&
                        !profile.equals("Reckless") && !profile.equals("Experimental")) {
                        throw new IllegalArgumentException("Invalid AI profile. Choose: Default, Cautious, Reckless, or Experimental");
                    }
                    options.aiProfile = profile;
                    break;

                case "--live":
                    options.showLiveProgress = true;
                    break;

                case "--verbose":
                    options.verbose = true;
                    break;

                case "--commander-opponents":
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing number after " + arg);
                    }
                    int opponents = Integer.parseInt(args[++i]);
                    if (opponents < 1 || opponents > 4) {
                        throw new IllegalArgumentException("Commander opponents must be between 1 and 4");
                    }
                    options.commanderOpponents = opponents;
                    break;

                default:
                    throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }

        // Validate options
        if (!options.showHelp && !options.showVersion) {
            if (options.testDeckPath == null) {
                throw new IllegalArgumentException("Test deck path is required (-d/--deck)");
            }

            if (!options.downloadDecks && options.deckDirectory == null) {
                throw new IllegalArgumentException(
                        "Must specify either --download or --deck-dir");
            }
        }

        return options;
    }

    /**
     * Print help message.
     */
    private static void printHelp() {
        System.out.println("Forge Deck Tester - Automated AI vs AI Deck Testing");
        System.out.println();
        System.out.println("Usage: java -jar forge-deck-tester.jar [OPTIONS]");
        System.out.println();
        System.out.println("Required Options:");
        System.out.println("  -d, --deck PATH          Path to test deck file (.dck)");
        System.out.println();
        System.out.println("Opponent Deck Options (choose one):");
        System.out.println("  --download               Download top decks from MTGGoldfish");
        System.out.println("  --deck-dir PATH          Load opponent decks from directory");
        System.out.println();
        System.out.println("Optional Settings:");
        System.out.println("  -n, --games NUM          Games per matchup (default: " + DEFAULT_GAMES + ")");
        System.out.println("  --top NUM                Number of top decks to download (default: " + DEFAULT_TOP_DECKS + ")");
        System.out.println("  -o, --output FILE        Save results to CSV file");
        System.out.println("  --ai-profile PROFILE     AI difficulty (Default, Cautious, Reckless, Experimental)");
        System.out.println("  --live                   Show live game progress (turn, phase, life totals)");
        System.out.println("  --commander-opponents N  Number of AI opponents in Commander (1-4, default: 1)");
        System.out.println();
        System.out.println("Cache Options:");
        System.out.println("  --force-refresh          Re-download decks ignoring cache");
        System.out.println("  --clear-cache            Clear cached decks before running");
        System.out.println();
        System.out.println("Other Options:");
        System.out.println("  -h, --help               Show this help message");
        System.out.println("  -v, --version            Show version information");
        System.out.println("  --verbose                Show all internal Forge errors and warnings");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Test deck against downloaded top 100 decks (uses cache)");
        System.out.println("  java -jar forge-deck-tester.jar -d mydeck.dck --download --top 100 -n 1000");
        System.out.println();
        System.out.println("  # Force refresh to get latest metagame decks");
        System.out.println("  java -jar forge-deck-tester.jar -d mydeck.dck --download --force-refresh");
        System.out.println();
        System.out.println("  # Test deck against local deck collection");
        System.out.println("  java -jar forge-deck-tester.jar -d mydeck.dck --deck-dir ./decks -n 500");
        System.out.println();
        System.out.println("  # Save results to CSV");
        System.out.println("  java -jar forge-deck-tester.jar -d mydeck.dck --download -o results.csv");
        System.out.println();
        System.out.println("Note: Downloaded decks are cached for 7 days in ./mtggoldfish_decks/");
    }

    /**
     * Print banner.
     */
    private static void printBanner() {
        System.out.println();
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                                                                           ║");
        System.out.println("║                     FORGE AUTOMATED DECK TESTER                           ║");
        System.out.println("║                          AI vs AI Simulation                              ║");
        System.out.println("║                              v" + VERSION + "                                      ║");
        System.out.println("║                                                                           ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    /**
     * Truncate string to max length.
     */
    private static String truncate(String str, int maxLength) {
        if (str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength - 3) + "...";
    }

    /**
     * Command-line options.
     */
    private static class Options {
        String testDeckPath = null;
        String deckDirectory = null;
        boolean downloadDecks = false;
        boolean clearCache = false;
        boolean forceRefresh = false;
        int gamesPerMatchup = DEFAULT_GAMES;
        int topDecksCount = DEFAULT_TOP_DECKS;
        String outputFile = null;
        String aiProfile = "Default";
        boolean showLiveProgress = false;
        boolean verbose = false;
        boolean showHelp = false;
        boolean showVersion = false;
        int commanderOpponents = 1; // 1-4 opponents in Commander
    }
}
