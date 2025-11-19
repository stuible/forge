package forge.decktester;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.*;
import forge.game.player.Player;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Automated deck testing tool that runs AI vs AI simulations.
 */
public class DeckTester {
    private static final int DEFAULT_GAMES_PER_MATCHUP = 1000;
    private static final int THREAD_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final boolean USE_PROCESS_PARALLELISM = true; // Use separate processes for true parallelism

    private final ExecutorService executor;
    private final Map<String, TestResults> resultsCache;
    private volatile boolean initialized = false;
    private String aiProfile = "Default";
    private boolean showLiveProgress = false;
    private volatile boolean cancelled = false;
    private int baseGameTimeoutSeconds = 150; // Base timeout for 2-player games
    private int commanderOpponents = 1; // Number of AI opponents in Commander games (1-4)

    // Live stats tracking
    private volatile int totalGamesPlayed = 0;
    private volatile int totalWins = 0;
    private volatile int totalLosses = 0;
    private volatile int totalDraws = 0;
    private volatile int totalErrors = 0;
    private volatile int totalGamesExpected = 0;

    // Direct output stream for live display (bypasses filters)
    private PrintStream directOut = System.out;

    // Progress output stream for worker processes
    private PrintStream progressOut = null;

    // Active game tracking for unified display
    private final Map<String, GameState> activeGames = new ConcurrentHashMap<>();
    private volatile boolean displayRunning = false;
    private Thread displayThread = null;

    // Game state snapshot for display
    private static class GameState {
        volatile int turn = 0;
        volatile String phase = "Starting";
        volatile List<Integer> playerLives = new ArrayList<>();
        volatile List<String> playerNames = new ArrayList<>();
        volatile String activePlayerName = "";
        volatile String opponentName = "";
        volatile boolean isCommander = false;
        volatile int numPlayers = 2;
    }

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
     * Set the progress output stream for worker processes.
     */
    public void setProgressOutputStream(PrintStream out) {
        this.progressOut = out;
    }

    /**
     * Enable/disable live progress display during games.
     */
    public void setShowLiveProgress(boolean show) {
        this.showLiveProgress = show;
    }

    /**
     * Set the base timeout for individual games (in seconds).
     * Actual timeout scales with player count for multiplayer games.
     */
    public void setGameTimeout(int timeoutSeconds) {
        this.baseGameTimeoutSeconds = timeoutSeconds;
    }

    /**
     * Calculate timeout based on number of players.
     * Multiplayer games take longer, so scale timeout accordingly.
     */
    private int getGameTimeout(int numPlayers) {
        // Base timeout * player count (4-player games get 4x timeout)
        return baseGameTimeoutSeconds * numPlayers;
    }

    /**
     * Cancel the current test run gracefully.
     */
    public void cancel() {
        this.cancelled = true;
    }

    /**
     * Set the number of AI opponents for Commander games (1-4).
     */
    public void setCommanderOpponents(int opponents) {
        if (opponents < 1 || opponents > 4) {
            throw new IllegalArgumentException("Commander opponents must be between 1 and 4");
        }
        this.commanderOpponents = opponents;
    }

    /**
     * Set the direct output stream for live progress (bypasses stdout filtering).
     */
    public void setDirectOutputStream(PrintStream out) {
        this.directOut = out;
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

        // Reset stats for this test run
        totalGamesPlayed = 0;
        totalWins = 0;
        totalLosses = 0;
        totalDraws = 0;
        totalErrors = 0;
        totalGamesExpected = opponentDecks.size() * gamesPerMatchup;
        activeGames.clear();
        cancelled = false;

        System.out.printf("%nTesting deck: %s%n", testDeck.getName());
        System.out.printf("Against %d opponent decks, %d games each (%d total games)%n%n",
                opponentDecks.size(), gamesPerMatchup, totalGamesExpected);

        // Start unified display thread if live progress is enabled
        // Note: With process parallelism, we show simplified progress (no turn-by-turn details)
        if (showLiveProgress) {
            if (USE_PROCESS_PARALLELISM) {
                System.out.println("Note: Live progress shows game completion stats only (process parallelism enabled).\n");
            }
            startUnifiedDisplay();
        }

        Map<String, MatchupResult> results = new ConcurrentHashMap<>();

        // Create matchup results tracking
        Map<String, MatchupResult> matchupResults = new ConcurrentHashMap<>();
        for (Deck opponent : opponentDecks) {
            matchupResults.put(opponent.getName(), new MatchupResult(testDeck.getName(), opponent.getName()));
            // Set number of players for each matchup
            boolean isCommander = isCommanderDeck(testDeck) || isCommanderDeck(opponent);
            matchupResults.get(opponent.getName()).numPlayers = isCommander ? commanderOpponents + 1 : 2;
        }

        // Submit all individual games to thread pool (not matchups)
        List<Future<Void>> futures = new ArrayList<>();
        AtomicInteger gamesCompleted = new AtomicInteger(0);

        for (Deck opponent : opponentDecks) {
            for (int gameNum = 0; gameNum < gamesPerMatchup; gameNum++) {
                final Deck oppDeck = opponent;

                Future<Void> future = executor.submit(() -> {
                    if (cancelled) return null;

                    MatchupResult result = matchupResults.get(oppDeck.getName());

                    try {
                        SimpleGameResult gameResult;

                        if (USE_PROCESS_PARALLELISM) {
                            // Use separate process for true parallelism
                            gameResult = playGameInProcess(testDeck, oppDeck);
                        } else {
                            // Use in-process execution (limited by Forge's internal locks)
                            GameOutcome outcome = playGame(testDeck, oppDeck);
                            gameResult = new SimpleGameResult(
                                outcome.isDraw() ? null : outcome.getWinningLobbyPlayer().getName(),
                                outcome.getLastTurnNumber(),
                                outcome.isDraw()
                            );
                        }

                        synchronized (result) {
                            if (gameResult.isDraw) {
                                result.draws++;
                                synchronized (this) {
                                    totalDraws++;
                                    totalGamesPlayed++;
                                }
                            } else if ("Input Deck".equals(gameResult.winner)) {
                                result.wins++;
                                synchronized (this) {
                                    totalWins++;
                                    totalGamesPlayed++;
                                }
                            } else {
                                result.losses++;
                                synchronized (this) {
                                    totalLosses++;
                                    totalGamesPlayed++;
                                }
                            }

                            result.totalTurns += gameResult.turns;
                        }

                    } catch (TimeoutException e) {
                        boolean isCmd = isCommanderDeck(testDeck) || isCommanderDeck(oppDeck);
                        int players = isCmd ? commanderOpponents + 1 : 2;
                        int timeout = getGameTimeout(players);
                        System.err.println("Game vs " + oppDeck.getName() + " (" + players + " players) timed out after " + timeout + " seconds");
                        synchronized (result) {
                            result.errors++;
                            synchronized (this) {
                                totalErrors++;
                                totalGamesPlayed++;
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Game vs " + oppDeck.getName() + " error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        synchronized (result) {
                            result.errors++;
                            synchronized (this) {
                                totalErrors++;
                                totalGamesPlayed++;
                            }
                        }
                    }

                    gamesCompleted.incrementAndGet();
                    return null;
                });

                futures.add(future);
            }
        }

        // Wait for all games to complete
        for (Future<Void> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                if (cancelled) {
                    break;
                }
            }
        }

        // If cancelled, cancel remaining futures
        if (cancelled) {
            for (Future<Void> future : futures) {
                future.cancel(true);
            }
        }

        // Print final results for each matchup
        boolean isCommanderMultiplayer = (isCommanderDeck(testDeck) && commanderOpponents > 1);

        if (isCommanderMultiplayer) {
            // For multiplayer Commander, show aggregated results
            System.out.println("\nMultiplayer Commander Results (pods with " + (commanderOpponents + 1) + " players):");
            int totalWinsAll = 0, totalLossesAll = 0, totalDrawsAll = 0, totalErrorsAll = 0;

            for (Deck opponent : opponentDecks) {
                MatchupResult result = matchupResults.get(opponent.getName());
                totalWinsAll += result.wins;
                totalLossesAll += result.losses;
                totalDrawsAll += result.draws;
                totalErrorsAll += result.errors;
                results.put(opponent.getName(), result);
            }

            synchronized (System.out) {
                if (totalErrorsAll > 0) {
                    System.out.printf("Overall: %d-%d-%d-%d (%.1f%% winrate, %d errors)%n",
                            totalWinsAll, totalLossesAll, totalDrawsAll, totalErrorsAll,
                            totalWinsAll + totalLossesAll > 0 ? (totalWinsAll * 100.0 / (totalWinsAll + totalLossesAll)) : 0,
                            totalErrorsAll);
                } else {
                    System.out.printf("Overall: %d-%d-%d (%.1f%% winrate)%n",
                            totalWinsAll, totalLossesAll, totalDrawsAll,
                            totalWinsAll + totalLossesAll > 0 ? (totalWinsAll * 100.0 / (totalWinsAll + totalLossesAll)) : 0);
                }
                System.out.println("Note: In multiplayer Commander, you played against various opponent deck combinations.");
            }
        } else {
            // For 1v1, show per-matchup results
            for (Deck opponent : opponentDecks) {
                MatchupResult result = matchupResults.get(opponent.getName());
                synchronized (System.out) {
                    if (result.errors > 0) {
                        System.out.printf("vs %s: %d-%d-%d-%d (%.1f%% winrate, %d errors)%n",
                                opponent.getName(),
                                result.wins,
                                result.losses,
                                result.draws,
                                result.errors,
                                result.getWinRate() * 100,
                                result.errors);
                    } else {
                        System.out.printf("vs %s: %d-%d-%d (%.1f%% winrate)%n",
                                opponent.getName(),
                                result.wins,
                                result.losses,
                                result.draws,
                                result.getWinRate() * 100);
                    }
                }
                results.put(opponent.getName(), result);
            }
        }

        // Stop unified display
        if (showLiveProgress) {
            stopUnifiedDisplay();
        }

        return results;
    }


    /**
     * Simple result structure for game outcomes.
     */
    private static class SimpleGameResult {
        final String winner;  // "Input Deck", "Opponent 1", or null for draw
        final int turns;
        final boolean isDraw;

        SimpleGameResult(String winner, int turns, boolean isDraw) {
            this.winner = winner;
            this.turns = turns;
            this.isDraw = isDraw;
        }
    }

    /**
     * Play a single game between two decks using a separate process for true parallelism.
     * @throws TimeoutException if the game times out
     */
    private SimpleGameResult playGameInProcess(Deck deck1, Deck deck2) throws TimeoutException, IOException, InterruptedException {
        // Save decks to temporary files
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "forge-deck-tester");
        tempDir.mkdirs();

        File deck1File = File.createTempFile("deck1-", ".dck", tempDir);
        File deck2File = File.createTempFile("deck2-", ".dck", tempDir);

        try {
            // Write decks to temp files
            DeckSerializer.writeDeck(deck1, deck1File);
            DeckSerializer.writeDeck(deck2, deck2File);

            // Build command to run worker process
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

            // Get the jar file location
            String jarPath = new File(DeckTester.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()).getPath();

            List<String> command = Arrays.asList(
                javaBin,
                "-jar", jarPath,
                "--worker",
                "--deck1", deck1File.getAbsolutePath(),
                "--deck2", deck2File.getAbsolutePath(),
                "--ai-profile", aiProfile,
                "--commander-opponents", String.valueOf(commanderOpponents)
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false); // Keep stderr separate for debugging

            Process process = pb.start();

            // Read output in parallel and parse progress updates
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();

            // Create game state for live display
            String gameId = Thread.currentThread().getName();
            GameState gameState = new GameState();
            gameState.opponentName = deck2.getName();
            boolean isCommander = isCommanderDeck(deck1) || isCommanderDeck(deck2);
            int numPlayers = isCommander ? commanderOpponents + 1 : 2;
            gameState.isCommander = isCommander;
            gameState.numPlayers = numPlayers;
            activeGames.put(gameId, gameState);

            Thread stdoutReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.append(line).append("\n");

                        // Parse progress updates
                        if (line.startsWith("PROGRESS:")) {
                            parseProgressUpdate(line, gameState);
                        }
                    }
                } catch (IOException e) {
                    // Ignore
                } finally {
                    activeGames.remove(gameId);
                }
            });

            Thread stderrReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderr.append(line).append("\n");
                    }
                } catch (IOException e) {
                    // Ignore
                }
            });

            stdoutReader.start();
            stderrReader.start();

            // Wait for process with timeout
            int timeoutSeconds = getGameTimeout(numPlayers);

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            // Wait for readers to finish
            stdoutReader.join(1000);
            stderrReader.join(1000);

            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("Worker process timed out");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Worker process failed with exit code " + exitCode +
                    "\nStdout: " + stdout.toString() +
                    "\nStderr: " + stderr.toString());
            }

            // Parse result from output
            return parseGameResult(stdout.toString());

        } catch (java.net.URISyntaxException e) {
            throw new IOException("Failed to get jar path", e);
        } finally {
            deck1File.delete();
            deck2File.delete();
        }
    }

    /**
     * Parse progress update from worker process.
     * Format: PROGRESS:turn=X|active=Name|phase=Phase|lives=20:15:30|names=Input Deck:Opponent 1:Opponent 2
     */
    private void parseProgressUpdate(String line, GameState state) {
        try {
            String data = line.substring(9); // Remove "PROGRESS:"
            String[] parts = data.split("\\|");

            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;

                switch (kv[0]) {
                    case "turn":
                        state.turn = Integer.parseInt(kv[1]);
                        break;
                    case "active":
                        state.activePlayerName = kv[1];
                        break;
                    case "phase":
                        state.phase = kv[1];
                        break;
                    case "lives":
                        state.playerLives.clear();
                        String[] lives = kv[1].split(":");
                        for (String life : lives) {
                            state.playerLives.add(Integer.parseInt(life));
                        }
                        break;
                    case "names":
                        state.playerNames.clear();
                        String[] names = kv[1].split(":");
                        for (String name : names) {
                            state.playerNames.add(name);
                        }
                        break;
                }
            }
        } catch (Exception e) {
            // Ignore malformed progress updates
        }
    }

    /**
     * Parse game result from worker process output.
     */
    private SimpleGameResult parseGameResult(String output) {
        // Look for result line: "RESULT:winner=Input Deck,turns=15,draw=false"
        for (String line : output.split("\n")) {
            if (line.startsWith("RESULT:")) {
                String[] parts = line.substring(7).split(",");
                String winner = null;
                int turns = 0;
                boolean isDraw = false;

                for (String part : parts) {
                    String[] kv = part.split("=");
                    if (kv.length == 2) {
                        switch (kv[0]) {
                            case "winner":
                                winner = kv[1].equals("null") ? null : kv[1];
                                break;
                            case "turns":
                                turns = Integer.parseInt(kv[1]);
                                break;
                            case "draw":
                                isDraw = Boolean.parseBoolean(kv[1]);
                                break;
                        }
                    }
                }

                return new SimpleGameResult(winner, turns, isDraw);
            }
        }

        throw new RuntimeException("Failed to parse game result from worker process:\n" + output);
    }

    /**
     * Play a single game directly (used by worker mode).
     * Always enables monitoring if progressOut is set.
     */
    public GameOutcome playGameDirect(Deck deck1, Deck deck2) throws TimeoutException {
        // Enable monitoring temporarily if we have a progress output stream
        boolean oldShowLiveProgress = showLiveProgress;
        if (progressOut != null) {
            showLiveProgress = true; // Force monitoring for progress output
        }

        try {
            return playGame(deck1, deck2);
        } finally {
            showLiveProgress = oldShowLiveProgress;
        }
    }

    /**
     * Play a single game between two decks (in-process version).
     * @throws TimeoutException if the game times out
     */
    private GameOutcome playGame(Deck deck1, Deck deck2) throws TimeoutException {
        // Detect format first
        boolean isCommander = isCommanderDeck(deck1) || isCommanderDeck(deck2);
        int numPlayers = isCommander ? commanderOpponents + 1 : 2;
        final int gameTimeoutSeconds = getGameTimeout(numPlayers);

        // Create players with specified AI profile
        List<RegisteredPlayer> players = new ArrayList<>();

        // Use appropriate RegisteredPlayer factory method based on format
        RegisteredPlayer rp1 = isCommander ? RegisteredPlayer.forCommander(deck1) : new RegisteredPlayer(deck1);
        rp1.setPlayer(GamePlayerUtil.createAiPlayer("Input Deck", aiProfile));
        players.add(rp1);

        RegisteredPlayer rp2 = isCommander ? RegisteredPlayer.forCommander(deck2) : new RegisteredPlayer(deck2);
        rp2.setPlayer(GamePlayerUtil.createAiPlayer("Opponent 1", aiProfile));
        players.add(rp2);

        // For Commander with multiple opponents, add additional AI players
        if (isCommander && commanderOpponents > 1) {
            for (int i = 2; i <= commanderOpponents; i++) {
                RegisteredPlayer rpExtra = RegisteredPlayer.forCommander(deck2);
                rpExtra.setPlayer(GamePlayerUtil.createAiPlayer("Opponent " + i, aiProfile));
                players.add(rpExtra);
            }
        }

        // Randomize player order for turn order (but keep track of original)
        List<RegisteredPlayer> randomizedPlayers = new ArrayList<>(players);
        Collections.shuffle(randomizedPlayers);

        // Set up appropriate game rules
        GameType gameType = isCommander ? GameType.Commander : GameType.Constructed;

        GameRules rules = new GameRules(gameType);
        if (isCommander) {
            rules.addAppliedVariant(GameType.Commander);
        }
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);

        // Create match and game with randomized player order
        Match match = new Match(rules, randomizedPlayers, "Test");
        final Game game = match.createGame();

        if (showLiveProgress) {
            // Run game in separate thread and monitor progress
            ExecutorService gameExecutor = Executors.newSingleThreadExecutor();
            Future<Void> gameFuture = gameExecutor.submit(() -> {
                match.startGame(game);
                return null;
            });
        // Performance optimization: reduce AI decision timeout from 5s to 3s
        game.AI_TIMEOUT = 3;
        game.AI_CAN_USE_TIMEOUT = true;

            // Monitor game progress in real-time (in a separate thread)
            Thread monitorThread = new Thread(() -> monitorGameProgress(game, deck1.getName(), deck2.getName(), isCommander));
            monitorThread.setDaemon(true);
            monitorThread.start();

            // Wait for game to complete with timeout
            try {
                gameFuture.get(gameTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                // Timeout - force end the game
                System.err.println("\nGame timed out - forcing end");
                gameFuture.cancel(true);
                gameExecutor.shutdownNow();
                throw e; // Re-throw to mark as error
            } catch (Exception e) {
                System.err.println("\nGame interrupted: " + e.getMessage());
                throw new RuntimeException("Game interrupted", e);
            } finally {
                gameExecutor.shutdown();
                try {
                    if (!gameExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        gameExecutor.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    gameExecutor.shutdownNow();
                }
            }
        } else {
            // Run game normally without monitoring but with timeout
            ExecutorService gameExecutor = Executors.newSingleThreadExecutor();
            Future<?> gameFuture = gameExecutor.submit(() -> match.startGame(game));
            try {
                gameFuture.get(gameTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("\nGame timed out - forcing end");
                gameFuture.cancel(true);
                gameExecutor.shutdownNow();
                throw e; // Re-throw to mark as error
            } catch (Exception e) {
                throw new RuntimeException("Game interrupted", e);
            } finally {
                gameExecutor.shutdown();
                try {
                    if (!gameExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        gameExecutor.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    gameExecutor.shutdownNow();
                }
            }
        }

        return game.getOutcome();
    }

    /**
     * Output progress to worker process stdout.
     */
    private void outputProgress(GameState state) {
        if (progressOut == null) return;

        // Format: PROGRESS:turn=X|active=Name|phase=Phase|lives=20:15:30:25|names=Input Deck:Opponent 1:Opponent 2
        StringBuilder progress = new StringBuilder("PROGRESS:");
        progress.append("turn=").append(state.turn);
        progress.append("|active=").append(state.activePlayerName);
        progress.append("|phase=").append(state.phase);
        progress.append("|lives=");
        for (int i = 0; i < state.playerLives.size(); i++) {
            if (i > 0) progress.append(":");
            progress.append(state.playerLives.get(i));
        }
        progress.append("|names=");
        for (int i = 0; i < state.playerNames.size(); i++) {
            if (i > 0) progress.append(":");
            progress.append(state.playerNames.get(i));
        }

        progressOut.println(progress.toString());
        progressOut.flush();
    }

    /**
     * Monitor game progress and update shared state for unified display.
     */
    private void monitorGameProgress(Game game, String deck1Name, String deck2Name, boolean isCommander) {
        String gameId = Thread.currentThread().getName();
        GameState state = new GameState();
        state.opponentName = deck2Name;
        state.isCommander = isCommander;

        // Initialize player tracking
        List<Player> gamePlayers = new ArrayList<>(game.getPlayers());
        state.numPlayers = gamePlayers.size();

        // Set initial life and names based on format and number of players
        int initialLife = isCommander ? 40 : 20;
        for (int i = 0; i < gamePlayers.size(); i++) {
            state.playerLives.add(initialLife);
            state.playerNames.add(gamePlayers.get(i).getName());
        }

        activeGames.put(gameId, state);

        // Output initial progress if in worker mode
        if (progressOut != null) {
            outputProgress(state);
        }

        try {
            String lastPhase = "Starting";
            while (!game.isGameOver()) {
                try {
                    Thread.sleep(50); // Check every 50ms

                    int currentTurn = game.getPhaseHandler().getTurn();
                    String currentPhase = game.getPhaseHandler().getPhase() != null ?
                        game.getPhaseHandler().getPhase().toString() : "Starting";

                    // Only update if phase changed
                    boolean phaseChanged = !currentPhase.equals(lastPhase);
                    if (!phaseChanged) {
                        continue;
                    }
                    lastPhase = currentPhase;

                    // Get active player
                    Player activePlayer = game.getPhaseHandler().getPlayerTurn();
                    String activeName = activePlayer != null ? activePlayer.getName() : "";

                    // Update shared state
                    state.turn = currentTurn;
                    state.phase = currentPhase;
                    state.activePlayerName = activeName;

                    // Update all player life totals and check if Input Deck is eliminated
                    List<Player> players = new ArrayList<>(game.getPlayers());
                    Player inputDeckPlayer = null;

                    for (int i = 0; i < players.size(); i++) {
                        if (i < state.playerLives.size()) {
                            state.playerLives.set(i, players.get(i).getLife());
                        }
                        if (players.get(i).getName().equals("Input Deck")) {
                            inputDeckPlayer = players.get(i);
                        }
                    }

                    // Output progress if worker mode (on every phase change)
                    if (progressOut != null) {
                        outputProgress(state);
                    }

                    // Early exit optimization: End game immediately if Input Deck has lost
                    if (inputDeckPlayer != null && inputDeckPlayer.hasLost()) {
                        // Input Deck is eliminated, we don't need to watch the rest
                        break;
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Game might be ending, ignore errors
                    break;
                }
            }
        } finally {
            // Remove this game from active games when done
            activeGames.remove(gameId);
        }
    }

    /**
     * Start the unified display thread that shows all active games.
     */
    private void startUnifiedDisplay() {
        displayRunning = true;
        displayThread = new Thread(() -> {
            // Clear screen once at start
            directOut.print("\033[2J\033[H");
            directOut.flush();

            while (displayRunning) {
                try {
                    // Move cursor to top instead of clearing (reduces flicker)
                    directOut.print("\033[H");

                    // Build unified display
                    StringBuilder display = new StringBuilder();

                    // Header
                    display.append("╔══════════════════════════════════════════════════════════════════════╗\n");
                    display.append("║                     LIVE TESTING DASHBOARD                           ║\n");
                    display.append("╚══════════════════════════════════════════════════════════════════════╝\n\n");

                    // Overall stats
                    int validGames = totalGamesPlayed - totalErrors;
                    double winRate = validGames > 0 ? (totalWins * 100.0 / validGames) : 0;
                    double progressPercent = totalGamesExpected > 0 ? (totalGamesPlayed * 100.0 / totalGamesExpected) : 0;

                    display.append(String.format("  OVERALL RECORD:     W:%3d / L:%3d / T:%3d  (%5.1f%% Win Rate)\n",
                        totalWins, totalLosses, totalDraws, winRate));
                    display.append(String.format("  ERRORS:             %3d  (timeouts/crashes)\n", totalErrors));
                    display.append(String.format("  PROGRESS:           %3d / %3d games  (%5.1f%% complete)\n",
                        totalGamesPlayed, totalGamesExpected, progressPercent));

                    // Progress bar
                    int barWidth = 50;
                    int filled = (int)(progressPercent / 100.0 * barWidth);
                    display.append("  [");
                    for (int i = 0; i < barWidth; i++) {
                        display.append(i < filled ? "█" : "░");
                    }
                    display.append("]\n");

                    display.append(String.format("  GAMES IN PROGRESS:  %3d\n\n",
                        activeGames.size()));

                    display.append("──────────────────────────────────────────────────────────────────────\n\n");

                    // Active games
                    if (activeGames.isEmpty()) {
                        display.append("  No games currently running...\n\n");
                    } else {
                        display.append("  ACTIVE GAMES:\n\n");

                        int gameNum = 1;
                        for (Map.Entry<String, GameState> entry : activeGames.entrySet()) {
                            GameState state = entry.getValue();
                            display.append(String.format("  Game %d vs %s", gameNum++, state.opponentName));
                            if (state.numPlayers > 2) {
                                display.append(String.format(" (%d players)", state.numPlayers));
                            }
                            display.append("\n");

                            // Calculate round number (each round = all players take a turn)
                            int roundNum = state.numPlayers > 0 ? (state.turn + state.numPlayers - 1) / state.numPlayers : state.turn;
                            display.append(String.format("    Round:  %3d  |  Phase: %-28s\n",
                                roundNum, state.phase));

                            // Display all player life totals with active player bold and leader green
                            display.append("    Players: ");

                            // Find the player with highest life (leader)
                            int maxLife = state.playerLives.stream().max(Integer::compareTo).orElse(0);
                            // Count how many players have max life (to detect ties)
                            long playersWithMaxLife = state.playerLives.stream().filter(life -> life == maxLife).count();

                            for (int i = 0; i < state.playerLives.size(); i++) {
                                if (i > 0) display.append(" | ");
                                String name = i < state.playerNames.size() ? state.playerNames.get(i) : "Player " + (i+1);
                                int life = state.playerLives.get(i);
                                boolean isActive = name.equals(state.activePlayerName);
                                // Only highlight as leader if they're alone in the lead (no tie)
                                boolean isLeader = life == maxLife && life > 0 && playersWithMaxLife == 1;

                                // Use ANSI bold for active player, green for leader
                                if (isActive) {
                                    display.append("\033[1m"); // Bold
                                }
                                if (isLeader) {
                                    display.append("\033[32m"); // Green
                                }

                                display.append(String.format("%-12s %3d", name + ":", life));

                                if (isActive || isLeader) {
                                    display.append("\033[0m"); // Reset
                                }
                            }
                            display.append("\n\n");
                        }
                    }

                    display.append("╚══════════════════════════════════════════════════════════════════════╝\n");

                    // Clear from cursor to end of screen to remove remnants
                    display.append("\033[J");

                    // Print the display
                    directOut.print(display.toString());
                    directOut.flush();

                    // Update every 500ms (reduces flicker further)
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // Continue on error
                }
            }

            // Clear screen when done
            directOut.print("\033[2J\033[H");
            directOut.flush();
        }, "UnifiedDisplay");
        displayThread.setDaemon(true);
        displayThread.start();
    }

    /**
     * Stop the unified display thread.
     */
    private void stopUnifiedDisplay() {
        displayRunning = false;
        if (displayThread != null) {
            try {
                displayThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
     * Check if a deck is Commander format.
     */
    private boolean isCommanderDeck(Deck deck) {
        // Commander decks have a Commander section or have exactly 100 cards
        return deck.has(DeckSection.Commander) || deck.getAllCardsInASinglePool().countAll() == 100;
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
        public int numPlayers = 2; // Track number of players for round calculation

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

        public double getAverageRounds() {
            int total = getTotalGames();
            if (total == 0) return 0.0;
            double avgTurns = (double) totalTurns / total;
            return numPlayers > 0 ? avgTurns / numPlayers : avgTurns;
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
