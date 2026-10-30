# Offline Emergency Messaging System

CBIT Hacktoberfest 2026 submission by **The Tarnished** (`HFT26-TEAM-5`).

This repository extends the official [Knit](https://github.com/getknit/knit) Android mesh messenger into an emergency-focused, offline-first communication system. The Android foundation is pinned to Knit `v2.10.0`; core communication is designed to work without cellular service, Internet access, cloud servers, or Google Play Services.

## Team information

| Attribute | Details |
| --- | --- |
| Event | CBIT Hacktoberfest 2026 |
| Team | The Tarnished |
| Team number | `TEAM-5` (`HFT26-TEAM-5`) |
| Registration ID | `HFEST26-3EGXMFBJ` |
| Domain | General Track |
| Team leader | Syed Sultan Mohiuddin Quadri (syedsultan.dev@gmail.com) |
| Institution | Nawab Shah Alam Khan College of Engineering and Technology |

### Team members

| # | Name | Email | Institution |
| --- | --- | --- | --- |
| 1 | Shaik Nizam Khizar | shaiknizamkhizer5606@gmail.com | Nawab Shah Alam Khan College of Engineering and Technology |
| 2 | Mohammed Osman | mohdosman.cs@gmail.com | Muffakham Jah College of Engineering and Technology |

## Project mission

The project aims to provide reliable emergency communication when normal Internet and cellular networks are unavailable or unreliable. Planned emergency extensions include structured SOS messages, priority handling, evidence-based delivery states, explicit acknowledgements, optional location sharing, and an emergency-focused interface.

The imported Knit foundation already provides:

- Bluetooth Low Energy and Wi-Fi Aware peer discovery and communication.
- Multi-hop mesh relaying with deduplication and hop limits.
- Local persistence and delay-tolerant store-and-forward delivery.
- Public, direct, and group conversations.
- Signed message authentication and end-to-end encryption for supported private conversations.
- Delivery receipts, attachments, optional location sharing, background service handling, and offline moderation.

Public Nearby messages are signed but are not confidential. Direct and group messages use Knit's encryption facilities. Emergency functionality must preserve this distinction honestly.

## Repository structure

```text
app/                 Main Android application
detekt-rules/        Knit-specific static-analysis rules
docs/                Architecture, protocol, security, and subsystem documentation
gradle/              Gradle wrapper and version catalog
vectors/             Cross-platform protocol test vectors
wear/                Optional Wear OS application
watchface*/          Optional Wear OS watch faces
AGENTS.md            CBIT and upstream engineering rules
COPYING              GNU GPL v3-or-later licence text
THIRD-PARTY-NOTICES.md
```

The application package and Knit architecture remain unchanged at this integration milestone.

## Build prerequisites

Knit `v2.10.0` uses a deliberately recent Android toolchain. Read `.agents/context/toolchain.md` before changing it.

- JDK 21
- Android SDK platform and build-tools versions declared in `app/build.gradle.kts`
- The repository's Gradle wrapper (`gradlew` / `gradlew.bat`)

From PowerShell in `C:\HFT26-TEAM-5`, a future baseline build can be run with:

```powershell
.\gradlew.bat :app:assembleDebug
```

Unit tests can be run with:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

No build or test result is claimed by this integration commit. Physical Android devices are required to validate real BLE, Wi-Fi Aware, and multi-hop behavior.

## Contribution and submission workflow

The official CBIT fork-and-pull-request workflow remains in effect:

1. Work in the team fork, not directly in the CBIT upstream repository.
2. Develop on focused feature branches such as `feature/prototype`.
3. Make small, descriptive commits and push them to the team fork.
4. Merge reviewed work into the fork's `main` branch when it is ready for submission.
5. Open the final submission pull request from the team fork's `main` branch to `cbitosc/HFT26-TEAM-5:main`.
6. Include the solution overview, architecture and stack, setup instructions, demo video or deployment link, screenshots, and an honest implemented/tested/planned feature matrix.

Suggested final pull-request title:

```text
[SUBMISSION] TEAM-5 - The Tarnished - Offline Emergency Messaging System
```

Never force-push, commit credentials, or push directly to the CBIT upstream repository.

## Attribution and licensing

The Android foundation is derived from **Knit**, copyright © 2026 Jeffrey Walter Mixon and contributors. The imported baseline is the signed, annotated `v2.10.0` tag from `https://github.com/getknit/knit`.

Knit and this derivative are distributed under the **GNU General Public License, version 3 or later**. See [COPYING](COPYING). Third-party components and bundled data retain their original licences and notices; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md), `third_party/`, and the relevant asset README files.

Do not remove Knit attribution, copyright notices, licence files, source headers, or third-party notices.
