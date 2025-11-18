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

        DeckTester tester = new DeckTester();

        try {
            // Step 1: Get or download opponent decks
            List<Deck> opponentDecks = getOpponentDecks(options);

            if (opponentDecks.isEmpty()) {
                System.err.println("No opponent decks found!");
                return;
            }

            System.out.printf("Loaded %d opponent decks%n%n", opponentDecks.size());

            // Step 2: Load test deck
            System.out.println("Loading test deck: " + options.testDeckPath);
            Deck testDeck = tester.loadDeck(options.testDeckPath);
            System.out.printf("Test deck loaded: %s (%d cards)%n%n",
                    testDeck.getName(), testDeck.getMain().countAll());

            // Step 3: Run tests
            System.out.println("AI Profile: " + options.aiProfile);
            System.out.println();

            long startTime = System.currentTimeMillis();

            tester.setAiProfile(options.aiProfile);
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
        }
    }

    /**
     * Get opponent decks (download or load from directory).
     */
    private static List<Deck> getOpponentDecks(Options options) throws Exception {
        DeckTester tester = new DeckTester();
        tester.initialize();

        if (options.downloadDecks) {
            // Use default cache directory (.cache/mtggoldfish_decks)
            MTGGoldfishScraper scraper = new MTGGoldfishScraper();

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

            // Load downloaded decks from cache directory
            return tester.loadDecksFromDirectory(MTGGoldfishScraper.DEFAULT_CACHE_DIR);

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

        System.out.println("OVERALL PERFORMANCE");
        System.out.println("-".repeat(80));
        System.out.printf("Total Games:        %d%n", totalGames);
        System.out.printf("Total Wins:         %d (%.1f%%)%n", totalWins,
                (double) totalWins / (totalWins + totalLosses) * 100);
        System.out.printf("Total Losses:       %d (%.1f%%)%n", totalLosses,
                (double) totalLosses / (totalWins + totalLosses) * 100);
        System.out.printf("Total Draws:        %d (%.1f%%)%n", totalDraws,
                (double) totalDraws / totalGames * 100);
        System.out.printf("Overall Win Rate:   %.2f%%%n%n", results.getOverallWinRate() * 100);

        // Best matchups
        System.out.println("BEST MATCHUPS (Top 10)");
        System.out.println("-".repeat(80));
        System.out.printf("%-40s %10s %10s %10s%n", "Opponent", "W-L-D", "Win Rate", "Avg Turns");
        System.out.println("-".repeat(80));

        List<MatchupResult> bestMatchups = results.getBestMatchups(10);
        for (MatchupResult matchup : bestMatchups) {
            System.out.printf("%-40s %3d-%3d-%2d  %9.1f%%  %9.1f%n",
                    truncate(matchup.opponentName, 40),
                    matchup.wins,
                    matchup.losses,
                    matchup.draws,
                    matchup.getWinRate() * 100,
                    matchup.getAverageTurns());
        }

        // Worst matchups
        System.out.println("\nWORST MATCHUPS (Bottom 10)");
        System.out.println("-".repeat(80));
        System.out.printf("%-40s %10s %10s %10s%n", "Opponent", "W-L-D", "Win Rate", "Avg Turns");
        System.out.println("-".repeat(80));

        List<MatchupResult> worstMatchups = results.getWorstMatchups(10);
        for (MatchupResult matchup : worstMatchups) {
            System.out.printf("%-40s %3d-%3d-%2d  %9.1f%%  %9.1f%n",
                    truncate(matchup.opponentName, 40),
                    matchup.wins,
                    matchup.losses,
                    matchup.draws,
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
        System.out.println();
        System.out.println("Cache Options:");
        System.out.println("  --force-refresh          Re-download decks ignoring cache");
        System.out.println("  --clear-cache            Clear cached decks before running");
        System.out.println();
        System.out.println("Other Options:");
        System.out.println("  -h, --help               Show this help message");
        System.out.println("  -v, --version            Show version information");
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
        boolean showHelp = false;
        boolean showVersion = false;
    }
}
