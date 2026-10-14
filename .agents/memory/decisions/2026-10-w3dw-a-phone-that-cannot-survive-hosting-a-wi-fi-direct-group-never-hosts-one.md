---
id: "2026-10.w3dw"
slug: a-phone-that-cannot-survive-hosting-a-wi-fi-direct-group-never-hosts-one
title: "A phone that cannot survive hosting a Wi-Fi Direct group never hosts one"
date: 2026-10-14
topics: [wifi-direct, transfer, calls, reliability]
---

# ADR 2026-10.w3dw — A phone that cannot survive hosting a Wi-Fi Direct group never hosts one

Status: Accepted (2026-10-14)

**What was observed.** The lab's Moto G Power runs TrebleDroid's LineageOS GSI (API 34) over its stock Android 11
vendor image. On 2026-10-08 it called the Pixel 9 and, as a call's caller does (ADR 2026-10.kxnw, C-3.10-3), hosted the
Wi-Fi Direct group. When the Pixel joined, the Moto's `system_server` died: `NullPointerException` in
`WifiP2pGroup.setClientInterfaceMacAddress` (`WifiP2pGroup.java:290`) from `GroupCreatedState`, where the old
supplicant HAL reports no client interface address. The phone soft-restarted, and Knit died with it. Joining worked:
the Pixel's call to the Moto went direct. A direct transfer's sender hosts too (ADR 2026-09.wtmz), so the same phone
sending a large file takes itself down the same way. It looked like a Knit crash, and nothing of ours was in the trace.

**What changed.** One verdict, `transfer/DirectHostGuard`, keeps a phone from hosting where hosting takes it down. It
is asked at the one seam every group goes up through: `DirectWifiLease`. A refused host never reaches `createGroup`,
and `DirectWifi.hostRefusal()` answers `TransferRefusal.CannotHost` beside `refusal()`. A joiner asks only `refusal()`,
so receiving a file and joining a call's group are never refused for it. Callers ask before they offer:
`DirectCallPath.invite` puts no `dw` in the OFFER (the callee never joins; C-3.10-3 already says the caller then raises
nothing), the walkie preference (`CallWiring.liveLikely`) counts no direct leg, and `TransferManager.mayOffer` makes
the chat refuse a send before the picker. The verdict has two sources:

- **A generic system image over an older vendor** (`device/SystemImage`, read once into `DeviceImage`). Both build
  fingerprints must say so. The system image's product or device names a GSI (`gsi_arm64`, `phhgsi_…`, `tdgsi_…`),
  and the vendor partition's Android release (`Build.getFingerprintedPartitions()`, public since API 29) is older than
  the system's. "The names differ" alone would catch every Qualcomm OEM phone whose system partition is a `qssi`
  single system image. "The vendor is older" alone would catch an OEM phone on a frozen vendor (GRF). Its framework
  was certified against that vendor's HALs, as a GSI's never is. Known before the first try, so the Moto never
  crashes again.
- **A latch for every other ROM**, shaped like ADR 037's. A durable marker (`data/settings/DirectHostStore`, out of
  backups) goes down before a group of ours goes up, and the release that takes it down clears it. A later start that
  finds it knows the process died hosting. A reboot is told apart by `SystemClock.elapsedRealtime()`, which a dead
  battery or a restart sets back and a `system_server` crash leaves running. A death the platform explains
  (`ProcessExitReasons`: our own Java or native crash, an ANR, the low-memory killer, the user, an update) counts for
  nothing. Two unexplained deaths running, with no clean host between them, latch hosting off under the app version +
  OS build (`modelGuardStamp`), so an update tries again. Diagnostics says which source holds and offers the latch's
  reset. The chat's refusal says the phone can receive large files but not send them, and points there.

Alternatives: role swap (the callee or receiver hosts) keeps the direct path for a GSI caller. It needs a capability
bit and a host/join × send/receive reshuffle of `TransferManager`, and is parked. Advertising "cannot host" on the wire
gains nothing: only the phone that would host needs to know. A broad "vendor older than system" test would veto GRF
phones that host fine.

**What it costs.** A GSI over an older vendor sends no large file and places no call over Wi-Fi Direct: its calls
ride the Internet, or a walkie offline. One latch at two strikes means a ROM with the same fault soft-restarts twice
before it stops. That is the price of transfers shipping in every build, where one false strike would silently cost a
phone its sending. The rule's GSI names are a list of three families. A GSI that names itself otherwise and lies in
both fingerprints falls through to the latch. Whether the platform files any exit record for a process that died with
`system_server` is not observed: none is the assumption, and either way the record's absence counts. Tests:
`SystemImageTest` (the Moto's two fingerprints, a `qssi` OEM, a GSI on its own release), `DirectHostGuardTest`,
`DirectWifiLeaseTest`, `TransferManagerTest.aPhoneThatMayNotHostRefusesToSendAndStillReceives`,
`DirectCallPathTest.aCallerThatMayNotHostOffersNoLegButAsACalleeStillJoins`. Owed: the Moto calling the Pixel and
sending it a file, on this build, with no restart.
