# summit-42

## Summit 42 is an example of building on Kinotic

Summit 42 shows what can be built on Kinotic, so it uses Kinotic the way any other product would. When Summit 42 needs
something Kinotic lacks, the change in [kinotic-ai/kinotic](https://github.com/kinotic-ai/kinotic) is generic and driven
by configuration: nothing in Kinotic names Summit 42, its zones, its domains or its templates. Keep changes to Kinotic
minimal; everything else lives here.

How to build a Kinotic app is kept in the skills of [kinotic-ai/agent-plugin](https://github.com/kinotic-ai/agent-plugin),
which the server reads at runtime. `prompts/app-builder.md` holds only what is specific to Summit 42: its tools, and how
the skills' shell and MCP steps map to them. Fix a gap in how to build an app in the plugin, never by copying it into the
prompt.

## Building in Claude Code Cloud

The cloud environment has JDK 21 installed, but the project requires JDK 25. Download it first if not already present (Oracle CDN is in the egress allowlist). The URL serves the latest JDK 25 update, so the archive's versioned top-level directory is stripped and the JDK always lands in `/tmp/jdk25`:

```bash
curl -sL "https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz" -o /tmp/jdk25.tar.gz
mkdir -p /tmp/jdk25 && tar xzf /tmp/jdk25.tar.gz -C /tmp/jdk25 --strip-components=1
```

Then build with JDK 21 as the Gradle daemon (has the egress proxy CA certs) and JDK 25 for compilation:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :server:test -Porg.gradle.java.installations.paths=/tmp/jdk25
```

The server builds against Kinotic's `5.0.0-SNAPSHOT` from the Central Portal snapshot repository. Kinotic publishes a
snapshot only on its nightly build (07:00 UTC) or a manual run of its `gradle-build.yml`, so a change that needs new
Kinotic code waits for that publish. When the snapshot repository is unreachable from the sandbox, build Kinotic's
`develop` and publish it to Maven Local, then add `mavenLocal()` through a Gradle init script; never commit that
repository to `build.gradle`.

The server tests run Elasticsearch in a Testcontainer and skip when Docker is unavailable, so CI is the check for them
when the sandbox cannot run the container.

## Branch and pull request workflow

A merged pull request is finished — never stack new commits onto its history, and never reuse
it to track follow-up work. When your PR merges, restart your working branch from the latest
`main` (keeping the same branch name, force-with-lease push) and open a **new** pull
request for the next unit of work. If the branch carries unmerged commits, rebase them onto
the new base instead of discarding them.

A pull request description never mentions Claude or any AI tool: no "Generated with Claude Code"
line, no `Co-Authored-By` trailer, no session link, and no other attribution to an assistant.

## Don't guess from names

Names suggest meaning but don't define it. Before using an annotation, framework hook, base class, or library helper you haven't used in this codebase before, read its source or docs and confirm what it actually does. Don't infer behaviour from a plausible-sounding name and ship it. If you can't verify the behaviour, ask — don't write a comment justifying the guess.

## Java Conventions

Always use Lombok where possible: `@Getter`, `@Setter`, `@Accessors(chain = true)`, `@NoArgsConstructor`, `@RequiredArgsConstructor`, `@Slf4j`, `@Data`, `@Builder`. Prefer `@RequiredArgsConstructor` over hand-written constructors for dependency injection. Use `@Slf4j` instead of manual `LoggerFactory.getLogger()` calls.

Prefer a single `return` per method. Guard clauses that short-circuit errors or degenerate cases at the top of a method are fine (throwing, or bailing before the real work), but branching LOGIC paths use if/else assigning a result variable that is returned once at the end (the `String ret; if (...) { ret = ...; } else { ret = ...; } return ret;` idiom used throughout the codebase) — never a `return` inside each branch.

Use `enum` for any field whose value is constrained to a known set — never `String` with magic-string constants. Spring and Vert.x both auto-coerce JSON strings to enum values when deserializing into typed POJOs, so the wire contract stays string-friendly while the in-process type catches typos at compile time. Examples: `BuildEventType`, Kinotic's `LlmProviderType`.

An enum that says what sort of thing something is gets named `<Thing>Type` (`BuildEventType`), never `<Thing>Kind`, and the field holding it is `type`, never `kind`.

When bridging a `CompletableFuture` into Vert.x, always pass the context: `Future.fromCompletionStage(stage, vertx.getOrCreateContext())`, never the single-argument overload. The stage may complete on a foreign thread (Spring AI, Caffeine loader, JDK executor), and without the context argument everything composed after it leaves the request's Vert.x context — breaking anything downstream that reads a context-local, such as `SecurityContext`'s participant. For delays, use `vertx.timer(...)` instead of a JDK scheduled executor for the same reason.

Calls into Kinotic's services made for a user run inside `SecurityContext.callAs` with that user's participant, so Kinotic authorizes them against the user's organization. Spring AI runs tools on its own threads, which carry no Vert.x context-local participant.

## Package Structure (Crucial!!)

The server follows Kinotic's layout convention. The rule is: if something will be used by another module/node, it belongs in `api/`. If not, it belongs in `internal/`. The `internal/` structure mirrors `api/` for implementations.

- `api/` — Public interfaces, types, and DTOs used by other modules or nodes (shared/exported), such as the `@Publish`ed `AppBuilderService` the UI calls
- `internal/` — Everything private to this module (not shared/exported)
  - `internal/api/` — Implementations of public `api/` interfaces (`@Publish`, `@Component`, etc.)
  - `internal/api/model/` — DTOs and value objects only used within this module

The `internal/api/` structure mirrors `api/` for implementations. Example: `api/services/AppBuilderService` -> `internal/api/services/DefaultAppBuilderService`.

When creating code to store data, call it a Repository, not a Store. If there is advanced logic needed outside of CRUD, create a Service that delegates to the Repository.

Configuration follows the same split: `api/config/` contains `@ConfigurationProperties` classes and settings POJOs meant to be configured by users, while `internal/config/` contains Spring `@Configuration` classes that wire beans internally.

Don't create a new package or folder to hold a single file. Single-file folders just spread related code across the tree without aiding discoverability. Place the file in the nearest existing package that fits. A new subpackage is justified once there are at least two or three related files that genuinely belong together.

***Give every top-level type its own file. Don't nest a class, enum, or record inside an interface — DTOs, result types, and enums that appear in an interface's method signatures belong in their own files in the same package, not inlined in the interface body. Nesting buries types, makes them awkward to import, and bloats the interface. The same applies to types nested inside a class purely for convenience.***

## Comments

**IMPORTANT: Write comments the way you would explain the code to another developer.**

Javadoc — block comments on classes, methods, fields, anything else — describes the contract from the caller's perspective: what something is for, what guarantees it makes, what the inputs and outputs mean. It should not document implementation details — how the class persists, which helper it delegates to, what bypass mechanism it uses internally — that's noise for someone using the API and rots when the implementation changes. Also they should not document what something does not do. Only what it does do. (Unless it is a security concern, Does not validate user)

Inline comments inside method bodies are different: they're for implementation details that aren't obvious from reading the code, and only when they aren't. A subtle invariant, the reason for an unusual ordering, a workaround for a specific bug, a non-obvious choice between two valid approaches — those earn an inline comment. Self-evident code does not. If you find yourself writing a comment that restates what the next line does, delete it. Like Javadoc, an inline comment says what the code does, never what it does not do.

The split is about audience, not formatting. Javadoc is for **consumers** of the API; inline is for **maintainers** of the body. Before writing a comment, ask which one needs it. The rationale for a defensive check, a workaround, or a tricky ordering belongs inline next to the code that does it — never in the Javadoc, even if it explains why the method behaves the way it does.

Keep comments concise — usually a single line. Spend more words only where the logic is genuinely not straightforward and the extra explanation earns its place: a subtle invariant, a non-obvious interaction, a reason that isn't visible in the code. A comment's length should track how hard the code is to follow, not how important the code is.

Comment the code as it is now, not its history. Don't narrate what the code used to do or the edit you're making — no "before", "previously", "used to", "changed from", or "now does X instead" phrasing. The diff and git history record what changed; the comment describes the present state.

Use standard programming vocabulary in comments — the terms from GoF, Fowler's Refactoring, and the JDK/framework docs: delegate, factory, guard clause, invariant, idempotent, race condition, callback, dispatch. Never literary metaphors or coined phrases ("prologue", "dance", "journey", "saga"). Reference the actual method and class names involved rather than describing them indirectly. State cause and effect directly.

Never remove or alter an existing authorship comment — `Created by <name> on <date>`, `@author`, or similar attribution. Preserve it verbatim (name, accents, emoji, date, punctuation) when editing or refactoring a file, including when you rewrite the surrounding Javadoc/JSDoc, and carry it with the type if you move that type to another file.

## Properties

Properties should never be created for something that will not need to be configured differently in different environments, i.e. the dev server vs production. In the case of a URL, route or anything else that will be the same for every environment, create a constant.

Never gate a bean on a Spring profile — `@Profile` is for test contexts only; profile-gated beans are hard to audit. Profiles are property bundles: an `application-<name>.yml` selects property values for a deployment shape. Enabling or disabling a feature is done with an explicit property read by `@ConditionalOnProperty`, so what a deployment runs can be read from its YAML alone.

One fact, one property. Never add a property whose value always equals another property's. Summit 42 runs Kinotic's modules in process, so a deployment configures them through their own `kinotic.*` properties; never copy one of those values into `summit42.*`, read Kinotic's property instead. Before adding a property, grep the yml profiles and deployment files for the value it would hold; if another property already holds it, read that one. Two properties that must always match are Shotgun Surgery across every deployment file, and a bug the first time one is changed without the other.

Summit 42's own settings are fields of `Summit42Properties`, bound under `summit42.*`.

## Dependency Versions

Never hardcode a dependency version in a `dependencies` block. Every version lives as a `*Version` property in `gradle.properties` (kept alphabetical) and is pinned once in the `dependencyManagement` block of `server/build.gradle`, or comes from a BOM imported there. The dependency is declared with no version, so the managed version applies.

One version per artifact, one place to bump it. Verify a change with `dependencyInsight` on the server's `compileClasspath`.

## Never work around an unpublished package

When a change needs a Kinotic version that is not published yet — a Java snapshot with a new Kinotic API, or a new
`@kinotic-ai/*` npm package — declare the real dependency and ask the maintainer to publish. Never dodge the publish
with a `file:`/`link:` reference, a vendored jar or tarball, a committed `mavenLocal()`, a copy of the Kinotic code, or
a downgrade to an older published version that lacks what the change needs. The publish is part of the change, and
asking for it is always cheaper than the workaround.

## Snapshot versions — nothing is in stone

While `summit42Version` in `gradle.properties` is a `-SNAPSHOT`, nothing depends on this code staying compatible: rename fields, break APIs, and reshape wire contracts between the server and the UI freely. Deprecation shims and compatibility fallbacks start when the first release exists — building them sooner is Speculative Generality.

Migrations follow the same rule while the data can be rebuilt: a schema change edits the table in its existing file under `server/src/main/resources/summit42/migrations/`, and the index is rebuilt to take it. Append-only discipline starts with the first release: from then on a change is a new versioned file, never an edit of one already applied, and since Elasticsearch cannot retype a mapped field, a column that changes type is a new column beside the old one, which stays mapped and unused.

## Keep migrations in sync with persisted entities

Every entity stored through an Elasticsearch-backed Repository gets its index mapping from the migration DDL in
`server/src/main/resources/summit42/migrations/`, and mappings are strict — an entity field missing from its CREATE TABLE fails the first save of that entity at runtime.
Whenever you change a persisted entity's fields, update its table in the same change. For DDL syntax and column
types, see Kinotic's `website/content/01.apps/09.reference/02.migration-sql-grammar.md`.

A `List<POJO>` field is an `OBJECT` column: Elasticsearch stores a list under an object mapping and
a query on one sub-field matches across it. `NESTED` is only for a query that must match two
sub-fields of the same element, and that query must exist in the change that introduces the
column.

## Keep docs in sync with code

When a change alters something the docs describe — a wire contract, public API signature, route, configuration option, or user-facing behavior — update `README.md` and `frontend/README.md` in the same change. They must always reflect the correct and current shape of the system; stale docs are a defect, not a follow-up. Before finishing, grep them for the symbols, routes, and field names you changed and reconcile every hit.

## Docs follow features, never the other way round

The READMEs describe what the code does; they never decide what the code should do. A request from the user drives a
feature, and the docs are updated afterwards to match what was built. Never treat a sentence in the docs as the
specification of a behaviour the code lacks, and never build, keep or refuse something because the docs say so. When a
request contradicts the docs, or the docs claim a behaviour the code does not have, say so and ask which way to go; do
not pick the docs' side on your own.

## Avoid these code smells

These are the named smells from Martin Fowler and Kent Beck's catalog in *Refactoring: Improving the Design of Existing Code*, grouped by Mäntylä's taxonomy — decades of industry consensus on what makes code hard to change, not house style or one reviewer's taste. That is why they bind: each one is a pattern the field has repeatedly watched turn into maintenance cost. Check every diff against this list before presenting it. The bar for any new abstraction is YAGNI (Beck and Jeffries, Extreme Programming) and the Rule of Three (Don Roberts, in Refactoring): it must carry information or remove duplication **today** — not that it might someday.

**Dispensables — code that should not exist**

- **Speculative Generality.** No one-value enums, no parameters every call site passes the same constant to, no "seam for a future toggle," no interface with a single implementation created "just in case," no config nobody asked for. Build for the current requirement; introduce the discriminator or abstraction when the second concrete case exists to design against.
- **Dead Code.** No defensive branches every caller already makes impossible, no unused parameters or imports, no commented-out code, no "kept for later" methods without an owner's explicit say-so. A guard that is genuinely load-bearing against a corrupted state earns an inline comment saying so — otherwise delete it.
- **Lazy Element.** A class, method, or package that no longer pulls its weight after a refactor gets inlined or deleted, not left behind.
- **Duplicated Code.** Before writing new logic, find the existing seam and compose it (one logic path). Two near-identical blocks in sibling classes means the shared piece was never extracted — extract it to the nearest common layer, not to a new grab bag.
- **Comments as deodorant.** A comment explaining confusing code is a signal to fix the code. The Comments section above governs what comments are for.

**Bloaters — things that have grown past one responsibility**

- **Long Function.** A method that does several things at different levels of abstraction gets decomposed, each piece named for what it does.
- **Large Class / junk drawers.** The smell is a class that accumulates *unrelated* members and changes for many reasons; split it along the reasons it changes. Entities too: a class holding one kind of thing must not carry a name claiming generality it doesn't have. The name itself isn't the smell: a shared `Util`/`Constants`/`Helper` class is fine for cohesive, stateless, well-named static members — a single known home beats scattering them. Don't overcorrect either — a class or file created to hold a single static method is a Lazy Element; fold it into the nearest cohesive home instead of trading a grab bag for file sprawl.
- **Long Parameter List / flag arguments.** A boolean or mode parameter that forks a method's whole behavior is two methods. More than ~4 parameters is a sign some of them are a missing type.
- **Data Clumps.** Values that always travel together belong in one type, not loose parameter pairs repeated across signatures.
- **Primitive Obsession.** Covered by the enum rule in Java Conventions — applies equally to ids, keys, and wire codes used across boundaries.

**Couplers — classes that know too much about each other**

- **Middle Man / needless indirection.** No support class, wrapper, or dispatch layer with a single consumer — inline it until at least two real consumers exist; a test is never the second consumer. A flow's logic should be followable inside one class; if understanding it requires hopping between classes, the indirection is the smell. Same rule for constants: used in one class → declared in that class; shared catalogs only for genuine cross-class or cross-boundary contracts.
- **Feature Envy.** A method that mostly reads and combines another class's data belongs on that class. If a handler keeps reaching into an entity to make a decision the entity could make, move the decision.
- **Inappropriate Intimacy.** Don't reach through another class's internals (its repository, its private collaborators) — go through its interface. Using a Kinotic module's `internal` packages from Summit 42 is this smell by definition.
- **Message Chains.** `a.getB().getC().getD()` couples the caller to the whole path. Ask the nearest object for what you actually need.

**Change preventers — structure that makes edits expensive**

- **Shotgun Surgery.** When one logical change forces edits in many files (a wire code, a route, a field name), the knowledge is scattered — give it a single home first, then change it.
- **Divergent Change.** When one class keeps changing for unrelated reasons, it has multiple responsibilities — split along the reasons it changes.
- **Repeated Switches.** The same `switch`/`if-else` chain over the same discriminator in more than one place means polymorphism or a map is missing. One occurrence is fine; the second copy is the smell.

**Object-orientation abusers**

- **Alternative Classes with Different Interfaces.** Two classes doing the same job must share a shape: same method names, same parameter order.
- **Refused Bequest.** Don't extend a base class to use one method while ignoring or overriding-to-nothing the rest — compose instead.
- **Temporary Field.** A field only meaningful during one operation is a missing parameter or a missing small object, not state.
- **Data Class with leaked logic.** Entities/DTOs stay dumb, but the logic operating on them must then live in ONE service — not spread across every caller.

## Tests serve the code — they never shape it

The smells catalog binds with no testability exception: never extract, export, widen
visibility, or add a parameter, property or seam whose only consumer is a test. A test is never the
second consumer under the Rule of Three. Test through the interface a real caller uses
and assert on outputs the code already produces (the events a chat streams, the files a commit sends, the request
Claude receives); reach edge branches by controlling real inputs, not by opening
internals. Prefer one behavioral test with real infrastructure (Elasticsearch in a container, an HTTP fake of an
external API on a local port) — gated to skip when the environment lacks it — over unit tests that each
cost a structural concession. Behavior unobservable through any public interface is a
production API gap: raise it, don't add a test-only door.

The same preference governs test level: a test that boots the server against real
collaborators beats a unit test that must fake or mock significant functionality — the
faked collaborator is where the bugs live (wire dispatch, deserialization, persistence
hooks), and a slower test that exercises them is worth more than a fast one that assumes
them. The fakes of Kinotic's services, Anthropic and GitHub stand in for what Summit 42 calls; a test asserts on what
Summit 42 does with them, never on the fakes themselves. The exception is logic with no external collaborators
(parsing, guards, pure transforms): there a unit test localizes failures earlier and more precisely than any
boot-the-server test can, and needs no fakes to begin with.
