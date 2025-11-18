#!/bin/bash
# Forge Deck Tester - Demo Script
# Demonstrates complete workflow

JAVA="/opt/homebrew/Cellar/openjdk/25.0.1/libexec/openjdk.jdk/Contents/Home/bin/java"
JAR="target/forge-deck-tester.jar"
DECK="example-deck.dck"

echo "========================================="
echo "Forge Deck Tester - Complete Demo"
echo "========================================="
echo ""

echo "1. Quick test with local decks (5 games)"
echo "-----------------------------------------"
$JAVA -jar $JAR --deck $DECK --deck-dir test-decks --games 5
echo ""

echo "2. Download and test MTGGoldfish decks (10 games)"
echo "--------------------------------------------------"
$JAVA -jar $JAR --deck $DECK --download --top 3 --games 10
echo ""

echo "========================================="
echo "Demo Complete!"
echo "========================================="
echo ""
echo "Next steps:"
echo "  - For full analysis: --games 1000"
echo "  - For more decks: --top 100"
echo "  - Export results: --output results.csv"
echo ""
