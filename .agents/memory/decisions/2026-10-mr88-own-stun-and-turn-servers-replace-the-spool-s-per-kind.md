---
id: "2026-10.mr88"
slug: own-stun-and-turn-servers-replace-the-spool-s-per-kind
title: "Own STUN and TURN servers replace the spool's per kind"
date: 2026-10-14
topics: [calls, spool, settings]
---

# ADR 2026-10.mr88 — Own STUN and TURN servers replace the spool's per kind

Status: Accepted (2026-10-14)

**What was observed.** Every ICE server a call used came from a connected spool: STUN from the HELLOs (C-7.6-20) and
TURN as a `turnreq` per call (C-7.6-12). A phone with no spool, or one whose spool issues no TURN, offered host
candidates only, and with "Relay all calls" on it could not connect at all. A person who runs their own coturn had no
way to use it, and every spool asked learned that a call was starting.

**What changed.** The Calls screen (Settings → Calls) takes the person's own STUN URLs (up to four) and one TURN server
with a static long-term username and password, each kind behind its own switch, and "Relay all calls" moved onto it.
`call/OverridingTurnSource` wraps the spool `TurnSource`: an own TURN server is returned as the call's only credential
and the spools' `turnServers` is never called, so no `turnreq` leaves the phone; own STUN URLs replace the HELLOs'
list. Each kind **replaces** the spools' for that kind and never adds to them: added, a spool would still learn of the
call, and C-7.6-12's two-server bound would have to be shared out. `CallManager.servers` is unchanged — an own server
arrives as a fresh credential, and its static username fails the `<exp>:` parse, so the cached-credential reuse never
picks it. The fields live in `data/settings/OwnIceStore` (not grown into `SettingsStore`), are re-validated on every
read by `OwnIceServers.effective`, and a kind that fails its rule reads as off. coturn's REST shared secret was left
out: a static credential covers hosted TURN and `lt-cred-mech`, and the phone minting HMACs would put a server-wide
secret on it.

The screen's Test (`call/IceProbe`, in `WebRtcCallMedia`) gathers against the fields as typed, one throwaway
connection per kind so a STUN answer from a UDP TURN server is never credited to the wrong kind, on the call factory,
with a receive-only audio m-line so no microphone opens. It is refused while a call is open and holds the factory
against a profile rebuild while it runs.

**What it costs.** The password sits in the clear in the settings DataStore beside a relay's `?k=` token (ADR
2026-09.66cw) and rides a Knit backup. An own TURN server sees both phones' addresses on a relayed call, as a spool's
does (§2.5); the screen says so. libwebrtc checks `turns:` against its own roots, which hold no Let's Encrypt root, so
a `turns:` server on such a certificate gathers no relay (the `turntls` diagnosis switch still applies). Kept true by
`CustomIceTest` (the URL rule, the redacted `toString`), `OverridingTurnSourceTest` (no `turnreq` with an own TURN),
`OwnIceStoreTest`, `ProbeVerdictTest` and docs/VOICE_CALLS.md §3.14.
