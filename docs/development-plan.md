# KotlinTesting — development plan towards a mature, feature‑complete multiplatform test library

*Companion to the [framework comparison](testing-frameworks-comparison.md). Written October 2026 against Kotlin 2.4.20, Xcode 27 / Swift 6.4, JUnit 6.1.*

## 1. Goal and non‑goals

**Goal.** Turn KotlinTesting into a Kotlin Multiplatform test library whose tests are written once in common code and executed by the *native tooling of each platform*:

* **Kotlin/JVM** — as a first‑class JUnit Platform engine, so Gradle, Maven, IntelliJ IDEA, Android Studio and CI tooling work unchanged.
* **Apple targets (iOS, macOS, tvOS, watchOS, simulators and devices)** — as XCTest test cases inside an `.xctest` bundle or host app, so `xcodebuild test`, Xcode's test navigator, test plans, `.xcresult`, device execution and Xcode Cloud work unchanged. This generalises the bridge that already exists in `kotlin-test-native-xctest`.
* **Other targets (Linux, Windows, Android Native, JS, Wasm)** — through a stand‑alone runner and the existing JS adapters so the same tests remain runnable everywhere.

"Mature and feature complete" is defined in §7 as a checklist derived from the gap table in the comparison (§3 there). In short: parity with the P0/P1 features of Swift Testing and JUnit Jupiter, uniform behaviour on every target, stable public API, and tooling integration that does not require hand‑written glue.

**Non‑goals (for 1.0).** UI automation (XCUITest/Compose `uiTest` stay separate), performance metrics beyond timing, mocking, assertion‑library competition (we remain assertion‑agnostic), replacing Kotest/TestBalloon as a DSL (we offer an annotation API first and a DSL second), Swift Testing as an execution backend (tracked as a stretch goal — Swift Testing has no supported registration ABI for non‑Swift tests, see §8).

## 2. Where the repository stands today

What works:

* `kotlin-test-native-xctest` wraps each Kotlin/Native `TestCase` in an `XCTestCase` by adding ObjC methods at runtime (`class_addMethod` + `NSInvocation`), gives XCTest a proper selector name per test, maps `@Ignore` to `XCTSkip` through a native `skipImplementation()` IMP, records failures as `XCTIssue` with source location and a stack‑trace attachment, and forwards XCTest observation events back to the Kotlin/Native `TestListener`/`TestLogger` chain.
* `frameworkTesting` exports the tests as a static framework (`KotlinTests`) that a Swift test target consumes by overriding `defaultTestSuite`.
* Xcode SDK/framework path resolution lives in a convention plugin; CI runs `xcodebuild build-for-testing` + `test-without-building` on a simulator.

What blocks maturity:

| Area | Finding | Consequence |
|---|---|---|
| Internal API coupling | The bridge imports `kotlin.native.internal.test.*` with `INVISIBLE_REFERENCE` suppression (`GeneratedSuites`, `TestProcessor`, `TestSettings`, `TestCase`, `TestListener`) | Breaks with any Kotlin/Native runtime refactor; cannot be published as a stable library |
| Discovery | Relies on the compiler flag `-tr` (generate test runner) being applied to the consumer's **main** compilation; tests live in `nativeMain` to be exported | Unconventional layout, no `commonTest` story, no JVM story |
| Target coverage | Library builds macOS/iOS; sample is `iosSimulatorArm64` only; no tvOS/watchOS, no device packaging | Not usable by real apps without extra work |
| Glue | Consumers must write a Swift `XCTestCase` subclass to graft `testSuite().tests` into `defaultTestSuite` | Manual, error‑prone, hidden from Gradle |
| Features | Only what `kotlin.test` provides: `@Test`, `@Ignore`, `@BeforeTest`, `@AfterTest`, `@BeforeClass`, `@AfterClass`; repetition/iterations explicitly unsupported in the observer | None of the gap‑table items |
| Distribution | No Maven publication, bespoke configurations/artifacts for consumers, no Gradle plugin, no API dump | Not consumable |
| Quality | No tests of the library itself, no Linux CI job for the Gradle side, Kotlin pinned to 2.2.21 (current is 2.4.20), README minimal | Regressions invisible |

## 3. Target architecture

```
                 ┌──────────────────────────────────────────────────────────────┐
                 │  kotlintesting-api (common)                                   │
                 │  @Test @Suite @BeforeAll/@AfterAll @BeforeEach/@AfterEach      │
                 │  @Parameterized/@ArgumentsSource @Tag @DisplayName @Timeout    │
                 │  @Disabled(if=) @Repeat @KnownIssue  —  DSL: testSuite { }     │
                 │  Assertions (kotlin.test‑compatible + assertAll, assume*)      │
                 │  Extension SPI: TestInterceptor, Condition, Listener           │
                 └───────────────▲──────────────────────────────────────────────┘
                                 │ compiles against
 ┌───────────────────────────────┴───────────────────────────────────────────────┐
 │  kotlintesting-compiler-plugin (+ Gradle plugin)                               │
 │  Emits a TestRegistry per module on every target: suites → tests →            │
 │  parameters, metadata, source locations. No reflection required.              │
 └───────────────────────────────┬───────────────────────────────────────────────┘
                                 │ feeds
 ┌───────────────────────────────▼───────────────────────────────────────────────┐
 │  kotlintesting-engine (common)                                                 │
 │  TestDescriptor tree · TestPlan (filters: name/tag/uid) · Executor             │
 │  (lifecycle, parameters, timeouts, repetition, parallel policy, interceptors)  │
 │  · Events (started/skipped/passed/failed/issue/attachment) · Reporters         │
 │  (TeamCity, JUnit XML, JSON event stream)                                       │
 └──┬───────────────────┬──────────────────────┬──────────────────┬───────────────┘
    │                   │                      │                  │
 ┌──▼──────────────┐ ┌──▼───────────────────┐ ┌▼───────────────┐ ┌▼────────────────┐
 │ backend-junit   │ │ backend-xctest        │ │ backend-native │ │ backend-js      │
 │ JUnit Platform  │ │ XCTestCase/Suite      │ │ stand‑alone    │ │ FrameworkAdapter│
 │ TestEngine,     │ │ wrappers, .xctest     │ │ runner (Linux, │ │ (Mocha / new    │
 │ Jupiter interop │ │ bundle & host app,    │ │ Windows, Apple │ │ browser DSL),   │
 │ (JVM, Android)  │ │ XCTestObservation,    │ │ fallback,      │ │ Wasm/JS, WASI   │
 │                 │ │ xcresult, devices     │ │ Android Native)│ │                 │
 └─────────────────┘ └──────────────────────┘ └────────────────┘ └─────────────────┘
```

Design rules:

1. **One test model, many backends.** Every feature is implemented once in `kotlintesting-engine`; a backend only (a) maps the descriptor tree to the platform's structure, (b) runs the executor callbacks inside the platform's lifecycle, (c) translates events into platform reports. This is how uniform behaviour is achieved — the opposite of `kotlin.test`, where the delegate framework decides.
2. **Compile‑time discovery.** A Kotlin compiler plugin (FIR checker + IR generation) emits a registry. This is the only mechanism that works identically on JVM, Native, JS and Wasm, and it is the approach proven by Burst, TestBalloon and Mokkery. The registry replaces `GeneratedSuites`/`-tr`.
3. **kotlin.test compatibility.** `kotlin.test.Test/Ignore/BeforeTest/AfterTest` and the kotlin.test assertions are recognised by the compiler plugin, so existing `commonTest` code runs on the new engine with no source change. Our own annotations are a superset.
4. **Assertion‑agnostic.** Any exception is a failure; `AssertionError` subclasses carry expected/actual where available; Power‑assert, Kotest assertions, AssertK and Turbine must work out of the box.
5. **Coroutine‑native.** `suspend` test functions are first‑class; the executor runs them with kotlinx‑coroutines‑test when present (virtual time), otherwise `runBlocking`/promise per platform.
6. **Public API from day one is small and versioned** (binary‑compatibility‑validator, `@ExperimentalKotlinTestingApi` for anything unstable).

## 4. Backends in detail

### 4.1 JVM — JUnit Platform engine (`backend-junit`)

* Implement `org.junit.platform.engine.TestEngine` (`kotlintesting`). Discovery reads the compiler‑generated registry from the classpath (no classpath scanning), honours `ClassSelector`, `MethodSelector`, `UniqueIdSelector`, `PackageSelector`, tag filters. This gives Gradle `useJUnitPlatform()`, Maven Surefire, IntelliJ gutters, and JUnit XML / Open Test Reporting for free.
* Each parameterized invocation, repetition and dynamic test becomes a child `TestDescriptor` with a stable `UniqueId` so IDEs can re‑run a single argument.
* Interop: Jupiter tests in the same module keep running on the Jupiter engine; a `kotlintesting-junit-jupiter` adapter lets our `TestInterceptor`s wrap Jupiter tests and lets Jupiter extensions (`@ExtendWith`) wrap ours, in the spirit of ST‑0021.
* Android: unit tests go through the same engine; instrumented tests are packaged as a `Runner` built on the engine (phase 5).

### 4.2 Apple — XCTest backend (`backend-xctest`)

Evolves the existing bridge:

* Keep the proven mechanism (dynamic `XCTestCase` subclass per suite, one ObjC method per test via `class_addMethod`, `NSInvocation`), but feed it from the engine's descriptor tree instead of `kotlin.native.internal.test`. One `XCTestCase` subclass **per Kotlin suite** (instead of one class for all tests) so Xcode's navigator shows `Suite/test` hierarchy and `-only-testing:Bundle/Suite/test` works.
* Nested suites → nested `XCTestSuite`s; parameterized cases → separate selectors with escaped argument names; display names → `name` override.
* Lifecycle: `@BeforeAll/@AfterAll` → `XCTestSuite` `setUp/tearDown` (already done for class‑level); `@BeforeEach/@AfterEach` → instance `setUp/tearDown`; `TestInterceptor` → `invokeTest` override.
* Skips → `XCTSkip` via the native IMP trick until KT‑43719 (ObjC exception import) lands; conditional skips evaluate at `setUp`. Known issues → `XCTExpectFailure`. Attachments → `XCTAttachment`. Issue severity warning → `XCTIssue` with `associatedError` and `detailedDescription` prefix (XCTest has no warning type). Timeouts → `executionTimeAllowance` + engine watchdog.
* Packaging (Gradle plugin): produce an `.xctest` bundle for the host target (`iosSimulatorArm64`, `iosArm64`, `macosArm64`, …) with a generated `Info.plist`, a generated principal `XCTestCase` subclass (`defaultTestSuite` override, replacing hand‑written Swift), and optionally a generated host app or SwiftPM test target. Tasks: `xcTest<Target>` (build), `xcTestRun<Target>` (`xcodebuild test-without-building` / `xcrun simctl`), device destinations via `-destination 'platform=iOS,id=…'`.
* Reporting: `.xcresult` is produced by Xcode; in addition the observer emits TeamCity/JUnit XML for Gradle so results appear in Gradle reports and IntelliJ when launched from Gradle.
* Threading: run tests on the XCTest‑provided thread; expose `Dispatchers.Main` integration so `UIApplication`‑dependent code works (today's `ensureUIApplication` sample).
* Linux `swift-corelibs-xctest` is explicitly out of scope; the stand‑alone runner covers Linux.

### 4.3 Stand‑alone native runner (`backend-native`)

* Replaces the compiler's `-tr` runner for Linux/Windows/Android Native and remains the default for Apple targets when no XCTest bundle is requested, so `./gradlew iosSimulatorArm64Test` keeps working.
* CLI compatible with today's flags (`--ktest_filter`, `--ktest_regex_filter`, `--ktest_logger`, `--ktest_repeat`, `--ktest_list_tests`, `--ktest_no_exit_code`) plus `--tags`, `--uid`, `--json-events <path>`, `--timeout`, `--parallel`.
* Implements `KotlinNativeTest`‑compatible TeamCity output so the Kotlin Gradle plugin's existing test tasks consume it.

### 4.4 JS / Wasm (`backend-js`)

* Implements `kotlin.test.FrameworkAdapter`‑style bridging so Mocha (Node, new browser DSL) and the Wasm/WASI runner execute the engine's leaf tests; suites become `describe` blocks; async leaf tests return promises. Nested structure is flattened only where the JS runner forbids async parents (documented limitation shared with Kotest).

## 5. Feature roadmap mapped to the gap table

| Gap # (comparison §3) | Feature | API sketch | Engine work | JUnit mapping | XCTest mapping |
|---|---|---|---|---|---|
| 1 | Parameterized tests | `@Test @Parameterized fun t(x: Int)` with `@ValueSource`, `@EnumSource` (auto), `@MethodSource`, `@ArgumentsSource(Provider::class)`, `@Burst`‑style `burstValues()` equivalent; DSL `test("x", arguments = listOf(...)) { }`; cartesian product and `zip` | Expand descriptors at discovery (static) or at execution (dynamic providers) with stable UIDs | Child descriptors | One selector per case |
| 2 | Suite lifecycle in common | `@BeforeAll/@AfterAll` on member functions of an `object`/companion or top‑level | Executor | `BeforeAll` semantics | `XCTestSuite.setUp` |
| 3 | Conditional execution | `@Disabled(reason)`, `@DisabledIf(Condition::class)`, `@EnabledOnPlatform(IOS, JVM)`, `assume(cond)` throwing `TestAbortedException` | `ExecutionCondition` SPI | `SKIPPED`/aborted | `XCTSkip` |
| 4 | Suspend tests | `@Test suspend fun` | `runTest` integration module | Blocking | Blocking on XCTest thread |
| 5 | Timeouts | `@Timeout(seconds)` / `.timeLimit` | Watchdog + cancellation | Report | `executionTimeAllowance` |
| 6 | Names, tags, metadata | `@DisplayName`, `@Tag`, `@Bug(url)` | Descriptor metadata | `TestTag`, display name | `name`, attachments |
| 7 | Filtering | Gradle `kotlintesting { filter { includeTags(...) } }`, CLI `--tags` | TestPlan | `TagFilter` | `-only-testing` + skip |
| 8 | Nested suites | `@Suite` nested classes/objects; DSL | Tree | `@Nested` | nested `XCTestSuite` |
| 9 | Dynamic tests | `@TestFactory fun f(): List<DynamicTest>`; DSL `testSuite { for (...) test(...) }` | Late expansion | `DynamicContainer` | selectors created at `testInvocations` time |
| 10 | Extensions | `TestInterceptor { around(test) }`, `Listener` registered via `@Extensions` or registry | Interceptor chain | `InvocationInterceptor` | `invokeTest` |
| 11 | Soft assertions | `assertAll { ... }`, `SoftAssertions` | Multi‑failure issue | `MultipleFailuresError` | multiple `XCTIssue`s |
| 12 | Known issues | `@KnownIssue(strict=true)`, `withKnownIssue { }` | Issue policy | `TestAbortedException` or pass‑with‑report | `XCTExpectFailure` |
| 13 | Repetition / retry | `@Repeat(n, failureThreshold)`, `@Retry(n)`, CLI `--repeat-until fail\|pass` | Executor | Children | `-test-iterations` compatible |
| 14 | Parallelism | `@Execution(CONCURRENT)`, `@ResourceLock`, engine config `maxConcurrency` | Scheduler | Platform parallel config | serial by XCTest; parallel across clones |
| 15 | Attachments | `attach("name", bytes/text/file)` | Event | `TestReporter.publishFile` | `XCTAttachment` |
| 16 | Public events | `TestEvent` stream + JSON writer (shape modelled on Swift Testing ABI v0) | Reporter | `TestExecutionListener` | `XCTestObservation` |
| 17 | Rich failures | Power‑assert `@PowerAssert` on our assertions; `SourceLocation` captured by compiler plugin | — | OpenTest4J expected/actual | `XCTSourceCodeLocation` |
| 18 | Fixtures | `@TempDir`, `TestInfo` parameter, `AutoCloseable` fixtures | Resolver | `ParameterResolver` | teardown blocks |
| 19 | Ordering | `@TestMethodOrder`, `@Order`, random with seed | Plan | `MethodOrderer` | ordered suite |
| 20 | Severity | `Issue.record(..., WARNING)` | Event | report entry | detailed description |
| 21 | Cancellation | `Test.cancel()` | Cooperative cancellation | aborted | `XCTSkip` |
| 24, 25 | Devices, xcresult | Gradle `xcTestRun<Target>(destination)` | — | — | native |

## 6. Phased delivery

Each phase ends with a tagged release, an updated compatibility matrix and docs. Durations are estimates for a team of two to three engineers.

### Phase 0 — Foundation (≈ 6 weeks)

* Upgrade to Kotlin 2.4.20; add Kotlin version matrix CI (current, previous, EAP).
* Repository layout: `api/`, `engine/`, `compiler-plugin/`, `gradle-plugin/`, `backends/{junit,xctest,native,js}/`, `integrations/{coroutines,power-assert}/`, `samples/`, `docs/`.
* Publishing: Maven Central coordinates, Gradle plugin portal, `kotlinx-binary-compatibility-validator`, explicit API mode, `@ExperimentalKotlinTestingApi`.
* CI: Linux job for JVM/Native‑host/JS, macOS job for Apple backend with simulator matrix; replace the "default scheme" script with explicit schemes.
* Self‑tests: engine unit tests on JVM; "meta‑tests" that run a sample suite through each backend and compare the emitted event stream against golden files.
* **Exit:** all current functionality builds on 2.4.20, published as `0.1.0-alpha`.

### Phase 1 — Harden the XCTest backend (≈ 10 weeks)

* Remove `kotlin.native.internal.test` usage: introduce the common `TestRegistry` + engine, and a temporary generator that converts `kotlin.test` annotations into registry entries (compiler plugin MVP: discovery of `@Test/@Ignore/@BeforeTest/@AfterTest/@BeforeClass/@AfterClass`, source locations).
* One `XCTestCase` subclass per suite; nested suites; proper `-only-testing` mapping; `XCTSkip` for `@Ignore`; `@BeforeClass/@AfterClass` in common code.
* All Apple targets (`macosX64/Arm64`, `ios*`, `tvos*`, `watchos*`, simulators and devices).
* Gradle plugin MVP: `kotlintesting { apple { xctestBundle(); hostApp(optional) } }` producing the `.xctest` bundle with generated `Info.plist` and principal class; `xcTest<Target>` and `xcTestRun<Target>` tasks; `.xcresult` summary + JUnit XML into Gradle.
* Documentation: "Run Kotlin tests in Xcode", "Run on a device", "CI with xcodebuild".
* **Exit:** `samples/ios-app` runs `commonTest` tests from Xcode's navigator on a simulator and a device without Swift glue; `0.2.0`.

### Phase 2 — JVM JUnit Platform engine (≈ 8 weeks)

* `backend-junit` `TestEngine` with discovery from the registry, selectors, tag filters, unique IDs; Gradle `useJUnitPlatform()` and IntelliJ run gutters; JUnit XML + Open Test Reporting.
* Interop module with Jupiter (`@ExtendWith` on our tests, our interceptors on Jupiter tests).
* Android unit tests through the engine.
* **Exit:** the same `commonTest` source set runs on JVM via JUnit Platform and on iOS via XCTest with byte‑identical event streams; `0.3.0`.

### Phase 3 — Common feature set v1 (≈ 14 weeks)

Implements P0 items (gap table 1–7) and the first P1 items (8, 9, 11, 12):

* Parameterized tests (static sources first, dynamic providers second), per‑argument UIDs/display names, IDE re‑run of a single argument on JVM, `-only-testing` per case on Xcode.
* `@BeforeAll/@AfterAll`, conditional execution and assumptions, `@Timeout`, suspend tests with kotlinx‑coroutines‑test integration, display names/tags/bug links, tag filtering on every backend, nested suites, dynamic tests, `assertAll`, known issues.
* Assertions module: kotlin.test‑compatible API + lazy messages + `@PowerAssert`‑ready functions.
* **Exit:** feature parity checklist §7 items A1–A12 green on JVM, Apple (simulator + device), Linux, JS; `0.5.0`.

### Phase 4 — Execution and extensibility (≈ 10 weeks)

* Interceptor/listener SPI, ordering, repetition/retry policies, parallel execution with resource locks (JVM, native runner) and XCTest parallel‑clone compatibility, attachments, issue severity, cancellation, fixtures (`@TempDir`, `TestInfo`, auto‑close), JSON event stream and `TestEvent` public API.
* **Exit:** §7 items B1–B9 green; `0.8.0` (API freeze candidate).

### Phase 5 — Tooling and developer experience (≈ 10 weeks)

* Gradle plugin complete: per‑target backend selection, filters, devices/simulators (`destination`), environment, test plans mapping, reports aggregation across targets, configuration cache compatibility.
* Xcode integration: generated SwiftPM test target or Xcode project fragment, `.xctestplan` with tags → our filters, `xcresult` → JUnit XML converter.
* IDE: IntelliJ support through JUnit Platform (JVM) and a run‑configuration producer for Apple backends via Gradle; evaluate reusing TestBalloon's plugin approach for gutters on non‑JVM targets.
* Android instrumented runner; Compose Multiplatform `uiTest` and Roborazzi compatibility verified.
* Migration guides: from `kotlin.test`, from Kotest (annotation subset), from XCTest/Swift Testing for iOS developers; cookbook for Mokkery, Turbine, AssertK, Power‑assert.
* **Exit:** `1.0.0-RC`.

### Phase 6 — 1.0 and beyond (≈ 8 weeks to 1.0, then continuous)

* API audit, binary‑compat baseline, Kotlin compatibility policy (support current and previous Kotlin minor; compiler plugin artifacts versioned per Kotlin version as TestBalloon/Burst do), performance benchmarks (discovery and execution overhead vs `kotlin.test`), security/sandbox review of the Gradle plugin's Xcode invocations.
* Post‑1.0 tracks: Swift Testing interop (consume `#expect` results through ST‑0021 when a Swift host is present; emit Swift‑Testing‑compatible event stream), exit tests on macOS/Linux, benchmark trait via kotlinx‑benchmark, property‑based testing module, Wasm/WASI parity, coverage story for Native (LLVM profiling in Kotlin/Native when available).

## 7. Definition of done — maturity checklist

**A. Authoring (must be identical on every target)**
A1 `@Test`/`@Suite` incl. nested · A2 per‑test and per‑suite lifecycle · A3 parameterized tests with per‑case reporting · A4 dynamic tests · A5 suspend tests with virtual time · A6 conditional execution & assumptions · A7 timeouts · A8 display names, tags, bug links · A9 soft assertions · A10 known issues · A11 Power‑assert‑compatible assertions · A12 kotlin.test source compatibility.

**B. Execution**
B1 filter by name/tag/UID on every backend · B2 ordering incl. seeded random · B3 repetition and retry policies · B4 parallel execution with resource locks where the platform allows · B5 interceptors/listeners SPI · B6 attachments · B7 issue severity and cancellation · B8 JSON event stream + TeamCity + JUnit XML · B9 exit codes and CI‑friendly logging.

**C. Platform integration**
C1 JUnit Platform engine (Gradle, Maven, IntelliJ, Android Studio) · C2 `.xctest` bundle + host app, `xcodebuild test`, device destinations, `.xcresult`, Xcode navigator hierarchy, `-only-testing` per test/case · C3 stand‑alone runner for Linux/Windows/Android Native with the legacy `--ktest_*` flags · C4 JS/Wasm via Mocha/new browser DSL/WASI · C5 Gradle plugin with configuration cache, aggregated reports.

**D. Quality and governance**
D1 self‑test suite with golden event streams per backend · D2 binary‑compatibility validation and explicit API · D3 compatibility matrix (Kotlin, Xcode, JUnit, Gradle) published per release · D4 docs site with guides and migration paths · D5 sample apps (iOS app, macOS SwiftPM, Android, Desktop) in CI · D6 release cadence aligned with Kotlin releases.

## 8. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Compiler‑plugin API instability across Kotlin versions | Every Kotlin release can break discovery | Version artifacts per Kotlin version; test against EAP in CI; keep an annotation‑processing (KSP) fallback for discovery like Kotest 6 |
| Dependence on `kotlin.native.internal.test` (current code) | Breaks silently | Phase 1 removes it entirely |
| ObjC runtime tricks (`class_addMethod`, IMP blocks, `NSInvocation`) | Xcode changes could reject dynamically created test classes | Covered by meta‑tests on every Xcode beta; alternative path: generated ObjC/Swift stubs per test at build time |
| Kotlin/Native cannot catch ObjC exceptions (KT‑43719) → `XCTSkip` workaround | Skips may misbehave | Keep native IMP; follow KT‑43719; wrap in `foreignExceptionMode = objc-wrap` |
| Apple de‑emphasises XCTest in favour of Swift Testing | Backend could become legacy | XCTest remains the only API for UI/performance and the interop target (ST‑0021); design event model to match Swift Testing ABI v0 so a future Swift Testing backend is incremental |
| Swift Testing has no public registration ABI for non‑Swift tests | Cannot be a backend today | Track swift‑testing evolution; prototype emitting test‑content metadata records; meanwhile use XCTest interop in Xcode 27 |
| Kotlin Gradle plugin internals (`-tr`, `KotlinNativeTest`, test task wiring) change | Gradle integration breaks | Depend only on public KGP DSL; register our own tasks; integration tests against KGP versions |
| Threading constraints in XCTest (main thread, `continueAfterFailure`) vs coroutine tests | Deadlocks/flakiness | Executor runs on XCTest thread, offers `Dispatchers.Main` test dispatcher; documented rules |
| Scope creep towards Kotest/TestBalloon feature sets | Delays 1.0 | Gap‑table priorities drive phases; DSL is layered on the annotation model, not the reverse |

## 9. Decisions to take early

1. **Annotation model vs DSL first.** Recommendation: annotation model first (JUnit/Swift Testing familiarity, trivial kotlin.test migration), DSL (`testSuite { }`) in Phase 3 on top of the dynamic‑tests feature.
2. **Discovery technology.** Recommendation: Kotlin compiler plugin (FIR + IR), with a build‑time registry generator fallback for toolchains where the plugin is unavailable.
3. **JVM engine vs mapping onto Jupiter.** Recommendation: own JUnit Platform engine (uniform semantics, parameterized/dynamic/known‑issue behaviours identical to Apple), plus Jupiter interop module.
4. **Apple packaging default.** Recommendation: generate the `.xctest` bundle and principal class from Gradle; host app optional; keep today's "export framework + Swift glue" as a documented manual path.
5. **Compatibility promise.** Recommendation: support the two latest Kotlin minors and the two latest Xcode majors; publish a matrix per release.

## 10. Immediate next steps (first two sprints)

1. Bump Kotlin to 2.4.20 and fix the build; add Linux + macOS CI jobs with explicit schemes.
2. Introduce the `engine` module with the `TestDescriptor`/`TestEvent` model and a JVM unit‑test suite.
3. Prototype the compiler plugin: discover `kotlin.test.@Test` functions and emit a registry on `iosSimulatorArm64` and `jvm`.
4. Port `kotlin-test-native-xctest` to consume the registry instead of `GeneratedSuites`; generate one `XCTestCase` subclass per suite.
5. Add a Gradle task that assembles an `.xctest` bundle and runs it with `xcodebuild test-without-building` on a simulator, replacing the Swift `defaultTestSuite` glue in `iosApp`.
