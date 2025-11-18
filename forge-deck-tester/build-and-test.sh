#!/bin/bash

# Build and Test Script for Forge Deck Tester
# This script builds the project using Maven (if available) and runs basic tests

set -e

echo "=================================================="
echo "  Forge Deck Tester - Build and Test Script"
echo "=================================================="
echo

# Check for Maven
if command -v mvn &> /dev/null; then
    echo "✓ Maven found"
    MVN_CMD="mvn"
elif [ -f "../mvnw" ]; then
    echo "✓ Maven wrapper found"
    MVN_CMD="../mvnw"
else
    echo "✗ Error: Maven not found"
    echo "  Please install Maven 3.6+ to build this project"
    echo "  Visit: https://maven.apache.org/install.html"
    exit 1
fi

# Check Java version
echo
echo "Checking Java version..."
java -version 2>&1 | head -3
JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 11 ]; then
    echo "✗ Error: Java 17 or later is required"
    echo "  Current version: $JAVA_VERSION"
    exit 1
fi
echo "✓ Java version OK"

# Build the project
echo
echo "Building Forge Deck Tester..."
echo "This may take several minutes on first build..."
echo

cd ..
$MVN_CMD clean package -DskipTests -pl forge-deck-tester -am

if [ $? -ne 0 ]; then
    echo
    echo "✗ Build failed"
    exit 1
fi

echo
echo "✓ Build successful"
echo

# Check if JAR was created
JAR_FILE="forge-deck-tester/target/forge-deck-tester.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "✗ Error: JAR file not found at $JAR_FILE"
    exit 1
fi

echo "✓ JAR created: $JAR_FILE"
JAR_SIZE=$(du -h "$JAR_FILE" | cut -f1)
echo "  Size: $JAR_SIZE"
echo

# Test help command
echo "Testing --help command..."
java -jar "$JAR_FILE" --help

if [ $? -eq 0 ]; then
    echo
    echo "✓ Help command works"
else
    echo
    echo "✗ Help command failed"
    exit 1
fi

# Create a test directory
echo
echo "Setting up test environment..."
cd forge-deck-tester
mkdir -p test-decks

# Check if example deck exists
if [ ! -f "example-deck.dck" ]; then
    echo "✗ Warning: example-deck.dck not found"
else
    echo "✓ Example deck found"

    # Copy example deck to test directory
    cp example-deck.dck test-decks/
    cp example-deck.dck test-decks/opponent-deck.dck

    echo
    echo "=================================================="
    echo "  Build Complete!"
    echo "=================================================="
    echo
    echo "To run a quick test (2 decks, 10 games each):"
    echo
    echo "  java -jar target/forge-deck-tester.jar \\"
    echo "    --deck example-deck.dck \\"
    echo "    --deck-dir test-decks \\"
    echo "    --games 10"
    echo
    echo "To download and test against MTGGoldfish top decks:"
    echo
    echo "  java -jar target/forge-deck-tester.jar \\"
    echo "    --deck example-deck.dck \\"
    echo "    --download \\"
    echo "    --top 10 \\"
    echo "    --games 100"
    echo
fi

echo
echo "Build artifacts:"
echo "  JAR: $(pwd)/target/forge-deck-tester.jar"
echo "  Size: $JAR_SIZE"
echo

exit 0
