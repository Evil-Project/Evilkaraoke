# Evilkaraoke

A Minecraft karaoke system for Paper servers with Fabric and NeoForge client mods. Port of [neurokaraokebot](https://github.com/Mr-Auto/neurokaraokebot) from Discord to Minecraft.

## Features

- **Server-authoritative queue management** — all command logic runs on Paper
- **Client-only audio playback** — mods stream audio from HTTP sources via JavaSound
- **Distance-based spatial audio** — volume attenuates based on player position and world
- **Multi-loader support** — Fabric and NeoForge clients on the same server
- **Audience targeting** — play for all players, specific players, or yourself (like `/playsound`)
- **Radio mode** — continuous random playback from configured stations
- **Statistics tracking** — song plays, user requests, leaderboards
- **Plug-and-play** — drop jars in `plugins/` and `mods/`, configure API endpoint, ready to use

## Requirements

- **Server**: Paper 1.21.11+ (Java 21)
- **Client**: Minecraft 1.21.11 + Fabric Loader 0.19.3+ **or** NeoForge 21.11.42+
- **API**: Neurokaraoke-compatible API endpoint (configure in `config.yml`)

## Installation

### Server

1. Download Paper 1.21.11+ from [papermc.io](https://papermc.io/downloads/paper)
2. Place `server-paper-0.1.0-SNAPSHOT.jar` in your `plugins/` directory
3. Start the server — `config.yml` and `messages.yml` are generated in `plugins/Evilkaraoke/`
4. Configure your Neurokaraoke API endpoint in `config.yml`:
   ```yaml
   neurokaraoke:
     base-url: "https://your-api.example.com"
     api-key: "your-key-here"
   ```
5. Reload the plugin: `/evilkaraoke reload`

### Client (Fabric)

1. Install Fabric Loader 0.19.3+ for Minecraft 1.21.11
2. Place `client-fabric-0.1.0-SNAPSHOT.jar` in your `mods/` directory
3. Launch Minecraft and join a server running Evilkaraoke

### Client (NeoForge)

1. Install NeoForge 21.11.42+ for Minecraft 1.21.11
2. Place `client-neoforge-0.1.0-SNAPSHOT.jar` in your `mods/` directory
3. Launch Minecraft and join a server running Evilkaraoke

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

- **common** — Shared protocol (packets, models, codec) - Java 21
- **server-paper** — Paper plugin (commands, queue, API client, stats) - Paper 1.21.11
- **client-common** — Loader-neutral audio engine (JavaSound, spatial volume, decoding) - Java 21
- **client-fabric** — Fabric mod entrypoint + custom payload networking - MC 1.21.11 + Fabric
- **client-neoforge** — NeoForge mod entrypoint + custom payload networking - MC 1.21.11 + NeoForge 21.11.42

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

### Discord Bot Mapping

| Discord Bot Command | Minecraft Command | Notes |
|---------------------|-------------------|-------|
| `/play <song>` | `/ek request <song>` | Queues song for playback |
| `/pause` | `/ek pause` | Pauses current track |
| `/resume` | `/ek resume` | Resumes paused track |
| `/skip` | `/ek skip` | Skips to next track |
| `/stop` | `/ek stop` | Stops and clears queue |
| `/queue` | `/ek queue` | Shows upcoming tracks |
| `/nowplaying` | `/ek current` | Shows current track |
| `/search <query>` | `/ek search <query>` | Search without queueing |
| `/join` / `/leave` | *(omitted)* | No voice channels in Minecraft |
| *(N/A)* | `/ek audience <target>` | Minecraft-specific: control who hears audio |
| *(N/A)* | `/ek radio <station>` | Continuous playback mode |

## Configuration

### config.yml

```yaml
neurokaraoke:
  base-url: "https://api.example.com"
  api-key: "your-key-here"
  timeout-seconds: 10

playback:
  default-volume: 1.0
  max-volume: 2.0
  
spatial-audio:
  enabled: true
  attenuation-distance: 64.0
  min-volume: 0.1

queue:
  max-per-user: 5
  max-total: 50
  
radio:
  radio21:
    enabled: true
  swarmfm:
    enabled: true
    
stats:
  enabled: true
  save-interval-seconds: 300
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

- Java 21 (required for Minecraft 1.21.11)
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

### Key Design Decisions

1. **Server-authoritative** — All game logic, queue management, and API calls happen on Paper. Clients are audio-only.

2. **JSON over custom payloads** — Simple, debuggable protocol. No Protobuf/binary complexity for this use case.

3. **HTTP audio streaming** — Server sends URLs, clients fetch and decode. No raw audio over network channels.

4. **Loader-neutral audio** — `client-common` has zero Minecraft dependencies. Pure JavaSound + Java 21 stdlib.

5. **Distance attenuation** — Mirrors `/playsound` behavior: volume = max(minVolume, baseVolume * (1 - distance/maxDistance))

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

- Verify `base-url` and `api-key` in `config.yml`
- Test the API endpoint with curl/browser
- Check Paper console for error details
- Increase `timeout-seconds` if requests are slow

## License

MIT

## Credits

- Ported from [neurokaraokebot](https://github.com/Mr-Auto/neurokaraokebot) by Mr-Auto
- Built with Claude Code (Opus 4.8)
