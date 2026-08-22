# Yardhal IRCv3 Checklist

Client-obligation inventory modeled on Halyard's spec-by-spec audit
(ircv3.net, including 2025–2026 ratifications). Check items off as they land.
Phase numbers refer to `docs/architecture.md`.

## Baseline

- [ ] Modern IRC baseline: message grammar, numerics, ISUPPORT/CASEMAPPING handling (P1)
- [ ] capability-negotiation 302: CAP LS 302, REQ/ACK, CAP NEW/DEL at runtime (P2)
- [ ] message-tags: parse/escape tags, enlarged limits, ERR_INPUTTOOLONG 417, request cap (P1/P2)
- [ ] server-time: use time tag as authoritative timestamp, especially in playback (P5)

## Identity & access

- [ ] sasl 3.1: AUTHENTICATE flow during negotiation (PLAIN) (P2)
- [ ] sasl 3.2: mechanism list parsing, post-registration re-auth (P2+)
- [ ] account-notify: ACCOUNT updates member account state (P5)
- [ ] account-tag: verified-account badge input (P5)
- [ ] extended-join: account + realname on JOIN (P5)
- [ ] setname: inbound SETNAME + send own realname change (P5)
- [ ] chghost: apply user/host updates silently (P5)
- [ ] draft/account-registration: REGISTER/VERIFY flows with standard-replies errors (P8)

## Presence

- [ ] away-notify: live away/back transitions (P5)
- [ ] MONITOR +/- within ISUPPORT limit, online/offline numerics (P5)
- [ ] extended-monitor: monitored targets emit presence-class events (P5)
- [ ] draft/pre-away: AWAY suppression during registration (P8)

## Messaging affordances

- [ ] message-ids: persist msgid; gate reply/react/redact/read-marker UI on it (P3 stores / P5 UI)
- [ ] echo-message: render own messages from echo, dedupe pending copy (P5)
- [ ] +draft/reply (+reply): send/receive replies, jump-to-source (P5)
- [ ] +draft/react / +draft/unreact: reactions pills with counts (P5)
- [ ] +typing: rate-limited active/paused/done, indicators with expiry (P5)
- [ ] draft/message-redaction: REDACT handling + own redacts (P5)
- [ ] draft/read-marker: MARKREAD send/apply for cross-device read state (P5)
- [ ] draft/multiline: reassemble multiline batches honoring limits (P5)
- [ ] +draft/channel-context: "re: #channel" chip on DMs (P8)

## History & transport

- [ ] batch: buffer/correlate by reference tag, degrade gracefully (P2 framing / P5 usage)
- [ ] chathistory batch type: silent history playback by server-time (P5)
- [ ] draft/chathistory: BEFORE/AFTER/AROUND/LATEST/TARGETS with msgid/timestamps (P5)
- [ ] netsplit/netjoin batches: collapse into one event (P5)
- [ ] labeled-response: label outbound commands, correlate responses incl. ACK/batches (P5)
- [ ] standard-replies: FAIL/WARN/NOTE → structured errors + toasts (P5)
- [ ] sts: upgrade to TLS port, persist policy with expiry, warning UI (P2 core / P4 UI)
- [ ] SNI: hostname in ClientHello (platform TLS does this by default) (P2)
- [ ] STARTTLS: NOT implemented (deprecated); direct TLS only

## Metadata & misc

- [ ] WHOX: %field selectors + numeric 354 when advertised (P5)
- [ ] multi-prefix: all status prefixes in NAMES/WHO (P5)
- [ ] userhost-in-names: full nick!user@host in NAMES (P5)
- [ ] no-implicit-names: suppress NAMES burst on JOIN, fetch lazily (P5)
- [ ] invite-notify: INVITE system lines for ops (P5)
- [ ] bot-mode: BOT ISUPPORT letter + badge (P5)
- [ ] account-extban: ban-account option in moderation menus (P6)
- [ ] draft/metadata-2: METADATA GET/SET/SUB, avatars/display names (P5)
- [ ] UTF8ONLY: always transmit UTF-8, skip legacy encoding heuristics (P5)
- [ ] draft/extended-isupport: full ISUPPORT set pre-registration (P5)
- [ ] draft/ICON: network icon ISUPPORT token fetch/cache (P6)
- [ ] draft/channel-rename: RENAME moves buffer/transcript/unread state (P5)
- [ ] client-batch: infrastructure only; no production use until ratified
- [ ] WebSocket transport: n/a (native TCP/TLS client)
- [ ] WEBIRC: server-only, n/a
