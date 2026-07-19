# Repository Guidelines

## Project Structure & Module Organization

Miao combines three applications:

- `app/`: Android client (`com.hyx.miao`) built with Kotlin, Jetpack Compose, Hilt, Room, and Retrofit. Production code is under `app/src/main/java/com/hyx/miao`; resources are in `app/src/main/res`. JVM tests live in `app/src/test`, and device tests in `app/src/androidTest`.
- `server/`: Fastify ES-module API. Routes are in `server/src/routes`, database helpers in `server/src/db`, and integration tests in `server/src/tests`.
- `admin/`: Vue 3/Vite administration UI. Pages are in `admin/src/views`, with routing in `admin/src/router`.

Architecture and product notes are maintained in `docs/`. Gradle dependency versions belong in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands

Run commands from the repository root:

- `./gradlew.bat assembleDebug`: build the Android debug APK.
- `./gradlew.bat test`: run Android JVM unit tests.
- `./gradlew.bat connectedAndroidTest`: run instrumented tests on a connected emulator/device.
- `./gradlew.bat lint`: run Android lint checks.
- `npm --prefix server run dev`: start the API with file watching on port 3000.
- `npm --prefix server run db:migrate`: apply database migrations.
- `npm --prefix admin run dev`: serve the admin UI on port 5173.
- `npm --prefix admin run build`: create the production admin bundle.

Use `npm ci` inside `server/` and `admin/` after dependency changes. Start PostgreSQL and Redis with `docker compose -f server/docker-compose.yml up -d`.

## Coding Style & Naming Conventions

Use Kotlin official style and four-space indentation. Name classes, composables, and Vue components in PascalCase; functions and properties in camelCase; Android resources in lowercase snake_case. Keep UI logic in `ui/`, persistence/network code in `data/`, and dependency providers in `di/`. JavaScript uses ES modules, two-space indentation, single quotes, and semicolon-free formatting consistent with existing files.

## Testing Guidelines

Name Kotlin tests `*Test.kt`. Add local logic tests under `src/test` and Compose/device behavior under `src/androidTest`. Backend tests use `node:test`; `npm --prefix server test` requires the API and its PostgreSQL/Redis dependencies to already be running. The admin app currently has no automated test script, so validate changes with a production build and targeted browser checks.

## Commit & Pull Request Guidelines

Git history is not available in this checkout. Use short, imperative commit subjects, optionally scoped, such as `server: validate refresh tokens`. Keep commits focused. Pull requests should explain the change and verification performed, link relevant issues, identify configuration or migration impacts, and include screenshots or recordings for Android/admin UI changes. Never commit `local.properties`, `.env` files, API keys, JWT secrets, or generated build output.
