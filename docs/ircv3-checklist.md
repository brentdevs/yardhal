# Yardhal IRCv3 Checklist

Client-obligation inventory modeled on Halyard's spec-by-spec audit
(ircv3.net, including 2025–2026 ratifications). Check items off as they land.
Phase numbers refer to `docs/architecture.md`.

## Baseline

- [x] Modern IRC baseline: message grammar, numerics, ISUPPORT/CASEMAPPING handling (P1)
- [x] capability-negotiation 302: CAP LS 302, REQ/ACK, CAP NEW/DEL at runtime (P2)
- [x] message-tags: parse/escape tags, enlarged limits, request cap (P1/P2; 417 surfacing pending)
- [x] server-time: use time tag as authoritative timestamp, especially in playback (P5)

## Identity & access

- [x] sasl 3.1: AUTHENTICATE flow during negotiation (PLAIN) (P2)
- [ ] sasl 3.2: mechanism list parsing, post-registration re-auth (P2+)
- [x] account-notify: ACCOUNT updates member account state (P5)
- [x] account-tag: verified-account badge input; `account` tag updates sender presence and ✓ on message rows/member sheet (P5)
- [x] extended-join: account + realname on JOIN (P5)
- [x] setname: inbound SETNAME + send own realname change via /setname (P5)
- [x] chghost: apply user/host updates silently (P5)
- [ ] draft/account-registration: REGISTER/VERIFY flows with standard-replies errors (P8)

## Presence

- [x] away-notify: live away/back transitions with away message (P5)
- [x] MONITOR +/- verbs, 730/731 numerics surfaced (P5)
- [x] extended-monitor: monitored targets (730/731/732) tracked so presence-class events update them (P5)
- [ ] draft/pre-away: AWAY suppression during registration (P8)

## Messaging affordances

- [x] message-ids: persisted; reply/react/redact gated on msgid presence (P3/P5)
- [x] echo-message: own messages reconciled against the server echo, msgid stamped (P5)
- [x] +draft/reply: send/receive replies with quoted preview and jump-to-source (P5)
- [x] +draft/react / +draft/unreact: reactions pills with counts, tap to toggle (P5)
- [x] +typing: send rate-limited active TAGMSG; inbound indicators with expiry (P5)
- [x] draft/message-redaction: REDACT handling + own redacts (P5)
- [x] draft/read-marker: capability requested; MARKREAD sent on read and applied inbound (cross-device); unread divider + jump (P5)
- [ ] draft/multiline: reassemble multiline batches honoring limits (P5)
- [ ] +draft/channel-context: "re: #channel" chip on DMs (P8)

## History & transport

- [x] batch: BATCH +/- frames tracked per session; netsplit/netjoin collapse; playback classified as history (P5)
- [x] chathistory batch type: replay routed as history (no unread/notification noise) (P5)
- [x] draft/chathistory: LATEST bootstrap on channel join when advertised (P5); full selectors pending
- [x] netsplit/netjoin batches: collapse into one event (P5)
- [ ] labeled-response: label outbound commands, correlate responses incl. ACK/batches (P5)
- [x] standard-replies: FAIL/WARN/NOTE → tagged system lines (P5; toast polish pending)
- [x] sts: upgrade to TLS port, persist and enforce policy with expiry (P2; warning UI pending)
- [x] SNI: hostname in ClientHello (platform TLS does this by default) (P2)
- [ ] STARTTLS: NOT implemented (deprecated); direct TLS only

## Metadata & misc

- [x] NAMES/353/366 member lists via multi-prefix-aware parser (P5); WHOX %fields pending
- [x] multi-prefix: prefix symbols retained; member sheet sections by role (P5)
- [x] userhost-in-names: full nick!user@host in NAMES, user/host retained in presence (P5)
- [x] no-implicit-names (equivalent): members fetched lazily via WHO on open (P5)
- [x] invite-notify: INVITE system lines (own invites in server buffer, others in channel) + 341 confirmation (P5)
- [x] bot-mode: BOT ISUPPORT letter, WHO/WHOX flag, 335 and `bot` tag drive member-sheet badge (P5)
- [x] account-extban: ACCOUNTEXTBAN/EXTBAN "Ban account" member action (P6)
- [ ] draft/metadata-2: METADATA GET/SET/SUB, avatars/display names (P5)
- [x] soju.im/FILEHOST ISUPPORT: endpoint discovery, TLS-policy enforcement, authenticated POST with multipart fallback, attachment-tagged messages (P8)
- [ ] UTF8ONLY: always transmit UTF-8, skip legacy encoding heuristics (P5)
- [ ] draft/extended-isupport: full ISUPPORT set pre-registration (P5)
- [ ] draft/ICON: network icon ISUPPORT token fetch/cache (P6)
- [ ] draft/channel-rename: RENAME moves buffer/transcript/unread state (P5)
- [ ] client-batch: infrastructure only; no production use until ratified
- [ ] WebSocket transport: n/a (native TCP/TLS client)
- [ ] WEBIRC: server-only, n/a

## Bouncer extensions (soju)

- [x] soju.im/bouncer-networks: capability + notify requested, BOUNCER NETWORK upsert/delete parsing with escaped attributes, ADDNETWORK/DELNETWORK, CONNECT/DISCONNECTNETWORK, BouncerServ service commands, draft diffing (P7)
- [x] znc.in/playback: playback start request + batch classification (P7)
