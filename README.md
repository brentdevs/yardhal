# Yardhal

Yardhal is a native Android IRC client for Android 13+ (minSdk 33), built with
Kotlin and Jetpack Compose. It speaks IRCv3 (CAP 302, SASL, server-time,
CHATHISTORY, message-tags and friends) and follows the layered architecture of
the Halyard iOS client.

## Status

Phase 0 — project scaffold. See `docs/architecture.md` for the roadmap.

## Development (NixOS)

```sh
nix develop
make check      # assembleDebug + unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Non-Nix machines need JDK 21 + an Android SDK (platform 35, build-tools 35.0.0)
with `ANDROID_HOME` set; then plain `./gradlew` works.

## Modules

- `core/protocol` — pure IRC wire protocol parsers (no I/O)
- `core/client` — TLS connection, CAP/SASL state machines
- `core/data` — stores, persistence, slash commands
- `app` — Compose UI + coordinator

Read `docs/architecture.md` before touching code, and `AGENTS.md` for the
working conventions.
