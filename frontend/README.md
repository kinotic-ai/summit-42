# Summit 42 frontend

The chat-only UI of Summit 42: people sign up with GitHub, create and name their apps, describe each one in a chat, and the
Summit 42 server builds and deploys it as a Kinotic app. A Vue 3 + Vite + TypeScript SPA that signs in to the Kinotic management gateway and
talks to `AppBuilderService`, in Kinotic's `management-api` zone, over Kinotic's STOMP connection (`@kinotic-ai/core`).
The workspace and app pages call Kinotic's own services over the same connection, through `@kinotic-ai/management-api`
and `@kinotic-ai/persistence`, so members, app users, data and deployments are managed here rather than in Kinotic's UIs.

## Run against a local Kinotic

Start the Kinotic management server (gateway on `localhost:58503`) and the Summit 42 server, then:

```bash
bun install
bun run dev          # http://localhost:5175
```

The management server must admit `http://localhost:5175` in its CORS pattern, as the
root [README](../README.md#kinotic-configuration) shows. Signing up needs the platform's GitHub sign-in
registration, since every workspace builds its apps in its own GitHub account.

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

`dist/` is a history-mode SPA: whatever serves it must answer unknown paths (`/login`, `/signup`, `/register`,
`/invite/accept`, `/github/callback`) with `index.html`.

## Routes

| Path | Page |
|---|---|
| `/` | The workspace's apps: connecting GitHub, creating an app, and a card per app with its link (every signed-in page redirects to `/login?referer=<path>` without a session, which returns there after signing in) |
| `/members` | The workspace's members: invite, resend or cancel an invitation, disable, enable or remove |
| `/settings` | The workspace's GitHub link, its own sign-in methods, and its id |
| `/apps/<application id>` | Build: the app's status and link beside the chat with the builder, which shows each step of a build as it runs |
| `/apps/<application id>/overview` | The app's link, status, data types, users, repository and recent deployments |
| `/apps/<application id>/data` | The records of each of the app's entity types, searchable and paged |
| `/apps/<application id>/users` | The people who sign in to the app: invite, disable, enable or remove |
| `/apps/<application id>/deployments` | The deployment's status, live commit, published pages and history |
| `/apps/<application id>/settings` | Rename the app, its id and repository, and deleting it |
| `/github/callback` | Where GitHub's install returns, through Kinotic, with `installation_id`, `state` and `code`: finishes linking GitHub and opens the workspace |
| `/login` | Sign in: social buttons, or email and password for the workspace this browser last used |
| `/signup` | Create an account with GitHub |
| `/register?token=` | Where a social sign-up returns: name the workspace |
| `/invite/accept?token=` | The emailed invitation's page: accept with a password or a sign-in provider; an invitation into an app then opens the app's own sign-in page |
