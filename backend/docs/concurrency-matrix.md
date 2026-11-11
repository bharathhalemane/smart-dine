# Concurrency and failure cases (design doc section 8b)

| # | Case | Covered by |
|---|---|---|
| 1 | Two customers order at once | OrderSessionRaceIT.ordersFromSeveralDinersAtOneTableAllSucceedDespiteContention, CustomerIsolationIT.tablesOrderingAtTheSameTimeDoNotInterfere, CustomerNamingIT.tenDinersArrivingTogetherGetTenDistinctNumbers, LoadSmokeIT |
| 2 | Two waiters open or close the same table | DiningSessionIT.sixteenSimultaneousScansCreateExactlyOneSession, SessionClosingIT.tenSimultaneousClearsSucceedExactlyOnce, SessionClosingIT.closingAndScanningAtTheSameMomentNeverLeavesTwoActiveSessions, SessionLifecycleIT.tenSimultaneousBillRequestsSucceedExactlyOnce, WaiterAssignmentIT.twoWaitersClaimingTheSameTableAtOnceExactlyOneWins |
| 3 | Duplicate order submit | OrderPlacementIT.tenSimultaneousSubmitsOfTheSameKeyCreateExactlyOneOrder, OrderSessionRaceIT.aFloodOfIdenticalSubmitsLeavesExactlyOneOrderAndOneCount |
| 4 | Order during session closing | OrderSessionRaceIT.ordersRacingAgainstAClosingTableAreEitherFullyAcceptedOrFullyRefused |
| 5 | Item becomes unavailable while in the cart | OrderPlacementIT.aFailedAttemptCreatesNothingAndDoesNotUseUpTheKey, ConcurrencyCasesIT.anItemSoldOutWhileOrdersAreBeingSubmittedNeverLeavesAHalfOrder |
| 6 | Webhook delivered several times | OnlinePaymentIT.theSameCaptureArrivingTwiceAtOnceIsCountedOnce, OnlinePaymentIT.twelveCopiesOfTheSameCaptureAtOnceAreCountedOnce, OnlinePaymentIT.anOnlineCaptureRacingACashPaymentNeverOverpaysAndNeverLosesMoney |
| 7 | Payment succeeds, browser never told | ReconciliationIT (webhook and ledger are the truth; drift is detected and repairable) |
| 8 | WebSocket disconnect | ChannelDirectoryIT (reload by REST), RealtimeEventsIT, RealtimeRecheckIT |
| 9 | Waiter reassigned during delivery | WaiterAssignmentIT, WaiterPickupIT (twoWaitersPickingUpTheSameOrderAtOnceExactlyOneWins), WaiterRequestIT |
| 10 | Owner accesses another hotel | TenantGuardIT, HotelTenancyIT, CustomerIsolationIT (13.4 adds a sweep over every route) |
| 11 | Stale customer session reuse | CustomerSessionIT.whenTheTableSessionClosesTheIdentityStopsWorkingAndARescanStartsFresh, CustomerIsolationIT.forgedAndMalformedCookiesNeverAuthenticate |
| 12 | Crash after persist, before response | OrderSessionRaceIT.aFailedInsertRollsBackTheGateSoTheCountStaysHonest, OrderPlacementIT.retryingWithTheSameKeyReturnsTheSameOrderAndCreatesNothingNew |
| + | Pay-before-food races (added after the design) | ConcurrencyCasesIT (payments with releases, payment with cancellation, payment with expiry) |
| + | Load against the design target (p95 < 300 ms, about 200 customers) | LoadSmokeIT (opt-in: -Dload.test=true) |