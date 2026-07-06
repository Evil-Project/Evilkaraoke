# Evilkaraoke

A Minecraft karaoke system for Paper, Fabric, and NeoForge servers with Fabric and NeoForge client playback. Port of [neurokaraokebot](https://github.com/Mr-Auto/neurokaraokebot) from Discord to Minecraft.

## Features

- Server-authoritative queue management — command logic runs on Paper, Fabric, or NeoForge servers
- Client audio playback — Fabric and NeoForge mods stream audio from HTTP sources via JavaSound
- Distance-based spatial audio — volume attenuates based on player position and world
- Multi-loader support — Fabric and NeoForge clients on the same server
- Audience targeting — play for all players, specific players, or yourself (like `/playsound`)
- Radio mode — continuous random playback from configured stations
- Statistics tracking — song plays, user requests, leaderboards
- Plug-and-play — use the Paper plugin or run the Fabric/NeoForge jar on both server and client, configure API endpoint, ready to use

## Requirements

- **Server**: Paper 26.2+, Fabric Loader 0.19.3+, or NeoForge 26.2.0.7-beta+ (Java 25)
- **Client**: Minecraft 26.2 + Fabric Loader 0.19.3+ **or** NeoForge 26.2.0.7-beta+
- **API**: Neurokaraoke-compatible API endpoint (configure in `config.yml`)
- **Optional permissions**: LuckPerms on Paper or LuckPerms Fabric on Fabric servers

## Quick Start

```bash
# Build the plugin and mods
./gradlew build

# Paper server: copy plugin to your Paper server
cp paper-plugin/build/libs/Evilkaraoke-Paper-1.0.0.jar /path/to/server/plugins/

# Fabric server or Fabric client: copy the same Fabric mod jar to mods/
cp fabric-mod/build/libs/Evilkaraoke-Fabric-1.0.0.jar ~/.minecraft/mods/

# NeoForge server or NeoForge client: copy the same NeoForge mod jar to mods/
cp neoforge-mod/build/libs/Evilkaraoke-NeoForge-1.0.0.jar ~/.minecraft/mods/
```

Start the server. Paper generates `plugins/Evilkaraoke/config.yml`; Fabric and NeoForge generate `config/evilkaraoke/evilkaraoke.json`. Edit the API endpoints, then run `/ek reload`.

```
/ek help                    # Show all commands
/ek search Caramelldansen   # Search for a song
/ek request Caramelldansen  # Queue and play
/ek current                 # What's playing?
/ek queue                   # What's up next?
/ek queue move 2 1          # Move your queued song earlier
/ek queue pause             # Pause (ops only)
/ek queue resume            # Resume (ops only)
/ek queue next              # Skip to next (ops only)
```

## Installation

### Server (Paper)

1. Download Paper 26.2+ from [papermc.io](https://papermc.io/downloads/paper)
2. Place `Evilkaraoke-Paper-1.0.0.jar` in your `plugins/` directory
3. Start the server — `config.yml` and `messages.yml` are generated in `plugins/Evilkaraoke/`
4. Configure your Neurokaraoke API endpoints in `config.yml` (see [Configuration](#configuration))
5. Reload the plugin: `/ek reload`

### Client (Fabric)

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2
2. Place `Evilkaraoke-Fabric-1.0.0.jar` in your `mods/` directory
3. Launch Minecraft and join a server running the Evilkaraoke Paper plugin or Fabric server mod

### Server (Fabric)

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 on the server
2. Place the same `Evilkaraoke-Fabric-1.0.0.jar` in the server `mods/` directory
3. Start the server and configure `config/evilkaraoke/evilkaraoke.json`
4. Optional: install LuckPerms Fabric to manage `evilkaraoke.*` permissions

The Fabric jar is both the server mod and the client audio mod. On a Paper server, install `Evilkaraoke-Paper-1.0.0.jar` on the server and install the Fabric jar only on players' clients.

### Client (NeoForge)

1. Install NeoForge 26.2.0.7-beta+ for Minecraft 26.2
2. Place `Evilkaraoke-NeoForge-1.0.0.jar` in your `mods/` directory
3. Launch Minecraft and join a server running Evilkaraoke

For a NeoForge server, place the same NeoForge jar in the server `mods/` directory. It registers the same command surface and server-authoritative playback flow as the Paper plugin.

### Testing Without an API

The plugin loads and most commands work even without a real API endpoint:

1. Start the server with the plugin installed
2. Check console for: `Evilkaraoke enabled with server-authoritative playback coordination.`
3. Run `/ek help` — should show the command list
4. Run `/ek doctor` — shows diagnostic info
5. Run `/ek listeners` — shows connected clients (requires client mod)

Commands requiring the API (`request`, `search`, `randomsong`) will fail gracefully with error messages.

## Commands

All commands use `/ek`.

### For All Players

- `/ek help` — Show command help
- `/ek search <query>` — Search for songs without queueing
- `/ek request <query>` — Search and queue a song
- `/ek request id <songId>` — Queue a song by Neurokaraoke song id
- `/ek request url <https://...> [title]` — Queue a direct public audio URL
- `/ek setlist [page]` — Browse Neurokaraoke setlists
- `/ek playlist [page]` — Browse public Neurokaraoke playlists and queue one by row
- `/ek queue` — Show upcoming tracks
- `/ek queue move <from> <to>` — Reorder your queued requested songs
- `/ek queue cancel <position|all>` — Remove one or all of your queued songs
- `/ek current` — Show currently playing track
- `/ek randomsong` — Queue a random song
- `/ek stats me` — View your statistics
- `/ek stats server` — View server statistics

### For Operators (require permission)

- `/ek queue pause` — Pause current playback
- `/ek queue resume` — Resume paused playback
- `/ek queue previous` — Return to the previous track
- `/ek queue next` — Skip current track
- `/ek queue stop` — Stop current playback
- `/ek audience <@a|@s|player>` — Set playback target (all/@a, self/@s, or specific player)
- `/ek radio <radio21|swarmfm>` — Start radio mode
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
evilkaraoke.command.randomsong: true     # Queue a random song
evilkaraoke.command.setlist: true        # Browse and queue setlists
evilkaraoke.command.playlist: true       # Browse and queue public playlists
evilkaraoke.command.issue: true          # Troubleshooting help
evilkaraoke.command.queue: true          # View queue
evilkaraoke.command.queue.move: true     # Move your queued songs
evilkaraoke.command.queue.cancel: true   # Cancel own queued songs
evilkaraoke.command.queue.random: true   # Toggle random queue playback
evilkaraoke.command.queue.loop: true     # Toggle queue or single-song loop
evilkaraoke.command.queue.pause: op      # Pause playback
evilkaraoke.command.queue.resume: op     # Resume playback
evilkaraoke.command.queue.previous: op   # Go to previous track
evilkaraoke.command.queue.next: op       # Skip to next track
evilkaraoke.command.queue.stop: op       # Stop playback
evilkaraoke.command.current: true        # View current track
evilkaraoke.command.stats: true          # View statistics
evilkaraoke.command.radio: true          # Use radio mode
evilkaraoke.command.audience: op         # Change audience
evilkaraoke.command.reload: op           # Reload config
evilkaraoke.command.doctor: op           # Diagnostics
evilkaraoke.command.listeners: op        # List connected clients
evilkaraoke.admin.queue.cancel: op       # Cancel any queued song
evilkaraoke.admin.queue.move: op         # Reorder any requested queued song
```

On Fabric, Evilkaraoke uses `fabric-permissions-api-v0`, so LuckPerms Fabric can control the same permission nodes Paper uses. `/ek stats me` shows the player's LuckPerms primary group when LuckPerms is installed.

## Architecture

```
┌─────────────────────────────────┐
│ Paper / Fabric / NeoForge Server│
│  ┌──────────────────────────┐   │       ┌──────────────────┐
│  │  /ek Commands             │   │       │  Fabric Client   │
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

- **shared-core** — Shared protocol (packets, models, codec) - Java 25
- **server-core** — Loader-neutral server core (commands, queue, API client, stats)
- **paper-plugin** — Paper plugin adapter - Paper 26.2
- **client-core** — Loader-neutral audio engine (JavaSound, spatial volume, decoding) - Java 25
- **fabric-mod** — Fabric client/server entrypoints + custom payload networking - MC 26.2 + Fabric
- **neoforge-mod** — NeoForge client/server entrypoints + custom payload networking - MC 26.2 + NeoForge 26.2.0.7-beta

### Protocol

Custom payloads over three channels (JSON-encoded):

1. **evilkaraoke:hello** (Client → Server)
   - Sent on join to register client capabilities
   - Contains: client version, Minecraft version, mod loader

2. **evilkaraoke:audio** (Server → Client)
   - Audio command packets: PLAY, PAUSE, RESUME, STOP, VOLUME, SYNC
   - PLAY includes: track metadata, playback target, position, volume, and delivery mode
   - Normal playback uses `SERVER_STREAM`: the server downloads the audio once, broadcasts encoded audio chunks to clients, and gives them a shared scheduled start time
   - `URL` delivery remains available for compatibility and tests

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
  publicPlaylistUrl: "https://api.neurokaraoke.com/api/playlist/public"
  publicPlaylistDetailUrl: "https://idk.neurokaraoke.com/public/playlist/"
  artistUrl: "https://api.neurokaraoke.com/api/artist/"
  audioBaseUrl: "https://audio.neurokaraoke.com/"
  imagesBaseUrl: "https://images.neurokaraoke.com"

playback:
  defaultTargets: "@a"
  defaultSource: "record"
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
- `paper-plugin/build/libs/Evilkaraoke-Paper-1.0.0.jar`
- `fabric-mod/build/libs/Evilkaraoke-Fabric-1.0.0.jar`
- `neoforge-mod/build/libs/Evilkaraoke-NeoForge-1.0.0.jar`

## Development

### Prerequisites

- Java 25 (required for Minecraft 26.2)
- Gradle 9.0+

### Project Structure

```
Evilkaraoke/
├── shared-core/         # Shared protocol (packets, models, codec)
├── server-core/         # Loader-neutral server core
├── client-core/         # Loader-neutral audio engine (JavaSound)
├── fabric-mod/          # Fabric mod (client + server networking)
├── neoforge-mod/        # NeoForge mod (client + server networking)
├── paper-plugin/        # Paper plugin (commands, API, queue, stats)
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
- Ensure Minecraft's Master and Jukebox/Records sliders are above zero
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
