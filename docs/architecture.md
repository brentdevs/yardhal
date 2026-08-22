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
  numerics, CTCP, ISUPPORT, casemapping, mIRC formatting parse. ✅
- **Phase 2 — Connection**: TLS, CAP LS 302, SASL PLAIN, registration,
  ping keepalive, reconnect/backoff, STS persist + upgrade; loopback +
  Ergo harness green. ✅
- **Phase 3 — Data + brain**: stores, reducer-style coordinator routing,
  slash commands, credential vault, network presets. ✅
- **Phase 4 — MVP UI**: network/channel lists, transcript, composer with
  nick completion, join sheet, foreground service + notifications,
  settings. ✅ (settings screen minimal)
- **Phase 5 — IRCv3 breadth**: landed so far — server-time everywhere,
  chathistory LATEST bootstrap, gap-free history seeding from the store,
  echo-message reconciliation, msgid-gated reactions/replies/redaction,
  typing both ways, presence via NAMES + PREFIX and WHOX (354 away/account),
  MONITOR verbs with status lines, standard-replies lines, MARKREAD
  mirroring, netsplit/netjoin collapse. Remaining: multiline batches
  (spec wip), metadata avatars.
- **Phase 6 — Polish**: whois panel, ignore list, LIST browser, link
  previews pending, two-pane tablet layout, traffic console, TOML theme
  engine applied to Material scheme ✅. Remaining: moderation surfaces
  beyond slash verbs, per-message link auto-open polish.
- **Phase 7 — Bouncers**: not started (soju BOUNCER cap, ZNC playback +
  management).
- **Phase 8 — Extras**: share-target intent lands text into the composer;
  media uploads need a user-configured upload endpoint (Halyard-style
  filehost/HTTP PUT) before they are meaningful; widgets and an on-device
  catch-up digest are future work — Android has no FoundationModels
  equivalent, so that feature needs a bundled model decision first.

The authoritative IRCv3 obligation inventory lives in
`docs/ircv3-checklist.md`; check items off as they land.
