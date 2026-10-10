# Yardhal

Yardhal is a native Android IRC client for Android 13+ (minSdk 33), built with
Kotlin and Jetpack Compose. It speaks IRCv3 (CAP 302, SASL, server-time,
CHATHISTORY, message-tags, bouncer-networks, filehost) and follows the layered
architecture of the Halyard iOS client.

## Features

- **Connections** — verified TLS, endpoint-specific certificate inspection and
  fingerprint trust, Android KeyChain client identities, authenticated SOCKS5,
  CAP LS 302, SASL PLAIN/SCRAM-SHA-256/EXTERNAL and saved NickServ identification;
  connectivity/resume recovery, bounded probes, configured alternate nicknames,
  durable auto-connect/disconnect intent and STS enforcement
- **Chat** — server-time ordering, chathistory backfill, per-conversation
  Room-backed scrollback, message grouping, avatars, mIRC formatting
  (colors, bold/italic/underline/strike, hex), unread markers with a
  jump-to-unread divider, expandable membership events, clickable channel/nick
  links, and a formatting toolbar that rejects formatting-only drafts
- **IRCv3 affordances** — reactions, replies, redaction, typing indicators,
  mention highlights, `MARKREAD` mirroring, netsplit collapse
- **Navigation** — collapsible per-network channel tree with pins, custom groups,
  DMs, server console, last-message previews, unread sorting, swipe gestures,
  member sheet with role sections (operators/voices/bots/users) and per-member
  kick/ban/ignore; network collapse state survives navigation and rotation
- **Search** — SQLite FTS4 index over all history with snippet results
- **Bouncers** — tailored soju/ZNC setup, automatically bound soju upstreams with
  durable identities, network/channel management, ZNC server/settings controls,
  explicit service availability and acknowledged partial-apply outcomes
- **Media** — inline linked images and GIFs, explicit video playback and file
  cards; persisted reveal/hide and opt-in loading, separate bounded media/avatar
  caches, configurable upload providers and advertised FILEHOST
- **Composition** — searchable/category/recent emoji, portable quotes with local
  parents, opt-in exact-nick relay attribution, binary Android share staging,
  immutable upload destinations and explicit URL insertion followed by Send
- **Themes** — TOML theme files applied to the Material 3 scheme, opt-in Material
  You colors, AMOLED backgrounds, and transcript font, spacing and size controls

### Media, uploads and privacy

Open **App options → Chat appearance → Media and privacy** for image discovery,
avatars/network icons, explicit video playback, animation/reduced motion and
separate cache usage, budgets and clear actions. Saved reveal/hide choices normally
retain up to 4,096 entries, protecting visible and recent choices and expiring old
reveals before old hides. Expired choices revert to global policy and can auto-load
if enabled. While retained, hidden media is not fetched or probed; leaving the
visible transcript or backgrounding cancels media work.

Previews and avatars contact linked hosts directly and disclose your IP; they
are not anonymized or proxied through IRC. HTTPS validation is not an SSRF
boundary: loopback, private, link-local and DNS-resolved local HTTPS endpoints
remain supported, including on redirects. Untrusted links can therefore cause
local-network requests. Disable automatic image discovery and avatars separately
if that network access is unwanted; explicit Reveal/Play still contacts the host.

**Chat appearance → Uploads and photo privacy** configures global or per-network
HTTP upload providers, protected provider-specific Basic credentials, size limits
and metadata policy. Selection is per-network, then global, then advertised
FILEHOST; a missing explicit provider fails rather than silently falling back.
Configured providers never receive IRC credentials. These are raw POST /
multipart-compatible endpoints, not arbitrary cloud-storage API integrations.
Authentication requires HTTPS, and TLS/STS policy still applies to uploads.

The paperclip and Android SEND/SEND_MULTIPLE shares copy granted `content://`
files into private staging. Binary shares offer a destination chooser and retain
the caption. Once assigned, the destination cannot follow later navigation.
Review the provider and consent before upload; progress, cancellation and retry
stay with that destination. Uploading never sends IRC chat: the resulting URL
enters only the matching destination draft (return there if viewing another
conversation), then press **Send**. After process recreation, **Insert URL again**
recovers a completed URL without uploading again; the ordinary draft is transient.
SAF read grants are retained only until a durable private copy exists or the
attachment is removed. Grant failures are actionable rather than silently
discarding process-recovery access.

Metadata stripping is the default for supported JPEG, PNG and static WebP.
JPEG preserves necessary orientation and supported Adobe color interpretation
while removing personal metadata. ICC-profile-bearing JPEG/PNG/WebP, GIF,
animated PNG/WebP, unknown image formats and unsafe orientation fail honestly
under Strip; choose Keep explicitly to preserve their original appearance and
bytes, accepting location/device/personal disclosure. No animation is flattened.
Video/file uploads are not a promise of metadata removal. Media format recognition
is broader than device decoder support; unsupported content retains an
external-open fallback.

**Chat appearance → Configured relay senders** applies only to the selected
network: an exact-nick allowlist enables separate relayed-author/source
presentation, not verified identity or account authentication. Quotes use a
permitted wire reply tag when possible, otherwise portable quoted text.

## Quick start (NixOS)

```sh
nix develop            # JDK 21 + Android SDK (provisions a writable SDK clone)
make check             # the quality gate: assembleDebug + Android lint + all unit tests
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

### Connecting a bouncer

Choose **soju** or **ZNC** in **Add network**, rather than treating the account
as a direct IRC server. Use the bouncer's endpoint and verified TLS.

- **soju:** supply the bouncer account and SASL password, or select SASL EXTERNAL
  with a TLS client identity. One account discovers its upstreams automatically.
  Each upstream receives a separate authenticated, bound connection; do not add
  those upstream endpoints manually.
- **ZNC:** supply the account, optional network name and ZNC password. Yardhal
  constructs `account/network:password` (or `account:password`) internally.
  The account field must not already contain slash/colon syntax.
- Configure joins on the bouncer. The soju/ZNC setup forms do not save a second
  client-owned autojoin list.
- Open **App options → Bouncer networks** to manage remote networks and settings.
  Editing a discovered soju upstream opens these controls rather than changing
  its inherited transport or authentication. Channel controls use a bound
  upstream/network connection.
- ZNC detailed settings require `controlpanel`; creation/deletion and connection
  controls use `*status` independently. Only settings actually reported by the
  service are editable. Server lists belong to the currently selected ZNC
  network. Unreported soju channel policy values remain unknown.
- ZNC catch-up and older history require the optional
  [playback module](https://wiki.znc.in/Playback), unless the downstream connection
  negotiates CHATHISTORY itself. Stock ZNC's join-time buffer replay and forwarded
  upstream ISUPPORT do not establish archive support. Buffer clipping keeps
  missing-range warnings visible rather than inventing recovery.
- Apply results count acknowledged changes, not commands sent. Partial results
  retain accepted changes and refresh actual state; they do not imply rollback.
  After an uncertain unlabeled timeout, the next operation first waits for an
  actual ordered PONG fence; reconnect if the bouncer cannot establish it.
- Disconnect preserves cached history and each upstream's saved connection
  intent. Disabled/rejected upstreams remain offline without blocking siblings.
  Removing a soju account explicitly confirms removal of its dependent local
  upstreams and metadata; it does not delete the remote soju account.

## Test

```sh
make check             # compile + Android lint + unit tests (protocol, client, data, app)
make lint              # just Android lint, using the same checks as CI
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
