---
id: "2026-10.fcrd"
slug: a-switched-off-call-kind-is-answered-no-answer
title: "A switched-off call kind is answered no answer, and blocking already silences a call"
date: 2026-10-18
topics: [calls, settings]
---

# ADR 2026-10.fcrd — A switched-off call kind is answered no answer, and blocking already silences a call

Status: Accepted (2026-10-18)

**What was asked.** A person in a build with calls wants to take no incoming calls at all, with live and mesh
calls as two separate choices, and a caller to such a person should see "No answer". Blocked people should not be able
to ring them either.

**What changed.** The Calls screen opens with one switch per call kind the build wires — "Take live calls" and "Take
mesh calls" — both on by default, kept in `data/settings/IncomingCallStore` (`calls_take_live`, `calls_take_walkie`,
carried by a backup like every other choice). `CallManager.onOffer` reads them through `takesIncoming` after its media
check: an offer of a kind switched off is answered `DECLINE rsn=timeout` and buried, with no RINGING, no media, no TURN
prefetch, no missed call and no record here (docs/VOICE_CALLS.md C-3.3-7). The caller's existing handling turns that
into "No answer" on the call screen and `CallOutcome.NoAnswer` in its thread, so no wire field, capability bit or
reason code is added and older builds read it the same way. The refusal is immediate rather than a ring that
times out: the callee holds no state for a call it will never take, and one call at a time stays the manager's rule.
The answer is "No answer" even mid-call, so the switch says nothing about a call in progress (a busy DECLINE would);
a glare offer (C-3.5-1) is still taken, because this phone was calling that peer. Since every call build now has
something to set, `CALL_SETTINGS_WIRED` is `CALLS_WIRED`, and the Settings row says which kinds are refused.

**Blocking.** Nothing was needed: `InboundPipeline.handleChat` drops every DM from a blocked sender before decrypt,
`CTL_CALL` included, and `handleTalk` drops their walkie chunks, so a blocked contact's offer never reaches the call
layer and their phone hears the same silence as from a phone that is off (it ends `no_route` at its ring-out, C-3.3-6).
A gate in `onCallSignal` would be dead code; `InboundPipelineTest.aCallFromABlockedContactNeverReachesTheHook` pins the
existing drop instead.

**What it costs.** A caller cannot tell "switched off" from "not picked up" and gets no hint before dialling: an
advertised capability that hid the call button would be a wire change and would tell everyone nearby which calls a
person refuses. The switches are read through a state on the app scope that reads as on until the store's first read,
so a call in that instant rings rather than being refused for a read still in flight. `CallManagerTest`'s
`aLiveCallToACalleeTakingNoneIsAnsweredNoAnswerWithoutRinging`, `eachIncomingSwitchRefusesItsOwnKindOnly`,
`aRefusedKindIsNoAnswerEvenWhileBusy` and `glareIsTakenWithTheKindSwitchedOff` keep it true.
