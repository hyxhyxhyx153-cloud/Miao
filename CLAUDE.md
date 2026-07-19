# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Miao is an Android application written in Kotlin using Jetpack Compose. It is currently a fresh Android Studio scaffold — a single `MainActivity` rendering a Compose "Hello Android" greeting. Package/namespace: `com.hyx.miao`.

## Commands

This is a Gradle project. On Windows use `./gradlew.bat` (the `gradlew` wrapper also works from a bash shell).

- Build (debug): `./gradlew.bat assembleDebug`
- Build (release): `./gradlew.bat assembleRelease`
- Install debug build on a connected device/emulator: `./gradlew.bat installDebug`
- Run unit tests (JVM): `./gradlew.bat test`
- Run a single unit test class: `./gradlew.bat test --tests "com.hyx.miao.ExampleUnitTest"`
- Run a single unit test method: `./gradlew.bat test --tests "com.hyx.miao.ExampleUnitTest.addition_isCorrect"`
- Run instrumented tests (requires a connected device/emulator): `./gradlew.bat connectedAndroidTest`
- Lint: `./gradlew.bat lint`
- Clean: `./gradlew.bat clean`

There is no separate "run" task; launch the app from Android Studio or via `installDebug` + launching the activity.

## Architecture

Single-module Gradle build: the root project delegates all app code to `:app` (see `settings.gradle.kts`). The top-level `build.gradle.kts` only declares plugins with `apply false`; real configuration lives in `app/build.gradle.kts`.

Source layout under `app/src/`:
- `main/java/com/hyx/miao/` — application code. `MainActivity.kt` is the single entry point (a `ComponentActivity` using `setContent { }`).
- `main/java/com/hyx/miao/ui/theme/` — Compose theming (`Theme.kt`, `Color.kt`, `Type.kt`). The `MiaoTheme` composable wraps the app UI and applies Material 3 with dynamic color support.
- `test/` — local JVM unit tests (JUnit 4).
- `androidTest/` — instrumented/UI tests (Espresso + Compose UI test, `AndroidJUnitRunner`).

## Conventions

- Dependencies are managed through the Gradle version catalog at `gradle/libs.versions.toml`. Add or bump libraries there and reference them via `libs.*` aliases in `app/build.gradle.kts` rather than hardcoding coordinates.
- UI is Jetpack Compose with Material 3; the Compose BOM pins all Compose artifact versions. Prefer composables and Material 3 components over Views/XML layouts.
- Kotlin official code style (`kotlin.code.style=official`), Java 11 source/target compatibility.
- Gradle configuration cache is enabled (`org.gradle.configuration-cache=true`).

## Environment notes

- `minSdk` 24, `targetSdk`/`compileSdk` 36.
- `local.properties` (SDK path) is machine-specific and not committed.
