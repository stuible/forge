# Troubleshooting Guide

## Build Issues

### Maven Not Found

**Problem**: `mvn: command not found`

**Solutions**:

1. **Install Maven via Homebrew (macOS)**:
   ```bash
   brew install maven
   ```

2. **Install Maven via apt (Linux)**:
   ```bash
   sudo apt-get install maven
   ```

3. **Install Maven manually**:
   - Download from: https://maven.apache.org/download.cgi
   - Extract and add to PATH

4. **Verify installation**:
   ```bash
   mvn --version
   ```

### Java Version Too Old

**Problem**: `error: invalid target release: 17`

**Current**: Java 11
**Required**: Java 17+

**Solutions**:

1. **Install Java 17 via Homebrew (macOS)**:
   ```bash
   brew install openjdk@17
   # Add to PATH
   echo 'export PATH="/usr/local/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
   source ~/.zshrc
   ```

2. **Install Java 17 manually**:
   - Download from: https://adoptium.net/
   - Install and set JAVA_HOME

3. **Verify installation**:
   ```bash
   java -version
   javac -version
   ```

### Build Fails with Compilation Errors

**Problem**: Code doesn't compile

**Diagnostics**:

1. **Check Java version**:
   ```bash
   java -version  # Should be 17+
   ```

2. **Clean build**:
   ```bash
   mvn clean
   mvn compile -pl forge-deck-tester -am
   ```

3. **Check for missing dependencies**:
   ```bash
   mvn dependency:tree -pl forge-deck-tester
   ```

4. **View full error output**:
   ```bash
   mvn compile -pl forge-deck-tester -am -X > build.log 2>&1
   cat build.log
   ```

## Runtime Issues

### Out of Memory Errors

**Problem**: `java.lang.OutOfMemoryError: Java heap space`

**Solutions**:

1. **Increase heap size**:
   ```bash
   java -Xmx4G -jar forge-deck-tester.jar ...
   ```

2. **Reduce workload**:
   ```bash
   # Fewer games
   --games 100

   # Fewer decks
   --top 20
   ```

3. **Monitor memory usage**:
   ```bash
   # macOS
   top -pid $(pgrep -f forge-deck-tester)

   # Linux
   htop -p $(pgrep -f forge-deck-tester)
   ```

### Slow Performance

**Problem**: Games running very slowly

**Diagnostics**:

1. **Check CPU usage** (should be near 100%):
   ```bash
   top
   ```

2. **Check disk I/O**:
   ```bash
   iostat 1
   ```

3. **Check thread count**:
   ```bash
   jstack <pid> | grep "Thread" | wc -l
   ```

**Solutions**:

1. **Reduce concurrent games** (edit DeckTester.java):
   ```java
   private static final int THREAD_POOL_SIZE = 4; // Instead of all cores
   ```

2. **Close other applications**

3. **Check system resources**:
   ```bash
   # macOS
   system_profiler SPHardwareDataType

   # Linux
   lscpu
   free -h
   ```

### Deck Loading Errors

**Problem**: `Failed to load deck: <filename>`

**Common Causes**:

1. **Invalid .dck format**
   - Must have `[metadata]` and `[Main]` sections
   - Card names must be exact (case-sensitive)
   - Quantities must be numbers

2. **Unimplemented cards**
   - Some cards may not be implemented in Forge
   - Check Forge's card database

3. **Encoding issues**
   - Use UTF-8 encoding
   - Avoid special characters

**Solutions**:

1. **Validate deck format**:
   ```bash
   cat your-deck.dck
   ```

   Should look like:
   ```
   [metadata]
   Name=Deck Name
   [Main]
   4 Card Name
   4 Another Card
   ...
   ```

2. **Test with example deck**:
   ```bash
   java -jar forge-deck-tester.jar --deck example-deck.dck --deck-dir test-decks --games 1
   ```

3. **Check for hidden characters**:
   ```bash
   cat -A your-deck.dck
   ```

### Web Scraping Failures

**Problem**: `Failed to download deck` or `Could not parse any decks`

**Common Causes**:

1. **No internet connection**
2. **MTGGoldfish website down**
3. **MTGGoldfish changed HTML structure**
4. **Rate limiting/blocked**

**Solutions**:

1. **Check internet connection**:
   ```bash
   ping -c 3 www.mtggoldfish.com
   ```

2. **Try manual download**:
   ```bash
   curl -I https://www.mtggoldfish.com/metagame/standard/full
   ```

3. **Use cached decks**:
   ```bash
   # If you've downloaded before, use cache
   java -jar forge-deck-tester.jar --deck mydeck.dck --download --top 100
   ```

4. **Use local deck directory instead**:
   ```bash
   # Download decks manually and use --deck-dir
   java -jar forge-deck-tester.jar --deck mydeck.dck --deck-dir ./my-decks
   ```

5. **Check for rate limiting**:
   - Wait 5-10 minutes
   - Script includes 500ms delay between requests
   - Too many requests may trigger temporary block

### Cache Issues

**Problem**: Cache not working or stale data

**Solutions**:

1. **Check cache location**:
   ```bash
   ls -la ./mtggoldfish_decks/
   cat ./mtggoldfish_decks/.cache_info.txt
   ```

2. **Clear cache**:
   ```bash
   java -jar forge-deck-tester.jar --deck mydeck.dck --download --clear-cache
   ```

3. **Force refresh**:
   ```bash
   java -jar forge-deck-tester.jar --deck mydeck.dck --download --force-refresh
   ```

4. **Manual cache clear**:
   ```bash
   rm -rf ./mtggoldfish_decks
   ```

5. **Check cache permissions**:
   ```bash
   ls -la ./mtggoldfish_decks/
   chmod -R u+rw ./mtggoldfish_decks/
   ```

## Game Simulation Issues

### Games Timing Out

**Problem**: Games taking forever or hanging

**Solutions**:

1. **Check for infinite loops** (rare):
   - Check game log output
   - Look for repeated patterns

2. **Set timeout** (modify DeckTester.java):
   ```java
   rules.setSimTimeout(30); // 30 seconds per game
   ```

3. **Skip problematic matchups**:
   - Note which deck causes issues
   - Remove from opponent deck directory

### Unrealistic Results

**Problem**: Win rates seem wrong (0%, 100%, or unexpected)

**Possible Causes**:

1. **AI limitations**:
   - AI may not play certain deck types well
   - Complex combos may not be executed optimally

2. **Unimplemented cards**:
   - Missing card implementations = weaker deck
   - Check Forge card database

3. **Sample size too small**:
   - Run more games (--games 1000+)
   - More games = more accurate results

4. **Deck construction issues**:
   - Invalid mana base
   - Missing key cards
   - Format mismatch (wrong card pool)

**Solutions**:

1. **Increase sample size**:
   ```bash
   --games 2000
   ```

2. **Verify deck lists**:
   ```bash
   cat deck1.dck
   cat deck2.dck
   ```

3. **Test specific matchup manually**:
   ```bash
   java -jar forge-deck-tester.jar --deck deck1.dck --deck-dir single-opponent/ --games 100
   ```

4. **Check AI profile**:
   - AI uses "Default" profile
   - Some deck types may need specific AI tuning

## Output Issues

### CSV File Corrupt or Unreadable

**Problem**: CSV won't open in Excel/spreadsheet software

**Solutions**:

1. **Check file exists and has content**:
   ```bash
   ls -lh results.csv
   head results.csv
   ```

2. **Check file encoding**:
   ```bash
   file results.csv
   ```

3. **Convert encoding if needed**:
   ```bash
   iconv -f UTF-8 -t WINDOWS-1252 results.csv > results-win.csv
   ```

4. **Open with specific tool**:
   - Excel: Import as CSV, UTF-8 encoding
   - Google Sheets: Import, UTF-8
   - LibreOffice: Import, UTF-8, comma delimiter

### No Console Output

**Problem**: Program runs but shows nothing

**Solutions**:

1. **Check if running**:
   ```bash
   ps aux | grep forge-deck-tester
   ```

2. **Flush stdout** (should be automatic)

3. **Redirect to file**:
   ```bash
   java -jar forge-deck-tester.jar ... 2>&1 | tee output.log
   ```

## Platform-Specific Issues

### macOS Issues

**Problem**: Security warnings or app won't run

**Solutions**:

1. **Allow Java execution**:
   ```bash
   xattr -d com.apple.quarantine forge-deck-tester.jar
   ```

2. **Check Gatekeeper**:
   - System Preferences > Security & Privacy
   - Allow apps from identified developers

### Linux Issues

**Problem**: Permission denied or missing libraries

**Solutions**:

1. **Make scripts executable**:
   ```bash
   chmod +x build-and-test.sh demo-usage.sh
   ```

2. **Install dependencies**:
   ```bash
   sudo apt-get install openjdk-17-jdk maven
   ```

### Windows Issues

**Problem**: Path issues or command not found

**Solutions**:

1. **Use Git Bash or WSL**

2. **Set JAVA_HOME**:
   ```cmd
   set JAVA_HOME=C:\Program Files\Java\jdk-17
   set PATH=%JAVA_HOME%\bin;%PATH%
   ```

3. **Use backslashes in paths**:
   ```cmd
   java -jar forge-deck-tester.jar --deck C:\decks\my-deck.dck --deck-dir C:\decks\opponents
   ```

## Getting Help

### Gather Debug Information

```bash
# System info
uname -a
java -version
mvn --version

# Disk space
df -h

# Memory
free -h   # Linux
vm_stat   # macOS

# Process info
ps aux | grep java

# Thread dump (if running)
jstack <pid> > threads.txt

# Heap dump (if running)
jmap -dump:format=b,file=heap.bin <pid>
```

### Report a Bug

Include:
1. Full error message
2. Command used
3. Java version
4. OS version
5. Steps to reproduce
6. Relevant logs

### Community Support

- GitHub Issues: https://github.com/Card-Forge/forge/issues
- Discord: https://discord.gg/HcPJNyD66a
- Forum: http://www.slightlymagic.net/forum/

## Quick Fixes Checklist

- [ ] Java 17+ installed?
- [ ] Maven installed?
- [ ] Built with `mvn clean package -DskipTests`?
- [ ] JAR exists at `forge-deck-tester/target/forge-deck-tester.jar`?
- [ ] Deck file in correct .dck format?
- [ ] Internet connection working (if using --download)?
- [ ] Enough disk space (check `df -h`)?
- [ ] Enough memory (try `-Xmx4G`)?
- [ ] Scripts executable (`chmod +x *.sh`)?
- [ ] Tried with example deck first?

## Common Error Messages

### `NoClassDefFoundError`

**Cause**: Missing dependency or classpath issue

**Fix**: Rebuild with maven-shade-plugin (already configured):
```bash
mvn clean package -DskipTests
```

### `UnsupportedClassVersionError`

**Cause**: JAR compiled with Java 17, running with older Java

**Fix**: Upgrade to Java 17+:
```bash
java -version  # Check current version
# Install Java 17+ (see above)
```

### `FileNotFoundException: deck.dck`

**Cause**: Deck file doesn't exist or wrong path

**Fix**: Use absolute path:
```bash
java -jar forge-deck-tester.jar --deck /full/path/to/deck.dck ...
```

### `IllegalArgumentException: Must specify either --download or --deck-dir`

**Cause**: Missing opponent deck source

**Fix**: Add one of:
```bash
--download              # Download from MTGGoldfish
--deck-dir /path/to/decks  # Use local decks
```

### `IOException: Failed to download deck`

**Cause**: Network issue or MTGGoldfish unavailable

**Fix**:
1. Check internet: `ping www.mtggoldfish.com`
2. Try again later
3. Use `--deck-dir` with local decks instead

## Performance Optimization

### Speed Up Testing

1. **Reduce games**:
   ```bash
   --games 100  # Instead of 1000
   ```

2. **Test fewer decks**:
   ```bash
   --top 20  # Instead of 100
   ```

3. **Use simpler decks**:
   - Aggro decks run faster than control
   - Fewer triggered abilities = faster

4. **Allocate more memory**:
   ```bash
   java -Xmx4G -jar forge-deck-tester.jar ...
   ```

### Maximize Throughput

1. **Ensure multi-threading**:
   - Tool uses all CPU cores by default
   - Check CPU usage is near 100%

2. **Run on dedicated machine**:
   - Close other applications
   - Disable background services

3. **Use SSD** (if saving many decks):
   - Faster disk I/O for deck loading

## Still Having Issues?

1. **Check documentation**:
   - README.md
   - TESTING.md
   - This file

2. **Try minimal example**:
   ```bash
   ./demo-usage.sh
   ```

3. **Enable debug output**:
   ```bash
   java -jar forge-deck-tester.jar ... 2>&1 | tee debug.log
   ```

4. **Ask for help**:
   - Include full error message
   - Include command used
   - Include system info
   - Provide debug.log

---

**Remember**: The tool leverages Forge's complex rules engine. Some edge cases and card interactions may not be perfect, but the overall statistics should be reliable with sufficient sample sizes (1000+ games per matchup).
