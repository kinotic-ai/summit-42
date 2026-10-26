# Summit 42 frontend

The chat-only UI of Summit 42: people sign up with GitHub, create and name their apps, describe each one in a chat, and the
Summit 42 server builds and deploys it as a Kinotic app. The description given when an app is created is sent as the
first message of its chat, so the build starts from it. A Vue 3 + Vite + TypeScript SPA that signs in to the Kinotic management gateway and
talks to `AppBuilderService`, in Kinotic's `management-api` zone, over Kinotic's STOMP connection (`@kinotic-ai/core`).
The organization and app pages call Kinotic's own services over the same connection, through `@kinotic-ai/management-api`
and `@kinotic-ai/persistence`, so members, app users, data and deployments are managed here rather than in Kinotic's UIs.

## Run against a local Kinotic

Start the Kinotic management server (gateway on `localhost:58503`) and the Summit 42 server, then:

```bash
bun install
bun run dev          # http://localhost:5175
```

The management server must admit `http://localhost:5175` in its CORS pattern, as the
root [README](../README.md#kinotic-configuration) shows. Signing up needs the system's GitHub sign-in
registration, since every organization builds its apps in its own GitHub account.

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

`dist/` is a history-mode SPA: whatever serves it must answer unknown paths (`/login`, `/login/<organization id>`, `/signup`, `/register`,
`/invite/accept`, `/github/callback`) with `index.html`.

## Routes

| Path | Page |
|---|---|
| `/` | The organization's apps: connecting GitHub, creating an app (its name, what it should do, and who can sign in: the people invited, or anyone who signs up; each step of setting it up shows as it runs, with the time taken so far, and the app's chat opens once it is created), and a card per app with its link (every signed-in page redirects to `/login?referer=<path>` without a session, which returns there after signing in) |
| `/members` | The organization's members, each pending until they first sign in: invite, resend an invitation, remove, disable or enable |
| `/settings` | The organization's GitHub link, the sign-in providers its members sign in with (identity providers, with the callback URL to register at the provider, and an email and a password: add, edit, turn on or off, delete), and the link to its sign-in page |
| `/apps/<application id>` | Build: a card tracking the running build's stage (planning, writing code, deploying, live) and how long it has taken, which stops once the app is live and starts over when Claude changes the app again, or the app's link once live, beside the chat with the builder, which shows each step of a build as it runs, between the parts of Claude's reply, and how long the current step or Claude's thinking has taken. Coming back to the chat during a build replays its steps so far and follows it live. A message sent during a build waits below the reply until Claude reads it after its current step, then shows where Claude read it |
| `/apps/<application id>/overview` | The app's link, status, data types, users, repository and recent deployments |
| `/apps/<application id>/data` | The records of each of the app's entity types, searchable and paged |
| `/apps/<application id>/users` | The people who sign in to the app, each pending until they first sign in: invite, resend an invitation, remove, disable or enable |
| `/apps/<application id>/deployments` | The deployment's status, live commit, published pages, and a history of each version deployed, published or failed |
| `/apps/<application id>/settings` | Rename the app; who can sign in to it (the people invited, or anyone who signs up) and its sign-in providers (an email and a password, Google or Microsoft through Kinotic with no setup, or a provider with your own credentials: add, edit, turn on or off, delete); its id and repository; and deleting it |
| `/github/callback` | Where GitHub's install returns, through Kinotic, with `installation_id`, `state` and `code`: finishes linking GitHub and opens the organization |
| `/login` | Sign in with GitHub, choosing the organization when the account belongs to several, or name the organization to sign in on its own page; a browser that signed in before opens its last organization's page instead |
| `/login/<organization id>` | The organization's sign-in: the identity providers it offers, and email and password while it takes one; "Use another" forgets the organization and returns to `/login`, as does a remembered organization that no longer exists |
| `/signup` | Create an account with GitHub |
| `/register?token=` | Where a social sign-up returns: name the organization |
| `/invite/accept?token=` | The emailed invitation's page: accept an invitation into the organization with any provider it offers or, while it takes passwords, by setting one; or one into an app by setting a password, which then opens the app's own sign-in page, or by signing in to the app through one of its providers, which the page links to |
