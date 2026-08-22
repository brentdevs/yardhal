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
  `LineFramer`, `BatchAssembler`, `CapabilityNegotiator` (CAP LS 302),
  SASL handlers (PLAIN first; SCRAM-SHA-256 later), `Reconnector` with
  backoff, STS policy handling. Exposes inbound events as a `Flow`.
  One `IrcConnection` per network.
- **`core/data`** — Android library holding persistent stores:
  `NetworkStore` (kotlinx.serialization + file/DataStore),
  `MessageStore` (Room, dedup by `msgid` or content hash,
  scrollback trimming), `ReadMarkerStore`, `MuteStore`,
  `SlashCommandParser`, mention matching, network presets.
  Credentials live in Android Keystore-backed storage.
- **`app`** — Compose UI plus `LiveCoordinator`, the central owner of
  per-network state. A foreground service keeps connections alive and
  raises notifications.

## Data flow on an inbound message

Planned shape (ported from Halyard):

1. Bytes off the TLS socket → `LineFramer` splits on `\r\n`.
2. Each line parses to an `IrcMessage` (`core/protocol`).
3. `BatchAssembler` buffers IRCv3 BATCH members or delivers immediately.
4. `LiveCoordinator`'s inbound reader consumes the connection's `Flow`.
5. The message runs through `PerNetworkState.apply(...)` — a pure-function
   reducer that mutates per-network state directly where it owns it and
   returns `[InboundEffect]` for everything crossing into UI-visible state.
6. `processEffect` translates effects into coordinator mutators
   (append message, replace members, bump revision...).
7. Compose observes StateFlows; Room persists fire-and-forget.

Outbound mirrors it: composer line → `SlashCommand.parse` → verb handler →
connection send.

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
  numerics, CTCP, ISUPPORT, casemapping, mIRC formatting parse.
- **Phase 2 — Connection**: TLS, CAP LS 302, SASL PLAIN, registration,
  ping keepalive, reconnect/backoff, STS persist + upgrade; loopback +
  Ergo harness green.
- **Phase 3 — Data + brain**: stores, reducer skeleton, slash commands,
  credential vault, network presets.
- **Phase 4 — MVP UI**: network/channel lists, transcript, composer with
  nick completion, join sheet, foreground service + notifications,
  settings. Daily-drivable on Libera-class networks.
- **Phase 5 — IRCv3 breadth**: chathistory backfill + gap fill,
  echo-message/msgid affordances, replies, reactions, typing, presence
  stack (away/account-notify, extended-join, WHOX, multi-prefix,
  monitor), standard-replies + labeled-response errors, netsplit
  collapse, read-marker, multiline, redaction, metadata avatars.
- **Phase 6 — Polish**: TOML theme engine, whois panel, channel modes +
  moderation UI, ignore list, LIST browser, link previews, two-pane
  tablet layout, traffic console.
- **Phase 7 — Bouncers**: soju BOUNCER cap multi-network, ZNC playback +
  management.
- **Phase 8 — Extras**: media uploads, share-target, shortcuts/widgets,
  catch-up digest.

The authoritative IRCv3 obligation inventory lives in
`docs/ircv3-checklist.md`; check items off as they land.
