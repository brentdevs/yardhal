# Yardhal

Yardhal is a native Android IRC client for Android 13+ (minSdk 33), built with
Kotlin and Jetpack Compose. It speaks IRCv3 (CAP 302, SASL, server-time,
CHATHISTORY, message-tags, bouncer-networks, filehost) and follows the layered
architecture of the Halyard iOS client.

## Features

- **Connections** — TLS with hostname verification, CAP LS 302 negotiation,
  SASL PLAIN, persistent foreground service, exponential-backoff reconnect,
  nick-collision auto-retry, STS policy handling
- **Chat** — server-time ordering, chathistory backfill, per-conversation
  Room-backed scrollback, message grouping, avatars, mIRC formatting
  (colors, bold/italic/underline/strike, hex), unread markers with a
  jump-to-unread divider
- **IRCv3 affordances** — reactions, replies, redaction, typing indicators,
  mention highlights, `MARKREAD` mirroring, netsplit collapse
- **Navigation** — per-network channel tree with pins, custom groups, DMs,
  last-message previews, unread sorting, swipe gestures, member sheet with
  role sections (operators/voices/bots/users) and per-member kick/ban/ignore
- **Search** — SQLite FTS4 index over all history with snippet results
- **Bouncers** — ZNC playback handling; soju `bouncer-networks` add/edit/
  connect/disconnect via the `BouncerServ` service commands
- **Media** — file uploads through the IRCv3 filehost extension
- **Themes** — TOML theme files applied to the Material 3 scheme

## Quick start (NixOS)

```sh
nix develop            # JDK 21 + Android SDK (provisions a writable SDK clone)
make check             # the quality gate: assembleDebug + all unit tests
```

The flake composes the Android SDK, emulator, and Gradle; the first
`nix develop` clones the SDK to `~/.cache/yardhal/android-sdk`. Maven's
AAPT2 binary cannot exec on NixOS, so the Makefile transparently points
AGP at the androidenv-patched one. On other systems, plain `./gradlew`
works with JDK 21 and an SDK (platform 35, build-tools 35.0.0).

## Run it

```sh
make install           # build, install on a connected phone, launch
make devices           # list adb targets (also useful for wireless debugging)
```

Requirements: an Android 13+ device with USB debugging enabled (Developer
options → Build number ×7), or wireless debugging paired via `adb pair`.
NixOS may require membership in `plugdev` for USB access
(`sudo usermod -aG plugdev $USER`, then re-login). Debug builds use a debug
key. APKs on GitHub Releases use a separate, stable release key, so an
existing debug install must be removed before installing a Release APK.

Prefer the emulator? A Pixel 6 AVD is provisioned by the flake and boots
in ~15 seconds (KVM):

```sh
make play              # build + boot a windowed emulator + install + launch
```

## Test

```sh
make check             # compile + unit tests (protocol, client, data, app)
make test-ircd         # round-trip against a real Ergo ircd (make test-ircd first
                       # downloads a pinned binary into .tools/)
```

`make test-ircd` proves the full wire lifecycle against a real server:
connect → CAP LS 302 → register → JOIN → PRIVMSG echo with msgid and
server-time tags. The module layout also allows pure-JVM tests for
`core/protocol` and `core/client`, with Robolectric only where Android
APIs are touched.

CI runs the debug build, Android lint, all unit tests, and the pinned Ergo
round-trip on pull requests, main, and version tags. A tag such as `v0.1.0`
on a commit in main also builds a signed APK, verifies its signature, and
publishes the APK and SHA-256 checksum on [GitHub Releases](https://github.com/brentdevs/yardhal/releases).
The tag supplies `versionName`; the workflow run number supplies an
increasing `versionCode`. Create tags only after the commit has merged:

```sh
git switch main
git pull --ff-only
git tag -a v0.1.0 -m "Yardhal 0.1.0"
git push origin v0.1.0
```

The release job reads `YARDHAL_RELEASE_KEYSTORE_BASE64` and
`YARDHAL_RELEASE_KEY_PASSWORD` from repository Actions secrets. Keep a
private backup of the signing keystore and password: Android requires the
same key for future APK updates.

## Modules

- `core/protocol` — pure IRC wire protocol (no I/O, no Android)
- `core/client` — TLS connection, CAP/SASL state machines, reconnector, STS, filehost upload
- `core/data` — Room message log with FTS search, JSON stores, slash commands, themes
- `app` — Compose UI, coordinator, foreground service

## Documentation

- `docs/architecture.md` — module map, data flow, roadmap, deferrals
- `docs/ircv3-checklist.md` — spec-by-spec inventory of what is done
- `AGENTS.md` — conventions and the contribution flow (PRs only)

## Status

Feature-complete MVP; all eight roadmap phases implemented. Work lands
through pull requests — see open PRs on the repository.
