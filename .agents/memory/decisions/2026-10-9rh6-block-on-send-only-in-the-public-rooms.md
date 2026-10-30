---
id: "2026-10.9rh6"
slug: block-on-send-only-in-the-public-rooms
title: "Block on send only in the public rooms"
date: 2026-10-07
topics: [moderation, privacy]
---

# ADR 2026-10.9rh6 — Block on send only in the public rooms

Status: Accepted (2026-10-07)

**What was observed.** The on-device classifiers miss things in both directions. ADR 2026-10.d62w found the
n-word passing the toxicity model in a DM because Detoxify scores a slur aimed at a person as an *insult*, a
label we ignore; the fix was a word list, and the next gap will need another. Meanwhile a false positive in a
private chat refused a message the reader might well have wanted, and a "send anyway?" dialog put a
classifier's guess between two people who chose to talk. A tampered client skips every sender-side check, so
the check that actually protects anyone was always the receiver's (`docs/CONTENT_MODERATION.md`, header).

**What changed.** Sender-side screening now runs only where strangers read what is sent: the **Nearby room**
(`MeshManager.sendChat` with no recipient and no group, and `AttachmentStore.ingest(screen = true)` from
`ChatViewModel` only when `isRoom`) and the **Meshtastic room** (`sendPublicPost`). A DM, a group message and a
commons post (`sendCommons`; a commons lives on a private spool its operator invites people to) are never
classified on the way out, and the explicit-image confirm dialog is gone. A link-preview card is screened on
the sender only for the room (`LinkPreviewService.fetchCard`; the memo now holds the unscreened card for both).
Receive-side screening is unchanged: every inbound message and blob is classified in every scope and hidden
behind the receiver's Content filtering toggle. Two things were added so that holds up without the send gate:

- **Avatars and group photos are screened when set.** `AvatarStore.saveOwnAvatar` screens the encoded 256²
  JPEG before storing it and answers null for a flagged one; the profile keeps its old photo
  (`PhotoChange.REFUSED`) and a group sends no update (`GroupDetailsViewModel.photoRefused`). A face is shown
  to everyone who sees the profile, strangers in the room included, so it is the one private-side surface with
  a public audience. Not gated by the toggle; fail-open when the model is latched (ADR 037).
- **A flagged message's words stay out of the shade and the lists.** With filtering on, the notification
  (`InboundPipeline.notificationBody`, mentions included) and the chat-list and message-requests preview
  (`messagePreview(…, hideFlagged)`) read "Message hidden". Neither surface has a tap to reveal, and with DMs
  no longer gated on send this is where a flagged DM's text would otherwise land.

The alternative a reader reaches for first is a per-conversation or per-user "screen what I send" switch. It
buys nothing a receiver does not already decide for themselves, and it adds a second toggle whose meaning
users would confuse with the first.

**What it costs.** A slur or an explicit picture in a DM now leaves the sender's phone; a receiver with
filtering off sees it. That is the receiver's choice, which is the point. This amends ADR 2026-10.d62w (its
"blocked on send" cost now holds in the rooms only; the slur list still flags in every scope on receipt) and
ADR 2026-09.n752 (the sender screens a card only for the room). The trap: `docs/SPOOL_PROTOCOL.md` §7.5 lets a
spool require send-side moderation for the scopes it carries; its client half is not built, and whoever
builds it must bring the send screen back for those scopes (DM, group and commons included) without the
"send anyway" bypass, which `ScopedTextModerator` and `AttachmentStore.ingest(screen = true)` already make a
one-line call. Tests: `MeshManagerTest.aDmAndAGroupMessageAreNeverClassifiedOnTheWayOut`,
`…aCommonsPostIsNeverClassifiedOnTheWayOut…`, `ChatViewModelTest.attachingAnImageInADmNeverAsksForAScreen…`,
`AttachmentStoreIngestTest.aPickForAPrivateChatIsNeverScreened`, `LinkPreviewServiceTest.aPrivateChatsCard…`,
`ProfileViewModelTest.aRefusedPhotoChangesNothing…`, `GroupDetailsViewModelTest.aRefusedGroupPhoto…`,
`InboundPipelineTest.aFlaggedMessageNotifiesAsHidden…`, `ChatListViewModelTest.aFlaggedMessagePreviewsAsHidden…`.
