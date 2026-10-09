# Yardhal IRCv3 Checklist

Client-obligation inventory modeled on Halyard's spec-by-spec audit
(ircv3.net, including 2025–2026 ratifications). Check items off as they land.
Phase numbers refer to `docs/architecture.md`.

## Baseline

- [x] Modern IRC baseline: message grammar, numerics, ISUPPORT/CASEMAPPING handling (P1)
- [x] capability-negotiation 302: CAP LS 302, REQ/ACK, CAP NEW/DEL at runtime (P2)
- [x] message-tags: parse/escape tags, enlarged limits, request cap; 417 surfaced as an error (P1/P2)
- [x] server-time: use time tag as authoritative timestamp, especially in playback (P5)

## Identity & access

- [x] sasl 3.1: AUTHENTICATE flow during negotiation (PLAIN) (P2)
- [x] sasl 3.2: mechanism list parsing and server-driven post-registration re-auth (P2+) — SCRAM-SHA-256 preferred over PLAIN, 908 fallback, CAP NEW/DEL sasl. Manual `IrcConnection.reauthenticate()` is a connection-layer API only, not an app command; REGISTER SUCCESS and VERIFY SUCCESS already authenticate the account without a second SASL exchange.
- [x] SASL EXTERNAL: real per-network Android KeyChain client certificate, TLS prerequisite, optional authorization identity, no password reference or guest fallback on failure (parity phase 2)
- [x] Required authentication fails closed on unavailable/rejected SASL and missing configured secrets; mechanism selection supports AUTO, PLAIN, SCRAM-SHA-256 and EXTERNAL (parity phase 2)
- [x] account-notify: ACCOUNT updates member account state (P5)
- [x] account-tag: verified-account badge input; `account` tag updates sender presence and ✓ on message rows/member sheet (P5)
- [x] extended-join: account + realname on JOIN (P5)
- [x] setname: inbound SETNAME + send own realname change via /setname (P5)
- [x] chghost: apply user/host updates silently (P5)
- [x] draft/account-registration: REGISTER/VERIFY flows with standard-replies errors (P8) — `/register [account] <email|*> <password>`, `/verify [account] <code>`, cap value via `AccountRegistrationPolicy`, password redacted in raw log; FAIL replies shown as server lines

## Presence

- [x] away-notify: live away/back transitions with away message (P5)
- [x] MONITOR +/- verbs, 730/731 numerics surfaced (P5)
- [x] extended-monitor: monitored targets (730/731/732) tracked so presence-class events update them (P5)
- [x] draft/pre-away: AWAY suppression during registration (P8) — `IrcConnectionConfig.initialAway` sent before CAP END when acknowledged, after 001 otherwise

## Messaging affordances

- [x] message-ids: scoped persistence and canonical echo healing; wire reply/react/redact actions require msgid. Durable local quote-parent row IDs are separate inputs for parity phase 5 (P3/P5; parity phase 3)
- [x] echo-message: own messages reconciled against the server echo, msgid stamped; identical fresh sends retain distinct durable rows and local quotes (P5; parity phase 3)
- [x] +draft/reply: send/receive replies with quoted preview and jump-to-source; parent relationships and off-page/deleted previews survive process death (P5; parity phase 3)
- [x] +draft/react / +draft/unreact: durable per-sender membership, reaction pills and tap-to-toggle; bounded retention exposes partial/lower-bound history (P5; parity phase 3)
- [x] +typing: send rate-limited active TAGMSG; inbound indicators with expiry (P5)
- [x] draft/message-redaction: REDACT handling + own redacts; transactional body/FTS/attachment/reaction removal, tombstones and retired-history floors prevent replay resurrection (P5; parity phase 3)
- [x] draft/read-marker: full-history durable unread/mention cursors; offline advances queue MARKREAD until supported accepted registration, clear on acknowledgement and reconcile monotonically; legacy unknown highlights are not guessed (P5; parity phase 3)
- [x] draft/multiline: cap + max-bytes/max-lines parsed; inbound batches reassembled (concat, batch msgid, playback-aware, defensive limit flush); composer newlines sent as limit-respecting batches with concat splitting, echo reconciled; separate PRIVMSGs without the cap (P5)
- [x] +draft/channel-context: `+channel-context`/`+draft/channel-context` on DMs stored per message; tappable "re: #channel" chip (P8)

## History & transport

- [x] batch: BATCH +/- frames tracked per session; netsplit/netjoin collapse; playback classified as history (P5)
- [x] chathistory batch type: replay routed as history (no unread/notification noise) (P5)
- [x] draft/chathistory: negotiated capability or CHATHISTORY ISUPPORT enables anchored channel/DM LATEST and local-first BEFORE paging; server/local limits and MSGREFTYPES honored; finite loading/error/retry/cancellation states (P5; parity phase 1)
- [x] CHATHISTORY TARGETS: bounded seven-day discovery, plain ISO target times, explicit incomplete-discovery indication without claiming unlimited recovery (P5)
- [x] CHATHISTORY BETWEEN: persisted interior-gap bounds, residual progress; short nonempty pages never imply completion; failure/cancellation never fabricate coverage (P5)
- [x] draft/chathistory-context and draft/chathistory-end: contextual extras preserved without counting against primary-message limits or advancing durable anchors; end-tag presence or successful empty CHATHISTORY completes the requested range (P5)
- [x] netsplit/netjoin batches: collapse into one event (P5)
- [x] labeled-response: /raw, /whois, /who, /mode family, /topic, /monitor, LIST and PRIVMSG sends labelled when acked; single replies, labelled batches and ACK correlated in the reducer; generic replies routed to the origin buffer; echoes reconciled by label (text match fallback) (P5)
- [x] standard-replies: FAIL/WARN/NOTE → tagged system lines (P5)
- [x] sts: upgrade to TLS port, persist and enforce policy with expiry; no insecure bypass of active policy (P2)
- [x] SNI: configured hostname verified in captured ClientHello (P2)
- n/a — STARTTLS: deprecated; use direct TLS and STS upgrade instead

## Metadata & misc

- [x] NAMES/353/366 member lists via multi-prefix-aware parser; WHOX `%cuhnfar` retains user/host/nick/flags/account/realname (P5)
- [x] multi-prefix: consume stacked symbols and retain the highest role for member-sheet sections (P5)
- [x] userhost-in-names: full nick!user@host in NAMES, user/host retained in presence (P5)
- [x] no-implicit-names: negotiate suppression of join-time NAMES; own JOIN completes membership-independent join state; fetch members lazily via WHO/WHOX on open (P5)
- [x] invite-notify: INVITE system lines (own invites in server buffer, others in channel) + 341 confirmation (P5)
- [x] bot-mode: BOT ISUPPORT letter, WHO/WHOX flag, 335 and `bot` tag drive member-sheet badge (P5)
- [x] account-extban: ACCOUNTEXTBAN/EXTBAN "Ban account" member action (P6)
- [x] draft/metadata-2: cap limits parsed; SUB avatar/display-name before autojoin; METADATA and 760/761/766/770/771/772/774/FAIL handled; metadata batches; SYNC retry on 774; /setavatar /setdisplayname; HTTPS-only cached avatars + display names in rows/member sheet (P5; Ergo 2.14 lacks metadata, covered by reducer + loopback tests)
- [x] soju.im/FILEHOST ISUPPORT: endpoint discovery, TLS-policy enforcement, authenticated POST with multipart fallback, attachment-tagged messages (P8)
- [x] UTF8ONLY: always transmit UTF-8, skip legacy encoding heuristics (P5) — strict UTF-8 inbound decoding with U+FFFD replacement, codepoint-safe truncation, `ISupport.utf8Only`
- [x] draft/extended-isupport: full ISUPPORT set pre-registration (P5) — `ISUPPORT` sent before CAP END when acknowledged; `draft/isupport` batches pass through, `-TOKEN` removals honoured by `ISupport.mergedWith`
- [x] draft/ICON: ISUPPORT token (with \xHH unescape, `{size}` template, `-draft/ICON`) shown on the network header via the HTTPS-only image cache (P6)
- [x] draft/channel-rename: RENAME moves buffer, transcript rows (FTS-consistent), read marker, mute, pins/groups/parted, autojoin and member state; system line; selection follows rename (P5)
- n/a — client-batch: no production use until ratified; multiline uses its own defined client batch frames
- n/a — WebSocket transport: native TCP/TLS client
- n/a — WEBIRC: server/gateway obligation, not an end-user client command

## Bouncer extensions and management

- [x] soju.im/bouncer-networks + notify: live escape-aware NETWORK upsert/explicit deletion, concurrent live-batch deltas retained through complete-list reconciliation, SASL-authenticated BIND before CAP END, ADDNETWORK/CHANGENETWORK/DELNETWORK (P7; parity phase 4)
- [x] Stable parent/netID upstream UUIDs, persisted bindings, offline shells and independent saved intent; disabled/deleted/rejected isolation and parent cascade (parity phase 4)
- [x] BouncerServ network enabled-state and bound-channel status/detach/timer/relay/reattach controls; changed-field diffs, labeled replies or ordered nonce fences, fresh PONG recovery after uncertain unlabeled replies, dismissal-safe submitted operations and acknowledged partial outcomes (parity phase 4)
- [x] ZNC mode-specific PASS, independent *status/*controlpanel availability, network/server/settings/channel management, masked-secret retention and freshly scoped baselines (parity phase 4)
- [x] Historical/current management and own service echoes cannot create ordinary DMs, unread or live notifications; late generations and uncertain replies cannot acknowledge another operation (parity phase 4)
- [x] Bouncer-aware history provider selection: forwarded ZNC upstream ISUPPORT alone is not downstream CHATHISTORY; use negotiated CHATHISTORY or actual znc.in/playback, otherwise retain local-only history (parity phase 4)
- [x] znc.in/playback: bounded global channel/DM discovery, older six-hour PLAY windows, canonical overlap deduplication, retained gaps under clipping, finite empty/error/reconnect outcomes; no invented archive end or missing msgids (P7; parity phase 1)

## Coverage audit

Test classes below live in their feature module's `src/test`; coordinator
reducer tests run on the plain JVM. Coordinator/store integration tests use
Robolectric only for Android persistence. `make test-ircd` provisions pinned
Ergo and runs all real-server tests without skips.

| Inventory item | Covering test |
| --- | --- |
| Modern IRC baseline | `IrcMessageTests.parsesSimplePrivmsg`; `PerNetworkStateTests.casemappingChangeRekeysTrackedChannels` |
| capability-negotiation 302 | `CapabilityNegotiatorTests.fullListingThenRequestThenAckWithSasl`; `MetadataCapabilityLoopbackTests.runtimeCapabilityUpdatesPublishChangedValuesAndWithdrawnFeatures` |
| message-tags | `IrcTagsTests.serializeSectionRoundTrip`; `PerNetworkStateTests.inputTooLongSurfacesAsAnErrorLine` |
| server-time | `PerNetworkStateTests.channelPrivmsgBecomesAChannelMessageWithServerTimeAndTags` |
| sasl 3.1 | `IrcConnectionIntegrationTests.negotiatesCapabilitiesWithSaslPlainThenRegisters` |
| sasl 3.2 / SCRAM | `ScramSha256MechanismTests.rfc7677TestVectorRoundTrip`; `ScramSha256MechanismTests.excessiveIterationCountsAreRejected`; `ScramSha256MechanismTests.maximumIterationCountAuthenticatesAgainstIndependentPbkdf2Server`; `SaslPrepTests.rfc4013Examples`; `SaslPrepTests.queryCombiningClassesRemainFrozenForCharactersAssignedAfterUnicode32`; `IrcConnectionIntegrationTests.reauthenticatesAfterRegistrationAndOnCapNew`; `IrcConnectionIntegrationTests.unicodeScramCredentialsAuthenticateAgainstPreparedLoopbackKeys`; `IrcConnectionIntegrationTests.saslPreparationFailurePreventsRegistration`; `ErgoRoundTripTest.registersAccountThenAuthenticatesWithScramAndPreAway` |
| SASL EXTERNAL / required authentication | `IrcExternalIntegrationTests.externalUsesPresentedClientCertificateAndEmptyAuthorizationIdentityBeforeRegistration`; `IrcDriverRecoveryTests.absentDeclinedAndIncompatibleRequiredSaslAllFailClosed`; `NetworkEditLifecycleTests.externalUsesTheSelectedRealClientCertificateWithoutRequiringAPasswordReference` |
| account-notify | `IdentityReducerTests.accountNotifyUpdatesAccount` |
| account-tag | `IdentityReducerTests.accountTagFeedsSenderAccountAndPresence`; `IdentityReducerTests.knownAccountsSurviveUntaggedIdentityNotificationsAndRemainAvailableForAccountBans` |
| extended-join | `IdentityReducerTests.extendedJoinCapturesAccountAndRealname` |
| setname | `IdentityReducerTests.setnameUpdatesRealnameSilentlyForOthers`; `ErgoRoundTripTest.fullRoundTripAgainstRealServer` |
| chghost | `IdentityReducerTests.chghostUpdatesUserAndHostWithoutTranscriptLine` |
| draft/account-registration | `AccountRegistrationPolicyTests.parsesKnownFlags`; `SlashCommandParserTests.verifyBuildsVerifyCommand`; `ErgoRoundTripTest.registersAccountThenAuthenticatesWithScramAndPreAway` |
| away-notify | `IdentityReducerTests.awayNotifyTracksAwayAndBackAndRepublishesMembers` |
| MONITOR | `PerNetworkStateTests.monitorNumericsAreTaggedAsMonitorLines` |
| extended-monitor | `IdentityReducerTests.extendedMonitorTracksMonitoredNicksOutsideChannels`; `IdentityReducerTests.monitorListNumericEstablishesTargetsForSubsequentIdentityNotifications` |
| draft/pre-away | `IrcConnectionIntegrationTests.preAwaySendsAwayBeforeCapEnd`; `IrcConnectionIntegrationTests.initialAwayWithoutPreAwayIsSentAfterWelcome` |
| message-ids | `MessageStoreTests.recordDeduplicatesByMsgid`; `DurableMessageTests`; `NetworkEditLifecycleTests.advertisedCasemappingMergesChannelAndDirectMessageHistoryAndMetadataAcrossEditAndReload` |
| echo-message | `LabeledResponseReducerTests.labelledEchoReconcilesPendingMessageEvenWhenTextDiffers`; `DurableMessageTests.identicalFreshLocalSendsKeepDistinctRowsAndQuoteParentsAcrossCanonicalEchoes`; `LiveCoordinatorIntegrationTests` |
| +draft/reply | `PerNetworkStateTests.channelPrivmsgBecomesAChannelMessageWithServerTimeAndTags`; `DurableMessageTests`; `OfflineCoordinatorTests`; `OfflineConversationPresentationTests` |
| +draft/react / +draft/unreact | `PerNetworkStateTests.tagmsgReactionsAndTypingBecomeEffects`; `PerNetworkStateTests.reactionFoldAddsAndRemovesPerSender`; `DurableMessageTests`; `ReactionRetentionTests` |
| +typing | `PerNetworkStateTests.tagmsgReactionsAndTypingBecomeEffects` |
| draft/message-redaction | `PerNetworkStateTests.redactEmitsRedactionForTheWireConversation`; `MessagingCoordinatorTests.wireRedactionOnlyChangesTheNamedConversationWhenMessageIdsOverlap`; `DurableMessageTests`; `NetworkMessageRetentionTests.retiredPendingRedactionsBlockParentReplayWithoutKeepingTargetLedgers` |
| draft/read-marker | `PerNetworkStateTests.markreadAppliesTimestampToTheTargetAndIgnoresStarTargets`; `DurableReadMarkerTests`; `OfflineCoordinatorTests` |
| draft/multiline | `IrcMultilineTests.splitsLongLinesBetweenWordsWithConcatTagAndRoundTrips`; `MessagingReducerTests.multilineNestedInChathistoryIsPlayback`; `MessagingCoordinatorTests.multilineComposerTextIsBatchedAndReconciledAgainstTheEchoedBatch`; `ErgoRoundTripTest.multilineEchoAndChannelRenameAgainstRealServer` |
| +draft/channel-context | `MessagingReducerTests.channelContextTagStoredOnDirectMessages`; `MessagingReducerTests.channelContextOnMultilineBatchOpeningApplies`; `MessagingCoordinatorTests.channelContextSurvivesCoordinatorHistoryReload`; `LiveCoordinatorIntegrationTests.openingPersistedSearchHitRestoresChannelContextIntoAnAbsentConversation`; `MessageStoreTests.channelContextRoundTripsThroughRecentAndHistory`; `YardhalDatabaseMigrationTests.versionOneUpgradePreservesTranscriptHashesRowIdsAndSearch` |
| batch | `PerNetworkStateTests.playbackBatchesIncludingNestedOnesSuppressHighlights`; `PerNetworkStateTests.disconnectReportsDisconnectedAndReconnectResetsConnectionState` |
| chathistory batch type | `MessagingReducerTests.multilineNestedInChathistoryIsPlayback` |
| draft/chathistory / limits / MSGREFTYPES | `IrcChatHistoryTests`; `PerNetworkStateTests.ownJoinOpensBufferAndRequestsTopicModeAndHistory`; `HistoryCoordinatorIntegrationTests.offlineTimestampTiedPagesPreserveEveryRowAndExistingLocalIdentity`; `HistoryCoordinatorIntegrationTests.localPagesTransitionToBeforeAndAdvanceWireReferencesWithoutReorderingEqualTimeRows`; `HistoryCoordinatorIntegrationTests.timestampOnlyBeforeOverlapAtArchiveStartHonorsExplicitEnd`; `HistoryCoordinatorIntegrationTests.labeledFailureCanRetryWithoutReplacingCachedIdentity` |
| CHATHISTORY TARGETS | `HistoryRequestTrackerTests.targetsRowsPreserveServerOrderAndWaitForBatchCompletion`; `HistoryCoordinatorIntegrationTests.targetsDiscoverKnownAndNewOfflineDirectMessagesWithoutProtocolTranscript`; `HistoryCoordinatorIntegrationTests.limitedTargetsWithoutEndExposeIncompleteDiscoveryWhileEveryReturnedConversationLoads` |
| CHATHISTORY BETWEEN / durable coverage | `HistoryCoordinatorIntegrationTests.emptySuccessfulBetweenClosesResidualGapWithoutDiscardingCachedRows`; `HistoryCoordinatorIntegrationTests.backgroundCatchupPreservesPreviouslyPersistedGapBeforeTranscriptIsOpened`; `HistoryCoordinatorIntegrationTests.queuedStorageCannotRestoreCompletedGapOverNewGapOrResurrectRenamedCoverage`; `HistoryCoverageStoreTests` |
| History context / multiline | `MessageHistoryTests.laterContextAndContextOnlyConversationsCannotAdvanceAnchors`; `HistoryCoordinatorIntegrationTests.relatedFutureContextCannotAdvancePersistedReconnectAnchorOrLoseReplyIdentity`; `HistoryCoordinatorIntegrationTests.multilineHistoryPreservesCompleteBodyIdentityAndSearchAcrossManyWireFragments` |
| netsplit/netjoin | `PerNetworkStateTests.netsplitBatchCollapsesQuitsIntoOneSummaryLine`; `PerNetworkStateTests.netjoinBatchSuppressesJoinLinesButTracksMembers` |
| labeled-response / history lifecycles | `LabeledResponseReducerTests.labelledBatchIsRoutedToOriginAndClearedAtBatchEnd`; `ErgoRoundTripTest.labeledResponsesCarryLabelsFromRealServer`; `HistoryRequestTrackerTests.labeledCasefoldedHistoryPreservesNestedMultilineWireOrder`; `HistoryRequestTrackerTests.closedHistoryReferenceIsReusableWhileUnfinishedNestedBatchRemainsQuarantined`; `HistoryRequestTrackerTests.lateLabeledTimeoutErrorsAndAcksAreConsumedWhileAnotherRequestIsActive`; `HistoryRequestTrackerTests.topLevelLabeledAckFinishesEveryHistoryOperationWithoutClaimingEmptyArchive`; `HistoryCoordinatorIntegrationTests.closingUnlabelledHistoryDrainsOldReplyBeforeNextConversationWithoutReconnect`; `HistoryCoordinatorIntegrationTests.explicitReconnectRetryResumesFailedGapOnFreshSocketWithoutDiscardingCache` |
| standard-replies | `PerNetworkStateTests.standardRepliesAreTaggedByVerb` |
| sts | `StsTests.plainConnectionUpgradesUnderActivePolicy`; `StsTests.expiredPolicyDeletedAndIgnored`; `FileStsPolicyStoreTests.policySurvivesStoreRecreation` |
| SNI | `TlsServerNameIndicationTests.clientHelloNamesTheConfiguredHost`; `TlsServerNameIndicationTests.absoluteDnsEndpointUsesNormalizedSniInTheActualClientHello`; `TlsServerNameIndicationTests.invalidSniEndpointStillSendsAClientHelloWithoutAnInvalidServerName` |
| NAMES/353/366 | `PerNetworkStateTests.namesAccumulateUntilEndAndMarkJoined` |
| multi-prefix | `NamesParserTests.keepsHighestRoleWhenMultiPrefixStacksPrefixes` |
| userhost-in-names | `IdentityReducerTests.userhostInNamesCapturesUserAndHost` |
| no-implicit-names | `MessagingReducerTests.noImplicitNamesJoinCompletesWithoutWaitingForNames`; `MessagingCoordinatorTests.noImplicitNamesJoinsBeforeLazyWhoxLoadsMembersOnlyOnce` |
| invite-notify | `IdentityReducerTests.inviteNotifyForOthersLandsInChannelBuffer`; `IdentityReducerTests.invitingNumericConfirmsInChannel` |
| bot-mode | `IdentityReducerTests.botModeTokenAndWhoFlagsMarkBots`; `PerNetworkStateTests.whoisAwayServerOperIdleAndBotNumericsReachTheCompletedResult` |
| account-extban | `IdentityReducerTests.accountExtbanBuildsBanMaskFromKnownAccount` |
| draft/metadata-2 | `MetadataReducerTests.metadataBatchPublishesProfilesOnceAtBatchEnd`; `MetadataReducerTests.subscriptionNumericsTrackKeys`; `MetadataCoordinatorLoopbackTests.metadataFlowsFromWireToProfilesAndBack` |
| soju.im/FILEHOST | `FilehostUploaderTests.rawPostSendsHeadersAndResolvesRelativeLocation`; `FilehostUploaderTests.bodyFormatRejectionRetriesMultipartThenSucceeds`; `FilehostUploaderTests.plainHttpBlockedWhenIrcConnectionUsesTls` |
| UTF8ONLY | `LineFramerTests.malformedUtf8IsReplacedNotDecodedAsLatin1`; `LineFramerTests.truncationNeverSplitsACodepoint` |
| draft/extended-isupport | `IrcConnectionIntegrationTests.extendedIsupportRequestsIsupportBeforeCapEnd`; `ISupportTests.negatedTokensRemoveEarlierValues` |
| draft/ICON | `MetadataReducerTests.iconIsupportTokenSetsAndClearsNetworkIcon`; `ImageUrlPolicyTests.sizeTemplateIsExpanded` |
| draft/channel-rename | `MessagingCoordinatorTests.renameMovesBufferTranscriptMarkersMutesPinsAndAutojoin`; `MessageStoreRenameTests.renamedRowsStaySearchableUnderTheNewConversation`; `MessagingReducerTests.outstandingLabeledReplyFollowsRenamedConversation`; `HistoryCoordinatorIntegrationTests.channelRenameDrainsUnlabelledResponseAndReissuesForNewTargetWithoutNetworkPoisoning` |
| soju.im/bouncer-networks / binding | `IrcBouncerNetworksTests.actualMessageTagEscapesDecodeSpacesAndSemicolons`; `IrcBouncerNetworksTests.networkDeletionRequiresExplicitAsteriskAndExactParameterCount`; `IrcBouncerBindingTests.bindingFollowsSuccessfulSaslAndPrecedesCapEndNickAndUserExactlyOnce`; `IrcBouncerBindingTests.actualSojuInvalidNetidWithoutBindContextHardBlocksRecoveryNudges`; `SojuDiscoveryTrackerTests` |
| Stable upstream lifecycle (parity phase 4) | `SojuUpstreamConfigTests`; `NetworkEditLifecycleTests.manuallyConnectedSojuAccountStartsNewChildrenWithoutChangingSavedAutoConnectOrExistingIntent`; `NetworkEditLifecycleTests.disabledUpstreamsAndAccountDisconnectKeepIndependentDesiredIntentAndSiblingTransports`; `NetworkEditLifecycleTests.rejectedBindPersistsAnIsolatedOfflineShellAndNeverRetriesOnConnectivityOrResume`; `NetworkEditLifecycleTests.serverManagedBouncerPartAndReattachRetainConversationAndDurableIntentUntilExplicitUserPart` |
| Bouncer management (parity phase 4) | `BouncerManagementTests.nestedLabeledResponseBatchesCorrelateToTheOperation`; `BouncerManagementTests.changedFieldOnlyCommandsReportRejectedAndPartialApplyWithoutLeakingSecrets`; `BouncerManagementTests.renameAndDisableUsesTheAcknowledgedNewNameAndEscapesRealnames`; `BouncerManagementTests.missingControlpanelIsObservedFromRealStatusReplyNotFromSending`; `BouncerManagementTests.bindingChangesClearDetailsAndRejectHeldChannelAndNetworkDrafts`; `BouncerZncManagementTests` |
| Bouncer review boundaries (parity phase 4) | `IrcBouncerBindingTests`; `ManualAuthenticationRedactionTests`; `SojuDiscoveryTrackerTests.concurrentIndependentLiveBatchDeltasSurviveListingClosureWithoutRevivingDeletes`; `NetworkEditLifecycleTests.remotelyDisabledAndDeletedUpstreamsStopEvenWhenTheirObservedStateCannotBeSaved`; `NetworkEditLifecycleTests.manualConnectWithUnchangedDurableIntentDoesNotRequireWritableConfiguration`; `BouncerManagementTests`; `BouncerEditorDraftTests`; `NetworkEditorValidationTests`; `NetworkSaverTests`; `BouncerPortTests`; `BouncerZncManagementTests` |
| Bouncer history availability (parity phase 4) | `IrcChatHistoryTests.forwardedIsupportCannotEnableHistoryWithoutDownstreamCapability`; `NetworkEditLifecycleTests.zncWithoutPlaybackKeepsStoredReadingButRejectsForwardedUpstreamHistoryAvailability`; native-playback integration fixtures now forward upstream CHATHISTORY ISUPPORT while retaining downstream ZNC capabilities |
| znc.in/playback | `HistoryRequestTrackerTests.bareZncPlaybackRequiresTimestampBoundsAndCorrectDmIdentity`; `HistoryCoordinatorIntegrationTests.clippedNativePlaybackRetainsGapUntilClosedRangeWitnessesCachedLowerBoundary`; `HistoryCoordinatorIntegrationTests.timedOutWildcardPlaybackCannotInjectLateBatchesBareMessagesOrNotifications`; `HistoryCoordinatorIntegrationTests.invalidNativeMetadataCannotInventGapOrPersistFallbackClockMessage`; `HistoryCoordinatorIntegrationTests.repeatedBoundedPlaybackReconnectsMergeDurableGapsWithoutOpeningClosedConversations`; `HistoryCoordinatorIntegrationTests.inclusivePlaybackUpperBoundaryAdvancesFiniteEmptyWindowsWithoutInventingGapsOrArchiveEnd` |
| Offline snapshot inputs (parity phase 3) | `OfflineStoreTests`; `OfflineSnapshotMappingTests`; `OfflineCoordinatorTests`; `OfflinePresentationTests`; `OfflineConversationPresentationTests` |
| Migration, retention and recovery (parity phase 3) | `YardhalDatabaseMigrationTests`; `NetworkMessageRetentionTests`; `ReactionRetentionTests`; `DatabaseRecoveryTests`; `StorageRecoveryTests`; `RemoteImageMaintenanceTests` |

Emulator smoke (`make play`) exercises rendered network icons, avatars,
display names, verified-account/away/bot member rows, account-ban wire syntax,
multiline send/echo, selected channel rename, channel-context navigation and
the traffic console. Protocol-only tests above do not claim UI rendering proof.

Parity phase 3 adds native Android 15/Ergo offline kill/cold-launch, saved
selection, cached roster/WHOIS/topic/modes and live refresh scenes; real reaction
and wire-reply restoration; prepared local quote/attachment metadata; 350 prepared
unseen mentions beyond the 200-row page; pending MARKREAD acknowledgement and an
older injected marker. Native v1/v3 migrations verify IDs/hashes/FTS. Isolated
message/reaction/cache/image retention and invalid-header/sidecar,
incomplete/future-upgrade and denied-write recovery fixtures verify runtime
budgets, exact retained evidence and honest fresh/temporary-store UI.
See [offline conversation state](architecture.md#offline-conversation-state)
for schema, freshness, retention and recovery boundaries.

Parity phase 4 was exercised through native Android 15 onboarding against soju
0.10.1 and ZNC 1.10.2 backed by Ergo 2.14. Wire captures prove authenticated
soju BIND before CAP END, two automatic bound upstreams, stable relaunch IDs,
escaped rename/disable, channel policy and detach/reattach controls. Managed
self-PART retains pinned/muted history rather than recording a user leave.
Actual ZNC permission rejection retains the accepted Nick change and reports
PARTIAL; unloading controlpanel leaves status-only add/delete usable. Real
server replacement and channel buffer changes are acknowledged and refetched.
The actual optional playback module preserves a missing-range warning when its
75-message buffer clips an offline interval; forwarded upstream ISUPPORT alone
is not falsely advertised as downstream history support.
Cold launch while soju is stopped retains selected history and identical
IDs/order/groups/pins/mutes/markers; real restart rediscovery does not duplicate
bindings. Isolated upstream deletion and confirmed account cascade retain
unrelated ZNC and leave zero removed-network message/metadata keys. Native
LATEST/BEFORE and catch-up retain all 180 distinct seeded soju rows, while repeated
ZNC playback retains 75 distinct buffered rows. Room contains zero service DM rows.
With notification permission granted, a live mention produces one message
notification; real replay of live/offline mentions produces none.

Review fixes were exercised on native Android 15 with real soju 0.10.1 and ZNC
1.10.2. A transparent relay delayed actual soju deletion replies five seconds:
the sheet was dismissed before acknowledgement, and reopening showed successful
completion with the upstream removed. ZNC's typed `017779` port survived host
editing; native server replacement refetched the changed endpoint and reset
secret intent, then a second Apply performed zero changes. Mode switching saved
soju without a hidden direct-server password reference; invalid soju whitespace
and ZNC `@` accounts were rejected by the actual form. Standalone JVM smokes prove
live-batch discovery preservation and actual `IrcConnection.rawTap` wildcard
diagnostic retention without decoded/escaped upstream or composed/raw ZNC secrets.
ZNC 1.9.1 deletion compatibility is based on its native `*status` grammar and
consumer regressions, not a claimed native 1.9.1 run.
The latest APK also applies a real remote disable while configuration writes are
blocked: the child stops despite saved `enabled=true`, stays stopped across parent
Disconnect/Connect, then a parent name edit preserves and durably saves
`enabled=false` once storage is restored. The parent and unrelated ZNC remain
connected.

Parity phase 1 was also exercised on actual Android 15 transcripts against
Ergo 2.14.0 with persistent history capped at five messages, ZNC 1.10.3 with native
playback and client LIMIT 4, and Ergo with history disabled. The observed paths
include repeated channel/DM paging, offline/new-DM recovery, short-page residual
gap filling, delayed-response timeout/late-response rejection, cancellation/retry,
missing-time rejection, and preserved old gaps before background replay.
The local-only fixture traversed all 451 equal-millisecond rows online and offline;
an oldest-message search returned its original row. Held-response/query screenshots
showed unchanged cached reading-anchor bounds through server and local prepends.
Live notification controls were enabled; historical replay did not recreate them.
The real native module omitted replay msgids and supplied no archive-end guarantee;
those limitations remain explicit rather than being counted as protocol coverage.

Parity phase 2 was exercised on Android 15 against Ergo 2.14.0 and microsocks
1.0.5 with RFC 1929 authentication. Observed cases include cold disabled startup
and durable explicit disconnect, manual opt-out clearing, airplane/Wi-Fi return,
foreground matching-token probes without another transport, visible PASS/PLAIN
rejection and corrected credentials, configured alternate-nickname collision
recovery and editable realname, and explicit SCRAM on the wire.
NickServ rejection blocked autojoin; successful identification renamed the client
before confirmation and JOIN followed confirmation into an authenticated-only channel.
The native document picker, PKCS#12 installer and KeyChain grant selected a real
client identity; EXTERNAL authenticated through the SOCKS5 relay with no password
reference. Clearing the identity and returning to password SASL also worked.
Private-leaf inspection/trust, changed-leaf rejection, pin removal and endpoint
scoping were checked against independently calculated fingerprints. Proxy-password
rejection was actionable. Echoed credentials were redacted in the raw console and
in the persisted Room transcript after restart; saved config and the encrypted vault
contained no fixture plaintext passwords. These are connection/authentication
proofs, not new IRCv3 capabilities or iOS background-hold/MPTCP claims.
