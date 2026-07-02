#!/bin/bash
# Release packaging script for Evilkaraoke
# Creates distribution archives for server and client

set -e

VERSION="0.1.0-SNAPSHOT"
BUILD_DIR="build/release"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "=== Evilkaraoke Release Builder ==="
echo "Version: $VERSION"
echo "Project: $PROJECT_ROOT"
echo ""

# Clean and build
echo "[1/5] Building project..."
cd "$PROJECT_ROOT"
./gradlew clean build --no-daemon --console=plain > /dev/null 2>&1
echo "✓ Build successful"

# Create release directory
echo "[2/5] Creating release directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{server,client-fabric,client-neoforge}
echo "✓ Release directory ready"

# Package server
echo "[3/5] Packaging server plugin..."
cp server-paper/build/libs/server-paper-$VERSION.jar "$BUILD_DIR/server/Evilkaraoke-$VERSION.jar"
cp README.md "$BUILD_DIR/server/"
cp QUICKSTART.md "$BUILD_DIR/server/"
cp server-paper/src/main/resources/config.yml "$BUILD_DIR/server/config-example.yml"
cat > "$BUILD_DIR/server/README.txt" << EOF
Evilkaraoke Server Plugin - Version $VERSION

Installation:
1. Copy Evilkaraoke-$VERSION.jar to your Paper 1.21.11 server's plugins/ folder
2. Start the server (generates default config)
3. Edit plugins/Evilkaraoke/config.yml with your API endpoint
4. Run /ek reload to apply changes

See README.md for full documentation.
See QUICKSTART.md for quick setup guide.

Requirements:
- Paper 1.21.11+ (Java 21)
- Neurokaraoke-compatible API endpoint

Commands:
- /ek help - Show all commands
- /ek request <song> - Queue a song
- /ek pause/resume/skip/stop - Playback controls (ops)
- /ek queue - Show upcoming tracks
- /ek current - Show now playing

For support, see README.md troubleshooting section.
EOF
echo "✓ Server package ready: $BUILD_DIR/server/"

# Package Fabric client
echo "[4/5] Packaging Fabric client mod..."
cp client-fabric/build/libs/client-fabric-$VERSION.jar "$BUILD_DIR/client-fabric/Evilkaraoke-Fabric-$VERSION.jar"
cat > "$BUILD_DIR/client-fabric/README.txt" << EOF
Evilkaraoke Fabric Client Mod - Version $VERSION

Installation:
1. Install Fabric Loader 0.19.3+ for Minecraft 1.21.11
2. Copy Evilkaraoke-Fabric-$VERSION.jar to your mods/ folder
3. Launch Minecraft and join a server running Evilkaraoke

Requirements:
- Minecraft 1.21.11
- Fabric Loader 0.19.3+

This mod is audio-only - all game logic runs on the server.
No configuration needed on the client side.

For support, see the server README.md.
EOF
echo "✓ Fabric package ready: $BUILD_DIR/client-fabric/"

# Package NeoForge client
echo "[5/5] Packaging NeoForge client mod..."
cp client-neoforge/build/libs/client-neoforge-$VERSION.jar "$BUILD_DIR/client-neoforge/Evilkaraoke-NeoForge-$VERSION.jar"
cat > "$BUILD_DIR/client-neoforge/README.txt" << EOF
Evilkaraoke NeoForge Client Mod - Version $VERSION

Installation:
1. Install NeoForge 21.11.42+ for Minecraft 1.21.11
2. Copy Evilkaraoke-NeoForge-$VERSION.jar to your mods/ folder
3. Launch Minecraft and join a server running Evilkaraoke

Requirements:
- Minecraft 1.21.11
- NeoForge 21.11.42+

This mod is audio-only - all game logic runs on the server.
No configuration needed on the client side.

For support, see the server README.md.
EOF
echo "✓ NeoForge package ready: $BUILD_DIR/client-neoforge/"

# Create archives
echo ""
echo "Creating distribution archives..."
cd "$BUILD_DIR"
tar czf "Evilkaraoke-Server-$VERSION.tar.gz" server/
tar czf "Evilkaraoke-Fabric-$VERSION.tar.gz" client-fabric/
tar czf "Evilkaraoke-NeoForge-$VERSION.tar.gz" client-neoforge/

# Summary
echo ""
echo "=== Build Complete ==="
echo ""
echo "Artifacts created in $BUILD_DIR:"
echo ""
echo "Server Plugin:"
echo "  - Evilkaraoke-Server-$VERSION.tar.gz"
echo "    (includes plugin JAR, README, QUICKSTART, config example)"
echo ""
echo "Client Mods:"
echo "  - Evilkaraoke-Fabric-$VERSION.tar.gz"
echo "  - Evilkaraoke-NeoForge-$VERSION.tar.gz"
echo ""
echo "Individual JARs also available in:"
echo "  - server/Evilkaraoke-$VERSION.jar"
echo "  - client-fabric/Evilkaraoke-Fabric-$VERSION.jar"
echo "  - client-neoforge/Evilkaraoke-NeoForge-$VERSION.jar"
echo ""
echo "File sizes:"
du -h "$BUILD_DIR"/*.tar.gz | awk '{print "  - " $2 ": " $1}'
echo ""
echo "✓ Ready for distribution"
