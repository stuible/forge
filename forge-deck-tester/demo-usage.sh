#!/bin/bash
# Demo Usage Script for Forge Deck Tester
# Shows various ways to use the tool

set -e

echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║                                                               ║"
echo "║        Forge Deck Tester - Usage Demonstration               ║"
echo "║                                                               ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo

JAR="target/forge-deck-tester.jar"

# Check if JAR exists
if [ ! -f "$JAR" ]; then
    echo "❌ Error: JAR not found at $JAR"
    echo "   Please build first with: mvn clean package -DskipTests"
    exit 1
fi

echo "✅ Found JAR: $JAR"
echo

# Function to run command with description
run_demo() {
    local description="$1"
    shift
    local command="$@"

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "📋 $description"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "💻 Command:"
    echo "   $command"
    echo

    read -p "Press ENTER to run (or Ctrl+C to skip)..."
    echo

    eval "$command"

    echo
    echo "✅ Completed"
    echo
}

# Demo 1: Help Command
run_demo "Display help message" \
    "java -jar $JAR --help"

# Demo 2: Version
run_demo "Display version" \
    "java -jar $JAR --version"

# Demo 3: Quick Local Test
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📋 Quick local test setup"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ ! -d "test-decks" ]; then
    echo "Creating test deck directory..."
    mkdir -p test-decks

    if [ -f "example-deck.dck" ]; then
        echo "Copying example deck as opponents..."
        cp example-deck.dck test-decks/opponent1.dck
        cp example-deck.dck test-decks/opponent2.dck
        echo "✅ Created 2 test opponent decks"
    else
        echo "⚠️  Warning: example-deck.dck not found"
        echo "   Creating minimal test decks..."

        cat > test-decks/opponent1.dck << 'EOF'
[metadata]
Name=Test Deck 1
[Main]
20 Mountain
20 Forest
20 Plains
EOF

        cat > test-decks/opponent2.dck << 'EOF'
[metadata]
Name=Test Deck 2
[Main]
20 Island
20 Swamp
20 Mountain
EOF

        cat > example-deck.dck << 'EOF'
[metadata]
Name=My Test Deck
[Main]
20 Plains
20 Island
20 Forest
EOF

        echo "✅ Created minimal test decks"
    fi
fi

echo

run_demo "Run quick local test (2 decks × 5 games = 10 games total)" \
    "java -jar $JAR --deck example-deck.dck --deck-dir test-decks --games 5"

# Demo 4: Cache demonstration
cat << 'EOF'
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 Cache Demonstration
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

The following demos show the caching system:
1. First run downloads decks (slow)
2. Second run uses cache (fast)
3. Force refresh re-downloads (slow)

EOF

read -p "Would you like to demo cache functionality? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    run_demo "Download 5 top decks (will cache)" \
        "java -jar $JAR --deck example-deck.dck --download --top 5 --games 2"

    run_demo "Re-run same test (should use cache)" \
        "java -jar $JAR --deck example-deck.dck --download --top 5 --games 2"

    run_demo "Force refresh (re-download)" \
        "java -jar $JAR --deck example-deck.dck --download --force-refresh --top 5 --games 2"
fi

# Demo 5: CSV Output
read -p "Would you like to demo CSV output? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    run_demo "Run test with CSV output" \
        "java -jar $JAR --deck example-deck.dck --deck-dir test-decks --games 10 --output demo-results.csv"

    if [ -f "demo-results.csv" ]; then
        echo "📄 CSV Output Preview:"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        head -10 demo-results.csv
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "✅ Full results saved to: demo-results.csv"
    fi
fi

# Demo 6: Full-scale test
cat << 'EOF'

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 Full-Scale Test Example
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

This would run:
- Download top 100 decks from MTGGoldfish
- Run 1000 games per matchup
- Total: 100,000 games
- Estimated time: 15-30 minutes

Command:
  java -Xmx4G -jar target/forge-deck-tester.jar \
    --deck example-deck.dck \
    --download \
    --top 100 \
    --games 1000 \
    --output full-results.csv

EOF

read -p "Would you like to run full-scale test? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo "⚠️  This will take 15-30 minutes..."
    read -p "Are you sure? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        run_demo "Full-scale test against top 100 decks" \
            "java -Xmx4G -jar $JAR --deck example-deck.dck --download --top 100 --games 1000 --output full-results.csv"
    fi
fi

echo
echo "╔═══════════════════════════════════════════════════════════════╗"
echo "║                                                               ║"
echo "║              Demo Complete!                                   ║"
echo "║                                                               ║"
echo "╚═══════════════════════════════════════════════════════════════╝"
echo
echo "📚 For more information:"
echo "   - README.md - User guide"
echo "   - TESTING.md - Testing guide"
echo "   - --help - Command-line help"
echo

exit 0
