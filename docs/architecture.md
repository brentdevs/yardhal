# Yardhal Architecture

This is the orientation document. Read it once before touching code; consult
specific files for details as the codebase grows.

Yardhal is a Kotlin/Compose port of the layered design proven by the Halyard
iOS client: four strict layers, a pure-function reducer translating wire
messages into state mutations, persistent stores keyed by `msgid`, and an
integration harness against a real IRC server.

## High-level module map

```
+----------------------------------------------------------+
|  app/   (Compose views, LiveCoordinator, foreground svc)  |
+----------------------------------------------------------+
|  core/data/   (stores, slash commands, persistence)       |
+----------------------------------------------------------+
|  core/client/  (TLS conn, CAP/SASL machines, framing)     |
+----------------------------------------------------------+
|  core/protocol/  (pure parsers, no I/O)                   |
+----------------------------------------------------------+
```

Each layer depends only on the ones below it.

- **`core/protocol`** — RFC 1459/2812 + IRCv3 message grammar, tags,
  CTCP, batches, ISUPPORT, casemapping, numerics as sealed types. Pure
  Kotlin value types; no sockets, no coroutines, no Android.
- **`core/client`** — `IrcConnection` (TLS socket via `javax.net.ssl`),
  `LineFramer`, `CapabilityNegotiator` (CAP LS 302), SASL handlers,
  `IrcReconnector` with backoff, STS policy handling, filehost uploads.
  Exposes inbound events as a `Flow`. One `IrcConnection` per network.
  IRCv3 batches are tracked by the app-layer reducer, not here.
- **`core/data`** — Android library holding persistent stores:
  `NetworkStore` (kotlinx.serialization + file/DataStore),
  `MessageStore` (Room, dedup by `msgid` or content hash,
  scrollback trimming), `ReadMarkerStore`, `MuteStore`,
  `SlashCommandParser`, mention matching, network presets.
  Credentials live in Android Keystore-backed storage.
- **`app`** — Compose UI plus the application-owned `LiveCoordinator`, the
  central owner of per-network state. The foreground service keeps the process
  alive while networks are configured and raises notifications.

## Data flow on an inbound message

1. Bytes off the TLS socket → `LineFramer` splits on `\r\n` (`core/client`).
2. Each line parses to an `IrcMessage` (`core/protocol`). `IrcConnection`
   consumes connection-scoped traffic itself (PING, CAP, SASL, STS, nick
   collisions during registration) and emits everything as `IrcEvent`s.
3. `IrcReconnector` re-creates connections with backoff and republishes
   their events as one `Flow` per network.
4. `LiveCoordinator` collects that flow and, under a per-session lock, calls
   `PerNetworkState.apply(event, InboundContext)`.
5. `PerNetworkState` (`app/.../coordinator/PerNetworkState.kt`) is the pure
   reducer. It owns per-network protocol state — casemapping, own nick,
   negotiated caps and values, ISUPPORT-derived settings, open batches,
   NAMES/WHO accumulation, channel membership, per-user presence and metadata,
   netsplit tracking, labeled replies and WHOIS assembly — mutates it directly,
   and returns a `List<InboundEffect>`
   for everything that crosses into UI state or the outside world. It never
   writes to sockets, stores, coroutine scopes or StateFlows; read-only
   queries it needs (clock, ignore list, which buffers exist, read markers)
   arrive through `InboundContext`. Handlers are grouped by concern in
   `SessionReducer.kt`, `MembershipReducer.kt`, `IdentityReducer.kt`,
   `ChatReducer.kt`, `MultilineReducer.kt`, `LabelReducer.kt`,
   `MetadataReducer.kt` and `NumericReducer.kt`. `UserProfiles.kt` tracks
   profile changes and publishes snapshots after metadata/join batches close.
6. `LiveCoordinator.processEffect` executes each `InboundEffect`: append or
   reconcile a message (and persist it to Room fire-and-forget), replace a
   member snapshot, set topic/join state/typing/reactions, redact, apply a
   read marker, rename a conversation and its persistent keys, send a raw line,
   schedule epoch-guarded metadata sync, publish profiles and network icons,
   WHOIS/LIST/bouncer updates. Pure buffer transforms live in `BufferOps.kt`;
   HTTP image fetching, bounded decoding and caching live in `ui/image`.
7. Compose observes the coordinator's StateFlows.

Reducer behaviour is pinned by plain-JVM tests
(`app/src/test/.../coordinator/*ReducerTests.kt`, `PerNetworkStateTests.kt`)
that feed raw lines and assert on returned effects and state.

Outbound mirrors it: composer line → `SlashCommandParser.parse` →
`LiveCoordinator.dispatchCommand` → connection send. Coordinator calls that
change protocol state (WHOIS expectation, LIST browsing, leaving a channel)
go through the same per-session lock.

## Conventions

See `AGENTS.md`. Short version: no comments in Kotlin sources, warnings are
errors, no force unwraps, strict layering, tests beside features, commits
explain why.

## Testing tiers

1. Pure-JVM unit tests for protocol + client + pure data logic.
2. Loopback fake-ircd harness (`ServerSocket`) for connection lifecycles.
3. Real-server round-trip against a pinned Ergo binary (downloaded on
   demand, self-skipping when absent) — connect → CAP → register → JOIN →
   PRIVMSG echo.

## Roadmap

Phases land in order; each phase ships with tests and updated docs.

- **Phase 0 — Scaffold**: Nix dev shell, Gradle multi-module skeleton,
  Compose shell app, quality gate, CI. ✅
- **Phase 1 — Protocol core**: wire grammar, tags escaping, prefixes,
  numerics, CTCP, ISUPPORT, casemapping, mIRC formatting parse. ✅
- **Phase 2 — Connection**: TLS, CAP LS 302, SASL PLAIN and SCRAM-SHA-256,
  post-registration authentication, pre-away and extended ISUPPORT,
  ping keepalive, reconnect/backoff, STS persist + upgrade; loopback +
  Ergo harness green. ✅
- **Phase 3 — Data + brain**: stores, pure per-network inbound reducer,
  slash commands, credential vault, network presets. ✅
- **Phase 4 — MVP UI**: network/channel lists, transcript, composer with
  nick completion, join sheet, foreground service + notifications,
  settings. ✅ (settings screen minimal)
- **Phase 5 — IRCv3 breadth**: server-time everywhere, chathistory LATEST
  bootstrap, gap-free history seeding from the store, labeled replies,
  echo-message reconciliation, msgid-gated reactions/replies/redaction,
  typing both ways, NAMES + PREFIX and WHOX presence, account/away/host/realname
  notifications, extended MONITOR, standard replies, MARKREAD mirroring,
  netsplit/netjoin collapse, limit-aware multiline composition and reassembly,
  metadata subscriptions, avatars and display names. Draft features use the
  advertised capability values; metadata is exercised against loopback servers
  because pinned Ergo 2.14 does not implement it.
- **Phase 6 — Polish**: ✅ whois panel, ignore list, LIST browser, two-pane
  tablet layout, traffic console, TOML theme engine, per-network channel
  tree with pins/custom groups/swipe actions/last-message previews,
  role-sectioned member sheet with account and bot badges, account-extban
  moderation, network icons, join-state machine with retry, unread divider
  with jump-to-unread, and Room-FTS message search with snippet results.
  Remaining: moderation surfaces beyond these actions and slash verbs,
  per-message link auto-open polish.
- **Phase 7 — Bouncers**: ZNC `znc.in/playback` requested; playback
  batches classified as history (no unread/highlight noise);
  `*status`/`*playback` routed to the server buffer. soju
  `soju.im/bouncer-networks` ported from Halyard: attribute model with
  escape-aware tokenizer, BOUNCER NETWORK upsert/delete parsing,
  ADDNETWORK/DELNETWORK/CONNECTNETWORK/DISCONNECTNETWORK, BouncerServ
  service commands, draft-vs-baseline diffing, and a management dialog.
  Known channels are re-joined on every connect so membership (and hence
  NAMES/WHO) survives restarts. Full soju editor edge cases want a live
  bouncer.
- **Phase 8 — Extras**: media uploads landed via the soju.im/FILEHOST
  ISUPPORT extension (endpoint discovered from 005, HTTPS enforced on TLS
  connections, SASL credentials reused as HTTP Basic, 201+Location resolved,
  multipart retried on body-format rejections) with a composer paperclip,
  SAF document picker and attachment-tag rendering. Share-target lands text
  into the composer. Widgets and an on-device catch-up digest are future
  work — Android has no FoundationModels equivalent, so that feature needs a
  bundled model decision first.

Deferred by design: link auto-open, catch-up digest and widgets.
WebSocket transport and WEBIRC do not apply to this native TCP/TLS client;
deprecated STARTTLS is replaced by direct TLS, and client-batch production
use waits for ratification. See `docs/ircv3-checklist.md`.

The authoritative IRCv3 obligation inventory lives in
`docs/ircv3-checklist.md`; check items off as they land.
