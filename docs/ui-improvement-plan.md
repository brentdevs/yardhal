# Yardhal UI improvement plan

This plan improves the Android experience around features Yardhal already has. The
first objective is to make network and conversation context obvious on a phone,
keep composing and reading state stable while moving between buffers, and make
search and utility workflows usable without cramped dialogs. Protocol features
and new IRCv3 obligations remain on the [architecture roadmap](architecture.md)
and [IRCv3 checklist](ircv3-checklist.md).

## What exists today

| Area | Current behavior | User-facing cost |
| --- | --- | --- |
| Navigation | `YardhalAppRoot` switches between overview and one conversation on phones; at 600 dp it uses a fixed 38/62 split. The selected buffer is held in local `remember` state. | Moving between busy channels takes a trip through the overview, and selection is fragile across activity recreation. |
| Overview | The per-network tree already has groups, pins, swipe actions, previews, and an unread dot. `Debug`, `List`, optional `Bouncer`, and a prominent `Remove` control share the overview with everyday navigation. | High-frequency actions compete with maintenance actions; the unread dot does not distinguish a mention from ordinary activity. |
| Conversation | Topic, member count, unread jump, search, and join all compete in the top bar. Messages have grouping, reactions, replies, and an unread divider. Long-press actions appear in an alert dialog. | The header crowds on narrow phones, and message actions feel disconnected from the selected message. |
| Composer | The field supports multiline text, attachment picking, and first-token nick completion. Its draft uses composable-local `remember` state. | A partially written message can disappear when changing buffers; completion does not work at the cursor later in a sentence. |
| Search and tools | Room FTS returns snippets, but search is an alert dialog; selecting a hit opens the buffer at its usual scroll position. Channel LIST, traffic, whois, and bouncer management also use dialogs. | Search lacks a reliable jump to the result. Longer lists and detail views have little room for filtering or context. |
| Network setup | Presets and connection fields appear in a fixed vertical column. | Small screens and the keyboard can crowd the final fields and Connect action. |

These are code-review findings, not usability measurements. Validate the proposed
layout on a device before treating the interaction targets below as met.

## Reference patterns and Yardhal's direction

[Revolution IRC](https://github.com/MCMrARM/revolution-irc) is a useful Android
reference for Material presentation, command/nick/channel completion, and user
control over chat typography and colors. [The Lounge](https://github.com/thelounge/thelounge)
is a useful reference for a responsive channel list, visible new-message state,
and continuity between compact and larger layouts; its
[client documentation](https://thelounge.chat/docs/usage) keeps help and settings
off the primary chat surface. Use these as interaction references, not as a
feature checklist or a reason to copy their branding.

The proposed model is conversation-first. On a compact screen, the conversation
header has an explicit button to open the buffer list as a sheet/drawer; Android
Back returns to the list. On expanded screens, keep the buffer list visible
beside the conversation, with a resizable or width-constrained pane rather than
a fixed ratio. The same list and selection state should drive both layouts.
The list retains Yardhal's network sections, pins, groups, and previews.

Each surface should have one obvious primary action and a short, stable action
hierarchy:

- **Buffer list:** show network connection status and the active network name;
  use the primary action for joining/starting a conversation once at least one
  network exists. Put Add network and network removal in network management.
  Keep channel browsing scoped to the selected network.
- **Conversation:** show channel/DM title and network, with topic as a single
  tappable summary that opens details. Keep members and search as visible
  actions when space allows; place Join, traffic, and less frequent controls in
  an overflow menu. Show a compact, actionable connection or join-failure
  banner above the transcript instead of leaving an ambiguous empty state.
- **Transcript:** retain distinct system events, day markers, unread divider,
  highlights, replies, and reactions. Give message text more horizontal room,
  align timestamps consistently, and use a contextual bottom sheet for Reply,
  Copy, React, and permitted Delete. Keep server capability and ownership checks
  behind each action. Handle links and attachments through explicit tap targets.
- **Composer:** preserve a draft per buffer across switching and activity
  recreation; complete the token at the cursor for nicks/channels/commands;
  keep reply context and attachment progress adjacent to the field. Show a clear
  send-disabled reason while offline, and preserve unsent text on send failure.

The visual system should use a small set of spacing, type, icon, and status
tokens on top of the existing Material 3/TOML theme engine. Colors may enhance
meaning, but unread, mention, failed join, and connection states must also have
text or shape cues. Offer a compact/comfortable transcript density choice and
chat text size before adding a large theme settings surface.

## Delivery slices

These are separate, reviewable PRs in priority order. Each slice includes
portrait phone and expanded-layout checks; none requires replacing the
protocol, data, or connection layers.

1. **Navigation and hierarchy (P0).** Extract one shared buffer-list UI for
   compact drawer/sheet and expanded pane. Add a visible list affordance and
   Back behavior, preserve selection through rotation, move Debug/List/Bouncer
   and network removal into contextual menus, and require an explicit network
   for LIST/traffic. Keep existing pin/group/swipe behavior. Done when a user
   can move between two channels in at most two taps from a conversation, the
   current network is unambiguous, and no destructive action sits on a normal
   network row.
2. **Reading and composing (P0).** Give the conversation header and transcript
   a deliberate compact layout, add connection/join-failure states, replace the
   message action dialog with a contextual sheet, and store drafts by buffer.
   Improve cursor-aware completion after drafts are stable. Done when switching
   buffers, rotating, and returning from background retain the right draft and
   scroll context; message actions remain available with TalkBack and without
   long press; offline and failed-join states explain the next action.
3. **Search that lands on a message (P1).** Make search a full-screen destination
   on phones and a pane on larger screens. Add network/conversation scope,
   loading/empty/error states, and result metadata. Use the FTS hit's stable
   `rowId` to load the right history window and scroll/highlight the exact
   result; opening a hit must not silently mark unrelated newer messages read.
   Done when a hit from a different buffer opens with the matched row visible
   and Back returns to the query and results.
4. **Network and utility flows (P1).** Make Add network scrollable and
   keyboard-safe with grouped identity, connection, and autojoin fields.
   Promote channel LIST to a searchable, network-scoped destination; give
   whois, bouncer management, and traffic a reusable detail/sheet pattern.
   Done when all fields and actions remain reachable at 360 dp with the keyboard
   open, and every utility view identifies its network.
5. **Polish and accessibility (P2).** Tune typography, spacing, icons, and
   light/dark/TOML contrast; add transcript density and text-size controls.
   Audit focus order, 48 dp touch targets, screen-reader names, dynamic type,
   keyboard/IME behavior, and edge-to-edge insets. If mention badges are added,
   extend the buffer/read-marker model to distinguish highlights from the
   current boolean `hasUnread` before painting new UI. Done when the core flows
   work at enlarged font size and with TalkBack, and state is understandable
   without color.

## Validation for implementation PRs

Use `make check` as the merge gate and exercise UI changes on a running app, as
required by `AGENTS.md`. For each slice, inspect 360–412 dp phones, a 600 dp
foldable/tablet boundary, and an 840+ dp layout in portrait and landscape; test
light and dark themes, large font, keyboard open, TalkBack, offline/reconnecting,
two networks, and a busy channel with history. Capture before/after screenshots
in the PR for the flows changed. Add focused Compose/Robolectric tests for
selection, draft retention, search-hit navigation, and accessibility semantics
where they protect behavior; use a real-server pass for join and message flows.

The initial success criteria are observable tasks rather than visual taste:
switch buffers within two taps, identify which network owns the current
conversation, resume an unsent draft after rotation, reach an FTS match at its
message, and recover from a failed join without guessing which control to use.
Measure these with short device walkthroughs before claiming the refresh done.
