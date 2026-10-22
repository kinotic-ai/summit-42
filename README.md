# Summit 42

Summit 42 is a chat-only app builder. People sign up, create and name as many apps as they like, and describe each
one in a chat; Claude builds it as a regular Kinotic application, commits it to the app's GitHub repository and
deploys it on Kinotic. Each person who signs up becomes a Kinotic organization, and never needs Kinotic's own UIs.

## Layout

| Path | What it is |
|---|---|
| `server/` | A Spring Boot member of the Kinotic cluster. It hosts `AppBuilderService` in Kinotic's `management-api` zone and serves the UI on port 8080. |
| `frontend/` | The Vue UI. It signs in to the Kinotic management gateway and chats with `AppBuilderService` over that gateway's STOMP connection. See [frontend/README.md](frontend/README.md). |

## How it works

- **Requests.** The UI's calls reach Summit 42 through the management gateway. The gateway authenticates the user's session and stamps every call with their organization participant, so Summit 42 has no login code of its own.
- **Apps.** The user creates an app in the workspace with a name and an optional one-line description. `AppBuilderService.createApp` creates a Kinotic application with that name (its id is the slugified name, and a name the organization already uses is refused) and one project whose private GitHub repository Kinotic generates from its app template. The workspace lists the organization's applications that have a project, with the URL of each deployed app.
- **Claude.** Each chat turn is about one app and runs through Spring AI with tools (`AppBuilderTools`) that work on that app's project:
  - read and commit the repository's files through GitHub's API;
  - wait for the deployment the commit starts, and report the URL of each UI;
  - read Kinotic's developer documentation.
- **Acting as the user.** `AppBuilderService` and its tools call Kinotic's services through `@Proxy` interfaces. They wrap those calls in `SecurityContext.callAs` with the participant of the user's call, so Kinotic authorizes them against the user's organization.
- **The model.** It comes from the platform's LLM configuration named by `summit42.llmConfigurationName` (default `summit-42`). Add that configuration on the system console's **Platform → LLM configurations** page. Summit 42 reads the configuration and its API key as itself, through `SecurityContext.callWithoutParticipant`, since Kinotic gives the key to no call made for a user.
- **Repositories.** Each app's repository lives in the user's own GitHub account, through the Kinotic GitHub App. The workspace asks the user to connect GitHub before the first app: it starts Kinotic's install with `<UI origin>/github/callback` as the return URL, GitHub returns to the management server, which forwards the browser there with GitHub's `installation_id`, `state` and `code`, and that page finishes the link with `GitHubAppInstallationService.completeInstall`. A signed-out browser signs in first and comes back to the callback. Claude writes the repository with a short-lived token from Kinotic's `ProjectRepoCredentialService`.
- **Managing apps.** Beside the chat, the UI calls Kinotic's management and persistence services itself, as the signed-in user: workspace members and their invitations (accepted on the UI's own `/invite/accept` page), each app's users, its data, its deployments and history, renaming and deleting it, and the GitHub link. Kinotic scopes each call to the user's organization.
- **Conversations.** Each app has its own, held in the server's memory, so a restart starts them over. The apps themselves live in Kinotic, so the list of apps survives a restart. One turn of an app runs at a time, and its conversation cannot be reset while it runs.

## Kinotic configuration

Kinotic knows nothing about Summit 42, so the management server it signs users in through needs one setting:

| Property | Environment variable | Value |
|---|---|---|
| `kinotic.domain.cors.allowedOriginPattern` | `KINOTIC_DOMAIN_CORS_ALLOWEDORIGINPATTERN` | the server's own pattern plus the UI's origin, such as `http://localhost:(5173\|5175\|9090)` locally or `https://(dev-portal\.kinotic\.ai\|dev-summit42\.kinotic\.ai)` on the development server; sign-up emails, social sign-ups, invitation links and the GitHub install return to that origin |

Kinotic sends invitations and sign-up verification emails in its own branding unless the UI's origin has templates
of its own. Summit 42's are in [`email-templates/`](email-templates): for each of `invitation` and `verification`, add
a template on the system console's **Platform → Email templates** page with the UI's origin (such as
`https://dev-summit42.kinotic.ai`), the matching email, and the contents of `subject.txt`, `body.html` and `body.txt`.
An invitation's link opens the UI's `/invite/accept` page, where the invitee sets a password or accepts through a
sign-in provider; an invitation into one of the user's apps then sends them to that app's own sign-in page.

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

`./gradlew :server:test` boots the server as the only member of a cluster of its own and calls `AppBuilderService` over Kinotic RPC, so it needs no Kinotic stack. Some tests run with no Kinotic services at all. Others use fakes of the Kinotic services Summit 42 calls, which run on the server's own node, and a local fake of Anthropic's API that the LLM configuration points at.

The server builds against `org.kinotic:*:5.0.0-SNAPSHOT`, published to the Central Portal snapshots repository. Until a snapshot with Summit 42's Kinotic changes is published, publish your Kinotic checkout to Maven local and add `mavenLocal()` to the repositories.

## Deploy

`./gradlew :server:bootBuildImage` builds `kinoticai/summit-42:<version>`. Build the UI first, because the jar serves `frontend/dist`.

CI (`.github/workflows/build.yml`) builds and tests every push and pull request to `develop` or `main`, and a nightly run rebuilds against the latest Kinotic snapshot. It stages the image on ghcr.io and, once every job passes on `develop` or `main`, pushes it to Docker Hub as `kinoticai/summit-42:<version>`. The repository needs:

| Setting | Kind | Value |
|---|---|---|
| `DOCKER_HUB_USERNAME`, `DOCKER_HUB_PASSWORD` | secrets | a Docker Hub account that can push `kinoticai/summit-42` |
| `VITE_KINOTIC_HOST`, `VITE_KINOTIC_PORT`, `VITE_KINOTIC_USE_SSL` | variables | the management gateway the image's UI connects to, such as `dev-api.kinotic.ai`, `443` and `true`; unset, the UI connects to `localhost:58503` |

Run the container beside the Kinotic servers, whose management server is configured as
[Kinotic configuration](#kinotic-configuration) shows, with:

- `KINOTIC_IGNITE_*` set so it joins their cluster;
- port 8080 published as `https://dev-summit42.kinotic.ai` on the development server;
- the UI built with `VITE_KINOTIC_HOST=dev-api.kinotic.ai`, `VITE_KINOTIC_PORT=443` and `VITE_KINOTIC_USE_SSL=true`, as the CI variables above do.
