# Evilkaraoke

A Minecraft karaoke system for Paper servers with Fabric and NeoForge client mods. Port of [neurokaraokebot](https://github.com/Mr-Auto/neurokaraokebot) from Discord to Minecraft.

## Features

- Server-authoritative queue management — all command logic runs on Paper
- Client-only audio playback — mods stream audio from HTTP sources via JavaSound
- Distance-based spatial audio — volume attenuates based on player position and world
- Multi-loader support — Fabric and NeoForge clients on the same server
- Audience targeting — play for all players, specific players, or yourself (like `/playsound`)
- Radio mode — continuous random playback from configured stations
- Statistics tracking — song plays, user requests, leaderboards
- Plug-and-play — drop jars in `plugins/` and `mods/`, configure API endpoint, ready to use

## Requirements

- **Server**: Paper 26.2+ (Java 25)
- **Client**: Minecraft 26.2 + Fabric Loader 0.19.3+ **or** NeoForge 26.2.0.7-beta+
- **API**: Neurokaraoke-compatible API endpoint (configure in `config.yml`)

## Quick Start

```bash
# Build the plugin and mods
./gradlew build

# Server: copy plugin to your Paper server
cp server-paper/build/libs/server-paper-0.1.0-SNAPSHOT.jar /path/to/server/plugins/

# Client: copy mod to your Minecraft instance (pick one)
cp client-fabric/build/libs/client-fabric-0.1.0-SNAPSHOT.jar ~/.minecraft/mods/
cp client-neoforge/build/libs/client-neoforge-0.1.0-SNAPSHOT.jar ~/.minecraft/mods/
```

Start the server — `config.yml` and `messages.yml` are generated in `plugins/Evilkaraoke/`. Edit the API endpoints, then run `/ek reload`.

```
/ek help                    # Show all commands
/ek search Caramelldansen   # Search for a song
/ek request Caramelldansen  # Queue and play
/ek current                 # What's playing?
/ek queue                   # What's up next?
/ek pause                   # Pause (ops only)
/ek resume                  # Resume (ops only)
/ek skip                    # Skip to next (ops only)
```

## Installation

### Server

1. Download Paper 26.2+ from [papermc.io](https://papermc.io/downloads/paper)
2. Place `server-paper-0.1.0-SNAPSHOT.jar` in your `plugins/` directory
3. Start the server — `config.yml` and `messages.yml` are generated in `plugins/Evilkaraoke/`
4. Configure your Neurokaraoke API endpoints in `config.yml` (see [Configuration](#configuration))
5. Reload the plugin: `/ek reload`

### Client (Fabric)

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2
2. Place `client-fabric-0.1.0-SNAPSHOT.jar` in your `mods/` directory
3. Launch Minecraft and join a server running Evilkaraoke

### Client (NeoForge)

1. Install NeoForge 26.2.0.7-beta+ for Minecraft 26.2
2. Place `client-neoforge-0.1.0-SNAPSHOT.jar` in your `mods/` directory
3. Launch Minecraft and join a server running Evilkaraoke

### Testing Without an API

The plugin loads and most commands work even without a real API endpoint:

1. Start the server with the plugin installed
2. Check console for: `Evilkaraoke enabled with server-authoritative playback coordination.`
3. Run `/ek help` — should show the command list
4. Run `/ek doctor` — shows diagnostic info
5. Run `/ek listeners` — shows connected clients (requires client mod)

Commands requiring the API (`request`, `search`, `randomsong`) will fail gracefully with error messages.

## Commands

All commands use `/evilkaraoke` or the alias `/ek`.

### For All Players

- `/ek help` — Show command help
- `/ek search <query>` — Search for songs without queueing
- `/ek request <query>` — Queue a song by name or URL
- `/ek queue` — Show upcoming tracks
- `/ek current` — Show currently playing track
- `/ek next` — Show next track in queue
- `/ek randomsong` — Queue a random song
- `/ek stats me` — View your statistics
- `/ek stats server` — View server statistics

### For Operators (require permission)

- `/ek pause` — Pause current playback
- `/ek resume` — Resume paused playback
- `/ek skip` — Skip current track
- `/ek stop` — Stop playback and clear queue
- `/ek audience <@a|@s|player>` — Set playback target (all/@a, self/@s, or specific player)
- `/ek radio <radio21|swarmfm>` — Start radio mode
- `/ek radiooff` — Stop radio mode
- `/ek reload` — Reload configuration
- `/ek doctor` — Show diagnostic information
- `/ek listeners` — List connected clients
- `/ek stats user <player>` — View player statistics
- `/ek stats top` — Show leaderboard

## Permissions

```yaml
evilkaraoke.command.help: true           # Help command
evilkaraoke.command.search: true         # Search songs
evilkaraoke.command.request: true        # Queue songs
evilkaraoke.command.queue: true          # View queue
evilkaraoke.command.current: true        # View current track
evilkaraoke.command.stats: true          # View statistics
evilkaraoke.command.radio: true          # Use radio mode
evilkaraoke.playback.pause: op           # Pause playback
evilkaraoke.playback.resume: op          # Resume playback
evilkaraoke.playback.skip: op            # Skip tracks
evilkaraoke.playback.stop: op            # Stop playback
evilkaraoke.playback.audience: op        # Change audience
evilkaraoke.admin.reload: op             # Reload config
evilkaraoke.admin.doctor: op             # Diagnostics
```

## Architecture

```
┌─────────────────────────────────┐
│       Paper Server              │
│  ┌──────────────────────────┐   │       ┌──────────────────┐
│  │  /evilkaraoke Commands   │   │       │  Fabric Client   │
│  │  Queue Management        │───┼───────┤  JavaSound Audio │
│  │  Playback Coordinator    │   │ JSON  │  Spatial Volume  │
│  │  Stats Tracking          │   │       └──────────────────┘
│  └──────────────────────────┘   │
│  Plugin Channels:                │       ┌──────────────────┐
│  - evilkaraoke:hello (C→S)      │       │ NeoForge Client  │
│  - evilkaraoke:audio (S→C)      │───────┤  JavaSound Audio │
│  - evilkaraoke:status (C→S)     │ JSON  │  Spatial Volume  │
└─────────────────────────────────┘       └──────────────────┘
```

### Modules

- **common** — Shared protocol (packets, models, codec) - Java 25
- **server-paper** — Paper plugin (commands, queue, API client, stats) - Paper 26.2
- **client-common** — Loader-neutral audio engine (JavaSound, spatial volume, decoding) - Java 25
- **client-fabric** — Fabric mod entrypoint + custom payload networking - MC 26.2 + Fabric
- **client-neoforge** — NeoForge mod entrypoint + custom payload networking - MC 26.2 + NeoForge 26.2.0.7-beta

### Protocol

Custom payloads over three channels (JSON-encoded):

1. **evilkaraoke:hello** (Client → Server)
   - Sent on join to register client capabilities
   - Contains: client version, Minecraft version, mod loader

2. **evilkaraoke:audio** (Server → Client)
   - Audio command packets: PLAY, PAUSE, RESUME, STOP, VOLUME, SYNC
   - PLAY includes: track metadata, HTTP audio URL, playback target, position, volume
   - Server never sends raw audio data — only URLs for clients to stream

3. **evilkaraoke:status** (Client → Server)
   - Client playback state: READY, PLAYING, PAUSED, BUFFERING, ERROR
   - Used for diagnostics and `/ek doctor` command

### Playback Targeting (Audience)

Like `/playsound`, you can control who hears the audio:

- **@a** (default) — All players in the same world
- **@s** — Only the command sender
- **player** — Specific player by name
- **Positional** — Audio source at coordinates with distance attenuation

## Configuration

### config.yml

```yaml
api:
  timeoutMillis: 8000
  retries: 3
  retryDelayMillis: 2000
  randomUrl: "https://api.neurokaraoke.com/api/songs/random"
  searchUrl: "https://api.neurokaraoke.com/api/songs"
  songUrl: "https://api.neurokaraoke.com/api/songs/"
  playlistUrl: "https://api.neurokaraoke.com/api/playlist/"
  artistUrl: "https://api.neurokaraoke.com/api/artist/"
  audioBaseUrl: "https://audio.neurokaraoke.com/"
  imagesBaseUrl: "https://images.neurokaraoke.com"

playback:
  defaultTargets: "@a"
  defaultSource: "music"
  defaultVolume: 1.0
  defaultPitch: 1.0
  defaultMinVolume: 0.0
  randomCacheSize: 2
  pauseBetweenSongsSeconds: 3
  requireClientMod: true
  allowRadio: true

stats:
  enabled: true
  countPlayAfterPercent: 75
  saveIntervalSeconds: 60

permissions:
  defaultControlAllowed: false

debug:
  logPackets: false
  logApiRequests: false
```

### messages.yml

All user-facing messages are customizable with MiniMessage formatting support. Colors, hover text, click actions fully supported.

## Building

```bash
./gradlew build
```

Artifacts:
- `server-paper/build/libs/server-paper-0.1.0-SNAPSHOT.jar` (385 KB, includes dependencies)
- `client-fabric/build/libs/client-fabric-0.1.0-SNAPSHOT.jar` (39 KB)
- `client-neoforge/build/libs/client-neoforge-0.1.0-SNAPSHOT.jar` (39 KB)

## Development

### Prerequisites

- Java 25 (required for Minecraft 26.2)
- Gradle 9.0+

### Project Structure

```
Evilkaraoke/
├── common/              # Shared protocol (packets, models, codec)
├── client-common/       # Loader-neutral audio engine (JavaSound)
├── client-fabric/       # Fabric mod (networking only)
├── client-neoforge/     # NeoForge mod (networking only)
├── server-paper/        # Paper plugin (commands, API, queue, stats)
├── gradle/              # Gradle wrapper + libs.versions.toml
└── build.gradle.kts     # Root build configuration
```

### Testing

```bash
./gradlew test
```

Tests cover:
- Protocol encoding/decoding (JSON round-trips)
- Queue management (request, random, next)
- Playback state machine (play, pause, resume, stop)
- Statistics tracking (plays, requests, leaderboards)
- Spatial audio (distance attenuation, cross-world)

## Troubleshooting

### "Command not found"

- Plugin didn't load. Check the `plugins/` folder and server console for errors.
- Run `/plugins` to see if Evilkaraoke is listed.

### "No Evilkaraoke clients connected"

- Verify the client mod is installed in `mods/` directory
- Check client logs for handshake errors
- Run `/ek listeners` to see connected clients
- Use `/ek doctor` for diagnostic info

### Audio not playing

- Check that the API endpoint is configured and reachable
- Verify the audio URL is accessible from the client
- Ensure client has JavaSound support (should work on all platforms)
- Check client logs for decoding errors

### "API request failed"

- Verify the API URLs in `config.yml`
- Test the endpoint: `curl https://api.neurokaraoke.com/api/songs/random`
- Check Paper console for error details
- Increase `api.timeoutMillis` if requests are slow

## License

MIT

## Credits

- Ported from [neurokaraokebot](https://github.com/Mr-Auto/neurokaraokebot) by Mr-Auto
