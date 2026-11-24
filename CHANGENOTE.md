# GroovyConcurrentUtils change notes

History of changes, newest first. One section per version: what changed, why, what a consumer has to do.
Entries before 1.2.0 are reconstructed from commit messages.

## 2.0.3 (2026-11-24): a validation-only xlsx range adds no empty rows; queries skip blank rows

Source: MCP FS 0.11.4's live checks after install (session 2026-11-23-11-14, Will chose "fix now, then baseline"). A plan range with only a data validation and a conditional format (`B2:B20`) created an empty cell in every row of the range; a query then returned 16 blank rows, and a map_by_key query handed FS a null key. Changes are in `gcu-taskgraph` only.
Tests: 2825 tests, 0 failures, 46 skipped. Three new tests in `Hardening201OfficeSpec` (50 in all), all red on 2.0.2. Mutation checks, all caught (1 red each): cells written regardless of what the range holds; blank rows kept; an empty map_by_key key allowed.

### What you must change

1. **A query no longer returns rows that are blank in every column it returns** (null or whitespace-only text). Code that counted rows to find the end of formatted space should read the sheet instead.
2. **A map_by_key row whose key cell is empty is refused**, naming the spreadsheet row(s): `QueryExecutor: map_by_key: keyColumn 'name' is empty in row 3`. 2.0.2 put it under a null key.

### Fixed

- **A range creates cells only when it writes something** (a style, zebra stripes, values, a formula, a column formula). A range with only a validation, a conditional format, borders or a comment covers the cells without creating them (before 2.0.3 an empty cell went into every row of the range, so `lastRowNum` was the range's last row).

## 2.0.2 (2026-11-23): an xlsx map_by_key query works

Source: found by MCP FS 0.11.4's new integration spec the same evening (session 2026-11-23-11-14), after 2.0.1 was published to mavenLocal; 2.0.1 is not changed, so this is 2.0.2. Changes are in `gcu-taskgraph` only.
Tests: 2822 tests, 1 failure, 46 skipped: the one failure, `ParallelGatewayTaskTest.testParallelGatewayInGraph`, passed 9 of 9 alone three times; it appends to an unsynchronised list from two parallel branches (a test race, not touched here). Two new tests in `Hardening201OfficeSpec` (47 in all), both red on 2.0.1. Mutation check: the key-column guard switched off, caught (1 red).

### What you must change

1. **`XlsxTask.query(QueryPlan)` and `QueryExecutor.execute` are declared `Object`** (were `List`): a List for list_of_maps and list_of_lists, a Map keyed by `keyColumn` for map_by_key. Code that assigned the result to a `List` variable keeps working for the list formats.
2. **A map_by_key `keyColumn` that is not a header, or is left out of `columns`, is refused by name** before any row is read.

### Fixed

- **map_by_key never worked:** `QueryExecutor.execute` was declared `List` and returned the Map it built, so every map_by_key query failed with a GroovyCastException; `XlsxTask.query` would have turned the Map into a list of entries with `as List`.

## 2.0.1 (2026-11-23): office adapters apply every plan field or refuse it by name

Source: Will, 2026-11-23 17:00 and 17:09 (session 2026-11-23-11-14): fix the office path and give it real integration tests, and "mend gcu as well if that's not actually working". Found while making MCP FS drive the GCU plans: much of the office model was accepted by the DSL and the plan objects and then silently not written.
Changes are in `gcu-taskgraph` only; the other modules are rebuilt at 2.0.1 unchanged.
Tests: 2820 tests, 0 failures, 46 skipped: the 2.0.0 suite (2775) plus `Hardening201OfficeSpec` (45). No existing test changed.
Red first: new `Hardening201OfficeSpec` (45 tests). On the 2.0.0 source 42 of the first 43 were red, each for the old behaviour; the green one, landscape page setup, is existing behaviour kept as a guard. The query-columns test (added later) was red with its guard disabled. Mutation checks, all caught (red count of 44 or 45): atomic write 33, refusal guard 5, row/default style 2, data validation 2, conditional format 3, pptx notes 1, pptx layout lookup 1, docx numbering 1, XlsxTask memory data 1, DSL values cells 2, DSL named-argument row 2, query unknown columns 1.

### What you must change

1. **A plan that used a field GCU cannot apply now fails** with `IllegalArgumentException: <Adapter>: nothing written -- <every problem>`, where 2.0.0 wrote the file without it. Refused: pptx `layout { theme }`, `layout { accentColor }`, named pptx `style(...)`, `CHART` shapes, a `TABLE` shape with no table, an unknown slide layout; a missing `styleRef`; an unknown validation or conditional-format type or operator; workbook options other than `createIfMissing` / `evaluateFormulas`; docx `properties` keys other than author, subject, description, category, keywords, company; a page size other than A4, A3, LETTER, LEGAL. Remove the field, or (theme) start from a template.
2. **`deferredRows` need their data:** pass `config.data` (dataKey to rows) to the adapter; XlsxTask passes working memory (`sharedFacts`, then `runContext`), then a Map previous value. 2.0.0 ignored deferred rows.
3. **A missing image now fails** (cell `imagePath`, IMAGE shape) instead of being skipped.
4. **A query `columns` entry that is not a header or a letter inside the range fails**, naming it and the headers; 2.0.0 dropped it.
5. **docx page setup needs `poi-ooxml-full` at run time** (exclude `poi-ooxml-lite`). Under lite it now throws an `IllegalStateException` that says so; 2.0.0 swallowed the `NoClassDefFoundError` and skipped the page setup.
6. **XlsxTask's flat actions now work:** `CREATE`, `WRITE`, `APPEND`, `TRANSFORM` (with `rowTransformer`); `useLlmTransform` throws `UnsupportedOperationException`. In 2.0.0 only `READ` did anything.

### Fixed (each was silently dropped or wrong in 2.0.0)

- **xlsx:** a row `styleRef` reached no cell; the sheet `defaultStyle` was ignored; column `styleRef` and `outlineLevel` ignored; range `dataValidation`, `conditionalFormat` (cellValue, formula, colorScale, dataBar, iconSet), `zebraStripe` and `comment` never applied; `deferredRows` never written; cell `imagePath` ignored; `rowGroups` / `columnGroups` now take `2:5`, `2..5`, `[2, 5]` or an `IntRange`.
- **pptx:** speaker notes dropped behind a caught exception (and READ now returns them); `resolveLayout` always returned null (a `return` inside an `each` closure), so a named layout was never used; RECTANGLE, OVAL and LINE were written as text boxes; the presentation title and properties not set; text-shape bold, italic, underline, font, size, colour and fill ignored; `defaultLayout` (background, fonts) ignored; transition `durationMs` ignored (now sets the speed).
- **docx:** heading paragraphs used styles the document did not define (Word showed body text); bullet and numbered lists had no numbering; table `headerStyle`, `bodyStyle` and `columnWidths` ignored; a section `style` font ignored; `category` and `company` properties dropped.
- **XlsxTask:** the `workbook { }` DSL was never wired to the adapter; only READ worked.
- **DSL named-argument forms** (`row 1, values: [...]`, `cell 'A1', value: 1`, `column 'A', width: 20`, `range 'A1:B2', style: 'x'`, `run 'x', bold: true`) threw `MissingMethodException`: Groovy passes the Map first. `values:` strings were taken as cell references ("Cell index must be >= 0").
- **Writes are atomic** (`OfficeFiles.writeAtomically`: a temporary file beside the target, then a move), so a failed write leaves the old file.
- **Javadoc:** the `PresentationDsl` example no longer shows `theme`, `accentColor` or a named style.
- **Book:** "Office Document DSL" chapter: new section "Applied or Refused (2.0.1)", the pptx example and transition note corrected, the POI dependency block names `poi-ooxml-full`.

## 2.0.0 (2026-11-23): five modules and an aggregate, the new baseline; one meaning per actor send (WP9)

Source: D5 in the 2026-11-20 decisions, and Will on 2026-11-23: the promises library as its own jar, TaskGraph as its own jar, the remaining parts as their own jars, and a fat jar of everything; the books restructured for the multi-project build; the whole project at 2.0.0 as the new baseline. Then (14:24) WP9, the API consistency work, into the same release, with Will's choices: `ask` returns a future, `<<` waits and `>>` sends, `ErrorMode.RETRY` made to work.
The split changes no behaviour: every class keeps its package. WP9 changes three behaviours, in actors and reactive topics only: see "What you must change" 4 to 6.
Tests: 171 result files, 2775 tests, 0 failures, 46 skipped (root 681, gcu-core 554, gcu-cluster 16, gcu-taskgraph 1314, gcu-actors 192, gcu-reactive 18). That is the 1.4.2 suite redistributed (169 files, 2763 tests, as at 1.4.2; in the split-only run one known `ManualTaskTest` timing failure, 9 of 9 green alone) plus `Hardening200ActorSpec` (7) and `Hardening200RetrySpec` (5). Two existing test classes changed: the three stand-in actors in `ActorSystemTest` implement the new `Actor` methods, and `ActorSupervisionIntegrationTest` calls `askSync` where it used the old blocking `ask`.
Red first: actor spec 6 of 7 red on the split source (the seventh, that `askSync` and `<<` still wait, is the unchanged behaviour); retry spec 3 of 5 red, two for the old behaviour itself (the topic ended by one undeliverable event; the "no retry handler" message) and one because the retry did not exist, while the two green ones show that such a publish does reach the strategy. Mutation checks, both caught: no timeout on the local `ask` future (1 red), the RETRY branch switched off (1 red).
`allJar` holds the same 1636 classes as the 1.4.2 jar (checked on the split source). The fat jar ran a promise and a two-task graph on a bare JVM.

### What you must change

1. **Nothing, if you depend on `org.softwood:GroovyConcurrentUtils`.** Change the version to 2.0.0. The artifact is now an aggregate whose POM depends on the five modules, so the same classes arrive in five jars instead of one. `testFixtures('org.softwood:GroovyConcurrentUtils:2.0.0')` still gives `TaskGraphSpec`.
2. **A Maven build that used the `test-fixtures` classifier** on `GroovyConcurrentUtils` names `gcu-taskgraph` instead (`<artifactId>gcu-taskgraph</artifactId><classifier>test-fixtures</classifier>`).
3. **A build that copied the single jar** (a `lib/` folder, a fat jar of its own) takes `GroovyConcurrentUtils-2.0.0-all.jar` from `gradlew allJar`, or the five module jars.
4. **A local `actor.ask(msg[, timeout])` that used the reply directly** now gets a `CompletableFuture`: call `askSync` (or `.get()` on the future). `ask` on a remote reference is unchanged. A class implementing `Actor` declares `CompletableFuture<Object> ask(Object, Duration)` and the new `Actor rightShift(Object)`.
5. **`actor >> msg`** used to fail (no such method); it now sends without waiting and returns the actor. `actor << msg` is unchanged: it waits for the reply.
6. **A `DataflowTopic` with `errorMode = ErrorMode.RETRY`** now retries a publish that no subscriber could take, and the publisher waits while it does (at most `maxRetries` pauses from `retryBackoff`, 25 ms doubling to 1 s). With any mode, such a publish no longer ends the topic (below).

### Changed (WP9)

- **`Actor.ask` returns `CompletableFuture<Object>`** on a local actor as on a remote reference (`GroovyActor.ask` blocked and returned the reply, `RemoteActorRef.ask` returned a future, the interface said `Object`). The future completes exceptionally with a `TimeoutException` after the timeout (default five seconds). `askSync`, `sendAndWait` and `<<` block, as before.
- **New `actor >> msg`**, on `GroovyActor` and `RemoteActorRef`: `tell`, returning the actor, so `actor >> 'a' >> 'b'` chains. The rule on actors is now the rule on agents: `<<` waits for the result, `>>` does not. The Javadoc's claim that `<<` is "GPars-compatible" is corrected (in GPars `<<` on an actor does not wait).
- **`ErrorMode.RETRY` works.** On a `DataflowTopic`, a publish that found no subscriber with demand (`FAIL_OVERFLOW`) is emitted again up to `maxRetries` times with a doubling pause from the new `retryBackoff`; only then does the strategy see it, and `DefaultErrorHandlingStrategy` logs "Retries exhausted" for RETRY (it logged "no retry handler implemented"). A completed or cancelled topic is not retried. `DataflowQueue` has no retry (an enqueue already waits `publishTimeout` for room).
- **Defect found doing this: one undeliverable event ended a `DataflowTopic` for everyone.** `publish` used `emitNext` with a failure handler; when the handler gives up on `FAIL_OVERFLOW`, Reactor terminates the sink with an overflow error, so every subscriber's stream ended and later events went nowhere. `publish` now uses `tryEmitNext`, which reports the outcome and leaves the sink running.

### Changed (module split)

- **The build is a Gradle multi-project build.** Modules, each published as `org.softwood:<module>:2.0.0`:
  - `gcu-core`: pools, promises (all three implementations, Vert.x included), dataflow, Gstream, configuration, agents, security services. The promises library: promises are built on dataflow and pools and read the configuration, so they cannot be separated from them.
  - `gcu-cluster`: `HazelcastManager`, its security settings and message validation, and the cluster state types for TaskGraph (`org.softwood.dag.cluster`) and actors (`org.softwood.actor.cluster`). Depends on gcu-core.
  - `gcu-taskgraph`: the TaskGraph engine, DSL, every task type (ShellTask and `WindowsJobObject` included), the script profile, EclipseStore persistence, resilience, sagas; and the test fixtures. Depends on gcu-core and gcu-cluster.
  - `gcu-actors`: actors and the remote transports, with the sample scripts and `Main`. Depends on gcu-core and gcu-cluster.
  - `gcu-reactive`: the reactive channels. Depends on gcu-core.
  - `GroovyConcurrentUtils`: the aggregate (no classes of its own).
- **Third-party libraries per module.** Each module declares only what it uses, as runtime dependencies (as the single jar did), except Vert.x in gcu-core and Hazelcast in gcu-cluster, which appear in GCU method signatures and so are compile dependencies. A consumer of gcu-core alone no longer receives Hazelcast, Netty, RSocket, Reactor, EclipseStore, Nashorn or Simple Java Mail.
- **Two jars of everything, built on request, not published:** `gradlew allJar` (every GCU class, no third-party classes, `build/libs/GroovyConcurrentUtils-2.0.0-all.jar`) and `gradlew fatJar` (GCU and every runtime dependency, service files merged, `-fat.jar`). Publishing either would put each class on a classpath twice next to the module jars.
- **Tests moved with their packages.** The cross-module suites (`Hardening*Spec`, the TaskGraph and actor clustering tests) are in the root project.
- Shared build settings (toolchain, the Groovy compiler heap, test platform, `--enable-native-access=ALL-UNNAMED` for test JVMs, a `--tests` filter that matches nothing in a module does not fail the run, POM and signing) are set once in the root `build.gradle`; `publishToMavenCentral` publishes all six artifacts.
- **Books:** new chapter "Modules and Building" in both books; the overview's capability table names each capability's module; each capability chapter says which module carries it; the TaskGraphSpec chapter's dependency setup corrected (it still named `groovy-concurrent-utils:1.0-SNAPSHOT`).
- **Diagrams are pictures now.** The nine PlantUML and two ditaa diagrams in the TaskGraph chapters printed their source text in both PDFs and the HTML (no diagram extension was configured). The docs build now uses asciidoctor-diagram 2.3.1 (PlantUML and ditaa bundled, PNG output, PlantUML's built-in Smetana layout so no Graphviz is needed: `docs/asciidoc/plantuml.cfg`). The module dependency sketch in "Modules and Building" is a PlantUML diagram too. Images are generated into `build/` only.
- **Part pages** (Will, 2026-11-23): a part still starts on a new page, but its title no longer has the page to itself; its first chapter follows on the same page, and every later chapter starts on a new page (`<<<` in the two book files, theme `heading.part.break-after: auto`, `heading.chapter.break-before: auto`). The library book is 412 pages (418), the TaskGraph book 361 (365).

### Not split (yet)

- The Vert.x promise implementation stays in gcu-core: `PromiseConfiguration` creates it by name.
- EclipseStore persistence and the Nashorn script engine stay in gcu-taskgraph: `TaskGraph` and `TaskGraphDsl` construct the persistence manager themselves.
- `TaskGraph` and `ActorSystem` call `HazelcastManager` directly, so gcu-taskgraph and gcu-actors depend on gcu-cluster and bring Hazelcast even with clustering off. Putting these three behind interfaces (the rest of D5) is later work.

## 1.4.2 (2026-11-23): nothing a shell run starts outlives it (Windows Job Object)

Source: the known gap recorded in 1.4.1, found with Git Bash; Will asked for it as a small release before 2.0.0, that a finished script should stop the background processes it left running, and that a task can say it means to keep one running until the graph ends.
This release changes a behaviour: see "What you must change".
Tests: 2763 run, 0 failing in the new and changed specs (2756 at 1.4.1, plus seven). In the final full run one `ManualTaskTest` escalation test failed on timing (`testManualTaskWithEscalationAndNotification`, a second test of that class with the known load-sensitivity); the class is 9 of 9 green alone. `Hardening141ShellSpec` has 57 (the 1.4.1 `@PendingFeature` is now an ordinary test, plus seven new).
Red first: on 1.4.1, 2 red because the old behaviour was wrong (a Git Bash background command and a PowerShell grandchild whose parent had exited both outlived the timeout) and 1 red because the switch did not exist; the two tests that a finished script's background processes are stopped, 2 red on the first 1.4.2 build, which still left them running; the three `keepBackground` tests, 3 red because it did not exist.
Mutation checks, three on the final behaviour, all caught: not putting the process in the job (1 red), letting the job go instead of ending it on a timeout (2 red), not ending the job when the script finishes before its output is read (1 red: a leftover holding the pipe delayed the result). The first two were run on the first 1.4.2 build; the code they change is the same in the final one. Two more for `keepBackground`, both caught: not ending kept processes when the graph ends (1 red), ending them when the script finishes regardless (1 red).

### What you must change

1. **A shell script that starts a background process meant to outlive it** (a server, a watcher). From 1.4.2 it is stopped when the script finishes, on Windows. Say `keepBackground true` on the task: it is then kept until the graph ends. A process meant to outlive the graph has to be run outside it.

### Changed

- **A Windows shell run is held in a Job Object** (`org.softwood.dag.task.shell.WindowsJobObject`). `LocalShellRunner` puts the process in a new job straight after it starts (and any child it has already started); a process started inside a job stays in it whatever happens to its parent. A timeout or a cancel ends the job, then walks the tree as before. 1.4.1 walked the tree only, which cannot reach a process whose parent has exited: Git Bash starts every command of a script through a fork stub that exits at once.
- **When the script finishes, the job is ended too** (Will, 2026-11-23): a background process the script left running is stopped, before the rest of its output is read, so a leftover holding the output pipe does not delay the result. In 1.4.1 it ran on, and held up the result by up to ten seconds while the output readers waited.
- **`keepBackground true`** on a `ShellTask` (Will, 2026-11-23): when the script finishes, a job that still holds processes is not ended but kept on the `TaskContext` until the graph ends, however it ends (completed, failed, aborted, timed out), or until the context is closed. The result's new `keptProcessIds` lists them (also in the audit record), so a later task can use or watch them. A timeout or a non-zero exit still stops them. Not offered to `process.shell(...)` in a restricted script. A kept process may hold the output pipe: the rest of the output is then read for 300 ms only.
- New: `ShellRequest.keepBackground`, `ShellResult.keptProcessIds` and `keeper`, `TaskContext.keepUntilGraphEnds` and `endKeptProcesses`; `TaskContext.close()` ends any still kept. A runner of a flow's own honours `keepBackground` by setting `keeper`.
- The job is made kill-on-close, so if the JVM dies during a run Windows ends the run's processes, kept ones included.
- The kernel32 calls go through the JDK's Foreign Function API: no new dependency. The JDK prints a one-time warning about native access unless the JVM is started with `--enable-native-access=ALL-UNNAMED`. If native access is refused, or a call fails, the run is logged once and falls back to walking the tree.
- New `LocalShellRunner.jobObjects` (default true) switches it off.

### Not verified

- Not on any operating system but Windows. Elsewhere, and where native access is refused, a timeout reaches only the processes linked to the run through their parents (a process group would be the equivalent), and a finished script's background processes are not stopped: once the script has exited its id may belong to another process, so the tree is not walked then. `keepBackground` has nothing to keep there, and `keptProcessIds` is empty.
- A sub-graph that shares its parent's context: kept processes belong to the graph that was running when they were kept, and are ended when it ends; a parent graph's end also ends any kept under a sub-graph that has already ended. Not tested with a sub-graph.
- A process started in the instant between the interpreter starting and being put in the job, other than a direct child, escapes it. bash and Python receive their script only after the job is set; PowerShell and cmd start nothing of the script's in that instant in any test run.
- A JVM already in a job that forbids nested jobs (pre-Windows 8) falls back to the tree walk; not tested.

## 1.4.1 (2026-11-23): ShellTask, allow 'process', allow 'sql', and capabilities reachable only through their methods

Source: release 3b of the hardening plan, project doc `gcu/brief-core-hardening-2026-11-18` (rev 8), decision D2 (remainder); design agreed with Will 2026-11-23 in project doc `gcu/design-1.4.1`.
This release adds capabilities that are off by default and closes one hole in 1.4.0. Code that relied on the hole has to change: see "What you must change".
Tests: 2756 run, 0 failing, 1 pending (2648 at 1.4.0, plus 108 in three new specs: 50 in `Hardening141ShellSpec`, one of them a `@PendingFeature` for the known gap below, 33 in `Hardening141SqlSpec`, 25 in `Hardening141CarriedSpec`). One 1.4.0 test changed on purpose: `allow 'process'` and `allow 'sql'` are no longer refused, so `Hardening140ScriptSpec` now checks the refusal of unknown names only.
Red first, on 1.4.0. `Hardening141ShellSpec` 46 of 46 red and `Hardening141SqlSpec` 31 of its first 31 red, all because the API did not exist; the two `old table (delete ...)` rows were added later, after a mutation check, and the four Git Bash tests after the servers' regression; neither has a red run. `Hardening141CarriedSpec`: 9 red because the old behaviour was wrong (a script could read and change what `files` and `http` hold), 15 green coverage tests of behaviour that was already right, 1 skipped (the symbolic link test needs a Windows account that may create links).
Mutation checks, eleven runs, nine caught at once: `allow 'process'` (1 red), the interpreter list (2), the tripwires (19), the environment scrub (2), stopping the process tree (1), the output cap (2), the read-only datasource (2), one statement per call (1), the row cap (2). The write-word check in a query survived: every case was also refused by another check. Two cases were added where it is the only check, H2 queries that delete through `old table (...)`, and with them it is caught (2 red).

### What you must change

1. **A restricted script that reads or sets a property of `files` or `http`, indexes them, or calls a method they do not list.** Refused from 1.4.1 with a `SecurityException`. `files` offers `read`, `readLines`, `exists`, `size`, `list`, `write`, `append`; `http` offers `get`, `post`, `put`, `delete`. Nothing in the MCP servers' main source does this.

Nothing else changes for existing flows: `ShellTask`, `allow 'process'` and `allow 'sql'` are new and off unless asked for.

### Added

Shell execution (D2, item 7)

- **`ShellTask`** (`shellTask(id) { ... }`, `TaskType.SHELL`, alias `shell`): runs a PowerShell, cmd, bash or Python script as a process. DSL: `interpreter`, `script`, `args` (values or a closure of `(ctx, prev)`), `workingDirectory`, `env`, `processTimeout` (default 60 s), `maxOutput` (1 MB), `maxErrorOutput` (64 KB), `runner`, `trusted`. Result: `exitCode`, `stdout`, `stderr`, `stdoutTruncated`, `stderrTruncated`, `durationMs`. A non-zero exit fails the task as an ordinary failure (`ShellTask.ShellExecutionException`), so the retry policy applies.
- **Off by default.** New graph words: `allow 'process'` and `interpreters 'powershell', ...`; without both the graph does not start. Also `interpreterPath name, executable`, `passEnvironment names...`, `shellRunner runner`.
- **The checks** (`org.softwood.dag.task.shell.ShellGuard`), made before any runner sees the run and, where they can be, when the graph is validated: interpreter named by the graph; script by temporary file (PowerShell, cmd) or stdin (bash, Python), never on the command line; arguments as a list, with no NUL, and on Windows no double quote or line break, and plain characters only for cmd and bash (both read their command line again); working directory inside the graph's directories; environment rebuilt from a keep-list, `passEnvironment` and `env`; destructive-pattern tripwires per interpreter; no elevation (`sudo`, `runas`, `-Verb RunAs`, `su`), trusted or not.
- **Running** (`LocalShellRunner`): stdin closed after the script; interpreters non-interactive and without profiles; output read as it comes and capped; on timeout or interrupt the process and every process it started are stopped.
- **`trusted true`** skips the interpreter list and the tripwires; everything else stays (Will, 2026-11-23). `allow 'process'` is still needed.
- **Audit**: each run is logged on `org.softwood.dag.audit` and passed to the new `TaskContext.auditListener`: task, interpreter, working directory, SHA-256 of the script, argument count, exit code, duration, truncation, trusted, timed out.
- **A runner of the flow's own**: new interface `ShellRunner` (`ShellResult run(ShellRequest)`), set on the graph or the task. For AW to hand the command to FS `execute`.
- **`allow 'process'` in a restricted script** binds `process`: `process.shell(interpreter, script, args)`, the same checks and runner, returning the result map without failing on a non-zero exit. Not `run`: the script profile refuses `run()` everywhere, because on a script it runs the script again.

Database access from scripts (D2, `allow 'sql'`)

- New graph word `datasource 'name', provider` (an `SqlProvider`), with `writable: true` and `maxRows: n`; stored as `TaskContext.datasources` (`ScriptDatasource`).
- **`allow 'sql'`** binds `sql` (`ScriptSql`): `query`, `queryForMap`, `queryForObject`, and `update` on a writable datasource. Every call names a declared datasource; parameters by `?`.
- `SqlStatementCheck`: one statement per call (quoted text and comments skipped; where a database could read more code, the check does too); a query starts with `SELECT` or `WITH` and contains no write word; `update` takes `INSERT`, `UPDATE`, `DELETE`, `MERGE` and no schema change, grant or procedure call.
- A query result over `maxRows` (10,000 by default) fails the call; it is counted after it is fetched. Each call leaves an audit record (datasource, operation, statement hash, rows).

### Changed

- **A script reaches a capability only through its methods.** New `ScriptCapability`, implemented by `ScriptFiles`, `ScriptHttp`, `ScriptSql` and `ScriptProcess`; `ScriptGuard` refuses any property read or write, index, operator, iteration or unlisted method on one. Before 1.4.1, Groovy's access to private members let a script add `localhost:<port>` to `http.allowLocal` and then call that service, set `files.config.maxFileSizeBytes`, or call `files.checked(...)`. `http`'s copy of the local allowance is also unmodifiable now.
- `allow` accepts `sql` and `process`.
- `SecureScriptBase`'s class comment describes what it is now: the helpers, not the control.

### Not verified

- bash on Windows is run through Git for Windows (`interpreterPath 'bash', 'C:/Program Files/Git/bin/bash.exe'`), with tests that run when it is installed: stdin delivery, working directory, arguments, scrubbed environment, a timeout that stops bash, and a non-zero exit. The WSL launcher that `bash` on PATH usually is was not run; on Windows, bash arguments stay limited to plain characters for both.
- **Known gap: a timeout does not reach a process whose parent has already exited.** The process tree is found by walking parents. MSYS (Git Bash) starts a script's commands through a fork stub that exits at once (seen: `sleep.exe`'s parent pid gone from the start), so `sleep 60 &` in a Git Bash script survives the timeout; so would any double-forked process. `Hardening141ShellSpec` holds it as a `@PendingFeature`. The fix is a Windows Job Object (and a process group elsewhere), planned for a later release.
- The symbolic link test did not run (no right to create links on the test machine).
- No `ShellTask` was run through a runner other than the test's own: the AW runner that delegates to FS is for the AW brief.
- `sql` with any database but H2. The statement check is text, not a grammar: a read-only datasource should have a read-only database account.
- No memory limit on a shell's process; a tripwire is a check against mistakes, not a boundary.

## 1.4.0 (2026-11-20): restricted evaluation for rule conditions and scripts

Source: release 3 of the hardening plan, project doc `gcu/brief-core-hardening-2026-11-18` (rev 7), decision D2.
This release changes defaults. Code that relied on the old ones has to say what it needs: see "What you must change".
Not in this release: `allow 'sql'`, `allow 'process'` and `ShellTask`. They belong together and follow in a later release; `allow` refuses both names until then.
Tests: 2648 passing, 0 failing (2169 at 1.3.0, plus 479 in four new specs: 85 in `Hardening140RuleSpec`, 198 in `Hardening140RuleSemanticsSpec`, 9 in `Hardening140FailureSignalSpec`, 187 in `Hardening140ScriptSpec`). The 16 script tests that existed before this release (6 in `ScriptTaskTest`, 10 in `ScriptTaskCustomBaseTest`) pass unchanged: none needed `trusted true`.
Red first. `Hardening140RuleSpec`: 42 of 85 red on 1.3.0 (38 because the old behaviour was wrong, 4 because `trusted` and the compiler did not exist; the other 43 are conditions inside the profile). `Hardening140ScriptSpec`: 92 of its first 161 red against the 1.3.0 `ScriptTask` (all 51 scripts outside the profile were accepted when the graph was validated, all 13 reaches through a bound object succeeded, `scriptTimeout` was not enforced, a variable leaked from one run to the next); 26 more tests were added after the new `ScriptTask` and have no red run.
Mutation checks, seven caught: for rules, the method allow-list, the by-type refusal and the by-name refusal; for scripts, the run-time receiver check (17 red), the denied method names (13 red) and the class allow-list (12 red); for the failure signal, attaching it (4 red). One survived and one was blunt: making the script interrupt an `InterruptedException` in place of an `Error` broke no test, because an interrupted script stops either way in every shape tested, so that choice is belt and braces and not proven necessary; replacing the interrupt check altogether broke 108 tests, far more than the timeout tests alone.

### What you must change

Only if you use `ControlTask` or `ScriptTask`. The MCP servers use neither in their main source: agentic-workflow's `control.policy` and `groovy.eval` nodes have their own evaluators and are not changed by this release.

1. **A `ControlTask` condition outside the rule profile.** A `when:` string is now one expression over `input`, `globals` and `memory`: comparisons, boolean logic, arithmetic, `in`, the match operators, the conditional and Elvis operators, literals, property and index access, a cast to a number type, `String` or `Boolean`, the methods `size`, `isEmpty`, `contains`, `containsKey`, `startsWith`, `endsWith`, `matches`, `toString`, `toLowerCase`, `toUpperCase`, `trim`, and `Math`. Anything else (`new`, a closure, an assignment, any other method, any other name) stops the graph when it is validated, naming the task and the rule. Rewrite the condition, or mark the task `trusted true`.
2. **A condition that could throw.** It now fails the task. It used to be read as false, so the next rule or the default applied. Where a value may be absent, say so in the condition: `input.items?.size() > 0`.
3. **A condition that read a property named `class`, `metaClass`, `classLoader`, `declaringClass`, `protectionDomain`, `properties` or `metaPropertyValues`.** Refused by name. For a map entry with one of those names use the index form: `input['properties']`.
4. **A `ControlTask` with a retry policy.** A failed `ControlTask` is no longer retried.
5. **A `ScriptTask` script that names a class outside the script profile**: `File`, `URL`, `Thread`, `ProcessBuilder`, `Class`, another script engine, `System` beyond the clock. The graph refuses to start and names the task. Grant the graph `allow 'files'` or `allow 'http'` and use the `files` and `http` helpers, move the work to a `FileTask` or `HttpTask`, or mark the task `trusted true`.
6. **A script that uses `with`, `tap`, `use`, `getClass`, `metaClass`, `invokeMethod`, `evaluate`, `binding`, `execute` or `toURL`**, a method pointer (`.&`), `.@`, `super`, `synchronized`, a computed method or property name, a class definition, a static import, an import alias, or any annotation but `@Field`. Refused when the graph is validated. Rewrite it, or mark the task `trusted true`.
7. **A script that reads an object's property by index**: `order['total']`. Write `order.total`. Maps and lists index as before.
8. **A script that uses `ctx` as the `TaskContext`.** In a restricted script `ctx` is a read-only view: `ctx.globals` is a copy that cannot be changed, and there are no credentials, pool or graph. Return the value for a later task to store, pass what the script needs through `bindings`, or mark the task `trusted true`.
9. **A script that calls `parse(...)` on a JSON, XML or YAML slurper.** Refused in a restricted script, because `parse` reads a file or an address. Use `parseText(...)`.
10. **A script that relied on what an earlier run left behind**: an undeclared variable set in one run and read in the next. Each run now has its own script instance and binding.
11. **A script with a `scriptTimeout`.** It is now enforced. A script that runs longer fails its task with a `TimeoutException`.
12. **A script in JavaScript.** It no longer has `Java.type`, `Packages` or reflection, and it gets a new engine each run, so nothing carries over in the engine.
13. **A script in any other language.** It needs `trusted true`, or the graph refuses to start.
14. **A `ScriptTask` with no script.** The graph refuses to start. It used to fail when the task ran.

`sandboxed false` needs no change: it still works and now means `trusted true`. It no longer refuses when the `ENVIRONMENT` variable says production.

### Changed

Rule conditions (D2, step 1)

- **A `ControlTask` condition is compiled under a rule profile.** Before 1.4.0 it was handed to a Groovy shell and could do anything the process could. New `org.softwood.dag.script.RestrictedCompiler.compileRule(text)` parses the condition, checks it against an allow-list and returns a `RuleExpression`.
- **A rule is never compiled to a class.** `RuleExpression` walks the checked expression and does each property read, index and method call itself, checking the object reached: by name (`class`, `metaClass` and relatives) and by type (classes, class loaders, reflection, files, paths, URLs, processes, threads, closures, anything closeable, the graph's context). A refusal is a `SecurityException`. This also ends the class loaded per rule per run.
- **A bad rule stops the graph before it runs.** New `org.softwood.dag.task.SelfValidating`; `TaskGraph.validate()` asks every task that implements it and reports every problem in one error. `ControlTask` implements it.
- **A condition that throws fails the node.**
- **`globals` is a read-only copy** for a restricted rule.
- **A rule is parsed once** and reused by every task and run that has the same text.
- New on the task: `trusted true`, which evaluates conditions as unrestricted Groovy against the live globals and logs a warning each run. A trusted condition is compiled once per task, must still compile for the graph to start, and still fails the node if it throws.
- `ControlTask` no longer writes its input or its rule maps to the log.
- **A rule inside the profile means what the same Groovy expression means.** `Hardening140RuleSemanticsSpec` evaluates 198 expressions twice, by the rule profile and by a plain Groovy shell against the same values, and requires the same value of the same type, or a failure in both. It covers map entries (whatever their name), getters, boolean getters, public fields, `propertyMissing`, `Expando`, enums, dates, lists, sets and arrays (`list.empty`, `list.first`, a property read from each element, negative and out-of-range indexes, `array.length`), text, mixed number types, `null` on either side of every operator, Groovy truth, `in` on lists, arrays, maps and text, the match operators, literals and `Math`. Writing it found seventeen differences in the first build of the profile, all corrected: for example `list.empty` was read from each element, `'key' in map` followed the Groovy 4 meaning and not Groovy 6's, and `text + null` failed.
- Known and deliberate differences from Groovy, all of them a refusal where Groovy would go on: methods outside the list; `globals` is a map copy, so `globals.keys` and `globals.all` are entries and not the binding's own getters; a list cannot be indexed by a range or a list; `-`, `*` and `/` on anything but numbers (`text * count` is allowed); `=~` gives a matcher that can only be read as true or false.

Scripts (D2, steps 2 to 4)

- **A `ScriptTask` script is compiled under a script profile.** Before 1.4.0 the "sandbox" was a script base class that could stop only a bare `execute()`: a script could run a process, read the environment, open a file and use reflection. New `RestrictedCompiler.compileScript(source, baseClass, loader)`, done by `ScriptCompiler` with the tables in `ScriptProfile`.
- **Checked before anything runs.** The only annotation is `@Field`, checked before the compiler acts on any annotation, so `@Grab` and `@ASTTest` never run. No class definitions, static imports or import aliases. Every class the script names must be in the profile. The deny lists of method and property names apply to everything, the script itself included. A class is used only through its public static members. `ScriptTask` implements `SelfValidating`, so all of this fails `TaskGraph.validate()`.
- **Checked while it runs.** The compiler wraps the receiver of every call, property read or write, index and `<<`, and whatever is iterated, spread, cast or taken apart by a multiple assignment, in a `ScriptGuard` check of the object actually reached. A file, path, URL, process, thread, class loader, reflection object, anything closeable, or a class held as a value is refused with a `SecurityException`.
- **Groovy's meaning is kept.** The check returns the receiver and Groovy makes the call. `Hardening140ScriptSpec` runs 75 scripts both restricted and as plain Groovy and requires the same result.
- **`ctx` is a read-only view** (`ScriptContextView`): `globals`, `get(name)`, `taskId`.
- **A new script instance and binding for every run**; the class is compiled once per task. Before 1.4.0 one instance was shared, so concurrent runs overwrote each other's variables.
- **`scriptTimeout` is enforced.** With one set, the script runs on its own thread; at the limit the task fails with a `TimeoutException` and the thread is interrupted. A restricted script has an interrupt check on every loop pass, method and closure, throwing `ScriptInterruptedError`.
- **A refusal is not retried** and is a permanent failure.
- **Level 2.** New graph word `allow 'files'`, `allow 'http'` (`TaskContext.scriptCapabilities`). `files` (`ScriptFiles`) reads and writes text under the unconfigured-`FileTask` rules; `http` (`ScriptHttp`) makes requests the `HttpTask` guard accepts and does not follow redirects.
- **Level 3.** New on the task: `trusted true`: plain Groovy and the `TaskContext`, logged each run. `sandboxed` is kept as the old word; the `ENVIRONMENT` check is gone.
- **Other languages.** JavaScript on Nashorn with `--no-java` and a class filter that exposes nothing (`RestrictedJavaScript`). Any other language only with `trusted true`. Script engines are no longer cached and shared between tasks.
- `customScriptBaseClass` still works: its public methods are callable by name and the script is still compiled under the profile.
- New classes in `org.softwood.dag.script`: `ScriptCompiler`, `ScriptProfile`, `ScriptGuard`, `ScriptCompilationException`, `ScriptInterruptedError`, `ScriptContextView`, `ScriptFiles`, `ScriptHttp`, `RestrictedJavaScript`.

Failure signalling

- **A failed task says whether it will run again, and if not, why.** A `TASK_FAILED` event has always been final for the task's own retry policy, but nothing in it said so, or told a failure that retrying cannot fix from one that ran out of attempts. The event's `metadata` now carries `willRetry: false` and `notRetriedBecause`: `not-retriable` (a refusal, a bad configuration, a timeout: another attempt gives the same failure), `no-retries-configured`, or `retries-exhausted`. A `TASK_RETRYING` event carries `willRetry: true`. The existing `retriesExhausted` key is unchanged.
- New: `TaskEvent.isPermanentFailure()`, true for a failed event marked `not-retriable`; constants `TaskEvent.WILL_RETRY`, `NOT_RETRIED_BECAUSE`, `NOT_RETRIABLE`, `NO_RETRIES_CONFIGURED`, `RETRIES_EXHAUSTED`. Whatever drives a graph should not re-run a task, or a scope that contains it, unchanged after a permanent failure. Every `ControlTask` failure is one.
- **The same signal is on the exception.** New `TaskFailureSignal`: `TaskFailureSignal.of(exception)` gives the task id, `notRetriedBecause`, the number of attempts and `permanent`, or null when no task marked the exception; `TaskFailureSignal.isPermanent(exception)` is the short form. It is for a caller that awaits the graph's promise and listens to no events. The exception a task throws keeps its type and nothing is wrapped: the signal travels as a suppressed exception, so it also shows as a `Suppressed:` line in a printed stack trace.
- Not covered: a JVM-level error (out of memory, linkage) carries neither the new keys nor the signal; an exception built with suppression switched off cannot carry the signal, though its event still does.

Not isolation. A rule can call the getters and `toString()` of the objects it is given. A script can call any public method of an object in its bindings and can pass any object it holds to a method it may call. Beyond the profile's classes the by-type list is a deny-list. There is no memory limit. Work a script starts elsewhere (`Promises`, a parallel stream) is not stopped by its timeout, a JavaScript loop is abandoned and not stopped, and a slow regular expression is not interrupted. See the security controls chapter.

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
