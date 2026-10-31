---
id: "2026-10.swst"
slug: art-s-whitespace-is-the-law-for-both-ports
title: "ART's whitespace is the law for both ports"
date: 2026-10-10
topics: [text, interop, testing]
---

# ADR 2026-10.swst — ART's whitespace is the law for both ports

Status: Accepted (2026-10-10)

**What was observed.** A display name, a status and a group name are flattened by `normalizeSingleLine`
(`trim()`, then `Regex("\\s+")` to one space), a rename is refused when `isBlank()`, and the "Name (Alias)"
clash key `NameKey` collapses the same `\s` runs. The iOS port modelled Java's `\s` as ASCII and Kotlin's
`isWhitespace` without U+180E — the desktop JDK's answers, because those are the ones a JVM unit test shows.
A phone answers differently: ART's `java.util.regex` is ICU, so `\s` is Unicode White_Space, and ART's
`Character.isWhitespace` hard-codes U+1680 and U+180E. The two ports then disagreed on what a name flattens to,
on whether U+180E alone is blank, and on the clash label for U+0085, U+1680, U+2028 and U+2029 (interop-audit
AND-13 = IOS-2).

**What changed.** No product code: ART's behaviour is the law, and iOS mirrors it. The instrumented
`ArtTextSemanticsTest` enumerates the BMP on a device and pins both sets (bar U+180E, below), the `normalizeSingleLine` and
`NameKey` answers that follow from them, and the lone-surrogate encode (`String.toByteArray(UTF_8)` gives `?`,
0x3F). Measured on managed emulators at API 33 and 34 and on API 36 and 37 emulators, identical on all four:

- regex `\s`: U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000..U+200A, U+2028, U+2029, U+202F,
  U+205F, U+3000;
- Kotlin `Char.isWhitespace()`: U+0009..U+000D, U+001C..U+001F, U+0020, U+00A0, U+1680,
  U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000 — the regex set minus U+0085, plus U+001C..U+001F.

**U+180E is not whitespace** in the shared law. ART's answer for it moved: API 33, 34 and 36 (ART module
360527520) still hard-code it, API 37 (module 370399999) does not. `Character` ships in the updatable ART
mainline module, so the answer follows a phone's module, not its API level, and iOS cannot track that; the law
takes ART's current answer, which is also Unicode 6.3's and the desktop JDK's. The residue is accepted: a phone
on an older module still trims a leading or trailing U+180E and calls a rename to U+180E alone blank. `NameKey`
drops it either way, as a format character. The test logs U+180E's answer with the module version and pins
only the rest.

The alternative a reader reaches for first — pin the sets in a JVM unit test — records the wrong law: the
desktop JDK's `\s` is the six ASCII characters, and its `isWhitespace` lacks U+180E. `DesktopJdkWhitespaceTest`
pins that difference so nobody "fixes" the ART lists from a JVM run.

**What it costs.** The pin runs only on a device or emulator
(`:app:pixel7api33DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.getknit.knit.text.ArtTextSemanticsTest`),
never in `testDebugUnitTest`, so a JVM-only change to these predicates can pass CI and still move what ART
answers. Any new text predicate that both ports must agree on (a `\s`, `isWhitespace`, `trim` or `isBlank` on
something the other port also reads) belongs in that test, and its answer goes to iOS from the device run.
Only the BMP is enumerated: no supplementary code point is whitespace in Unicode today.
