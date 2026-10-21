---
id: "2026-10.wk3m"
slug: a-customer-key-s-requests-carry-a-keystore-device-proof
title: "a customer key's requests carry a Keystore device proof"
date: 2026-10-21
topics: [anywhere, tickets, keystore, privacy]
---

# ADR 2026-10.wk3m — a customer key's requests carry a Keystore device proof

Status: Accepted (2026-10-21)

**Context.** A paid Knit Anywhere key is for one phone, and nothing enforced it: every phone that opened the invite
got tickets. The issuer half is knit-spool-coordinator ADR 2026-10.473c (a key binds to the first device key it sees;
409 `bound-elsewhere` and a person-started move behind a cooldown). The spec is `docs/VOICE_CALLS.md` §2.6 "Device
binding" (C-7.6-39 to C-7.6-46) and `docs/SUPPORTER_BADGE.md` C-5-3/C-5-8. This is the client half, under the swap's
rules (ADR 2026-10.3sk7: issuer origin only, no redirects, bound to the validated network).

**Decision.**

- **Device keys.** `mesh/spool/DeviceKeys` makes an EC P-256 pair per customer key through the `DeviceKeystore` seam
  (`data/crypto/AndroidDeviceKeystore`: non-exportable, StrongBox where the phone has `FEATURE_STRONGBOX_KEYSTORE`,
  falling back to the TEE on any StrongBox failure, no auth requirement). A key's slot is 16 bytes of
  `SHA-256("knit/device-alias/v1" ‖ 0 ‖ host ‖ 0 ‖ key)`: the host's slot and the key in it, so a replaced key gets a
  new slot. Each pair's alias is `knit_device_<slot>_<id>` with a fresh id, so a pair is never made over another.
  `TicketKeyStore` makes the pair when a customer key arrives and sweeps (`retain`) after every write that replaces or
  forgets one; `TicketRefresher` sweeps once at start, which covers a restore. A free key never gets one.
- **The proof.** `DeviceProof.message` is C-7.6-41's `m`. `OkHttpTicketIssuer` signs every send afresh for its own path
  (swap, badge, move). A 400 `bad-proof` whose `Date` is more than 60 s off is re-signed once on the issuer's clock,
  and that offset is kept in memory per origin.
- **Answers.** `IssuerAnswer` gains `BoundElsewhere`, `MoveTooSoon`, `UpgradeRequired` and `BadProof`; `BadgeAnswer`
  the first, third and fourth. Each counts only with its own `error` word. `bound-elsewhere` parks the key
  (`KeyAnswer.BOUND_ELSEWHERE`, `KeyState.movableAt`) for the ticket and badge refreshers both; 426 and `bad-proof`
  back off as no verdict.
- **The move** is `TicketRefresher.move`, reached through `MeshController.moveAnywhereHere` from the relay's detail
  screen behind a confirmation. Never scheduled, never retried.
- **"Tell once"** is the relay screen's Tickets row ("in use on another phone", then Move it here or the time it can
  move). No notification: the row is state, not a repeated message.

**A Keystore failure never locks the account out** (the C-7.6-45 amendment). A lockout needs one of two events: the
bound private key destroyed, or an issuer that only ever sees proofs from pairs it did not bind. So:

1. **Nothing is made over a pair, nor deleted on the Keystore's word.** Every pair gets a new alias. A pair is deleted
   only when its key is no longer held (`retain`) or when the issuer accepted a proof from another pair of the key
   (`DeviceKeyring.accepted`, called on every 200 to a proof), which proves it unbound. A certificate read that comes
   back empty (`Look.Broken`) is a refusal, never a delete: it is the legacy keystore's half-made pair, but keystore2
   also answers a busy backend's certificate read with null. A `KeyPermanentlyInvalidatedException` sets a pair aside;
   it does not delete it.
2. **The pair that signs** is the one the issuer last accepted (`spool_device:<slot>`, transient), else the oldest. A
   restore over the same install keeps the Keystore and drops the marks, and the oldest is still the bound one.
3. **A refusal sends nothing.** A listing, lookup, generation or signature the Keystore refused is `DeviceKey.Refused`
   or a null signature: no request, and the ask backs off as no verdict. While the preferred pair is unreadable, no
   other pair signs in its place, since a stray's 409 parks the key and a move signed with it rebinds the account. An
   empty listing for a key with an accepted pair is likewise a refusal; both keystores answer an empty set on error. A pair whose lookup or signature has refused
   for over an hour (`SET_ASIDE_AFTER_MS`) is set aside for six (`SET_ASIDE_MS`, in memory, on a monotonic clock). A
   new pair is made beside it, at most three per key (`MAX_PAIRS`), and the old one is tried first again when the
   set-aside ends. A pair that never answers again, or a listing that stays empty, therefore cannot stall the phone.
4. **Unsigned only with no pair.** `DeviceKey.Unmade` (no headers) comes only from `Made.Unsupported`
   (`NoSuchAlgorithmException`, `NoSuchProviderException`, `InvalidAlgorithmParameterException`,
   `UnsupportedOperationException`) on a phone that holds no pair for the key.
5. **A park is retried across this phone's pairs.** `KeyState.parkedBy` records the pairs a 409 refused, and
   `forKey`'s `avoid` steers past them. Each other pair the phone holds, and a proof after an unsigned 409
   (`parkedUnsigned`), earns one more ask; when every pair has been refused, the park stands until a move.
6. **No race and no throw.** `create`, `forKey`, `accepted` and `retain` share one mutex, and `retain` reads the held
   keys inside it, after the store wrote them. A key entry that does not decode makes `retain` delete nothing. Every
   Keystore call runs on `Dispatchers.IO`. No public call throws: a DataStore failure costs a guess, never a crash.
7. **The clock offset follows the clocks.** A `bad-proof` is re-signed when its `Date` is more than 60 s from the
   time the proof was signed at (its `Knit-Device-Time`, not the raw clock), and the offset is dropped once a `Date` agrees with the phone's
   clock again.

**Alternatives.**

- Sending unsigned on any Keystore failure, as C-7.6-45 first read: a busy keystore2 would park a bound phone for the
  cooldown.
- One alias per key, remade after a day of loss evidence: an adversarial review found three ways a transient fault
  still overwrote an intact bound pair. Evidence that was not continuous, marks a restore drops, and a mark removed
  before the remake succeeded each let it happen.
- Keying the slot on the host alone: a restore over the same install keeps the old Keystore pairs while the backup may
  bring a different key.
- Keeping the clock offset on disk: a restart re-learns it from one `bad-proof`.

**Consequences / limits.**

- The Keystore and `spool_device:` never ride a backup (`SettingsKeys.TRANSIENT_PREFIXES`), so a restored phone makes
  new pairs and meets 409, which offers the move (C-7.6-46).
- A phone whose pair is truly gone, or refuses for good, makes a new pair after an hour of refusals; the issuer then
  answers 409 and the person moves the account. Stray pairs (at most three per key) are deleted once the issuer accepts
  one.
- Nothing logs the key, the public key, `m`, a signature or the alias; log reasons are words (`keystore`,
  `bad-proof`).
- Guards: `DeviceKeysTest` (each rule above, the sweep race included), `DeviceProofVectorTest` (the `m` and signature vector the issuer
  copies), `OkHttpTicketIssuerTest`, `TicketRefresherTest`, `BadgeRefresherTest`, `TicketKeyStoreTest`.
- Device trial, passed 2026-10-21 (a Pixel 9 and an emulator against a LAN knit-spool-coordinator issuer, which confirmed
  each binding on its side): in `optional` mode, the first proof binds and the second phone's ask gets 409 and parks;
  its early move gets `move-too-soon`, the relay page shows the move date; with a zero cooldown the move rebinds, and the
  first phone's next ask gets 409 with no move date, and its move rebinds back. In `required` mode, signed moves and swaps
  from both phones get 200; the issuer showed the unsigned 426 itself, since a phone that can make a pair never sends one.
