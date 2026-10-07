---
id: "2026-10.d62w"
slug: identity-slurs-are-blocked-by-a-word-list-in-every-conversation
title: "Identity slurs are blocked by a word list in every conversation, not only the Nearby room"
date: 2026-10-07
topics: [moderation, privacy]
---

# ADR 2026-10.d62w — Identity slurs are blocked by a word list in every conversation, not only the Nearby room

Status: Accepted (2026-10-07)

**What was observed.** The n-word sent in a DM was neither blocked on send nor collapsed on receipt. DMs and
groups ran the toxicity classifier alone (profanity is room-only by design), and the classifier does not
catch a slur aimed at a person. Scored through the shipped `toxicity.tflite`, the fp32 export and the
upstream `detoxify` 0.5.2 package with the upstream checkpoint (all three agree within 0.01, so it is the
model, not our conversion):

| text | toxicity | insult | identity_attack | severe_toxicity |
|---|---|---|---|---|
| the n-word alone | 0.95 | 0.89 | 0.06 | 0.00 |
| "you are a" + the n-word | 0.99 | 0.98 | 0.09 | 0.00 |
| "I hate all black people" | 0.94 | 0.14 | 0.90 | 0.00 |

Two other common slurs scored 0.00 on every label. `MlTextModerator` enforces `severe_toxicity`,
`identity_attack`, `sexual_explicit` and `threat`, and ignores `toxicity` / `insult` so private rudeness
goes through. Jigsaw's raters labelled a slur aimed at someone as an insult; `identity_attack` learned from
statements about a group. And `severe_toxicity` is inert: Detoxify trains on rater fractions cut at 0.5, and
only 13 of Civil Comments' 1,804,874 rows reach it, so it never rose above 0.08 on anything we tried — its
0.85 threshold can never fire. That was mistaken, until measured, for a classifier that covered hate speech.

**What changed.** A second generated list, `assets/moderation/slurs_en.txt`, runs first in every scope:
`ScopedTextModerator.room` is slurs → profanity → ML, `.direct` is slurs → ML, and a slur hit reports
`TextVerdict.Category.SLUR`. `scripts/gen-profanity-list.py` derives it from the same dsojevic corpus: the
`racial` and `religious` tags at severity ≥ 3 (severity 2 holds words with innocent or discussion uses —
`negro`, `haji`, `swastika`), plus a hand-picked `faggot` / `shemale` / `tranny` (the `lgbtq` tag stays out
as a tag, since it mixes slurs with self-descriptions), plus each term's plural, because the matcher is
whole-token and does not stem. Dropped from the private-chat list only: `nigga` / `niggs` (in-group use —
blocking it in a DM blocks Black users talking to friends) and `coon` (raccoon). The room's profanity list
still carries both. Profanity stays room-only.

The alternatives a reader reaches for first: enforce `insult` or `toxicity` in DMs — that is the rudeness
filter private chats deliberately do not have; lower `identity_attack` — it read 0.06, below any threshold
that would not fire on ordinary text about identity; retrain the model — soft labels would revive
`severe_toxicity` but not teach slurs (Civil Comments came from a platform that peer-reviewed comments
before publishing them, so they are likely rare in it — not counted), and teaching them needs a hate-speech corpus and an evaluation set for reclaimed
use, quotation and counter-speech first. A word list is deterministic, auditable and already
evasion-tolerant (leet, stretched and spaced-out letters) for exactly this class.

**What it costs.** A DM that quotes or discusses a slur is blocked on send and collapsed on receipt (the
receive side stays behind the Content filtering toggle and tap-to-reveal, never dropped). Known residual
false positives: `faggot` is also a British meatball dish and `tranny` US slang for a gearbox. English
only, and nothing reclaimed or in-group is caught in private chats by design. A device whose toxicity model
is latched off (ADR 037) now still screens DM slurs, and nothing else. The trap: editing the list by hand
— it is generated; change the dials (`SLUR_TAGS`, `SLUR_SEVERITY_FLOOR`, `SLUR_DROP`,
`SLUR_ALWAYS_INCLUDE`) and regenerate. `SlurListTest` pins the inclusions, the exclusions, and the DM chain
flagging a slur with no model; `AliasTest` checks generated aliases against both lists.
