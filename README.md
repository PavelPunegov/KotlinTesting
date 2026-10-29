# Kotlin Testing

Kotlin Multiplatform Library that provides an ability to run Kotlin tests (`kotlin.test`) using XCTest framework.

## Writing tests

Tests can be written both in Kotlin and Swift.

### Kotlin tests

See `frameworkTesting` module and `frameworkTesting/src/nativeMain/kotlin/tests.kt`

### Swift tests

See `iosApp/swiftHostApp/swiftHostAppTests/swiftHostAppTests.swift` where Kotlin Framework with tests was imported.

## Running tests

Tests can be run in Xcode or using the command-line

### Executing Xcode build

```shell
cd iosApp/swiftHostApp 
xcrun xcodebuild test -scheme swiftHostApp -destination "platform=iOS Simulator,name=iPhone 16 Pro"
```

### Viewing test results

Open the `.xcresult` file in Xcode or use the `xcresulttool` in the command-line

```shell
xcrun xcresulttool get test-results summary --path <PATH_TO_XCRESULT>
```

## Documentation

- [Swift Testing vs XCTest vs kotlin.test vs JUnit 5/6 — capability comparison](docs/testing-frameworks-comparison.md): API comparison by area, the list of features missing in `kotlin.test`, what `kotlin.test` provides on each Kotlin Multiplatform target, and a survey of other Kotlin testing libraries.
- [Swift Testing internals](docs/swift-testing-internals.md): how Swift Testing builds on XCTest bundles, discovers tests from compile-time records in the `__swift5_tests` section instead of the Objective-C runtime, how xcodebuild and SwiftPM run it, and how XCTest interop works.
- [Development plan](docs/development-plan.md): resolved architecture decisions (`kotlin.test`-compatible annotations, KSP-generated runners, mapping onto JUnit Jupiter on Kotlin/JVM, Kotlin/Native `.xctest` bundles for Apple targets), feature roadmap, phases, maturity checklist and risks.
