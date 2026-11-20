# GroovyConcurrentUtils change notes

History of changes, newest first. One section per version: what changed, why, what a consumer has to do.
Entries before 1.2.0 are reconstructed from commit messages.

## 1.3.0 (2026-11-20): safe defaults for HTTP, files, remote actors and the cluster

Source: release 2 of the hardening plan, project doc `gcu/brief-core-hardening-2026-11-18` (rev 6), decisions D3, D9, D10, D11.
This release changes defaults. Code that relied on the old ones has to say what it needs: see "What you must change".
Tests: 2169 passing, 0 failing (2132 before, plus 37 in four new specs). All but one of the 37 were run red on 1.2.2; the one that passed is a guard that an unsecured cluster configuration is unchanged.

### What you must change

Only if you use the capability named. The MCP servers (AW, CS, FS) use none of the four in their main source.

1. **`HttpTask` calling a local service.** A URL on `localhost`, `127.0.0.1` or `::1` is refused unless the flow names the host and port: `allowLocal 'localhost:8082'` on the task, or once in the graph for every `HttpTask` in it.
2. **`HttpTask` calling a host on a private network** (10.x, 172.16-31.x, 192.168.x, link-local, IPv6 unique-local, carrier NAT). Refused. Mark the task `trusted true`, which turns the guard off for that task and is logged. `allowLocal` does not open private networks.
3. **`HttpTask` following redirects.** Five at most; a sixth fails the task (the last redirect response used to be returned). A redirect that leaves the origin is followed without the task's credentials. A redirect to an address the guard refuses fails the task.
4. **`FileTask` with no `securityConfig`.** It can now reach only the graph's working directory (the JVM's if the graph names none). Name what else it needs: `allowedDirectories ...` on the task or the graph, `workingDirectory ...` on the graph. A named directory must exist. For the old behaviour, `trusted true` on the task or `securityConfig FileTaskSecurityConfig.permissive()`.
5. **A remote actor transport started as a server.** It listens on `127.0.0.1`. To be reached from another machine set `transport.bindAddress` before `start()`, and either pass an `AuthConfig` with `enabled` true or set `transport.insecure = true`; without one of those it refuses to start.
6. **Actors reached through a transport.** Only actors marked remote are delivered to: `remote true` in the actor builder, or `system.markRemote('name')`. Any other actor is answered as not found.
7. **A cluster started with a `HazelcastSecurityConfig` whose task results or messages are application classes.** Java deserialisation in the cluster is now limited to an allow-list. Name your packages in `serializationAllowedPrefixes`, each ending in a dot.
8. **`HttpTaskSecurityConfig` with a wildcard host.** `*.example.com` now matches subdomains only. It used to match any host ending in `example.com`, and the bare domain. Add `example.com` itself if you need it.

### Changed

HTTP (D3)

- **`HttpTask` checks where a request is going.** Before every request and every redirect hop: the scheme, then every address the host resolves to. Public hosts pass; loopback needs `allowLocal`; private ranges and multicast are refused; cloud metadata addresses are always refused, named or not. Before 1.3.0 the guard configuration existed and `HttpTask` never called it.
- **Redirects are followed by `HttpTask` itself**, one hop at a time, so that each is checked. 303, and 301 or 302 on a POST, become a GET with no body. HTTPS to HTTP is not followed.
- **Credentials stay with the origin.** After a redirect to another scheme, host or port, the task's `auth` settings and its `Authorization`, `Proxy-Authorization` and `Cookie` headers are not sent.
- New on the task: `allowLocal`, `trusted`, `securityConfig(HttpTaskSecurityConfig)`. New on the graph DSL: `allowLocal`. New: `HttpTaskSecurityConfig.standard()`, `checkRequest(uri, allowLocal)`.
- A URL the guard refuses is a `SecurityException` and is not retried.
- `HttpTaskSecurityConfig.permissive()` no longer checks the `ENVIRONMENT` variable.

Files (D9)

- **An unconfigured `FileTask` is strict.** It used to get the permissive configuration: no path limit, no symlink check, no size limit. It now runs under the strict limits (no symlinks followed, 100 MB a file, 1 GB and 10,000 files an operation, depth 50) inside its scope.
- New on the task: `allowedDirectories`, `trusted`. New on the graph DSL: `workingDirectory`, `allowedDirectories`.
- `FileTaskSecurityConfig.permissive()` no longer checks the `ENVIRONMENT` variable. It refused when that said production, while an unconfigured task was given the permissive configuration regardless.

Remote actors (D10)

- **Loopback by default.** The HTTP and RSocket server transports bound every interface.
- **A wider address needs authentication**, or `insecure = true`. Without TLS on a wider address the transport warns.
- **Only actors marked remote are reachable.** Any actor could be messaged by name. An unmarked actor and a missing one now get the same answer.
- The HTTP transport's worker pool is bounded (32 threads, a queue of 256, then the dispatch thread). It was unbounded.

Cluster (D11)

- **Task events are signed.** With `messageSigningEnabled` and a key in the security configuration, TaskGraph signs each task event it publishes. `ClusterTaskEvent.sign` and `verify` existed before and nothing called them.
- **A checked way to receive them.** `HazelcastManager.addTaskEventListener` delivers only events whose signature verifies; unsigned and altered events are dropped and logged. The library has no listener of its own on the topic.
- **A deserialisation allow-list** with a security configuration: GCU's cluster classes, Hazelcast's defaults, and `serializationAllowedPrefixes`. This is in the open-source edition of Hazelcast.
- **`initialize` with one argument warns** that the cluster has no security settings. Its behaviour is otherwise unchanged.

### Limits to know

- **The HTTP guard checks resolved addresses, not the socket.** The JDK HTTP client does not expose the address it connected to. The guard checks every address the JVM has just resolved, and the connection then uses the JVM's address cache (30 seconds by default). Running with `networkaddress.cache.ttl=0` removes that protection.
- **Signing covers the task event topic only.** The shared maps and actor lifecycle events are not signed. A member that has joined can write to them.
- **The deserialisation filter does not cover the member join handshake.** TLS and member authentication remain Hazelcast Enterprise features. Run a cluster on a network you control; the security controls chapter describes running members over an encrypted private network.
- All of this reduces what a steered flow can reach. It is not isolation: flows still run inside the JVM.

### Not verified

- No cluster was started: signing and the filter were tested on the manager's methods and the built `Config`. That TaskGraph's published events arrive signed on another member is not tested.
- The one-argument `initialize` warning has no spec (it needs a running Hazelcast instance on a shared singleton).
- Of the 36 red tests, 10 were red for the old behaviour itself; 26 were red because the new DSL or API did not exist, which does not prove the old behaviour. For the HTTP guard two mutation checks were run and both were caught (checking only the first hop; sending credentials across origins). No other mutation checks were run.
- AW and FS have not been run against 1.3.0.

### Documentation

- Security controls chapter: HTTP, files, remote transports and cluster are now "done (1.3.0)", each section rewritten from plan to behaviour. Actors, clustering, TaskGraph security and execution integrity chapters updated.

## 1.2.2 (2026-11-20): defects in TaskContext, dataflow, actors and tokens; opt-in result redaction

Source: release 1 of the hardening plan, project doc `gcu/brief-core-hardening-2026-11-18` (rev 5). Nothing here needs a consumer to change code.
Tests: 2132 passing, 0 failing (2110 before, plus 22 in `Hardening122Spec`: 15 features, two of them run once per overload). All 22 were run red on 1.2.1 before the fixes; the first failure was M3, the builder's config not on the context.

### Fixed

TaskGraph

- **`TaskContext.builder().config(...)` was ignored (M3).** `build()` read the map and then created the context from the default configuration. The context now has the config it was given. A null or empty map still loads the default.
- **Lazy caches were created more than once (M4).** `getIdempotencyCache()`, `getMemoCache()` and `getDeadLetterQueue()` were an unguarded check-then-create. Tasks starting together were each handed a different object and all but one were lost. In the red test, 8 threads asking a fresh context got more than one instance in 570 of 600 cases. Each is now created at most once per context.

Promises and dataflow

- **A promise had two states (DF7).** `DataflowPromise` kept its own state beside its variable's and moved it first, so `isDone()` could be true while `get()` still blocked. The promise now reads its state from the variable. A side effect: two listeners per promise that only kept the second state in step are gone.
- **Derived promises were never released (DF9).** A promise kept every promise derived from it (`then`, `flatMap`, `filter`, `accept`) for its whole life. Each is now dropped when it completes.
- **A listener due after pool shutdown was dropped (DF11).** It was logged and never ran, which for a promise callback means a node that never finishes. It now runs on the calling thread.
- **Blocking reads had no timeout (DF12).** `Dataflows` property and index reads, and method calls forwarded by a `DataflowVariable`, waited for ever on a name nobody binds. Both now take an optional `readTimeout`; unset, they wait as before.
- **A task's own `InterruptedException` interrupted the reader (DF13).** `DataflowPromise.get()` could not tell the task's error from an interrupt of the waiting thread and set the waiter's interrupt flag. The flag is now set only when the waiter was interrupted. The exception the caller sees is unchanged.
- **Only one `task` overload could be cancelled (DF15).** `DataflowFactory.task` for `Callable`, `Supplier`, `Runnable` and `Function`, and all three `DataflowVariable.task` forms, now keep the handle of their work, so `cancel(true)` interrupts it; and work that ends after its variable was completed elsewhere no longer throws from inside its own error handler.

Actors

- **`setMaxMailboxSize` on a running actor stopped it processing (A5).** The mailbox was replaced while the loop waited on the old one. The loop now moves to the new mailbox, queued messages are carried over in order, and a message sent during the change is not lost.

Security services

- **A signed token with no `exp` gave a `NullPointerException` (S3).** `JwtTokenService.validateToken` now throws `InvalidTokenException`, as it does for every other token it will not accept.

### Added

- **Result redaction, off by default (M1).** `TaskContext.resultRedactor` is a closure, `(value)` or `(taskId, value)`, that returns the form of a task result that may be shown. When set it is applied to the library's own log lines that print a result, to the result stored in a persisted snapshot, and to a new field `TaskEvent.redactedResult`. `TaskEvent.result` is never altered. A redactor that throws hides the value. `ctx.redact(taskId, value)` applies it from application code.
- `Dataflows.readTimeout` and `DataflowVariable.readTimeout` (DF12, above).
- `TaskContext(pool, promiseFactory, config)` constructor; `TaskEvent.Builder.redactedResult(...)` and a `TaskEvent` constructor taking it.

### What a consumer may notice

None of these needs a change. They are the places where behaviour differs from 1.2.1.

1. A context built with `TaskContext.builder().config(map)` now uses `map`. Code that passed a config there and was unknowingly running on the default will see its own settings take effect.
2. A listener or promise callback that falls due after its pool was shut down now runs, on the completing thread. Before, it did not run at all.
3. `setMaxMailboxSize(n)` with fewer than the queued number of messages throws `IllegalStateException` and changes nothing. Before, it threw after emptying the mailbox.
4. `validateToken` on a token with no `iat` returns claims with `issuedAt` null. Before, it threw a `NullPointerException`.
5. `df.readTimeout` on a `Dataflows` is now the timeout setting, not a dataflow variable of that name. Reach such a variable as `df['readTimeout']`.
6. `cancel(true)` on a promise wrapping a variable started by any `task` form now interrupts the work. 1.2.0 made this true for the closure form of `DataflowFactory.task`, which is the one `executeAsync` and TaskGraph use; that path is unchanged.

### Left as it is, by decision

- **DF10.** `DataflowFactory.task` still submits to the pool's executor directly, outside `ConcurrentPool` admission control and metrics. Routing it through `execute` deadlocks a task that starts another task and waits for it under a `maxInFlight` limit, which is how TaskGraph nodes run. The dataflow chapter says so.

### Not verified

- No mutation checks were run; the red run on 1.2.1 is the evidence that each spec fails without its fix.
- Redaction of persisted snapshots has no spec of its own (it needs a live EclipseStore). The redaction of log lines and events is covered.
- AW has not been run against 1.2.2. Most of this release is on the path AW runs daily: its suite is the acceptance test.

### Documentation

- Dataflow, promises, actors, security services, TaskContext, observability and execution integrity chapters describe the changes. The security controls chapter has a new row and section for result redaction.

## Security controls chapter and hardening plan (2026-11-20) — documentation only, version stays 1.2.1

- **New chapter, "Security Controls: What Is Enforced and What Is Planned"** (`docs/asciidoc/chapters/12-security-controls.adoc`), in both books. For scripts and rule conditions, shell commands, HTTP calls, file operations, remote actor transports, the cluster, Java deserialisation and configuration files it states what limits each one today, the agreed design, and the release that delivers it, with a status table and a list of how to use the library safely at this version.
- **Every release that changes a control updates that chapter**: its row in the status table and the section that describes it.
- **Agreed plan** (project doc `gcu/brief-core-hardening-2026-11-18`, rev 5): 1.2.2 defects; 1.3.0 safe defaults (`HttpTask` guard, `FileTask` default, remoting defaults, cluster signing); 1.4.0 restricted evaluation for `ScriptTask` and `ControlTask` plus a `ShellTask`; 2.0.0 module split with a `gcu-taskgraph` jar and `GroovyConcurrentUtils` kept as an aggregate.

## 1.2.1 (2026-11-20) — second hardening pass: actors, agents, serialisation, cluster settings, config, timers

Source: second security, completeness and robustness review, project doc `gcu/review-2-2026-11-20`. It read the parts the first review did not (actors, agents, pools, the rest of promises, config, security services, cluster, remoting) and took in the findings made while writing the book.
Tests: 2110 passing, 0 failing (2093 before, plus 17 in `Hardening121Spec`). All 17 were run red on 1.2.0 before the fixes.

### Fixed

Actors

- **An actor stopping itself deadlocked (A1).** A supervision `STOP` directive, reaching the restart limit, and `ESCALATE` with no parent all called `stop()` on the mailbox loop's own thread, which then waited for the loop to end. The actor never terminated and the caller of the failing `ask` waited out its timeout. `stop()` on the loop thread now records the stop; the loop finishes it when the handler returns, and the caller gets the handler's error at once.
- **`stop()` dropped queued messages (A3).** Documented as graceful, it processed only the message in hand and discarded the rest of the mailbox. It now processes everything queued before the stop.
- **Asks left in the mailbox waited for their timeout (A3).** When an actor is force-stopped or stops itself, asks that will never be processed now fail immediately with `IllegalStateException`.
- **The text `__STOP__` killed the mailbox loop (A4).** The stop marker was a string any sender could send, after which the actor accepted messages and never processed them. It is now a private object compared by identity.
- `stop(timeout)` now notifies death-watchers when it completes, as `stop()` does.

Agents

- **`getStateVersion()` counted reads (G1).** It advanced on `getValue()` and never on an update. It now advances once per update that completes and is unchanged by reads.

Remoting

- **Unfiltered Java deserialisation (S1).** `JavaSerializer.deserialize` read any class from the bytes it was given. It now reads through an allow-list: JDK value types and `GString` by default, plus package prefixes passed to `new JavaSerializer([...])`. Anything else is refused with `SerializationException`; depth, reference count and array length are capped.

Cluster

- **`HazelcastSecurityConfig` was never applied (C1).** `HazelcastManager.initialize` built its configuration from the cluster map alone. New `initialize(clusterConfig, security)` and `HazelcastManager.buildConfig(clusterConfig, security)` validate and apply the settings: bind address, port, port auto-increment, member allow-list, multicast only if allowed, TLS and member authentication. With a security config there is no fallback to multicast and a start-up failure is thrown instead of logged.

Configuration

- **External sources that were written but not wired (F1).** `ConfigLoader.load` read external files only from the `externalFiles` option. It now also reads files named by `-Dapp.config` and `config[-profile].*` in directories from `-Dapp.configDir` and the `externalDirs` option. `app.config` and `app.configDir` are no longer copied into the settings as keys.

TaskGraph

- **`TaskBinding.set(key, null)` threw (M2).** Setting null now removes the key, for `set` and for `binding[key] = null`.

Pools and promises

- **Cancelled timers stayed queued (P3).** A timeout timer cancelled because its task finished stayed in the shared scheduler, holding the task's future and result, until the timeout would have fired. The shared scheduler now removes cancelled timers at once. This covers `executeWithTimeout` and `Promise.timeout`.
- **`orTimeout` never cancelled its timer (DF8).** It now does, when the promise completes.
- **`Promises.from(CompletableFuture)` kept the wrapper (DF14).** A failed future's `CompletionException` is unwrapped; the promise fails with the real cause.

Build

- POM name, description, URL and SCM defaults said GroovyFX. They now describe this library.

### Behaviour changes a consumer can see

1. `actor.stop()` takes as long as the queued messages take to process. Use `stop(timeout)` or `stopNow()` to bound it.
2. An ask queued on an actor that is force-stopped fails with `ExecutionException` (cause `IllegalStateException`) and not `TimeoutException`.
3. `new JavaSerializer()` refuses application classes on deserialise. Pass the package prefixes of your message classes to the constructor.
4. `agent.stateVersion` no longer changes on reads.
5. A JVM started with `-Dapp.config=...` or `-Dapp.configDir=...` now loads those files, and the settings map no longer contains the keys `config` and `configDir` from them.
6. `binding.set(k, null)` removes `k` where it used to throw `NullPointerException`.
7. `HazelcastManager.initialize(config)` with one argument is unchanged.

Nothing on the TaskGraph execution path changed except M2 and the timer release. AW should still be run against 1.2.1 before it is adopted.

### Documentation

- The book describes the changed behaviour in the actors, agents, clustering, configuration, promises and pools chapters.
- **Second book.** `docs/asciidoc/taskgraph.adoc` builds the TaskGraph chapters on their own (`groovy-concurrent-utils-taskgraph.pdf`) from the same chapter files as Part V of the full book.

### Not in this release

Still open, with the decisions they wait on, in `gcu/brief-core-hardening-2026-11-18` (rev 4): restricted script evaluation (D2), the `HttpTask` guard (D3), `FileTask`'s permissive default, result redaction, `TaskContext` lazy caches and `Builder.config`, dataflow items DF7, DF9 to DF13 and DF15, and from this review: remote transports bind to all interfaces and accept unauthenticated callers by default, `setMaxMailboxSize` on a running actor strands the loop on the old queue, `actor << msg` is a blocking ask, `ErrorMode.RETRY` only logs. TLS and member authentication for the cluster need Hazelcast Enterprise.

One existing test, `ManualTaskTest.testManualTaskDSLWithNotificationsAndEscalation`, failed once in three full runs today (before any change) and passed in the other two. It is timing-dependent and was not touched.

## Documentation restructure (2026-11-20) — no code change, no version change

The documentation now follows the same layout as the MCP server projects: one book, `docs/asciidoc/index.adoc` plus `chapters/`, built to HTML (`gradlew asciidoctor`) and PDF (`gradlew asciidoctorPdf`, output `build/docs/asciidocPdf/groovy-concurrent-utils-reference.pdf`).

- **New book, "GroovyConcurrentUtils: Capabilities and Reference".** Parts I to IV are new chapters, one per capability, written against the 1.2.0 source: overview, pools, promises, dataflow, Gstream, reactive channels, actors, agents, configuration, security services, clustering, plus a glossary and a history map.
- **TaskGraph guide moved in as Part V.** The 17 chapters moved from `src/main/groovy/org/softwood/dag/docs/asciidoc/chapters/` to `docs/asciidoc/chapters/tg-*.adoc`. Content unchanged except: hard-coded "Chapter N" references became cross-references (the chapters renumber inside the larger book), a duplicate anchor in the TaskGraphSpec chapter and a reference to a missing image were removed.
- **Superseded material archived.** The capability readmes from `docs/`, the implementation notes that lived under `src/main/groovy`, and the old TaskGraph index and generated PDF are under `archive/`. They are no longer maintained.
- **Build.** `org.asciidoctor.jvm.pdf` 4.0.5 added; AsciidoctorJ pinned to 3.0.1 (the plugin default was 2.5.7 on JRuby 9.3.8.0); the tasks convert `index.adoc` only; the reference to a stylesheet that did not exist is gone; JVM flags stop the JRuby native-access and `sun.misc.Unsafe` warnings on JDK 25. The redundant `repositories` block in `build.gradle` is removed (settings.gradle declares them). A docs build went from 28 warnings to none.

Found while writing the chapters from the source. The first two and the last were fixed in 1.2.1 (above); the others are documented in the book as they behave:

- `HazelcastManager.initialize` does not apply `HazelcastSecurityConfig`: a cluster started through it has no TLS and no member authentication, and uses multicast when no member list is given.
- `Agent.getStateVersion()` advances on `getValue()` (a read), not on updates.
- `actor << msg` is a blocking `askSync` with a five-second timeout, not a send. Local `Actor.ask` blocks; `RemoteActorRef.ask` returns a `CompletableFuture`.
- `ErrorMode.RETRY` in the reactive package only logs.
- The POM defaults in `build.gradle` (`pomName`, `pomDescription`, `pomUrl`, SCM) still say GroovyFX.

## 1.2.0 (2026-11-18) — core hardening: dataflow delivery, graph validation, task state, cancellation

Source: security review and dataflow review of 2026-11-18, brief `gcu/brief-core-hardening-2026-11-18` (WP1 and WP2, plus the dataflow findings the review added).
Tests: 2093 passing, 0 failing (2075 before, plus 12 in `CoreHardeningSpec` and 6 in `DataflowIntegritySpec`). Every new test that guards a fix was run red on 1.1.2 first.

### Fixed

Dataflow (the layer every TaskGraph result and callback travels through)

- **Lost callbacks (DF1).** A listener registered while a `DataflowVariable` was being bound could be missed for ever. Registration is now decided under the completion lock. Affects `whenBound`, `whenAvailable`, `whenError`, `then`, `recover`, `toFuture` and every `DataflowPromise` callback and combinator built on them, including `Promises.all`. Measured before the fix: 41 of 20,000 racing registrations never delivered.
- **`get()` that never returned (DF2).** `DataflowPromise.get()` and `get(timeout, unit)` read the variable's own future instead of a second future completed by listeners. Measured before the fix: 3 of 20,000 calls racing `accept()` timed out with the value already set.
- **Error state visible before its error (H5).** `setError` and `setCancelled` assign the error before the state. The substitute "Error state detected but error value is null" exception is gone, and so are the three `Thread.sleep(10)` retry workarounds for it in `TaskBase` and `ServiceTask`.
- **`cancel(true)` did not interrupt (DF3).** A promise from `executeAsync` now holds its pool `Future`; `cancel(true)` interrupts the running work, `cancel(false)` leaves it to finish.
- **Unbounded listener error list (DF4).** Capped at 100 entries as documented.
- **`Dataflows` wiring (DF5).** Assigning a failed variable to another now fails the target instead of binding `null`.
- **Late task completion (DF6).** A task that finishes after its variable was completed elsewhere is logged at debug instead of throwing into a discarded `Future`.

TaskGraph and TaskBase

- **Graph validation (H1).** New `TaskGraph.validate()`, called by `start()` before the graph is consumed. A dependency cycle, a graph with no root, or an edge to a task that does not exist is refused with an `IllegalStateException` naming the tasks. All three used to return a promise that never resolved.
- **Guarded task state (H3).** `TaskBase` state assignments go through `transitionState()`. A CANCELLED or SKIPPED task can no longer be overwritten with RUNNING, COMPLETED or FAILED by its own worker.
- **Single outcome (H6).** A run's outcome is claimed once, by completion, `abortFlow()` or graph timeout. `GRAPH_COMPLETED` / `GRAPH_FAILED` is emitted exactly once. Measured before the fix: two terminal events in the majority of 150 runs of a two-leaf graph. `start()` is claimed atomically as well.
- **Cancellation with correct state (H2).** `abortFlow()` and a graph timeout set a cancellation signal on the `TaskContext`. No new attempt starts, a retry wait ends without retrying, a cancelled task whose work finishes later stays CANCELLED and schedules no successor, and no terminal graph event follows an abort. A graph timeout now also resolves `graphCompletionPromise`, not only the promise `start()` returned.
- **Interrupts and JVM errors (H4).** An `InterruptedException` or `CancellationException` (direct or as a cause) ends the task as CANCELLED and is never retried. `VirtualMachineError` and `LinkageError` fail the task without retry.

### Added

- `TaskGraph.validate()`.
- `TaskContext.requestCancel()`, `isCancelRequested()`, `clearCancel()`.
- `interruptOnTimeout true` on a task and `interruptOnCancel true` on a graph: opt-in interruption of work that is already running. Both default to false.
- `DataflowVariable.attachTask(Future)` and `cancelTask(boolean)`.
- `TaskBase.interruptInFlight()`.

### Behaviour changes a consumer can see

1. `start()` throws for a graph that cannot complete. Previously it returned a promise that hung.
2. `promise.cancel(true)` on an `executeAsync` promise interrupts the work. Pass `false` for the old behaviour.
3. A task that was cancelled reports CANCELLED, not COMPLETED or FAILED, and a graph containing a cancelled task never reports `GRAPH_COMPLETED`.
4. After `abortFlow()` no `GRAPH_COMPLETED` / `GRAPH_FAILED` event is emitted. The aborting caller owns the terminal report.
5. An interrupted or cancelled task is not retried and is not written to the dead letter queue.
6. `task.state = X` is ignored when X is not a legal transition, except an explicit reset to CREATED or SCHEDULED. `execute()` on a COMPLETED or FAILED task restarts it.
7. `DataflowExpression.getError()` no longer returns a substitute `IllegalStateException`.

Unchanged by default: a timed-out or aborted task's running work is still left to finish unless the new opt-in flags are set.

### Not in this release

Still open from the reviews: the script "sandbox" is not isolation, `ControlTask` evaluates unrestricted Groovy, `HttpTask` does not apply `HttpTaskSecurityConfig`, `FileTask` defaults to a permissive security config, results are unredacted in logs/events/persistence, `TaskBinding.set(key, null)` throws, and dataflow items DF7 to DF15 (see `gcu/dataflow-review-2026-11-18`).

### Documentation

- New chapter `16-execution-integrity.adoc`: validation, task state, how a run ends, cancellation, timeouts, retries, dataflow delivery guarantees, known limits.
- `09-security.adoc`, `01-architecture.adoc`, `11-taskbase.adoc` and `index.adoc` corrected where they described behaviour the code does not have.

### Verification

See the release record in the project doc `gcu/release-1.2.0` for the test counts, the AW compatibility run and what was not verified.

## 1.1.2 (2026-11-16) — Groovy 6.0.0

- Groovy 5.0.3 to 6.0.0; Spock 2.5-groovy-6.0.
- `GraphEventListener.getName()` uses `getClass()`: `this.class` inside an interface default method throws on Groovy 6.
- GroovyCompile worker heap raised to 4g for the test source set.
- No API change. 2075 tests.

## 1.1.1

- `IBinding.getPage(offset, size)` default and `TaskBinding` override; `TaskBindingSpec`.
- Task-context chapter: paging section.

## 1.1.0

- Context explosion protection.
- `ITaskProvider` SPI extension pattern documented.
