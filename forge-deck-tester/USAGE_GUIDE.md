# Forge Deck Tester - Usage Guide

## Quick Start

### Prerequisites
- Java 17 or later installed
- Built JAR file: `forge-deck-tester/target/forge-deck-tester.jar`

### Basic Command
```bash
java -jar forge-deck-tester/target/forge-deck-tester.jar \
  --deck YOUR_DECK.dck \
  --deck-dir OPPONENT_DECKS_DIRECTORY \
  --games 1000
```

---

## Command Line Options

### Required Options

| Option | Description | Example |
|--------|-------------|---------|
| `-d, --deck PATH` | Path to your test deck file (.dck format) | `--deck mydeck.dck` |

### Opponent Deck Options (choose one)

| Option | Description | Example |
|--------|-------------|---------|
| `--deck-dir PATH` | Load opponent decks from directory | `--deck-dir ./decks` |
| `--download` | Download top decks from MTGGoldfish* | `--download --top 100` |

\* *Note: MTGGoldfish integration is experimental and may require enhancement*

### Optional Settings

| Option | Description | Default | Example |
|--------|-------------|---------|---------|
| `-n, --games NUM` | Number of games per matchup | 1000 | `--games 500` |
| `--top NUM` | Number of top decks to download | 100 | `--top 50` |
| `-o, --output FILE` | Save results to CSV file | None | `-o results.csv` |

### Cache Options (for --download)

| Option | Description |
|--------|-------------|
| `--force-refresh` | Re-download decks ignoring cache |
| `--clear-cache` | Clear cached decks before running |

### Other Options

| Option | Description |
|--------|-------------|
| `-h, --help` | Show help message |
| `-v, --version` | Show version information |

---

## Example Usage

### 1. Test Against Local Deck Collection
```bash
java -jar forge-deck-tester.jar \
  --deck example-deck.dck \
  --deck-dir test-decks \
  --games 1000
```

**Use case**: Test your deck against a curated collection of opponent decks.

### 2. Quick Test (Fast Results)
```bash
java -jar forge-deck-tester.jar \
  --deck example-deck.dck \
  --deck-dir test-decks \
  --games 10
```

**Use case**: Quick validation during deck building (10 games = ~10 seconds).

### 3. Export Results to CSV
```bash
java -jar forge-deck-tester.jar \
  --deck example-deck.dck \
  --deck-dir test-decks \
  --games 1000 \
  --output results.csv
```

**Use case**: Statistical analysis in Excel or other tools.

### 4. Large-Scale Testing
```bash
java -jar forge-deck-tester.jar \
  --deck example-deck.dck \
  --deck-dir all-standard-decks \
  --games 1000 \
  --output full-analysis.csv
```

**Use case**: Comprehensive metagame analysis (expect several hours runtime).

---

## Deck File Format

Deck files must be in Forge `.dck` format:

```
[metadata]
Name=My Deck Name
Format=Standard

[Main]
4 Lightning Bolt
4 Monastery Swiftspear
20 Mountain
... (minimum 60 cards total)

[Sideboard]
3 Abrade
2 Unlicensed Hearse
...
```

### Important Notes
- Decks must have **at least 60 cards** in the main deck
- Card names must match Forge's card database exactly
- Sideboard is optional (not used in AI vs AI testing)

---

## Understanding the Output

### Progress Display
```
[1/100] vs Dimir Control: 550-430-20 (56.1% winrate)
[2/100] vs Rakdos Midrange: 490-510-0 (49.0% winrate)
...
```

Each line shows:
- Matchup number
- Opponent deck name
- Win-Loss-Draw record
- Win rate percentage (excluding draws)

### Summary Statistics

```
OVERALL PERFORMANCE
--------------------------------------------------------------------------------
Total Games:        100,000
Total Wins:         54,230 (54.2%)
Total Losses:       45,770 (45.8%)
Total Draws:        0 (0.0%)
Overall Win Rate:   54.23%
```

### Best/Worst Matchups

Shows top 10 and bottom 10 matchups to identify:
- **Favorable matchups**: Where your deck excels
- **Problem matchups**: Decks you struggle against

### Performance Metrics

```
Total Test Duration:  02:15:30
Games per Second:     12.3
Matchups Tested:      100
```

Helps estimate runtime for future tests.

---

## Performance Expectations

| Games per Matchup | Time per Matchup | 100 Matchups |
|-------------------|------------------|--------------|
| 10 | ~10 seconds | ~17 minutes |
| 50 | ~45 seconds | ~1.25 hours |
| 100 | ~1.5 minutes | ~2.5 hours |
| 1000 | ~15 minutes | ~25 hours |

*Times are approximate and vary based on deck complexity and CPU cores.*

**Multi-threading**: The tool automatically uses all available CPU cores for maximum performance.

---

## Tips & Best Practices

### For Quick Iteration
- Use **10-50 games** during deck building
- Test against a small, diverse set of opponent decks (5-10)
- Focus on identifying major weaknesses

### For Validation
- Use **100-500 games** before tournaments
- Test against top-tier meta decks only
- Look for consistent patterns in matchup results

### For Deep Analysis
- Use **1000+ games** for statistical significance
- Test against the full metagame (50-100 decks)
- Export to CSV for detailed analysis
- Run overnight or on dedicated hardware

### Deck Collection Tips
- Organize opponent decks by format/metagame
- Use descriptive deck names (e.g., "Dimir_Midrange_2025_Standard")
- Keep decks updated with latest meta changes
- Include both tier 1 and fringe decks for comprehensive testing

---

## Interpreting Results

### Win Rate Analysis

| Win Rate | Interpretation |
|----------|----------------|
| > 60% | Strong overall matchup spread |
| 50-60% | Competitive deck |
| 45-50% | Needs refinement |
| < 45% | Major weaknesses |

### Matchup-Specific

| Win Rate | Action |
|----------|--------|
| > 70% | Favorable - don't over-sideboard |
| 50-70% | Winnable - focus on tight play |
| 30-50% | Difficult - heavy sideboard needed |
| < 30% | Very unfavorable - consider deck choice |

### Statistical Confidence

| Games | Confidence | Use Case |
|-------|------------|----------|
| 10 | Low | Quick trends only |
| 50 | Medium | Deck building |
| 100 | Good | Pre-tournament validation |
| 1000 | High | Statistical analysis |

Standard deviation for win rate at 1000 games: ±3%

---

## Troubleshooting

### "Deck file not found"
- Check that path is correct
- Use absolute paths or relative to current directory
- Ensure file has `.dck` extension

### "No opponent decks found"
- Verify directory path is correct
- Ensure `.dck` files are present
- Check that decks have at least 60 cards

### "Can't find bundle for base name en-US"
- Ensure you're running from Forge project directory
- Verify `forge-gui/res/languages/en-US.properties` exists
- Try using absolute path to JAR file

### Slow Performance
- Reduce games per matchup
- Reduce number of opponent decks
- Close other applications to free CPU cores
- Use simpler decks (fewer complex interactions)

### Out of Memory
- Increase Java heap size: `java -Xmx4G -jar ...`
- Reduce thread pool size (edit source: `THREAD_POOL_SIZE`)
- Process fewer decks at once

---

## Advanced Usage

### Custom Java Heap Size
```bash
java -Xmx8G -jar forge-deck-tester.jar --deck mydeck.dck --deck-dir decks --games 1000
```

### Redirect Output to File
```bash
java -jar forge-deck-tester.jar \
  --deck mydeck.dck \
  --deck-dir decks \
  --games 1000 \
  2>&1 | tee test-log.txt
```

### Running in Background (Linux/Mac)
```bash
nohup java -jar forge-deck-tester.jar \
  --deck mydeck.dck \
  --deck-dir decks \
  --games 1000 \
  --output results.csv \
  > output.log 2>&1 &
```

### Batch Testing Multiple Decks
```bash
#!/bin/bash
for deck in my-decks/*.dck; do
    echo "Testing $deck"
    java -jar forge-deck-tester.jar \
        --deck "$deck" \
        --deck-dir opponent-decks \
        --games 500 \
        --output "results/$(basename $deck .dck).csv"
done
```

---

## CSV Output Format

When using `--output results.csv`, the file contains:

```csv
Matchup,Wins,Losses,Draws,WinRate,AvgTurns
Dimir Control,550,430,20,0.5612,12.5
Rakdos Midrange,490,510,0,0.4900,11.8
...
```

Fields:
- **Matchup**: Opponent deck name
- **Wins**: Games won
- **Losses**: Games lost
- **Draws**: Games drawn
- **WinRate**: Win percentage (0.0 to 1.0, excluding draws)
- **AvgTurns**: Average game length in turns

Import into Excel, Python, R, or other tools for further analysis.

---

## Support

For issues or questions:
1. Check the help: `java -jar forge-deck-tester.jar --help`
2. Review troubleshooting section above
3. Check [Forge documentation](https://github.com/Card-Forge/forge)
4. Report bugs with full error messages and deck files

---

## Next Steps

1. **Build your deck collection**: Create or obtain `.dck` files for opponent decks
2. **Start small**: Test with 10-50 games to verify everything works
3. **Scale up**: Increase to 1000 games for production analysis
4. **Iterate**: Use results to improve your deck
5. **Automate**: Create scripts for regular testing

Happy testing! 🎮⚔️
