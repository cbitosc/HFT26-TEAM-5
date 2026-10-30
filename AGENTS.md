
# AGENTS.md — CBIT Hacktoberfest 2026
# Project: Offline Emergency Messaging System
# Team: The Tarnished — HFT26-TEAM-5

## 1. Project Mission

Build a reliable Android application for emergency communication when normal
internet connectivity and cellular networks are unavailable or unreliable.

The project will extend the existing Knit Android mesh-messaging foundation.

The application should support:

- Emergency SOS messages.
- Nearby peer-to-peer communication.
- Multi-hop message relaying.
- Store-and-forward delivery.
- Emergency message prioritisation.
- Message delivery and acknowledgement statuses.
- Optional location sharing.
- Secure message authentication and appropriate encryption.
- Battery-conscious background operation.
- Recovery and synchronisation when devices reconnect.

The core emergency messaging system MUST work without internet access.

Optional online AI features must never become a dependency for core messaging.

## 2. Primary Engineering Principle

DO NOT reinvent functionality that already exists in Knit.

Before implementing any feature:

1. Inspect the existing implementation.
2. Understand its architecture and dependencies.
3. Identify reusable classes, services, models and interfaces.
4. Reuse existing functionality wherever practical.
5. Extend existing components without unnecessarily replacing them.

Do not create duplicate networking, discovery, encryption, persistence,
message-relay or location systems unless the existing implementation
cannot satisfy a documented requirement.

## 3. Repository Protection

This repository is the official CBIT hackathon submission repository.

- Preserve the existing repository identity and Git history.
- Do not replace the repository with another repository.
- Do not overwrite the CBIT starter README without first understanding it.
- Do not create unnecessary nested Git repositories.
- Do not remove existing starter files without justification.
- Preserve the original Knit attribution and required licence notices.
- Preserve third-party copyright and licence information.
- Do not push directly to the upstream repository.
- Work on the designated feature branch.
- Never force-push.
- Never commit secrets, API keys, passwords or signing credentials.

Before major structural changes, explain the proposed changes and their risks.

## 4. Architecture and Code Quality

Build a maintainable application, not a disposable demonstration.

- Follow the architecture and conventions already established by the project.
- Prefer small, focused, reusable components.
- Separate UI, domain logic, data access and networking responsibilities.
- Reuse existing dependency injection and persistence mechanisms.
- Avoid unnecessary architectural migrations.
- Avoid duplicate implementations of the same functionality.
- Avoid unnecessary dependencies.
- Use descriptive names and meaningful error handling.
- Keep existing working behaviour intact.
- Do not introduce placeholder implementations that appear functional.
- Do not silently swallow exceptions.

Do not change the technology stack without a clear technical reason.

## 5. Emergency Messaging Requirements

SOS messages should support, where compatible with the existing architecture:

- A unique message identifier.
- Sender identity.
- Message content.
- Creation timestamp.
- Emergency priority.
- Optional location information.
- Message authenticity verification.
- Relay and delivery metadata.
- Expiry or time-to-live handling.
- Acknowledgement information.

Emergency messages should receive priority over ordinary messages
where the existing transport architecture permits it.

Avoid creating unlimited message floods.

Use deduplication, hop limits, expiry and appropriate retry behaviour.

Never claim that a message was delivered or relayed without actual evidence.

Distinguish clearly between:
- Pending
- Sent
- Relayed
- Delivered
- Acknowledged
- Failed or expired

## 6. Security Requirements

Security is essential because emergency messages may contain sensitive data.

- Reuse existing cryptographic implementations where appropriate.
- Never implement custom cryptographic algorithms.
- Verify message signatures and sender authenticity.
- Understand which existing message types are encrypted and which are plaintext.
- Do not describe plaintext public messages as encrypted.
- Do not expose private keys or sensitive information in logs.
- Validate incoming messages.
- Protect against duplicate messages, malformed payloads and replay attacks.
- Do not weaken existing security controls merely to make a demonstration work.

Document any security limitations honestly.

## 7. Android Requirements

- Respect the project's minimum and target SDK configuration.
- Reuse existing Android services and lifecycle handling.
- Respect Android background execution restrictions.
- Handle permissions properly.
- Handle Bluetooth and Wi-Fi availability changes.
- Check actual device support for Wi-Fi Aware before using it.
- Provide graceful fallbacks when a transport is unavailable.
- Avoid excessive battery consumption.
- Do not assume every Android phone supports every mesh transport.
- Test on physical Android devices wherever possible.

Do not promise that phones without this application can participate in
the custom peer-to-peer mesh.

## 8. Offline-First Requirements

Core messaging must not depend on:
- Cloud servers.
- Internet connectivity.
- Remote databases.
- External AI APIs.
- Continuous access to a central coordinator.

Messages should be persisted locally and forwarded when suitable peers
become available.

Optional online services must fail gracefully when offline.

## 9. Development Workflow

Work in small, independently verifiable milestones.

For every milestone:

1. Inspect the relevant existing code.
2. Explain the intended implementation briefly.
3. Make focused changes.
4. Build the application.
5. Run relevant tests.
6. Fix regressions introduced by the changes.
7. Summarise changed files and important decisions.
8. Explain how I can manually verify the feature.

Do not proceed to a major new milestone when the current milestone
has unresolved build-breaking errors.

Do not claim tests passed unless they were actually executed.

Do not fabricate physical-device test results.

## 10. Resource Efficiency

I am using Codex through a ChatGPT Plus subscription.

- Avoid repeated full-repository analysis.
- Do not reread large files unless necessary.
- Prefer targeted searches and inspections.
- Avoid unnecessary refactoring.
- Avoid repeatedly generating alternative implementations.
- Do not create large amounts of documentation that do not help development.
- Preserve useful findings in project documentation when appropriate.
- Do not run expensive or redundant operations without a reason.

Prioritise working software over excessive explanations.

## 11. Working With Me

I am a beginner in native Android development but have experience with
Python, machine learning and FastAPI.

Explain unfamiliar Android concepts in practical language.

When giving terminal commands:
- Specify whether they are PowerShell commands.
- Specify the directory from which to run them.
- Give complete commands.
- Avoid assuming I know Android-specific tooling.

Do not overwhelm me with several unrelated tasks at once.

Give me one next action at a time when user input is required.

Do not make major architectural decisions silently.

## 12. Definition of Done

The project is not complete merely because it compiles.

The final application should have:

- A working Android application.
- Functional offline peer-to-peer messaging.
- Functional emergency SOS workflow.
- Appropriate multi-hop relay behaviour.
- Message persistence and recovery.
- Honest delivery and acknowledgement states.
- Appropriate security protections.
- Graceful handling of unavailable transports.
- A usable emergency-focused interface.
- Physical-device testing across multiple phones.
- Clear setup, build and testing documentation.
- No known critical build or runtime errors.

The final submission must accurately distinguish implemented,
tested, partially implemented and planned functionality.
