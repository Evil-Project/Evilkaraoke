# Evilkaraoke

Evilkaraoke rewrites the Neurokaraoke Discord bot experience as a Minecraft Paper/Bukkit plugin plus client-side audio playback mods.

## Modules

- `common` — shared models and protocol codec.
- `server-paper` — Paper/Bukkit plugin. All commands, queueing, API calls, stats, permissions, and playback coordination live here.
- `client-common` — loader-independent client audio/playback abstractions.
- `client-fabric` — Fabric client mod entrypoint.
- `client-neoforge` — NeoForge client mod entrypoint.

## Target behavior

The server plugin is authoritative. Client mods are used solely for audio playback, similar to how vanilla `/playsound` sends playback intent to selected players.

Current implementation is in progress.
