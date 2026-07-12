#!/bin/bash
# Release packaging script for Evilkaraoke
# Creates distribution archives for server and client

set -e

VERSION="${1:-${RELEASE_VERSION:-1.0.2}}"
BUILD_DIR="build/release"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_ARGS=()

if [ -n "${JAVA_INSTALLATIONS_PATHS:-}" ]; then
    GRADLE_ARGS+=("-Dorg.gradle.java.installations.paths=$JAVA_INSTALLATIONS_PATHS")
fi

echo "=== Evilkaraoke Release Builder ==="
echo "Version: $VERSION"
echo "Project: $PROJECT_ROOT"
echo ""

# Clean and build
echo "[1/5] Building project..."
cd "$PROJECT_ROOT"
./gradlew "${GRADLE_ARGS[@]}" clean build --no-daemon --console=plain -PreleaseVersion="$VERSION"
echo "✓ Build successful"

# Create release directory
echo "[2/5] Creating release directory..."
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{server,fabric-mod,neoforge-mod}
echo "✓ Release directory ready"

# Package server
echo "[3/5] Packaging server plugin..."
cp paper-plugin/build/libs/Evilkaraoke-Paper-$VERSION.jar "$BUILD_DIR/server/Evilkaraoke-Paper-$VERSION.jar"
cp README.md "$BUILD_DIR/server/"
cp paper-plugin/src/main/resources/config.yml "$BUILD_DIR/server/config-example.yml"
cat > "$BUILD_DIR/server/README.txt" << EOF
Evilkaraoke Server Plugin - Version $VERSION

Installation:
1. Copy Evilkaraoke-Paper-$VERSION.jar to your Paper 26.2 server's plugins/ folder
2. Start the server (generates default config)
3. Edit plugins/Evilkaraoke/config.yml with your API endpoint
4. Run /evilkaraoke reload to apply changes

See README.md for full documentation and quick start guide.

Requirements:
- Paper 26.2+ (Java 25)
- Neurokaraoke-compatible API endpoint

Commands:
- /evilkaraoke help - Show all commands
- /evilkaraoke request <song> - Queue a song
- /evilkaraoke pause/resume/next/stop - Playback controls (ops)
- /evilkaraoke queue - Show upcoming tracks
- /evilkaraoke current - Show now playing

For support, see README.md troubleshooting section.
EOF
echo "✓ Server package ready: $BUILD_DIR/server/"

# Package Fabric client
echo "[4/5] Packaging Fabric client mod..."
cp fabric-mod/build/libs/Evilkaraoke-Fabric-$VERSION.jar "$BUILD_DIR/fabric-mod/Evilkaraoke-Fabric-$VERSION.jar"
cat > "$BUILD_DIR/fabric-mod/README.txt" << EOF
Evilkaraoke Fabric Client Mod - Version $VERSION

Installation:
1. Install Fabric Loader 0.19.3+ for Minecraft 26.2
2. Copy Evilkaraoke-Fabric-$VERSION.jar to your mods/ folder
3. Launch Minecraft and join a server running Evilkaraoke

Requirements:
- Minecraft 26.2
- Fabric Loader 0.19.3+

This mod is audio-only - all game logic runs on the server.
No configuration needed on the client side.

For support, see the server README.md.
EOF
echo "✓ Fabric package ready: $BUILD_DIR/fabric-mod/"

# Package NeoForge client
echo "[5/5] Packaging NeoForge client mod..."
cp neoforge-mod/build/libs/Evilkaraoke-NeoForge-$VERSION.jar "$BUILD_DIR/neoforge-mod/Evilkaraoke-NeoForge-$VERSION.jar"
cat > "$BUILD_DIR/neoforge-mod/README.txt" << EOF
Evilkaraoke NeoForge Client Mod - Version $VERSION

Installation:
1. Install NeoForge 26.2.0.7-beta+ for Minecraft 26.2
2. Copy Evilkaraoke-NeoForge-$VERSION.jar to your mods/ folder
3. Launch Minecraft and join a server running Evilkaraoke

Requirements:
- Minecraft 26.2
- NeoForge 26.2.0.7-beta+

This mod is audio-only - all game logic runs on the server.
No configuration needed on the client side.

For support, see the server README.md.
EOF
echo "✓ NeoForge package ready: $BUILD_DIR/neoforge-mod/"

# Create archives
echo ""
echo "Creating distribution archives..."
cd "$BUILD_DIR"
tar czf "Evilkaraoke-Server-$VERSION.tar.gz" server/
tar czf "Evilkaraoke-Fabric-$VERSION.tar.gz" fabric-mod/
tar czf "Evilkaraoke-NeoForge-$VERSION.tar.gz" neoforge-mod/

# Summary
echo ""
echo "=== Build Complete ==="
echo ""
echo "Artifacts created in $BUILD_DIR:"
echo ""
echo "Server Plugin:"
echo "  - Evilkaraoke-Server-$VERSION.tar.gz"
echo "    (includes plugin JAR, README, config example)"
echo ""
echo "Client Mods:"
echo "  - Evilkaraoke-Fabric-$VERSION.tar.gz"
echo "  - Evilkaraoke-NeoForge-$VERSION.tar.gz"
echo ""
echo "Individual JARs also available in:"
echo "  - server/Evilkaraoke-Paper-$VERSION.jar"
echo "  - fabric-mod/Evilkaraoke-Fabric-$VERSION.jar"
echo "  - neoforge-mod/Evilkaraoke-NeoForge-$VERSION.jar"
echo ""
echo "File sizes:"
du -h ./*.tar.gz | awk '{print "  - " $2 ": " $1}'
echo ""
echo "✓ Ready for distribution"
