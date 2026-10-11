---
id: "2026-10.zeja"
slug: what-s-new
title: "What's New: a chat-list card, bundled announcements"
date: 2026-10-11
topics: [ui, onboarding]
---

# ADR 2026-10.zeja — What's New: a chat-list card, bundled announcements

Status: Accepted (2026-10-11)

**What was observed.** A release with features worth knowing about had no way into the app that people would
see. A launch dialog interrupts the one thing they opened Knit for, and a row under About is a row nobody
opens.

**What changed.** Announcements are a catalog bundled in the APK (`ui/whatsnew/WhatsNew.kt`, newest first,
strings in `strings.xml`): Knit is offline, so nothing is fetched, and the release build stays reproducible.
The chat list offers `WhatsNew.latest` as a `WhatsNewCard`, the **first item in the list** rather than the
pinned banner band: that band (`CloneBanner`, `MeshOffBanner`, `RadioWarningBanner`) holds states the user has
to act on, and this is news. The card stands aside while `showGettingStarted` is up, and a new install never
sees it, because `OnboardingViewModel` marks the newest entry seen (as `DemoWriter` does for the store
captures). Both its buttons mark the entry seen. Left alone, `WhatsNewPolicy.cardFor` retires it after
`SHOW_FOR_MS` (7 days) or `SHOW_FOR_OPENS` (10) chat-list visits, which `SettingsStore.noteWhatsNewOpen`
counts once per `ChatListViewModel`. The `whatsNew` route, opened from the card or from Settings' overflow,
lists every entry and marks the newest seen; under each entry, a link opens the release's full notes on
whatsnew.fyi (`releaseNotesUrl`, built from `WhatsNewEntry.release`, so it needs no lookup). An entry is keyed
by a content id, never the version: a patch release adds nothing, and a feature spread over two versions is
announced once.

**What it costs.** An entry's copy is written by hand for each release that earns one, about what people can
now do (not `CHANGELOG.md`'s list of changes), and must not name a surface a release build hides
(`BuildConfig.COMMONS`). An id is never reused or renamed, since `whats_new_seen` remembers it. The record is
carried by a backup on purpose: what someone has read is about them, not the phone. Regression:
`WhatsNewPolicyTest`, `SettingsStoreTest`'s visit case, `ChatListScreenContentTest`'s two card cases.
