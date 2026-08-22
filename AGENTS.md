# AGENTS.md — Yardhal

Instructions for AI coding agents (and humans) working on Yardhal, an Android
IRC client for Android 13+. The design is a Kotlin/Compose port of the
layered architecture used by the Halyard iOS client: strict module layering,
a pure-function inbound reducer, and a real-server integration harness.

## Layout

- `core/protocol` — pure Kotlin wire-protocol module. RFC 1459/2812 + IRCv3
  message grammar, tags, CTCP, ISUPPORT, casemapping. No I/O, no Android
  dependencies, plain-JVM unit tested.
- `core/client` — connection layer. TLS sockets, line framing, capability
  negotiation (CAP LS 302), SASL state machines, reconnect/backoff, STS.
  Depends only on `core/protocol` + kotlinx.coroutines.
- `core/data` — Android library. Persistent stores (networks, messages,
  read markers, mutes), slash-command parsing, mention matching. Room +
  DataStore + kotlinx.serialization.
- `app` — Jetpack Compose UI + the central coordinator that owns per-network
  state and dispatches inbound effects.
- `docs/architecture.md` — orientation document; read it first.

## Build & test (NixOS)

All commands run inside the Nix dev shell:

```sh
nix develop          # JDK 21 + Gradle 8.14.x + writable Android SDK clone
make check           # assembleDebug + every module's unit tests (the gate)
make build           # just :app:assembleDebug
make test            # just unit tests
```

The first `nix develop` provisions a writable SDK clone at
`~/.cache/yardhal/android-sdk` (copied from the Nix store template by
`scripts/ensure-sdk.sh`; delete it to re-provision after flake changes).

NixOS-specific: Maven-shipped AAPT2 cannot exec here, so the Makefile passes
`-Pandroid.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/35.0.0/aapt2`
(the androidenv-patched binary). If you invoke `./gradlew` directly on NixOS,
add that flag yourself; on other OSes/CI it is unnecessary.

Install on a device/emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Conventions

- **Kotlin sources contain no comments** — no `//`, `/* */`, no KDoc on
  private code. Encode invariants in test names, assertions, and identifier
  names. Public API in `core/*` may carry KDoc where it aids Quick-doc-style
  discovery, but prefer self-evident naming.
- **Warnings are errors.** Every module sets `allWarningsAsErrors = true`.
  Fix root causes; never suppress.
- **No force unwraps (`!!`).** Prefer structured errors / early returns.
- **Layering is strict**: `protocol` ← nothing; `client` → protocol;
  `data` → client+protocol; `app` → everything. Never reach downward past a
  layer or add Android imports to `core/protocol`/`core/client`.
- **Tests live beside the feature.** Pure-JVM tests for protocol/client;
  Robolectric only where Android APIs are touched (`core/data`, `app`).
- **Commits explain WHY**: imperative subject, body covering root cause.

## Quality gate

`make check` MUST pass before pushing. CI runs the same on GitHub Actions.

## Roadmap

The phased roadmap lives in `docs/architecture.md`. When adding a feature,
find its phase there, mirror the corresponding Halyard pattern (see
docs/architecture.md references), and keep the IRCv3 spec inventory honest:
check off obligations in `docs/ircv3-checklist.md` as they land.
