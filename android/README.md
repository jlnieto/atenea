# Atenea Android

Android native client for Atenea's mobile surface.

This project is the beginning of the migration documented in:

- `../docs/android-native-migration.md`
- `../docs/android-native-shell-v1.md`

## Current scope

The native client currently implements the first usable operator shell:

- Kotlin Android project
- Jetpack Compose shell with compact top bar and drawer navigation
- encrypted token storage
- login against `POST /api/mobile/auth/login`
- automatic access-token refresh through `POST /api/mobile/auth/refresh`
- text command against `POST /api/core/commands`
- recent Core command history from `GET /api/core/commands`
- native `Inicio`, `Core`, `Operaciones`, `Archivos` and `Ajustes` destinations
- native `Proyectos`, `WorkSession`, `Conversación` and `Rescate` surfaces
- session-first WorkSession dashboard with Core actions, deliverables, close guidance and event sync
- governed WorkSession PNG/JPEG/WebP selection, idempotent upload, exact turn binding and authenticated historical viewing when the server capability is ready
- operations status from `/api/mobile/operations/*`
- Apache recovery through Core confirmation
- authenticated file upload through `POST /api/mobile/uploads`
- update check from Atenea's protected APK manifest
- APK download and handoff to Android's system installer
- FCM push token registration and notification display when Firebase build config is present
- screen kept awake while the app is open

It does not yet include:

- native voice engine
- foreground service
- wake word

## Modules

- `:app`: Android entrypoint and composition root
- `:api`: small Atenea HTTP client and DTOs
- `:secure`: encrypted session storage
- `:core-console`: current Compose operator shell, Home/Core/Ops/Settings screens and shared UI components

Future modules should follow `../docs/android-native-migration.md`.

## Build

This VPS currently has no host Java/Gradle/Android SDK installed. The canonical repo command builds through Docker:

```bash
../scripts/android-build.sh
```

That script builds `docker/android-builder.Dockerfile`, mounts the repository and runs `gradle :app:assembleDebug`.

Expected command from this directory once Gradle and Android SDK exist:

```bash
gradle :app:assembleDebug
```

The API base URL defaults to:

```text
https://atenea.yudri.es
```

Override it at build time with:

```bash
../scripts/android-build.sh :app:assembleDebug -PATENEA_API_BASE_URL=https://atenea.yudri.es
```

For local backend testing from a device, use a reachable LAN URL:

```bash
../scripts/android-build.sh :app:assembleDebug -PATENEA_API_BASE_URL=http://192.168.1.20:8085
```

Enable native push by filling the Firebase Android config file for the target build environment. The backend must also have FCM service-account credentials through `ATENEA_MOBILE_PUSH_FCM_*`.

```bash
/srv/atenea/platform/secrets/android-firebase-dev.env
/srv/atenea/platform/secrets/android-firebase-preview.env
/srv/atenea/platform/secrets/android-firebase-prod.env
```

Builds load `prod` by default. Use `ATENEA_ANDROID_ENV` to select another Firebase config file:

```bash
ATENEA_ANDROID_ENV=dev ../scripts/android-build.sh :app:assembleDebug
ATENEA_ANDROID_ENV=preview ../scripts/android-build.sh :app:assembleDebug
ATENEA_ANDROID_ENV=prod ../scripts/android-build.sh :app:assembleRelease
```

## First manual test

1. Install the debug app on an Android device or emulator.
2. Log in with an Atenea mobile operator account.
3. Check `Inicio` and verify it shows the operational summary.
4. Open `Operaciones` and run `Actualizar`.
5. Open `Proyectos`, enter a project session and verify the WorkSession dashboard loads status, insights, deliverables and timeline.
6. Open `Conversacion` from the WorkSession and send a scoped instruction through Core.
7. In an eligible Atenea WorkSession, confirm the composer shows `Imágenes`, select up to four PNG/JPEG/WebP files and verify each is visibly ready before Send. Do not use the general `Archivos` inbox for a Codex turn attachment.
8. Send a simple Core command, for example:

```text
comprueba apache en el dedicado
```

Expected result:

- Core returns a command id and status.
- The screen shows `operatorMessage` when present.
- Recent Core history refreshes after the command.
- WorkSession actions use `POST /api/core/commands` with `SESSION` scope; direct mobile mutation endpoints are treated as compatibility aliases only.
- Operaciones renders script reports as readable summary, steps and metrics, not raw JSON.
- Archivos stores uploaded files under `/srv/atenea/workspace/repos/internal/atenea/operator-uploads`.

## Build output

Successful debug builds produce:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Publish the current debug APK to the protected Atenea download URL with:

```bash
../scripts/android-publish-apk.sh
```

Protected download URL:

```text
https://atenea.yudri.es/apk/atenea-debug.apk
```

The Caddy route is protected with HTTP Basic Auth. The generated local credentials are stored outside git at:

```text
/srv/atenea/platform/secrets/android-apk-basic-auth.txt
```

There is also a secret URL route without HTTP prompt for first mobile installation and in-app update checks:

```text
https://atenea.yudri.es/apk/{token}/android/atenea-debug.apk
https://atenea.yudri.es/apk/{token}/android/manifest.json
```

The token is stored outside git at:

```text
/srv/atenea/platform/secrets/android-apk-download.env
```

Current VPS verification:

```bash
../scripts/android-build.sh
```

Result:

```text
BUILD SUCCESSFUL
```

## Native shell rule

Do not place new screens directly in `CoreConsoleApp.kt`.

Current shell rules:

1. `CoreConsoleApp.kt` only decides login vs authenticated shell.
2. `AteneaShell.kt` owns navigation.
3. Shared visual primitives live in `AteneaDesign.kt`.
4. Core command rendering lives in `CoreCommandUi.kt`.
5. Updates live in `Ajustes`, not in the daily workflow.
6. File uploads use the stable backend inbox documented in `../docs/android-native-shell-v1.md`.

## Migration rule

Port in this order:

1. Core text
2. Core confirmations and clarifications
3. native voice engine push-to-talk
4. operations
5. WorkSession dashboard and conversation
6. rescue
7. notifications
8. foreground command mode
9. wake word evaluation
