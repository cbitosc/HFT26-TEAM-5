---
id: "2026-10.gpa3"
slug: a-quote-is-checked-against-the-original-it-names
title: "A quote is checked against the original it names"
date: 2026-10-13
topics: [chat, moderation, security]
---

# ADR 2026-10.gpa3 — A quote is checked against the original it names

Status: Accepted (2026-10-13)

**What was observed.** A quoted reply carries a snapshot of what it quotes, `ReplyRef(messageId, authorId, author,
snippet, hasAttachment)`. That way the quote still renders when the original was never received, was swept, or scrolled out
of history. The receiver stored that snapshot verbatim (`InboundPipeline.deliverChat` → `withReply`), unclamped,
and the bubble drew the sender's `author` string and `snippet`. So any peer could put words in anyone's mouth,
including the reader's own: a quote naming the reader's node id rendered as "You". The snapshot was also a second text
channel nothing screened. `inboundModeration` classified `content.body` only, so a slur in a snippet, or a self-quote of
a slur in a group (never screened on the way out, ADR 2026-10.9rh6), reached the bubble unscreened. A reply to a message
flagged on this phone showed normally, its quote included. The composer's blanking of a flagged original's snippet
(`buildReplySnippet`) is the *sender's* verdict, gated on the sender's toggle, and a tampered client skips it.

Two facts bound the fix. A frame id is 128 random bits, bound to no author, and two senders can mint the same one, so
an id that resolves proves nothing until author and thread match too. The original's stored `(id, senderId,
conversationId, body)` *is* authenticated, by the frame signature or the AEAD's AAD. And originals routinely land
after their replies: custody serves newest-first, and a parked or replayed frame lands late. Room originals are swept
(30 days, 2,000 rows, 200 per stranger) while their replies stay.

**What changed.** The receiver judges every inbound reply that isn't ours against the original it names, and stores the
verdict. No wire change: the fix is receiver-only and needs no paired iOS change.

- **The verdict** (`data/message/QuoteCheck.kt`, `quoteVerdict`) is VERIFIED when this phone holds the message under the
  quoted id, in the reply's own thread, by the author the quote names. That message must also be an ordinary one (not a
  status line, not a heard Meshtastic post, whose sender column is this phone by convention) and not the reply itself.
  Anything else that resolves is a MISMATCH. `messages.replyCheck` (DB v23, local only) holds `REPLY_UNCHECKED`,
  `REPLY_VERIFIED` or `REPLY_MISMATCH`.
- **A verified quote is rebuilt from the original** (`settleQuote`), by the composer's own rule (`quoteSnippet`), with
  labels in the reader's language (`QuoteLabels`, built from the same strings the composer uses). The rebuilt snippet is
  written over the snapshot, so it outlives the original's retention sweep. A mismatch keeps nothing of the claim.
- **When it runs** (`data/QuoteVerifier`): on a first delivery, before the insert when the original is already held
  (`judge`). Otherwise `settleAfterInsert` runs after any inbound insert: it re-checks the new row and settles the
  replies waiting on it. Each side reads only after its own insert has committed, so whichever of reply and original
  lands second sees the other. The write is a conditional `UPDATE … WHERE replyCheck = 0` that touches only the quote
  columns, so no transaction is needed and nothing written in between is undone. `settleBacklog` runs once per
  `MeshManager.start()`, after the own-profile seed, for rows from before v23 and for a settle a crash cut short. Our
  own replies are never judged or rewritten: we quoted a row we held, and `resealAndFlood` re-ships the stored quote.
- **Moderation** (`messages.replyToFlagged`, local only). A verified quote carries the original's *own* verdict. It is
  non-transitive: a reply to a reply hidden only for its quote is not hidden. An unverified quote's snippet is screened
  on arrival by the same moderator, in the same scope, as a body. When the original lands, the flag is recomputed from
  it, because the text it described was replaced. `MessageEntity.hiddenByFilter` is the one hide predicate. Every
  surface reads it: the bubble, the details screen, the list previews, the notification. `MessageDao.searchBodies`'
  SQL mirrors it. A reply hidden for its quote collapses behind the same tap-to-reveal. The quote shows a stand-in,
  and carries the tap hint itself when the reply has no text.
- **Rendering** (`ChatViewModel.quoteViewFor`). The author is the reader's own label for the quoted id
  (`directory.label`: a contact's name, else the alias), "You" for our own id, and never `ReplyRef.author`. This closes
  what ADR 058 deferred. An unverified quote says so under its words. An unavailable one (a mismatch, or an unmatched
  quote of *our* id, since this phone holds everything it sent) shows "Quoted message unavailable" and does not jump.
  `MessageDao.depthOf` is scoped to the thread, so a forged cross-thread id cannot size the window. On admission the
  pipeline drops a quote whose ids are blank or longer than 64 chars, and clamps author and snippet.

The alternative a reader reaches for first is a render-time lookup keyed by the window's quoted ids (ADR 2026-09.fjcw's
shape). It is not simpler. The snippet-screen verdict has to be stored anyway, since classification can't run at render
time. Notifications, list previews and search would each need a self-join. And a verified room quote would revert to
unverified once its original was swept. The second alternative is a sender-side flag in `ReplyRef`. It is a wire change
that would need a paired iOS change, and it would only ever hide more, on a sender's word. The reader's own moderator
already decides what the reader sees.

**What it costs and does not cover.** An id squatter still wins first-write: whoever stores a frame under a given id
first owns it locally. A reply quoting the real original then reads "unavailable" there, which is a safe failure. The
real fix binds a frame id to its author, and it is a wire break (`docs/NEXT_WIRE_BREAK.md`). An unverified quote
now names the quoted author with our trusted label beside the sender's words; the "Unverified — original not on this
phone" cue is what keeps that from reading as genuine. Replies stored before the upgrade whose original is absent are
not screened retroactively; they were already shown. An iOS reply must quote a same-thread frame id with the original's
sender as `authorId`, or it reads "unavailable" here. The iOS receiver likely shares the original bug.

Regression: `QuoteCheckTest` (the verdict matrix, labels, non-transitivity, the pinned `REPLY_*` values),
`QuoteVerifierTest` (both arrival orders, once-only, no clobbered tick or voice metadata, own replies untouched, the
backlog), `InboundPipelineTest`'s reply cases (forged snippet, a reply ahead of its original, wrong author, a hidden
notification, a malformed quote), `ChatViewModelTest.aQuoteSaysHowFarItWasChecked…`, the quote cases in
`ChatScreenContentTest`, `MessageSearchDaoTest`, `MessageRepositoryTest`'s cross-thread `depthOf`,
`KnitDatabaseMigrationTest` 22→23, and `mesh/lab/QuoteLabTest` (the four shapes end to end).
