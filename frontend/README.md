# Summit 42 frontend

The chat-only UI of Summit 42: people sign up, create and name their apps, describe each one in a chat, and the
Summit 42 server builds and deploys it as a Kinotic app. A Vue 3 + Vite + TypeScript SPA that signs in to the Kinotic management gateway and
talks to the `summit-42` zone's `AppBuilderService` over Kinotic's STOMP connection (`@kinotic-ai/core`).

## Run against a local Kinotic

Start the Kinotic management server (gateway on `localhost:58503`) and the Summit 42 server, then:

```bash
bun install
bun run dev          # http://localhost:5175
```

The management server must admit `http://localhost:5175` in its CORS pattern and list the `summit-42` zone, as the
root [README](../README.md#kinotic-configuration) shows. With the email service disabled locally, the sign-up
verification link is printed in the management server's log.

## Configuration

Build-time Vite variables (copy `.env.example` to `.env.local` to override):

| Variable | Default | Meaning |
|---|---|---|
| `VITE_KINOTIC_HOST` | `localhost` | Host of the Kinotic management gateway |
| `VITE_KINOTIC_PORT` | `58503` | Its port |
| `VITE_KINOTIC_USE_SSL` | `false` | `true` for `https`/`wss` |

REST calls go to `http(s)://host:port` and the STOMP connection to the same host and port. For the Kinotic
development server use `dev-api.kinotic.ai`, `443`, `true`.

## Build

```bash
bun run build        # vue-tsc type-check, then vite build into dist/
```

`dist/` is a history-mode SPA: whatever serves it must answer unknown paths (`/login`, `/signup/verify`, `/register`,
`/github/callback`) with `index.html`.

## Routes

| Path | Page |
|---|---|
| `/` | The workspace: the list of apps, creating an app, and with `?app=<application id>` the chat with the builder about that app (redirects to `/login?referer=<path>` without a session, which returns there after signing in) |
| `/github/callback` | Where GitHub's install returns, through Kinotic, with `installation_id`, `state` and `code`: finishes linking GitHub and opens the workspace |
| `/login` | Sign in: social buttons, or email and password for the workspace this browser last used |
| `/signup` | Create an account with a social provider or email |
| `/signup/verify?token=` | The emailed link's page: name the workspace and set a password |
| `/register?token=` | Where a social sign-up returns: name the workspace |
