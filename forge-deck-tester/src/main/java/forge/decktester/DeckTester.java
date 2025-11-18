package forge.decktester;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.gui.download.GuiDownloadService;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.ImageFetcher;

import org.jupnp.UpnpServiceConfiguration;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Automated deck testing tool that runs AI vs AI simulations.
 */
public class DeckTester {
    private static final int DEFAULT_GAMES_PER_MATCHUP = 1000;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();

    private final ExecutorService executor;
    private final Map<String, TestResults> resultsCache;
    private volatile boolean initialized = false;
    private String aiProfile = "Default";

    public DeckTester() {
        this.executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.resultsCache = new ConcurrentHashMap<>();
    }

    /**
     * Set the AI profile for both players.
     * @param profile Profile name: Default, Cautious, Reckless, or Experimental
     */
    public void setAiProfile(String profile) {
        this.aiProfile = profile;
    }

    /**
     * Initialize Forge engine.
     */
    public void initialize() throws Exception {
        if (initialized) {
            return;
        }

        System.out.println("Initializing Forge engine...");

        // Set up minimal headless GUI interface
        GuiBase.setInterface(new HeadlessGuiBase());

        // Initialize localizer manually to avoid errors
        try {
            File forgeRoot = findForgeRoot();
            File langDir = new File(forgeRoot, "forge-gui/res/languages");
            if (langDir.exists()) {
                forge.util.Localizer.getInstance().initialize("en-US", langDir.getAbsolutePath() + "/");
            }
        } catch (Exception e) {
            System.err.println("Warning: Could not initialize localizer: " + e.getMessage());
        }

        // Initialize Forge model
        FModel.initialize(null, (prefs) -> null);

        initialized = true;
        System.out.println("Forge engine initialized");
    }

    /**
     * Find the Forge root directory.
     */
    private File findForgeRoot() {
        String jarPath = DeckTester.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        try {
            File jarFile = new File(java.net.URLDecoder.decode(jarPath, "UTF-8"));
            // Navigate from forge-deck-tester/target/forge-deck-tester.jar up to forge root
            return jarFile.getParentFile().getParentFile().getParentFile();
        } catch (Exception e) {
            return new File(".");
        }
    }

    /**
     * Test a deck against multiple opponent decks.
     */
    public Map<String, MatchupResult> testDeck(
            Deck testDeck,
            List<Deck> opponentDecks,
            int gamesPerMatchup) throws Exception {

        initialize();

        System.out.printf("%nTesting deck: %s%n", testDeck.getName());
        System.out.printf("Against %d opponent decks, %d games each%n%n",
                opponentDecks.size(), gamesPerMatchup);

        Map<String, MatchupResult> results = new ConcurrentHashMap<>();
        List<Future<MatchupResult>> futures = new ArrayList<>();

        // Submit all matchups to thread pool
        for (int i = 0; i < opponentDecks.size(); i++) {
            final Deck opponent = opponentDecks.get(i);
            final int matchupNumber = i + 1;

            Future<MatchupResult> future = executor.submit(() -> {
                MatchupResult result = runMatchup(testDeck, opponent, gamesPerMatchup);

                // Print progress
                synchronized (System.out) {
                    System.out.printf("[%d/%d] vs %s: %d-%d-%d (%.1f%% winrate)%n",
                            matchupNumber,
                            opponentDecks.size(),
                            opponent.getName(),
                            result.wins,
                            result.losses,
                            result.draws,
                            result.getWinRate() * 100);
                }

                return result;
            });

            futures.add(future);
        }

        // Collect results
        for (Future<MatchupResult> future : futures) {
            try {
                MatchupResult result = future.get();
                results.put(result.opponentName, result);
            } catch (Exception e) {
                System.err.println("Error in matchup: " + e.getMessage());
            }
        }

        return results;
    }

    /**
     * Run a single matchup between two decks.
     */
    private MatchupResult runMatchup(Deck deck1, Deck deck2, int numGames) {
        MatchupResult result = new MatchupResult(deck1.getName(), deck2.getName());

        for (int i = 0; i < numGames; i++) {
            try {
                GameOutcome outcome = playGame(deck1, deck2);

                if (outcome.isDraw()) {
                    result.draws++;
                } else if (outcome.getWinningLobbyPlayer().getName().equals("AI-1")) {
                    result.wins++;
                } else {
                    result.losses++;
                }

                // Track game length
                result.totalTurns += outcome.getLastTurnNumber();

            } catch (Exception e) {
                System.err.println("Error in game " + i + ": " + e.getMessage());
                result.errors++;
            }
        }

        return result;
    }

    /**
     * Play a single game between two decks.
     */
    private GameOutcome playGame(Deck deck1, Deck deck2) {
        // Create players with specified AI profile
        List<RegisteredPlayer> players = new ArrayList<>();

        RegisteredPlayer rp1 = new RegisteredPlayer(deck1);
        rp1.setPlayer(GamePlayerUtil.createAiPlayer("AI-1", aiProfile));
        players.add(rp1);

        RegisteredPlayer rp2 = new RegisteredPlayer(deck2);
        rp2.setPlayer(GamePlayerUtil.createAiPlayer("AI-2", aiProfile));
        players.add(rp2);

        // Set up game rules
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);

        // Create match and game
        Match match = new Match(rules, players, "Test");
        Game game = match.createGame();

        // Start and run the game
        match.startGame(game);

        return game.getOutcome();
    }

    /**
     * Load deck from file.
     */
    public Deck loadDeck(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new FileNotFoundException("Deck file not found: " + filePath);
        }
        return DeckSerializer.fromFile(file);
    }

    /**
     * Load all decks from directory.
     */
    public List<Deck> loadDecksFromDirectory(String directory) throws IOException {
        List<Deck> decks = new ArrayList<>();
        Path dir = Paths.get(directory);

        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }

        Files.walk(dir)
                .filter(path -> path.toString().endsWith(".dck"))
                .forEach(path -> {
                    try {
                        Deck deck = DeckSerializer.fromFile(path.toFile());
                        if (deck != null && deck.getMain().countAll() >= 60) {
                            decks.add(deck);
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to load deck: " + path + " - " + e.getMessage());
                    }
                });

        return decks;
    }

    /**
     * Shutdown executor.
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    /**
     * Matchup result data.
     */
    public static class MatchupResult {
        public final String deckName;
        public final String opponentName;
        public int wins = 0;
        public int losses = 0;
        public int draws = 0;
        public int errors = 0;
        public long totalTurns = 0;

        public MatchupResult(String deckName, String opponentName) {
            this.deckName = deckName;
            this.opponentName = opponentName;
        }

        public int getTotalGames() {
            return wins + losses + draws;
        }

        public double getWinRate() {
            int total = wins + losses; // Exclude draws from win rate calculation
            return total > 0 ? (double) wins / total : 0.0;
        }

        public double getAverageTurns() {
            int total = getTotalGames();
            return total > 0 ? (double) totalTurns / total : 0.0;
        }
    }

    /**
     * Overall test results.
     */
    public static class TestResults {
        public final String deckName;
        public final Map<String, MatchupResult> matchups;

        public TestResults(String deckName, Map<String, MatchupResult> matchups) {
            this.deckName = deckName;
            this.matchups = matchups;
        }

        public double getOverallWinRate() {
            int totalWins = 0;
            int totalGames = 0;

            for (MatchupResult result : matchups.values()) {
                totalWins += result.wins;
                totalGames += result.wins + result.losses;
            }

            return totalGames > 0 ? (double) totalWins / totalGames : 0.0;
        }

        public List<MatchupResult> getBestMatchups(int count) {
            return matchups.values().stream()
                    .sorted(Comparator.comparingDouble(MatchupResult::getWinRate).reversed())
                    .limit(count)
                    .collect(Collectors.toList());
        }

        public List<MatchupResult> getWorstMatchups(int count) {
            return matchups.values().stream()
                    .sorted(Comparator.comparingDouble(MatchupResult::getWinRate))
                    .limit(count)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Minimal headless GUI implementation for AI vs AI simulation.
     */
    private static class HeadlessGuiBase implements IGuiBase {
        @Override
        public boolean isRunningOnDesktop() {
            return true;
        }

        @Override
        public boolean isLibgdxPort() {
            return false;
        }

        @Override
        public String getCurrentVersion() {
            return "1.0.0-deck-tester";
        }

        @Override
        public String getAssetsDir() {
            // Return the forge-gui directory (not res subdirectory)
            // because Forge appends "res/" to this path
            try {
                File forgeRoot = new DeckTester().findForgeRoot();
                File guiDir = new File(forgeRoot, "forge-gui");
                if (guiDir.exists()) {
                    return guiDir.getAbsolutePath() + "/";
                }
            } catch (Exception e) {
                System.err.println("Warning: Could not locate assets directory: " + e.getMessage());
            }
            return "";
        }

        @Override
        public ImageFetcher getImageFetcher() {
            return null;
        }

        @Override
        public void invokeInEdtNow(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void invokeInEdtLater(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void invokeInEdtAndWait(Runnable proc) {
            proc.run();
        }

        @Override
        public boolean isGuiThread() {
            return true;
        }

        @Override
        public ISkinImage getSkinIcon(FSkinProp skinProp) {
            return null;
        }

        @Override
        public ISkinImage getUnskinnedIcon(String path) {
            return null;
        }

        @Override
        public ISkinImage getCardArt(PaperCard card) {
            return null;
        }

        @Override
        public ISkinImage getCardArt(PaperCard card, boolean backFace) {
            return null;
        }

        @Override
        public ISkinImage createLayeredImage(PaperCard card, FSkinProp background,
                                              String overlayFilename, float opacity) {
            return null;
        }

        @Override
        public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
            System.err.println("Bug Report: " + title + " - " + text);
        }

        @Override
        public void showImageDialog(ISkinImage image, String message, String title) {
            // No-op for headless
        }

        @Override
        public int showOptionDialog(String message, String title, FSkinProp icon,
                                     java.util.List<String> options, int defaultOption) {
            return defaultOption;
        }

        @Override
        public String showInputDialog(String message, String title, FSkinProp icon,
                                       String initialInput, java.util.List<String> inputOptions,
                                       boolean isNumeric) {
            return initialInput;
        }

        @Override
        public <T> java.util.List<T> getChoices(String message, int min, int max,
                                                 java.util.Collection<T> choices,
                                                 java.util.Collection<T> selected,
                                                 java.util.function.Function<T, String> display) {
            return new java.util.ArrayList<>(selected);
        }

        @Override
        public <T> java.util.List<T> order(String title, String top, int remainingObjectsMin,
                                            int remainingObjectsMax, java.util.List<T> sourceChoices,
                                            java.util.List<T> destChoices) {
            return destChoices;
        }

        @Override
        public String showFileDialog(String title, String defaultDir) {
            return defaultDir;
        }

        @Override
        public File getSaveFile(File defaultFile) {
            return defaultFile;
        }

        @Override
        public void download(GuiDownloadService service, java.util.function.Consumer<Boolean> callback) {
            // No-op
            if (callback != null) {
                callback.accept(false);
            }
        }

        @Override
        public void refreshSkin() {
            // No-op
        }

        @Override
        public void showCardList(String title, String message, java.util.List<PaperCard> list) {
            // No-op
        }

        @Override
        public boolean showBoxedProduct(String title, String message, java.util.List<PaperCard> list) {
            return false;
        }

        @Override
        public PaperCard chooseCard(String title, String message, java.util.List<PaperCard> list) {
            return list.isEmpty() ? null : list.get(0);
        }

        @Override
        public int getAvatarCount() {
            return 0;
        }

        @Override
        public int getSleevesCount() {
            return 0;
        }

        @Override
        public void copyToClipboard(String text) {
            // No-op
        }

        @Override
        public void browseToUrl(String url) throws java.io.IOException, java.net.URISyntaxException {
            // No-op
        }

        @Override
        public IAudioClip createAudioClip(String filename) {
            return null;
        }

        @Override
        public IAudioMusic createAudioMusic(String filename) {
            return null;
        }

        @Override
        public void startAltSoundSystem(String filename, boolean isSynchronized) {
            // No-op
        }

        @Override
        public void clearImageCache() {
            // No-op
        }

        @Override
        public void showSpellShop() {
            // No-op
        }

        @Override
        public void showBazaar() {
            // No-op
        }

        @Override
        public IGuiGame getNewGuiGame() {
            return null;
        }

        @Override
        public HostedMatch hostMatch() {
            return null;
        }

        @Override
        public void runBackgroundTask(String message, Runnable task) {
            task.run();
        }

        @Override
        public String encodeSymbols(String str, boolean formatReminderText) {
            return str;
        }

        @Override
        public void preventSystemSleep(boolean preventSleep) {
            // No-op for headless
        }

        @Override
        public float getScreenScale() {
            return 1.0f;
        }

        @Override
        public UpnpServiceConfiguration getUpnpPlatformService() {
            return null;
        }
    }
}
