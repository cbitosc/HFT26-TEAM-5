---
id: "2026-10.5hzt"
slug: a-transfer-s-ready-is-sent-until-the-receiver-connects
title: "A transfer's READY is sent until the receiver connects, and a send that ended short is retried from its card"
date: 2026-10-05
topics: [transfer, ui]
---

# ADR 2026-10.5hzt — A transfer's READY is sent until the receiver connects, and a send that ended short is retried from its card

Status: Accepted (2026-10-05; `TransferManager.hostAndSend` / `resendReady`, `TransferTimings.readyResendMs`,
`TransferManager.retry` / `sourceReadable`, `TransferRecord.sourceUri` / `retried` / `retryable`,
`TransferView.retryable`, `TransferCard`'s Retry, `ChatViewModel.retryTransfer` / `retrySources`). Amends ADR
2026-09.wtmz decision 4 and ADR 2026-09.7uqe's card.

**What was observed.** On 2026-10-05 a 700 MB send from the Pixel 9 to the Pixel 8 failed with "no connection
was made in time" (reason 5). The Wi-Fi Direct group was fine: the Pixel 9 hosted it on 5.2 GHz at 17:45:01 and
waited 42 s for a client. The Pixel 8 never came, because it could not open the READY that names the group. That
READY was the first frame of the Pixel 9's ratchet epoch 13, and the Pixel 8 still held keys for epoch 13 from an
earlier session, written when that session's frames were re-served under the kept `prevRoot` (#135). It dropped
the frame as `AEAD_FAIL`, and two more after it, then asked for a session reset at 17:45:15. The reset healed the
ratchet with 28 s of the Pixel 8's 45 s READY wait still to run. Nothing sent READY again, because the reset's
recovery re-seals only stored DMs and group seeds, and a `CTL_TRANSFER` is never stored. The fix for the
collision itself is #135's. This record is about the transfer's single point of failure, which any lost or
unreadable frame would have hit.

**What changed.**

1. **The host sends READY again every `readyResendMs` (5 s) while it waits for the connection, for as long as the
   receiver could still be waiting for it (`readyWaitMs`).** The copies are identical. The receiver already
   ignores every READY after the first, so a repeat costs one sealed ctl frame and nothing else. Each copy is
   sealed fresh, under whatever session the pair holds by then, and goes over Bluetooth while hosting has Wi-Fi
   Aware paused. Against the 2026-10-05 failure, the re-sends also count toward the receiver's reset heuristic, so
   the reset comes sooner and the next copy opens under it. *Rejected:* the receiver repeating ACCEPT. The host
   answers an ACCEPT for an offer no longer in `Offered` with CANCEL, so a repeat would end the transfer it means
   to save. *Rejected:* re-sending READY on the session-reset hook. It covers this one cause, where a timer covers
   every lost frame, and the transfer would then depend on a ratchet event it has no other reason to know about.
2. **A sender's card offers Retry once the transfer ended short.** That covers a failure for any reason, an expired or
   cancelled offer, a busy peer and a record a process death left mid-phase. A finished file has no Retry, and
   neither has an offer the other person turned down themselves. `TransferRecord.retryable` is the one rule. Retry
   offers the same file again as a new transfer with its own id and row, and marks the old row `retried` so its card
   stops offering it. The old row keeps its place in the thread. The thread's refusals run first, as for the
   overflow item, and the disclosure does not, because this phone has sent the file before.
3. **The record keeps the sender's `sourceUri`, and Retry is drawn only for a file the chat found it can read.**
   `AndroidTransferFiles.openSource` already takes the picker's grant as persistable, so the URI survives a reboot.
   The file can still be moved or deleted, or the grant revoked, so `ChatViewModel` checks each row that could offer
   Retry once, off the main thread (`TransferManager.sourceReadable`). A row nobody has checked draws no Retry, so
   the button never appears and then vanishes. A tap that finds the file gone after all says "Knit couldn't read
   that file" and takes the button away. *Rejected:* opening the picker when the file is gone, which is the first
   thing a reader would reach for. A dead button that becomes a file picker surprises more than a missing one does,
   and the overflow item is one tap away. The URI is this phone's own handle and means nothing on another one. The
   receiver's row never carries one, and the record still never holds the credentials or the stream key.

**What it costs.** Up to eight extra sealed ctl frames per transfer, and only when the receiver is slow to
connect. A healthy run connects inside the first 5 s and sends none. The copies are custodied like the first one.
The grants `openSource` persists are never released. That predates this change, but Retry now relies on them, so
releasing one has to wait until its row is `Done` or `retried`. Android caps persisted grants per app (128 before
API 30, 512 after), which a heavy sender could reach. A record written before this change has no `sourceUri` and
never offers Retry. Tests: `TransferManagerTest`'s `aFirstReadyThatNeverLandsIsSentAgainAndTheFileStillCrosses`
(fails with the re-send disabled), `readyIsNoLongerSentOnceTheReceiverConnects`,
`aFailedSendIsRetriedAsANewOfferOfTheSameFile`, `aRetryWhoseFileIsGoneIsRefusedAndKeepsItsCard`;
`TransferViewTest.retryIsOfferedOnTheSendersCardForATransferThatEndedShort`; `TransferCardTest`;
`ChatViewModelTest.retryIsOfferedOnlyForAFileThatCanStillBeReadAndAGoneFileTakesItAway`. Still owed: a device
run of a Retry after a real failure, and of a READY re-send across a session reset.
