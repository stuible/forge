# Forge Deck Tester

Automated AI vs AI deck testing tool built on the Forge MTG Rules Engine.

## Overview

Forge Deck Tester allows you to test a deck against the top metagame decks automatically. It runs AI vs AI simulations using Forge's sophisticated rules engine and AI, providing comprehensive statistics about your deck's performance.

## Features

- **Automated Testing**: Run 1000s of games automatically without manual intervention
- **MTGGoldfish Integration**: Download and test against top 100 meta decks
- **AI vs AI Simulation**: Uses Forge's advanced AI for realistic gameplay
- **Comprehensive Statistics**: Win rates, matchup analysis, game length metrics
- **Multi-threaded**: Parallel execution for fast results
- **CSV Export**: Save detailed results for further analysis
- **CLI Interface**: Easy-to-use command-line tool

## Installation

### Prerequisites

- Java 17 or later
- Maven 3.6+ (for building from source)

### Building

```bash
# From the forge root directory
mvn clean package -DskipTests

# The executable JAR will be created at:
# forge-deck-tester/target/forge-deck-tester.jar
```

## Usage

### Basic Usage

Test your deck against top 100 decks from MTGGoldfish:

```bash
java -jar forge-deck-tester.jar \
  --deck /path/to/your/deck.dck \
  --download \
  --top 100 \
  --games 1000
```

### Test Against Local Deck Collection

```bash
java -jar forge-deck-tester.jar \
  --deck /path/to/your/deck.dck \
  --deck-dir /path/to/opponent/decks \
  --games 500
```

### Save Results to CSV

```bash
java -jar forge-deck-tester.jar \
  --deck /path/to/your/deck.dck \
  --download \
  --output results.csv
```

### Force Refresh Latest Metagame

```bash
java -jar forge-deck-tester.jar \
  --deck /path/to/your/deck.dck \
  --download \
  --force-refresh \
  --top 100
```

## Command-Line Options

### Required Options

- `-d, --deck PATH` - Path to your test deck file (.dck format)

### Opponent Deck Options (choose one)

- `--download` - Download top decks from MTGGoldfish
- `--deck-dir PATH` - Load opponent decks from a directory

### Optional Settings

- `-n, --games NUM` - Number of games per matchup (default: 1000)
- `--top NUM` - Number of top decks to download (default: 100)
- `-o, --output FILE` - Save results to CSV file

### Cache Options

- `--force-refresh` - Re-download decks ignoring cache
- `--clear-cache` - Clear cached decks before running

The tool automatically caches downloaded decks for 7 days in `./mtggoldfish_decks/`. This means:
- **First run**: Downloads decks from MTGGoldfish (takes a few minutes)
- **Subsequent runs**: Uses cached decks (instant loading)
- **After 7 days**: Automatically refreshes cache on next run

Use `--force-refresh` to get the latest metagame decks immediately.

### Other Options

- `-h, --help` - Show help message
- `-v, --version` - Show version information

## Deck File Format

Decks must be in Forge .dck format:

```
[metadata]
Name=My Deck
Format=Standard

[Main]
4 Lightning Bolt
4 Monastery Swiftspear
4 Eidolon of the Great Revel
20 Mountain
...

[Sideboard]
3 Tormod's Crypt
2 Grafdigger's Cage
...
```

You can create decks using Forge's deck editor or convert from other formats.

## Output

The tool provides:

### Console Output

- Real-time progress updates
- Overall performance statistics
- Top 10 best matchups
- Bottom 10 worst matchups
- Performance metrics (games/second, total duration)

Example:

```
================================================================================
  TEST RESULTS: Mono Red Aggro
================================================================================

OVERALL PERFORMANCE
--------------------------------------------------------------------------------
Total Games:        100000
Total Wins:         54230 (54.2%)
Total Losses:       45670 (45.7%)
Total Draws:        100 (0.1%)
Overall Win Rate:   54.30%

BEST MATCHUPS (Top 10)
--------------------------------------------------------------------------------
Opponent                                   W-L-D    Win Rate  Avg Turns
--------------------------------------------------------------------------------
Control Deck A                           750-250-0      75.0%        8.5
Combo Deck B                             680-320-0      68.0%        6.2
...

WORST MATCHUPS (Bottom 10)
--------------------------------------------------------------------------------
Opponent                                   W-L-D    Win Rate  Avg Turns
--------------------------------------------------------------------------------
Aggro Deck X                             250-750-0      25.0%        5.1
...
```

### CSV Output (Optional)

Detailed matchup data in CSV format:

```csv
Matchup,Wins,Losses,Draws,WinRate,AvgTurns
Control Deck A,750,250,0,0.7500,8.53
Combo Deck B,680,320,0,0.6800,6.21
...
```

## Performance

- Uses multi-threading (# of CPU cores)
- Typical speed: 50-200 games/second (depending on hardware and deck complexity)
- 100k games against 100 decks: ~15-30 minutes on modern hardware

## Architecture

The tool leverages several Forge modules:

- **forge-core**: Deck management and card database
- **forge-game**: Complete MTG rules engine
- **forge-ai**: Sophisticated AI player with simulation-based decision making
- **forge-gui**: Game orchestration without GUI dependencies

## Limitations

- AI is strong but not perfect (may not play optimally in all situations)
- Complex interactions may have edge case bugs
- Some cards may not be fully implemented
- Results are probabilistic (variance exists in small sample sizes)

## Tips for Best Results

1. **Use adequate sample sizes**: 1000+ games per matchup recommended
2. **Test relevant metagame**: Use current top decks for accurate results
3. **Multiple test runs**: Run tests multiple times to account for variance
4. **Analyze matchups**: Look at both overall win rate and specific matchups
5. **Consider sideboarding**: Test both game 1 and post-sideboard configurations

## Troubleshooting

For detailed troubleshooting, see **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)**.

### Quick Fixes

#### Out of Memory Errors

Increase JVM heap size:

```bash
java -Xmx4G -jar forge-deck-tester.jar ...
```

### Slow Performance

- Reduce number of games per matchup
- Reduce number of opponent decks
- Check that you're not running other heavy processes

### Deck Loading Errors

- Ensure deck file is in proper .dck format
- Check that all card names are spelled correctly
- Verify cards are implemented in Forge

## Contributing

This tool is part of the Forge project. Contributions welcome!

- Report bugs: https://github.com/Card-Forge/forge/issues
- Submit PRs: https://github.com/Card-Forge/forge/pulls
- Join Discord: https://discord.gg/HcPJNyD66a

## License

GPL-3.0 (same as Forge)

## Credits

Built on the Forge MTG Rules Engine by the Forge community.

## Example Workflow

```bash
# 1. Build the tool
cd /path/to/forge
mvn clean package -DskipTests

# 2. Create or obtain a test deck
# (Use Forge's deck editor or convert from another format)

# 3. Run comprehensive testing
java -jar forge-deck-tester/target/forge-deck-tester.jar \
  --deck my-deck.dck \
  --download \
  --top 100 \
  --games 1000 \
  --output results.csv

# 4. Analyze results
# - Review console output for overall performance
# - Open results.csv in spreadsheet software for detailed analysis
# - Identify problem matchups and adjust deck accordingly

# 5. Iterate
# - Make deck adjustments
# - Re-run tests
# - Compare results
```

## Advanced Usage

### Custom Deck Collections

Test against specific decks:

```bash
# Organize opponent decks in a directory
mkdir opponent-decks
cp deck1.dck opponent-decks/
cp deck2.dck opponent-decks/
...

# Run tests
java -jar forge-deck-tester.jar \
  --deck my-deck.dck \
  --deck-dir opponent-decks \
  --games 2000
```

### Batch Testing Multiple Decks

Create a shell script:

```bash
#!/bin/bash

for deck in decks-to-test/*.dck; do
    echo "Testing $deck"
    java -jar forge-deck-tester.jar \
        --deck "$deck" \
        --download \
        --games 1000 \
        --output "results_$(basename $deck .dck).csv"
done
```

## FAQ

**Q: How accurate is the AI?**
A: Forge's AI is sophisticated, using simulation-based decision making. It's not perfect but provides reasonable approximations of competitive play.

**Q: Can I test Commander/EDH decks?**
A: The current implementation focuses on 1v1 Constructed. Multiplayer support would require modifications.

**Q: How long does it take?**
A: Depends on hardware, deck complexity, and sample size. Typical: 100k games in 15-30 minutes.

**Q: Can I test with sideboarding?**
A: Currently tests game 1 only. Sideboarding support could be added.

**Q: Why are some win rates extreme (0% or 100%)?**
A: Either very good/bad matchups, or AI limitations with specific deck types.

**Q: Can I contribute?**
A: Yes! This is open source. Report issues, submit PRs, or suggest features.
