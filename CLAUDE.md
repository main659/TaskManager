# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

`SPEC.md` is the authoritative design document — read it before starting any work. The repo is currently the unmodified Android Studio template (single `:app` module, placeholder `Greeting` in `MainActivity`). Work proceeds in the numbered slices of SPEC.md §8 (core logic → persistence → UI → widget → Google sign-in/import → push → pull → polish).

## Working agreements (from SPEC.md §11)

- Plan each slice and wait for approval before coding.
- Write `core` tests first.
- Do not add dependencies (libraries, Gradle plugins, version-catalog entries) without asking.
- Keep changes scoped to the current slice; no drive-by refactors.
- Before declaring a slice done, run `./gradlew test` and `./gradlew assembleDebug`.
- One commit per working slice, with a descriptive message.

## Commands

On Windows use `.\gradlew.bat` (or `./gradlew` from Git Bash).

- Build debug APK: `./gradlew assembleDebug`
- All unit tests: `./gradlew test`
- One module's unit tests: `./gradlew :app:testDebugUnitTest`
- Single test class / method: `./gradlew :app:testDebugUnitTest --tests "com.david.taskmanager.ExampleUnitTest"` (append `.methodName` for one method; for a pure-Kotlin `core` module it will be `./gradlew :core:test --tests "..."`)
- Instrumented tests (device/emulator required): `./gradlew connectedAndroidTest`
- Lint: `./gradlew lint`
- Debug signing SHA-1 (for the Google OAuth client): `./gradlew signingReport`

## Build setup

- AGP 9.x with built-in Kotlin support: `app/build.gradle.kts` applies only `android.application` and `kotlin.compose`; there is no separate `kotlin-android` plugin.
- All versions live in `gradle/libs.versions.toml`; reference dependencies via `libs.*` aliases.
- compileSdk/targetSdk 37, minSdk 26, Java 11 bytecode. Configuration cache is enabled in `gradle.properties`.
- R8 keep rules go in `app/src/main/keepRules/` (not `proguard-rules.pro`).
- Package, namespace and applicationId: `com.david.taskmanager`.

## Target architecture (SPEC.md §7)

- **`core` module (to be created) — pure Kotlin, no Android dependencies.** Domain model, `AvailabilityEvaluator` (state + lock reasons + unlock date), `DependencyGraph` (cycle detection, dependents lookup), and sync decision logic as pure functions (local state + remote changes → list of actions).
- **`app` module** — Room (source of truth), repositories, Compose UI with ViewModels exposing `StateFlow`, Glance widget, WorkManager workers (sync, midnight refresh), and a `GoogleTasksGateway` interface with a real implementation and `FakeGoogleTasksGateway` for tests.

## Invariants that are easy to break

- Never call `LocalDate.now()` / `Instant.now()` directly — always read time from an injected `Clock`. "Today" is the device-local date.
- Task state AVAILABLE/LOCKED is derived, never stored. A dependency is satisfied when the dependency is DONE and `date(completedAt, local tz) + delayDays <= today`; a CANCELLED dependency counts as satisfied (SPEC §10 default).
- Reject self-dependencies and cycles with a clear error. Deleting a task removes it from other tasks' dependency lists.
- Local DB is the source of truth for structure: dependencies, `availableFrom` and delays are never written to Google Tasks. Only AVAILABLE tasks are mirrored to Google.
- Every sync operation must be idempotent (local id ↔ Google id mapping table; running sync twice must never create duplicates). Outgoing changes go through a persisted, retried queue.
- Availability must be re-evaluated at local midnight (widget + Google push) without the app being opened.
