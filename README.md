# Summit 42

Summit 42 is a naive clone of [Base 44](https://base44.com), built by extending
[Kinotic OS](https://github.com/kinotic-ai/kinotic). It is a chat-only app builder: people sign up with GitHub, create and name as
many apps as they like, and describe each one in a chat; Claude builds it as a regular Kinotic application, commits it
to the app's GitHub repository and deploys it on Kinotic. Each person who signs up becomes a Kinotic organization, and
never needs Kinotic's own UIs.

Summit 42 shows what can be built on Kinotic. It runs Kinotic's own modules in process and uses Kinotic's services for
everything an app needs (organizations, sign-in, invitations, GitHub repositories, deployments, data), so its own code
is the chat, the tools Claude builds with, and the UI.

## Layout

| Path | What it is |
|---|---|
| `server/` | A Spring Boot member of the Kinotic cluster that runs Kinotic's `kinotic-domain` and `kinotic-management-api` modules in process. It hosts `AppBuilderService` and `InvitationService` in Kinotic's `management-api` zone, and serves `/health` on port 8080. |
| `frontend/` | The Vue UI, deployed as static files on its own. It signs in to the Kinotic management gateway and chats with `AppBuilderService` over that gateway's STOMP connection. See [frontend/README.md](frontend/README.md). |

## How it works

- **Requests.** The UI's calls reach Summit 42 through the management gateway. The gateway authenticates the user's session and stamps every call with their organization participant, so Summit 42 has no login code of its own.
- **Apps.** The user creates an app in the organization with a name and an optional one-line description. `AppBuilderService.createApp` creates a Kinotic application with that name (its id is the slugified name, and a name the organization already uses is refused), whose `PLATFORM` tenant management gives each of the app's users a tenant of their own, and one project whose private GitHub repository Kinotic generates from its app template. The UI lists the organization's applications that have a project, with the URL of each deployed app.
- **Claude.** Each chat turn is about one app and runs through Spring AI with tools (`AppBuilderTools`) that work on that app's project:
  - read and commit the repository's files through GitHub's API;
  - wait for the deployment the commit starts, and report the URL of each UI;
  - read Kinotic's skills and its developer documentation.

  The turn streams Claude's reply as it is written, and each tool call as a step the chat shows the moment it starts, between the parts of the reply. Each step says whether it reads, commits or deploys (`BuildStepType`), from which the build page shows the turn's stage: planning, writing code, deploying or live. A turn runs to its end on the server whether or not anyone is watching: a chat closed or left mid-build stops following it, and the chat opened again replays the running turn's steps from its start (`followRunningTurn`) and follows the rest live. A call to the model has no overall time limit, since a reply that writes a whole app streams for many minutes; a call that receives nothing for 5 minutes has lost its connection, and fails the turn.
- **Skills.** How to build each part of a Kinotic app comes from the skills of Kinotic's agent plugin ([kinotic-ai/agent-plugin](https://github.com/kinotic-ai/agent-plugin)), the same ones Claude Code uses, so the plugin is the one place that knowledge is kept. The server reads the plugin's `main` branch from GitHub, and checks it for a new commit at most every 5 minutes while chats run, so a plugin change reaches Claude without a redeploy. Each turn's system prompt lists the skills, and Claude reads one with the `readSkill` tool. The `create-app` skill is left out, since Summit 42 creates the app itself, and `prompts/app-builder.md` tells Claude how the skills' shell and MCP steps map to Summit 42's tools.
- **Acting as the user.** Summit 42 runs Kinotic's domain and management-api modules in process, as the management server does, and `AppBuilderService` and its tools call their services directly. They wrap those calls in `SecurityContext.callAs` with the participant of the user's call, so Kinotic authorizes them against the user's organization. The system server keeps system-api, so Summit 42 reaches the LLM configurations over the cluster through a `@Proxy` interface.
- **The model.** It comes from the platform's LLM configuration named by `summit42.llmConfigurationName` (default `summit-42`). Add that configuration on the system console's **Platform → LLM configurations** page. Summit 42 reads the configuration and its API key as itself, through `SecurityContext.callWithoutParticipant`, since Kinotic gives the key to no call made for a user.
- **Repositories.** Each app's repository lives in the user's own GitHub account, through the Kinotic GitHub App. The UI asks the user to connect GitHub before the first app: it starts Kinotic's install with `<UI origin>/github/callback` as the return URL, GitHub returns to the management server, which forwards the browser there with GitHub's `installation_id`, `state` and `code`, and that page finishes the link with `GitHubAppInstallationService.completeInstall`. A signed-out browser signs in first and comes back to the callback. Claude writes the repository with a short-lived token from Kinotic's `ProjectRepoCredentialService`.
- **Managing apps.** Beside the chat, the UI calls Kinotic's management and persistence services itself, as the signed-in user: the organization's members and their invitations (accepted on the UI's own `/invite/accept` page), each app's users, its data, its deployments and history, renaming and deleting it, the GitHub link, and the identity providers the organization's members sign in with (`OwnedOidcConfigurationService`). Kinotic scopes each call to the user's organization.
- **Sign-up, sign-in and invitations.** Every organization builds its apps in its own GitHub account, so a person signs up with GitHub, through Kinotic's social sign-up. Signing in offers every way Kinotic's organization login supports: GitHub, through Kinotic's social sign-in, which finds the person's organization; or, on the organization's own page, `/login/<organization id>`, the identity providers the organization configured and is offering, and email and password. An invitation into the organization is accepted the same ways. People invited into one of the apps accept with a password, since they sign in to the app, not to Summit 42. Summit 42 sends its invitations with its own email template, in [`server/src/main/resources/email/`](server/src/main/resources/email), whose links open Summit 42's UI at `summit42.uiBaseUrl`. The UI sends invitations through `InvitationService`, which calls Kinotic's `MemberInvitationService` in process as the signed-in user, passing the template. Everything else about sign-up and members goes to Kinotic directly.
  An invitation's link opens the UI's `/invite/accept` page, where the invitee accepts an invitation into the organization with any provider it offers or a password, or one into an app with a password, which then sends them to that app's own sign-in page.
- **Conversations.** Each app has its own, saved in Elasticsearch in the `summit42_conversation` index after each message, so a user finds it again after signing back in or a restart. Summit 42 creates the index with its own migration, [`V1__conversations.sql`](server/src/main/resources/summit42/migrations/V1__conversations.sql), which it applies with Kinotic's migration executor at startup. One turn of an app runs at a time, and its conversation cannot be reset while it runs.

## Kinotic configuration

Summit 42 runs Kinotic's domain and management-api modules and hosts the `management-api` zone, so it answers some of
the cluster's management-api calls, the portal's included. It therefore takes every `kinotic.domain.*` and
`kinotic.managementApi.*` setting the management server has, with the same values: among them the Elasticsearch
connection, secret storage (a backend both servers share, so a secret either server saves the other can read; the
`development` profile's in-memory store is per process, as it is between Kinotic's own servers), email, platform
secrets, the GitHub App's private key and webhook secret, `kinotic.domain.portalBaseUrl`,
`kinotic.domain.appApiBaseUrl`, `kinotic.managementApi.apiBaseUrl`, and the Loki, Tempo and Mimir URLs. The
`development` profile uses the values of the management server's own `development` profile, except that email links
are logged rather than sent. Its `application.yml` sets what is the same everywhere: the GitHub App's id, slug and repository
template, the OAuth clients kinotic-domain requires, and `kinotic.managementApi.disableMcpServer`, since Summit 42 has
no API gateway. Its own settings are:

| Property | Environment variable | Value |
|---|---|---|
| `summit42.uiBaseUrl` | `SUMMIT42_UIBASEURL` | Summit 42's UI as a browser reaches it, where the emailed links open, such as `https://dev-summit42.kinotic.ai` |
| `summit42.llmConfigurationName` | `SUMMIT42_LLMCONFIGURATIONNAME` | the platform LLM configuration Claude runs with; `summit-42` by default |
| `summit42.ssl.enabled`, `certPath`, `keyPath` | `SUMMIT42_SSL_ENABLED`, `SUMMIT42_SSL_CERTPATH`, `SUMMIT42_SSL_KEYPATH` | TLS on port 8080 from PEM files; off by default |

Kinotic knows nothing about Summit 42, so the management server it signs users in through needs one setting:

| Property | Environment variable | Value |
|---|---|---|
| `kinotic.domain.cors.allowedOriginPattern` | `KINOTIC_DOMAIN_CORS_ALLOWEDORIGINPATTERN` | the server's own pattern plus the UI's origin, such as `http://localhost:(5173\|5175\|9090)` locally or `https://(dev-portal\.kinotic\.ai\|dev-summit42\.kinotic\.ai)` on the development server; social sign-ups and the GitHub install return to that origin |

## Run locally

1. Start the Kinotic stack from `kinotic/deployment/docker-compose`, with a GitHub App of your own as Kinotic's contributing guide describes, and the management server configured as [Kinotic configuration](#kinotic-configuration) shows.
2. Add an LLM configuration named `summit-42` with an API key on the system console.
3. Start the server with the `development` profile, which points it at the stack's Elasticsearch on `localhost:9200`,
   logs emailed links instead of sending them, reads your GitHub App from the file Kinotic's dev tools write for the
   management server, and opens emailed links on the UI's dev server, `http://localhost:5175`. Set the Ignite
   discovery so it joins the stack's cluster:
   ```bash
   SPRING_PROFILES_ACTIVE=development \
   KINOTIC_IGNITE_DISCOVERYTYPE=LOCAL \
   KINOTIC_IGNITE_LOCALADDRESSES=<the Kinotic servers' discovery addresses> \
   ./gradlew :server:bootRun
   ```
4. Run `bun run dev` in `frontend/` and open `http://localhost:5175`. Signing up needs the platform's GitHub sign-in
   registration.

`./gradlew :server:test` boots the server as the only member of a cluster of its own and calls `AppBuilderService` over Kinotic RPC, so it needs no Kinotic stack. It needs Docker: the tests start Elasticsearch in a container for the saved conversations, and are skipped without Docker. Kinotic's in-process services are replaced by fakes, and the LLM configurations by a fake published on the server's own node, which points at a local fake of Anthropic's API.

The server builds against `org.kinotic:*:5.0.0-SNAPSHOT`, published to the Central Portal snapshots repository. Until a snapshot with Summit 42's Kinotic changes is published, publish your Kinotic checkout to Maven local and add `mavenLocal()` to the repositories.

## Deploy

The server and the UI deploy separately.

**The server.** `./gradlew :server:bootBuildImage` builds `kinoticai/summit-42:<version>`. CI (`.github/workflows/build.yml`) builds and tests every push and pull request to `develop` or `main`, and a nightly run rebuilds against the latest Kinotic snapshot. It stages the image on ghcr.io and, once every job passes on `develop` or `main`, pushes it to Docker Hub as `kinoticai/summit-42:<version>`, with the Docker Hub account in the repository's `DOCKER_HUB_USERNAME` and `DOCKER_HUB_PASSWORD` secrets. Run the container beside the Kinotic servers, whose management server is configured as [Kinotic configuration](#kinotic-configuration) shows, with:

- `KINOTIC_IGNITE_*` set so it joins their cluster;
- the management server's Elasticsearch, secret storage, email, platform secrets and GitHub App settings, and `SUMMIT42_UIBASEURL`, as [Kinotic configuration](#kinotic-configuration) lists;
- port 8080 for its health check, with `summit42.ssl` on to serve it over TLS.

**The UI.** `bun run build` in `frontend/` writes static files to `dist/`, built with the `VITE_*` variables naming the management gateway (see [frontend/README.md](frontend/README.md#configuration)). Serve them from any static host that answers unknown paths with `index.html`, at the origin `summit42.uiBaseUrl` names.

The Kinotic development server runs both: its sites Worker serves the UI at `https://dev-summit42.kinotic.ai`, and the server is `https://dev-summit42-api.kinotic.ai` behind the edge that passes each TLS connection through unopened. Kinotic's `deployment/terraform/proxmox` root runs them when the `dev-server` root's `summit42_enabled` is set, and its `redeploy.sh` builds and uploads the UI from this repository's `main`.

## License

Summit 42 is licensed under the [Elastic License 2.0](LICENSE.md). Kinotic OS and the other components it depends on
are licensed under their own licenses.
