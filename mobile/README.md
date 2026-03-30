# Atenea Mobile

Native operator shell for Atenea mobile full operation.

## Current status

The repository now contains a first React Native client bootstrapped with Expo and wired to the backend mobile surface:

- inbox
- projects overview
- session summary
- dedicated session conversation workspace
- terminal-style conversation UX tuned for mobile operator work
- billing queue
- periodic auto-refresh for inbox, projects, billing and session state
- session event feed
- direct SSE consumption in the conversation workspace with polling fallback
- resolve session from project cards
- session actions for:
  - publish
  - pull-request sync
  - close
- conversation actions for:
  - read turns
  - send prompts
  - return to the session control view
- deliverable actions for:
  - generate
  - approve
  - mark billed

Current implementation focus:

- validate the mobile backend contract in a real native client
- provide a compact operator shell for future session, delivery and billing flows
- keep the client thin while the backend remains the source of truth
- keep live operational visibility without introducing native infra too early

## Backend contract used

The current client reads from:

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

The client is intentionally aligned with the session-first mobile backend already present in the repository.

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

## Environment note

The scaffold currently uses Expo 54 / React Native 0.81.

That stack expects a newer Node runtime than the one currently installed in this workspace. TypeScript validation already passes, but running Expo locally should be done with Node `20.19.4+` to avoid engine warnings and runtime friction.

## Scope of this phase

This phase does not yet implement:

- richer action UX with dedicated confirmations and retries

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
- internal routing from push open into `Session` or `Billing`
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
