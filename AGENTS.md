# Repository Guidelines

## Project Structure & Module Organization

Evilkaraoke is a Gradle Kotlin DSL multi-module Java 25 project for Paper, Fabric, and NeoForge.

- `shared-core/`: loader-neutral models, protocol packets, JSON codec, URL security, and utilities.
- `server-core/`: shared server behavior: API client, command workflow, queue, playback, config, stats, and platform contracts.
- `paper-plugin/`: Paper plugin lifecycle, Bukkit messaging, Paper permissions, resources, and adapter code.
- `client-core/`: shared client audio, JavaSound decoding, stream handling, and client network control.
- `fabric-mod/` and `neoforge-mod/`: loader entrypoints, payload registration, resources, and packaging.

Java source is under `src/main/java`, tests under `src/test/java`, and loader/plugin metadata under `src/main/resources`.

## Build, Test, and Development Commands

- `./gradlew build`: builds all modules, runs tests, and creates plugin/mod artifacts.
- `./gradlew :paper-plugin:build`: builds the shaded Paper plugin jar.
- `./gradlew :fabric-mod:build`: builds the Fabric client/server mod jar.
- `./gradlew :neoforge-mod:build`: builds the NeoForge client/server mod jar.
- `./gradlew :shared-core:test :server-core:test :client-core:test`: runs focused shared-module unit tests.
- `./gradlew :paper-plugin:runServer`: starts a local Paper test server with EULA accepted by Gradle config.
- `./gradlew :fabric-mod:runClient`: starts the Fabric development client.

## Coding Style & Naming Conventions

Use Java 25, 4-space indentation, and the existing package-by-feature layout. Keep loader-specific APIs out of `shared-core`, `server-core`, and `client-core`. Prefer records for immutable data carriers and explicit package names such as `server.api`, `server.playback`, or `client.audio`. No separate formatter is configured; keep imports tidy and match surrounding style.

## Testing Guidelines

Tests use JUnit 5 via Gradle. Place tests beside the matching module and package, with names ending in `Test` such as `JavaSoundAudioBackendTest`. Add focused tests for protocol, queue, API parsing, playback coordination, and stream handling changes. Run the smallest relevant module tests first, then affected loader builds.

## Commit & Pull Request Guidelines

History follows Conventional Commits: `fix:`, `feat(client):`, `feat(server):`, `test:`, `docs:`, `ci:`, `build(ci):`, and `chore:`. Keep commits scoped to one behavior or module group.

Pull requests should include a concise summary, affected modules, tests run, and any manual Minecraft/Paper verification. Link issues when applicable and include screenshots only for visible client UI changes.

## Security & Configuration Tips

Do not bypass `AudioUrlValidator` for user-provided audio URLs. Avoid committing generated `run/`, `build/`, server world, log, or local config output. Keep default API endpoints and permissions documented in `README.md` and resource config files.
