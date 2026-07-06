# Evilkaraoke — Production Readiness Review

_Scope: correctness of the playback path + the unfinished multi-loader refactor._
_Note: this review was done by static analysis, then the current tree was verified
with `./gradlew build` on the configured Java 25 toolchain._

## TL;DR

The playback/queue **logic is solid and well covered by tests**, and the
`server-core` and `paper-plugin` copies of that logic are functionally identical
(only the Bukkit-vs-abstraction plumbing differs). The single biggest production
risk is **not a playback bug — it is architectural debt**: `server-core` (the
intended source of truth) is currently dead code, and `paper-plugin` ships its
own diverged duplicates. Two real client-side bugs were found and fixed.

## Fixed in this pass

1. **Background music volume clobber (Fabric + NeoForge).** When a karaoke track
   ended, the client restored Minecraft's `MUSIC` and `RECORDS` category volumes to
   a hardcoded `1.0f` instead of the player's configured slider values. A user who
   set music to 30% had it forced back to 100% after every song. Now restores to
   `options.getSoundSourceVolume(...)`, with a null-guard on `options`.
   - `fabric-mod/.../EvilKaraokeFabricClient.java`
   - `neoforge-mod/.../EvilKaraokeNeoForgeClient.java`

2. **Paused-during-buffering status desync (client-core).** If a `PAUSE` arrived
   while a track was still buffering/seeking, `JavaSoundAudioBackend` unconditionally
   set status to `PLAYING` before the pump loop, so the server briefly saw `PLAYING`
   for a track that was actually paused and silent. Now reflects `handle.paused`.
   Also removed the dead `drainAndClose` method.
   - `client-core/.../JavaSoundAudioBackend.java`

## The main production issue: unfinished `server-core` cutover

`settings.gradle.kts` includes both `server-core` and `paper-plugin`, but
`paper-plugin/build.gradle.kts` only depends on `shared-core`. So today:

- `paper-plugin` runs its **own** `playback/PlaybackCoordinator`,
  `queue/KaraokeSession`, `api/NeurokaraokeClient`, `stats/*`, `config/*`,
  `command/EvilKaraokeCommand` (1329 lines), and messaging classes.
- `server-core`'s parallel implementation — `PlaybackCoordinator`,
  `KaraokeSession`, `EvilKaraokeCommandService` (1334 lines), `EvilKaraokeServerCore`,
  JSON config, `ServerPlaybackPlatform`/`KaraokePlayer` abstractions — is **never
  loaded at runtime**.

This means any fix made in `server-core` (including the tested playback coordination)
does **not** reach the running Paper plugin, and the two copies will keep drifting.
Fabric/NeoForge clients are already thin and correct; the problem is server-side only.

### Why I did not blind-cut it over in this pass

Making `paper-plugin` delegate to `server-core` is **not** a mechanical dedup — it
embeds a product decision and can't be verified without a compiler here:

- **Config format changes.** `paper-plugin` uses `config.yml` + `messages.yml`
  (Bukkit). `server-core` uses a JSON config (`evilkaraoke.json`) via
  `EvilKaraokeServerConfig`. Cutting over silently would break existing servers'
  configs — that's a migration decision, not a refactor.
- **Command layer swap.** The Paper `EvilKaraokeCommand` and the `server-core`
  `EvilKaraokeCommandService` are separate 1300+ line implementations; subtle
  message/permission/tab-completion differences need diffing and testing.

### Recommended cutover (do this with the Java 25 build in the loop)

1. Add `api(project(":server-core"))` to `paper-plugin/build.gradle.kts`.
2. Add Paper adapters (small, ~80 + ~50 lines):
   - `PaperPlaybackPlatform implements ServerPlaybackPlatform` — wraps the Bukkit
     scheduler (`runTaskLater().getTaskId()`), `Bukkit.getOnlinePlayers()`, and
     `PlaybackMessenger` for `sendAudio`.
   - `PaperCommandActor implements CommandActor` — wraps `CommandSender`/`Player`
     and `PermissionService`.
3. Rewrite `EvilKaraokePlugin.onEnable` to build `EvilKaraokeServerCore(logger,
   dataDir, platform)`, register `/ek` to delegate to `EvilKaraokeCommandService`,
   and route plugin-message channels to `core.handlePayload(...)`.
4. Decide the config story (keep `config.yml` by adapting into
   `EvilKaraokeServerConfig`, **or** migrate to JSON with a one-time importer).
   Recommendation: keep `config.yml` to avoid breaking existing servers, and have
   the Paper config loader populate the `server-core` config record.
5. Delete the now-duplicated Paper classes (`playback`, `queue`, `api`, `stats`,
   and the old `command`), port their tests to `server-core` where not already
   covered, and run `./gradlew build`.

## Other hardening notes (reviewed, no change needed / follow-ups)

- **Playback coordination** (`PlaybackCoordinator`): client-confirmed watchdog vs
  metadata-cutoff auto-advance, stale-packet gating, and multi-client
  terminal-state advancement are all correct and tested. No changes needed.
- **Thread-safety**: coordinator mutable state is effectively main-thread-confined
  (all scheduling funnels through the platform scheduler; `KaraokeSession` is fully
  `synchronized`). Async API completions only touch `KaraokeSession` (safe) and
  hand off to `runNow`. OK as-is, but worth a comment documenting the threading
  contract to prevent regressions.
- **Audio streaming** (`JavaSoundAudioBackend`): SSRF validation via
  `AudioUrlValidator` is applied on every hop including redirects; finite assets are
  bounded to 128 MB; ICY metadata stripping and MPEG-frame sync are handled. Good.
- **`skip()` on a single-looped track** replays the same track (because
  `KaraokeSession.next()` returns the looped current). If skip should override an
  active single-loop, that's a small product decision to confirm.
