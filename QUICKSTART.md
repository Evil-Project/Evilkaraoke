# Quick Start Guide

## Prerequisites

- Paper 1.21.11 server (Java 21)
- Minecraft 1.21.11 client with Fabric Loader 0.19.3+ OR NeoForge 21.11.42+
- Neurokaraoke API access (endpoint + key)

## Installation (5 minutes)

### Step 1: Install Server Plugin

```bash
# Download or build the plugin
./gradlew :server-paper:shadowJar

# Copy to your Paper server
cp server-paper/build/libs/server-paper-0.1.0-SNAPSHOT.jar /path/to/server/plugins/

# Start server (generates default config)
cd /path/to/server
java -jar paper-1.21.11.jar
```

### Step 2: Configure API

Edit `plugins/Evilkaraoke/config.yml`:

```yaml
api:
  randomUrl: "https://your-api.example.com/api/songs/random"
  searchUrl: "https://your-api.example.com/api/songs"
  songUrl: "https://your-api.example.com/api/songs/"
  playlistUrl: "https://your-api.example.com/api/playlist/"
  artistUrl: "https://your-api.example.com/api/artist/"
  audioBaseUrl: "https://audio.example.com/"
  imagesBaseUrl: "https://images.example.com"
```

Reload: `/ek reload`

### Step 3: Install Client Mod

**For Fabric:**
```bash
cp client-fabric/build/libs/client-fabric-0.1.0-SNAPSHOT.jar ~/.minecraft/mods/
```

**For NeoForge:**
```bash
cp client-neoforge/build/libs/client-neoforge-0.1.0-SNAPSHOT.jar ~/.minecraft/mods/
```

Launch Minecraft 1.21.11 and join your server.

## First Commands

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

## Testing Without API

You can test the plugin loads correctly even without a real API:

1. Start server with plugin installed
2. Check console for: `Evilkaraoke enabled with server-authoritative playback coordination.`
3. Run `/ek help` - should show command list
4. Run `/ek doctor` - shows diagnostic info
5. Run `/ek listeners` - shows connected clients (requires client mod)

Commands requiring API (`request`, `search`, `randomsong`) will fail gracefully with error messages.

## Common Issues

### "Command not found"
- Plugin didn't load. Check `plugins/` folder and server console for errors.
- Try `/plugins` to see if Evilkaraoke is listed.

### "No Evilkaraoke clients connected"
- Client mod not installed or wrong Minecraft version
- Check client logs for errors
- Verify Fabric Loader 0.19.3+ or NeoForge 21.11.42+

### "API request failed"
- Check `config.yml` has correct API URLs
- Test API endpoint with curl: `curl https://your-api.example.com/api/songs/random`
- Increase `api.timeoutMillis` if network is slow

## Permissions Setup

By default, all players can queue songs but only ops can control playback.

**Give all players full control:**
```yaml
# In your permissions plugin (LuckPerms, etc.)
evilkaraoke.playback.pause: true
evilkaraoke.playback.resume: true
evilkaraoke.playback.skip: true
evilkaraoke.playback.stop: true
```

**DJ role with control, regular users can only queue:**
```yaml
groups:
  dj:
    permissions:
      - evilkaraoke.playback.*
  default:
    permissions:
      - evilkaraoke.command.request
      - evilkaraoke.command.queue
      - evilkaraoke.command.current
```

## Audience Targeting

Control who hears the music:

```
/ek audience @a        # Everyone (default)
/ek audience @s        # Only you
/ek audience Steve     # Only Steve
/ek audience @a[world=spawn]  # Everyone in spawn world
```

Positional audio (distance-based volume) is automatic when you use `@a`.

## Radio Mode

Continuous playback from configured stations:

```
/ek radio radio21      # Start Radio21 station
/ek radio swarmfm      # Start SwarmFM station
/ek radiooff           # Stop radio mode
```

Configure stations in `config.yml`:
```yaml
radio:
  radio21:
    enabled: true
  swarmfm:
    enabled: true
```

## Statistics

Track plays and requests:

```
/ek stats me           # Your stats
/ek stats user Steve   # Steve's stats
/ek stats server       # Server totals
/ek stats top          # Leaderboard
```

Stats are saved to `plugins/Evilkaraoke/stats.json` every 60 seconds (configurable).

## Building from Source

```bash
git clone <your-repo>
cd Evilkaraoke
./gradlew build

# Artifacts:
# server-paper/build/libs/server-paper-0.1.0-SNAPSHOT.jar
# client-fabric/build/libs/client-fabric-0.1.0-SNAPSHOT.jar
# client-neoforge/build/libs/client-neoforge-0.1.0-SNAPSHOT.jar
```

## Next Steps

- Configure your Neurokaraoke API endpoint
- Set up permissions for your players
- Customize messages in `messages.yml`
- Adjust spatial audio settings in `config.yml`
- Enable debug logging if troubleshooting: `debug.logPackets: true`

## Support

- Check `VERIFICATION.md` for detailed test results
- See `README.md` for full documentation
- Review server console logs for errors
- Use `/ek doctor` for diagnostic information
