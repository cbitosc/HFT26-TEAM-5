---
id: "2026-10.59fu"
slug: a-reaction-to-your-message-notifies-and-can-head-the-chat-list
title: "A reaction to your message notifies and can head the chat list"
date: 2026-10-19
topics: [ui, notifications, receipts]
---

# ADR 2026-10.59fu — A reaction to your message notifies and can head the chat list

Status: Accepted (2026-10-19; `ReactionRepository.apply`'s answer, `InboundPipeline.noticeReaction`,
`Notifier.retractReaction`, `NotifMessage.key`, `ReactionDao.observeNewestOnOwnMessages`, `ReactionHead`,
`ChatListAssembler.reactionLineFor`, `MessageEntity.writtenBy`)

**What was observed.** A reaction was silent. It wrote a row in `reactions` and drew a chip in the thread, and
nothing else: the ctl contract (`docs/ENCRYPTED_RECEIPTS_REACTIONS.md` §3, ADR 018) said a sealed reaction is
"never persisted as a message, never notified, never acked", and the cleartext path did the same without saying
so. Someone thumbs-upping your message went unseen until you happened to open that thread. The maintainer asked
for what every other messenger does: a notification, and a last line in the chat list.

**What changed.**

- **Who notifies.** Another person's reaction to a message this phone wrote (`MessageEntity.writtenBy`: our sender
  id, an ordinary message, no Meshtastic origin), in a thread `isAccepted` admits, from a reactor not blocked who
  reads the thread (`InboundPipeline.readsThread`: in a DM the peer, in a group a member of its roster, in a room
  anyone). The last rule is there because every carrier sees a DM's or a group frame's id: without it a stranger's
  cleartext reaction to one of our messages would notify, and alternating emoji would alert each time; the row is
  still written, as it always was, so nothing changes about what a peer accepts. A reaction to someone else's
  message, our own reaction, and an orphan (its target not stored — never ours, since our sends are always stored
  first) notify nothing. It rides the thread's own MessagingStyle notification as a
  line from the reactor ("Reacted 👍 to “lunch?”"), so it takes the kind's channel, the on-screen suppression, the
  summary, Reply and Mark-read, and the conversation shortcut as they are.
- **When.** After the row commits, and only when the reactor's visible emoji changed: `ReactionRepository.apply`
  returns that (an add, a replace, a retraction it wrote). A stale frame, a newer stamp with the same emoji, and a
  retraction of nothing return false, so a custody re-serve or a cleartext replay never notifies twice. The sealed
  forms carry the row out of the ctl transaction (`CtlOutcome.reaction` for the DM form, the group form's commit
  result) and notify in the post-commit half, exactly as `CTL_PROFILE`'s avatar pull does: a frame that rolls back
  notified nothing.
- **Replace and withdraw.** The line is keyed (`reactionNotificationKey`, one per message and reactor): a changed
  emoji replaces it in place and still counts once in the summary, and a retraction withdraws it
  (`Notifier.retractReaction`), re-rendering silently or cancelling the notification when it was the last line.
  The notifier's state is in memory, so a retraction after a process restart leaves the line where it was.
- **The chat list.** Per thread, the newest live reaction someone who reads the thread (the same rule, in SQL: a
  DM's peer, a roster member by the quoted `LIKE` `MessageDao.sealedHolding` uses, anyone in a room) made to our
  words is a row's line, time and place when it is strictly newer than the thread's head (`reactedAt > head.sentAt`, ties to the message) —
  "Reacted 👍 to “lunch?”" in a DM, "Bob reacted 🔥 to “trail at 9?”" in a group or room. It earns no tick (we sent
  nothing) and no unread count (the badge counts messages, `countUnreadIn`'s `kind = 0`): the call and transfer
  precedent. A draft speaks while it is newer than the newest of the two. A retraction drops the reaction from the
  query, and the row falls back on its own. The share sheet's target list reads the same ViewModel.

The alternative a reader reaches for first is a message row: write a `KIND_*` notice for each reaction and let the
existing preview, sort and notify paths do the rest. It fails three ways. A reaction is replaceable and
retractable under last-writer-wins, so the row would need its own LWW and delete rules beside the reaction's; a
notice in the thread would draw a line for every tap; and a received reaction would become a stored message that
`isStatusNotice` must then keep out of unread counts, ticks, search and backups. Reading the `reactions` table
where it already is costs a query, not a second record of the same fact.

The clock is the reaction's stored `updatedAt`: the reactor's own, bounded on the way in to
`Protocol.MAX_FUTURE_SKEW_MS` past ours (ADR 2026-09.gdhp). It sorts against the thread's messages on the footing
they sort on each other, the sender's clock, so a reaction that arrives a day late through custody stays where it
happened rather than jumping to the top. No received-at column was added, and nothing moved on the wire.

**What it costs.** One more Room flow on the chat list, over the `reactions` table joined by primary key into
`messages`. It re-runs on every write to either table, as the summary queries already do; its cost tracks how
many reactions this phone holds, not how many messages. The query takes each thread's newest instant and joins
back, and a same-millisecond tie is broken by message id, then reactor id (`ReactionRepository.observeNewestOnOwn`),
so the line cannot flip between emissions; the bare `MAX … GROUP BY` idiom would pick an arbitrary row. The ctl
contract's other clauses stand: a reaction is never persisted as a message and never acked.

What it does not cover: unread badges (the badge still counts only messages), our own reactions as a list line,
and a reaction between two other members of a group. Each was offered and declined (2026-10-19).

Regression: `ReactionRepositoryTest` (`applyReportsOnlyAChangeOfTheVisibleReaction`, the tie), `ReactionDaoTest`
(the query's filters), `MessageNotifierTest` (replace, two reactors, withdraw), `NotificationTest`,
`ChatListReactionPreviewTest`, and `InboundPipelineTest`'s reaction-notification cases for the three inbound forms.
