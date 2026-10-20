# Summit 42

Summit 42 is a chat-only app builder. People sign up, describe the app they want, and Claude builds it as a regular
Kinotic application, commits it to the app's GitHub repository and deploys it on Kinotic. Each person who signs up
becomes a Kinotic organization.

## Layout

| Path | What it is |
|---|---|
| `server/` | A Spring Boot member of the Kinotic cluster. It hosts `AppBuilderService` in the `summit-42` zone and serves the UI on port 8080. |
| `frontend/` | The Vue UI. It signs in to the Kinotic management gateway and chats with `AppBuilderService` over that gateway's STOMP connection. See [frontend/README.md](frontend/README.md). |

## How it works

- **Requests.** The UI's calls reach Summit 42 through the management gateway. The gateway authenticates the user's session and stamps every call with their organization participant, so Summit 42 has no login code of its own.
- **Claude.** Each chat turn runs through Spring AI with tools (`AppBuilderTools`) that:
  - create the app: a Kinotic application and project whose repository Kinotic generates from its app template;
  - read and commit the repository's files through GitHub's API;
  - wait for the deployment the commit starts, and report the URL of each UI;
  - read Kinotic's developer documentation.
- **Acting as the user.** Each tool calls Kinotic's services through `@Proxy` interfaces. It wraps those calls in `SecurityContext.callAs` with the participant of the chat call, so Kinotic authorizes them against the user's organization.
- **The model.** It comes from the platform's LLM configuration named by `summit42.llmConfigurationName` (default `summit-42`). Add that configuration on the system console's **Platform → LLM configurations** page.
- **Repositories.** Each app's repository lives in the user's own GitHub account, through the Kinotic GitHub App. The workspace asks the user to connect GitHub before the first chat: it starts Kinotic's install with the workspace as the return URL, GitHub returns to the Kinotic portal's install callback, where the user signs in to the portal once, and the portal finishes the link and sends the browser back. Claude writes the repository with a short-lived token from Kinotic's `ProjectRepoCredentialService`.
- **Conversations.** They are held in the server's memory, so a restart starts them over. The apps themselves live in Kinotic: each organization's app is its application with the id `summit-42`, which the builder finds again after a restart.

## Kinotic configuration

Kinotic knows nothing about Summit 42, so the management server it signs users in through needs two settings:

| Property | Environment variable | Value |
|---|---|---|
| `kinotic.apiGateway.organizationServiceZones` | `KINOTIC_APIGATEWAY_ORGANIZATIONSERVICEZONES` | `summit-42`, so organizations' users may call `AppBuilderService` and the management server routes to it |
| `kinotic.domain.cors.allowedOriginPattern` | `KINOTIC_DOMAIN_CORS_ALLOWEDORIGINPATTERN` | the server's own pattern plus the UI's origin, such as `http://localhost:(5173\|5175\|9090)` locally or `https://(dev-portal\.kinotic\.ai\|dev-summit42\.kinotic\.ai)` on the development server; sign-up emails and social sign-ups return to that origin |

## Run locally

1. Start the Kinotic stack from `kinotic/deployment/docker-compose`, with a GitHub App of your own as Kinotic's contributing guide describes, and the management server configured as [Kinotic configuration](#kinotic-configuration) shows.
2. Add an LLM configuration named `summit-42` with an API key on the system console.
3. Build the UI:
   ```bash
   cd frontend && bun install && bun run build
   ```
4. Start the server so it joins the stack's Ignite cluster:
   ```bash
   KINOTIC_IGNITE_DISCOVERYTYPE=LOCAL \
   KINOTIC_IGNITE_LOCALADDRESSES=<the Kinotic servers' discovery addresses> \
   ./gradlew :server:bootRun
   ```
5. For UI work, run `bun run dev` in `frontend/` and open `http://localhost:5175`.

The server builds against `org.kinotic:*:5.0.0-SNAPSHOT`, published to the Central Portal snapshots repository. Until a snapshot with Summit 42's Kinotic changes is published, publish your Kinotic checkout to Maven local and add `mavenLocal()` to the repositories.

## Deploy

`./gradlew :server:bootBuildImage` builds `kinoticai/summit-42:<version>`. Build the UI first, because the jar serves `frontend/dist`.

Run the container beside the Kinotic servers, whose management server is configured as
[Kinotic configuration](#kinotic-configuration) shows, with:

- `KINOTIC_IGNITE_*` set so it joins their cluster;
- port 8080 published as `https://dev-summit42.kinotic.ai` on the development server;
- the UI built with `VITE_KINOTIC_HOST=dev-api.kinotic.ai`, `VITE_KINOTIC_PORT=443` and `VITE_KINOTIC_USE_SSL=true`.
