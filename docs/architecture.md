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

Reconnect clears connection-scoped members, presence and metadata, publishes
empty member snapshots, and marks existing/restored channels JOINING. The
visible conversation requests members only after its CHANNEL/JOINED transition;
the coordinator additionally requires registration. Persisted-history loading
is separate, and hidden channels are not eagerly queried.

`/monitor c` clears the session's local MONITOR targets before sending `MONITOR C`,
without waiting for a server acknowledgement. Users shared with joined channels
or pending NAMES replies remain tracked; unshared users and profiles are pruned.
Profile removals publish immediately unless an open metadata/join batch defers
publication until its close.

Room schema 3 stores nullable channel context and history-context eligibility
through transcript reloads and search-hit restoration. The explicit 1→2 migration
adds channel context; 2→3 adds the context flag and scopes the unique message-ID
index to `(networkId, conversation, msgid)`. Existing row IDs, content hashes and
FTS rows survive both migrations. There is no destructive fallback.
Rename-following retains only the active selected key and one pending destination,
retargeted across rapid renames before buffer publication. Consuming a redirect,
changing selection, reusing its source or removing its destination/network clears
the pending state; no historical alias map is retained.

Remote image state is remembered by loader, resolved URL and requested size,
with explicit loading, success and unavailable outcomes. A changed identity
starts with its matching cached image or a placeholder, never the previous
identity's bitmap; keyed loading retains cancellation behavior. Attachment
previews load only on tap. Rejected URLs and failed downloads or decoding show
an unavailable state with an external-open action rather than a loading spinner.

Transcript channel links preserve full punctuation and Unicode names and route
through `LiveCoordinator.openChannel`: joined or joining buffers are reused;
new or failed channels use the normal JOIN command path. Offline join attempts
keep the target in the join dialog. External HTTP(S) links normalize their scheme.
Composer sendability uses parsed visible text, so formatting-only drafts cannot
send while formatted messages retain their original wire content.

Network collapse state belongs to the root's saved state, shared by the overview
and drawer across navigation and configuration changes. Event-run keys use the
smallest monotonic local message ID so adding live or restored events does not
reset expansion. Unread and mention scans inspect all messages: server-time
timestamps can arrive out of order, especially during playback.

Reducer behaviour is pinned by plain-JVM tests
(`app/src/test/.../coordinator/*ReducerTests.kt`, `PerNetworkStateTests.kt`)
that feed raw lines and assert on returned effects and state.

Outbound mirrors it: composer line → `SlashCommandParser.parse` →
`LiveCoordinator.dispatchCommand` → connection send. Coordinator calls that
change protocol state (WHOIS expectation, LIST browsing, leaving a channel)
go through the same per-session lock.

## History recovery

[Halyard parity phase 1 / issue 12](https://github.com/brentdevs/yardhal/issues/12)
adds history recovery without renumbering the architecture roadmap below.

- `HistoryCoordinator` owns local cursors, provider selection, reconnect anchors,
  bounded discovery, request queues and persisted gap intervals.
  `HistoryRequestTracker` correlates complete responses before replay crosses
  into transcript or storage effects.
- Room pages contain 200 rows with a lexicographic `(timestampMs, rowId)` boundary.
  Timestamp ties cannot strand rows. Local pages are consumed before server
  `CHATHISTORY BEFORE`; offline and unsupported servers retain disk navigation.
  Saved networks remain visible after `/quit`, without a live connection.
- Registration seeds each conversation from its newest eligible stored
  PRIVMSG/NOTICE/ACTION, using row ID to break timestamp ties. Server/control
  buffers and contextual history extras are excluded. Existing channels and DMs
  use anchored LATEST; new conversations use unanchored LATEST.
- CHATHISTORY capability or ISUPPORT enables the provider. Requests honor the
  advertised limit, capped locally at 100, and use only supported MSGREFTYPES.
  Opaque msgids preserve timestamp ties and ordering across clock skew;
  timestamp-only catch-up overlaps by ten seconds. A rejected msgid reference
  can be retried explicitly with a supported timestamp.
- TARGETS scans at most seven days, starting from the oldest eligible reconnect
  anchor when newer, with at most the server/local request limit. Plain ISO
  timestamps in target rows are parsed as server time. Missing end indication
  is displayed as incomplete discovery, even for a short nonempty response.
  One bounded response does not establish unlimited offline-DM discovery.
- One request is in flight per network: six-second hard deadline, thirty-second
  queue lifetime, and an eight-MiB conservative parsed-frame capture budget.
  Logical multiline messages and contextual extras are not mistaken for the
  server's primary-message count. Partial, failed and cancelled responses do not
  replay messages or establish coverage.
- Labels, inherited labeled-response wrappers, nested batches and CASEMAPPING
  protect request identity. Socket epochs and session generations reject replaced
  connections. Ambiguous unlabelled timeout/cancellation quarantines late replay
  until a fresh connection; the transcript exposes reconnect-and-retry.
- Persisted gaps are restored when buffers are created, before background
  catch-up can add another interval. Bounds are persisted before new anchors can
  reach Room. BETWEEN progresses residual gaps; only an explicit end indication
  or a successful empty CHATHISTORY response closes coverage. A short nonempty
  page is not proof of completion.
- Native ZNC uses `ZNC *playback PLAY` rather than an echoed `PRIVMSG *playback`
  command. Discovery is bounded to seven days; older navigation uses six-hour
  floating-point Unix-second windows. Wildcard replay aggregates closed batches
  with a two-second quiet period; an open batch still has the hard deadline.
  Closed range replay can finish a known gap when it witnesses its cached lower
  boundary, by msgid or exact sender/kind/body/server-time identity. Bare quiet
  replay and empty windows do not prove archive completeness. Clipped or
  nonadvancing ranges retain unknown coverage with explicit controls.
- Transcript keys and Compose anchoring preserve the readable message and offset
  through prepends and gap fills. Historical catch-up never forces follow-latest.
  Canonical overlap heals persisted msgids/row IDs without replacing existing
  local IDs, FTS identities or richer in-memory reply/context metadata. Replay
  itself neither raises live notifications nor regresses read markers.

Semantics follow the [IRCv3 CHATHISTORY specification](https://ircv3.net/specs/extensions/chathistory)
and [ZNC Playback module interface](https://wiki.znc.in/Playback). Native playback
does not guarantee archive retention, precise progress through clipped timestamp
ties, or tags which the module omits. The exercised module strips replay msgids;
stored IDs are retained through canonical matching, not invented from wire data.

## Network configuration

Each network's overview menu exposes **Edit network**. The shared add/edit form
prefills host, port, TLS, nickname, display name, autojoin channels and SASL
account. Editing keeps the network ID and preserves unexposed configuration
fields, transcripts, selection, read markers, pins, groups and mutes.

`LiveCoordinator.updateNetwork` persists an existing ID through `NetworkStore`.
A display-name-only or unchanged save updates the live name without reconnecting.
Connection, identity, autojoin or credential changes replace the session once;
the old session's child scope is canceled and stale events are rejected.
Membership, presence, typing, topics and connection features reset before the
updated connection starts. STS remains host-specific. Open, unparted channels
continue to rejoin; removing an autojoin entry does not leave or delete its
conversation. Explicit PART intent is recorded before the server reply and
survives edits and reconnects. An explicit join or newly added autojoin entry
clears that intent.

Advertised CASEMAPPING changes rekey retained conversations, selection, history
reservations and persistent metadata. Colliding conversations merge transcripts;
pins, read markers, mutes and group definitions survive, with one group membership
per merged conversation. Room writes, key migrations and history reads are
ordered, and queued restoration retains the original live target spelling.
Initial bootstrap casemapping is not evidence of how existing database keys were
normalized; persisted keys are left intact until the prior mapping is known.
Closed legacy history stores only normalized names: unavailable original
spellings cannot be reverse-expanded safely when a server uses a less permissive
mapping.

Passwords stay in the credential vault and are never prefilled. An empty edit
password keeps the saved secret; any nonempty replacement, including only spaces,
uses a fresh vault key. `NetworkSaver` deletes that staged key on a rejected add
or update and reports failure to the editor, which shows an error and retains the
draft. The previous SASL key is deleted only after a successful save and only
when it is not also the configured server-password key. **Clear saved SASL
password** removes the SASL reference without deleting a shared server secret.
The SASL account is independent of nickname and can remain saved without a
password, but the effective connection disables SASL until a password is
configured. Nonsecret drafts survive rotation; plaintext password drafts do not.
Cancel and Android Back discard edits. A restored, missing edit target falls
through to normal content while its stale editor state is dismissed.

`NetworkSaverTests` exercises production credential saves, shared-key handling
and rejected-save rollback with real stores and loopback authentication.
`NetworkEditLifecycleTests` covers cosmetic saves, endpoint and registration
changes, conversation-state preservation, stale events, virtual-time backoff
cutover, restart restoration, STS enforcement, CASEMAPPING collisions and queued
history restoration with Room and real loopback connections. Editor prefill,
rotation, cancellation and rejected-save presentation are verified on Android;
there is no Compose test suite.

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

CI runs debug assembly, lint and the full test suite as separate ten-minute
phases, matching the `make check` ordering within the existing thirty-minute
job limit. Test start/end logging identifies a stalled case; a phase timeout
leaves time for failure-report upload instead of exhausting the whole job.

The in-memory Room factory runs its query executor inline. Cancelling and joining
a coordinator scope can otherwise leave Room's detached query work opening SQLite
after the harness has closed the database, racing Robolectric's native reset.
Inline execution keeps the query within the caller's lifetime; harnesses still
join their scopes before closing Room. The on-disk application builder retains
Room's asynchronous executors.

SCRAM uses the complete RFC 4013 Unicode 3.2 SASLprep profile: mapping, frozen
NFKC normalization, prohibited-character checks, bidi restrictions, and the
query/stored unassigned-character distinction. Usernames use QUERY and passwords
use STORED. Empty prepared credentials fail at mechanism construction. Preparation
failure emits one SASL failure without sending credentials or downgrading.
Server iteration counts must be within 4,096–1,000,000 inclusive. The upper bound
is a client resource policy, not a protocol requirement; out-of-range challenges
abort authentication before password derivation without clamping or downgrading.

`scripts/generate-saslprep-tables.py` reproduces the frozen encoded initializer
from CPython's RFC 3454 `stringprep` predicates and `unicodedata.ucd_3_2_0`, without
runtime dependencies or dependence on Android/JVM Unicode versions:

```sh
nix develop --command python3 scripts/generate-saslprep-tables.py
```

The compressed payload contains ten big-endian, length-prefixed uint32 arrays:
B.1, C.1.2, prohibited, A.1, D.1 and D.2 ranges; combining-class pairs;
expanded NFKD code-point/offset/length triples; decomposition values; and canonical
composition triples. Hangul normalization is algorithmic. Its uncompressed SHA256
is `42a08a6261c74bc6e3ee56b62b408ae7af3fdf6f6512432ceacc6f910cc43761`.

The prepared loopback fixture covers normalization-changing Unicode credentials.
The live Ergo scenario covers a normalized username and NFKC-stable Unicode
password: [Ergo 2.14.0 stores SCRAM keys without password preparation](https://github.com/ergochat/ergo/blob/v2.14.0/irc/accounts.go#L2286-L2299).
No server-specific preparation fallback is introduced.

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
- **Phase 5 — IRCv3 breadth**: server-time everywhere, local tuple paging,
  anchored channel/DM chathistory LATEST/BEFORE/BETWEEN, bounded TARGETS discovery
  and retained gap coverage, labeled replies, echo-message reconciliation,
  msgid-gated reactions/replies/redaction,
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
  with jump-to-unread, Room-FTS message search with snippet results, and
  identity-preserving network editing with credential-vault updates.
  Remaining: moderation surfaces beyond these actions and slash verbs,
  per-message link auto-open polish.
- **Phase 7 — Bouncers**: ZNC `znc.in/playback` uses bounded discovery,
  older windows and coverage-aware gap filling; replay suppresses live
  notification effects. soju
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
