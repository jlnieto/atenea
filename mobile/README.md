# Atenea Mobile

Native operator shell for Atenea mobile full operation.

## Current status

The repository now contains a React Native client bootstrapped with Expo and split into:

- a `Core` tab as the primary operator entrypoint
- live read surfaces still using the mobile/session-first contracts where they remain useful

Current client outcomes:

- core-first operator console with free-text command input
- voice capture in the `Core` tab with backend transcription
- project and session active context kept in app state and sent to `Atenea Core`
- recent core command history
- core command-event SSE consumption with polling fallback
- clarification and confirmation follow-up routed through the `Core` tab
- `speakableMessage` playback through `expo-speech`
- inbox
- projects overview
- session summary
- dedicated session conversation workspace
- terminal-style conversation UX tuned for mobile operator work
- billing queue
- periodic auto-refresh for inbox, projects, billing and session state
- session event feed
- direct SSE consumption in the conversation workspace with polling fallback
- project context activation from project cards through `Atenea Core`
- session open from project cards through `Atenea Core`
- session actions through `Atenea Core` for:
  - publish
  - pull-request sync
  - close
  - generate deliverable
- conversation prompts sent through `Atenea Core`
- direct mobile actions still retained for:
  - approve deliverable
  - mark billed

Current implementation focus:

- operate the `development` domain through `Atenea Core` from the native app
- keep read models dense while mutating actions consolidate behind `Core`
- harden clarification, confirmation and interruption UX
- add real speech-to-text on top of the existing core contract

## Backend contract used

The current client uses these core endpoints:

- `POST /api/core/commands`
- `POST /api/core/commands/{commandId}/confirm`
- `POST /api/core/voice/commands`
- `GET /api/core/commands`
- `GET /api/core/commands/{commandId}/events`
- `GET /api/core/commands/{commandId}/events/stream`

It also still reads from:

- `POST /api/mobile/auth/login`
- `POST /api/mobile/auth/refresh`
- `POST /api/mobile/auth/logout`
- `GET /api/mobile/auth/me`
- `POST /api/mobile/notifications/push-token`
- `POST /api/mobile/notifications/push-token/unregister`
- `GET /api/mobile/notifications/push-devices`
- `GET /api/mobile/inbox`
- `GET /api/mobile/projects/overview`
- `GET /api/mobile/sessions/{sessionId}/summary`
- `GET /api/mobile/billing/queue`

The client is now hybrid by design:

- `Atenea Core` is the operator entrypoint for project/session actions
- `/api/mobile/*` remains the compact read layer for inbox, summaries, conversation rendering and billing

## Configuration

The API base URL is controlled through:

- `EXPO_PUBLIC_ATENEA_API_BASE_URL`

Default:

- `http://localhost:8081`

Example:

```bash
cd mobile
EXPO_PUBLIC_ATENEA_API_BASE_URL=http://192.168.1.20:8081 npm start
```

Backend bootstrap operator for local/dev use:

- `ATENEA_AUTH_BOOTSTRAP_ENABLED=true`
- `ATENEA_AUTH_BOOTSTRAP_EMAIL=operator@atenea.local`
- `ATENEA_AUTH_BOOTSTRAP_PASSWORD=secret-pass`
- `ATENEA_AUTH_BOOTSTRAP_DISPLAY_NAME=Mobile Operator`

Example:

```bash
ATENEA_AUTH_BOOTSTRAP_ENABLED=true \
ATENEA_AUTH_BOOTSTRAP_EMAIL=operator@atenea.local \
ATENEA_AUTH_BOOTSTRAP_PASSWORD=secret-pass \
./scripts/run.sh
```

Voice transcription backend for the `Core` tab:

- `ATENEA_CORE_VOICE_ENABLED=true`
- `ATENEA_CORE_VOICE_API_KEY=...`
- optional: `ATENEA_CORE_VOICE_TRANSCRIPTION_MODEL=gpt-4o-mini-transcribe`

Example:

```bash
ATENEA_CORE_VOICE_ENABLED=true \
ATENEA_CORE_VOICE_API_KEY=sk-... \
./scripts/run.sh
```

## Environment note

The scaffold currently uses Expo 54 / React Native 0.81.

That stack expects a newer Node runtime than the one currently installed in this workspace. TypeScript validation already passes, but running Expo locally should be done with Node `20.19.4+` to avoid engine warnings and runtime friction.

## Scope of this phase

This phase does not yet fully implement:

- dedicated confirmation UX for every sensitive action
- stronger interruption and retry UX across long-running operator flows
- full migration of deliverable approval and billing actions to `Atenea Core`
- voice capture outside the `Core` tab

This phase already implements:

- backend operator auth for `/api/mobile/*`
- login, refresh, logout and `me`
- JWT access token + persisted refresh token rotation
- authenticated app session restored from `expo-secure-store`
- Expo push-token registration baseline
- backend persistence of Expo push devices per operator
- backend push dispatch baseline for key operator events
- foreground notification capture in the native shell
- in-app recent notification rail with manual dismiss
- secure local persistence of recent notifications per operator session
- persisted pending-action recovery hints across app restarts
- internal routing from push open into `Session` or `Billing`
- `Core` tab as the primary conversational entrypoint
- `Atenea Core` command history and command-event stream in the app
- app-managed project/session context passed to `Atenea Core`
- audio recording with `expo-audio`
- server-side transcription through `POST /api/core/voice/commands`
- text-to-speech playback from `speakableMessage` through `expo-speech`
- direct SSE consumption for the session conversation workspace
- conversation UI split from session control UI
- terminal-like conversation rendering:
  - monospace layout
  - fenced code blocks
  - inline code
  - list / quote formatting
  - green emphasis for bold content
- closed sessions shown as historical context, while `Projects` resolves a new session instead of reusing `CLOSED`
- mobile `Close` action hidden for non-published sessions

## Notifications baseline

The native client now uses:

- `expo-notifications`
- `expo-device`
- `expo-secure-store`
- `expo-audio`
- `expo-speech`

Current behavior:

- asks for notification permission on a real device
- resolves an Expo push token
- registers the token in Atenea for the authenticated operator
- unregisters the token on logout
- can receive backend-triggered Expo push notifications when backend dispatch is enabled
- stores the most recent received/opened notifications in app memory
- opening a push routes the operator into the relevant mobile tab when payload context is available

Important Expo Go limitation:

- Expo Go on SDK 53+ does not support remote push notifications
- when the app runs inside Expo Go, push initialization is intentionally disabled
- notification registration, listeners and permission prompts remain active only for development builds / production builds

Backend push dispatch is currently implemented for:

- `RUN_SUCCEEDED`
- `CLOSE_BLOCKED`
- `PULL_REQUEST_MERGED`
- `BILLING_READY`

Backend push delivery is disabled by default and must be enabled explicitly with:

- `ATENEA_MOBILE_PUSH_ENABLED=true`

Optional override:

- `ATENEA_MOBILE_PUSH_EXPO_PUSH_URL`

Those are follow-up phases on top of this operator shell.
