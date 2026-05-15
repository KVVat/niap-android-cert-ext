# TestBed UI Plugins

This repository contains custom test plugins and target Android applications designed to work with the **TestBed Core** ecosystem.

## Project Structure

* **:common-utils**: Shared utility classes and extensions used by multiple plugins.
* **:test-sample**: A sample JUnit-based test plugin for Android device automation.
* **:apps:target-test-app**: A target Android application used for verifying test scenarios, featuring Room database integration.

## Getting Started

### Prerequisites

* **JDK 17** is required for both JVM plugins and Android app builds.
* **Project Layout**: The **TestBed Core** repository must be located at `../testbed-core` relative to this project root to enable automatic plugin deployment paths.

### 1. Building and Deploying Plugins

To compile a plugin into a JAR file and automatically deploy it to the TestBed Core's plugin directory, run the specific project's `jar` task. For the sample plugin:

`./gradlew :test-sample:jar`

The build script is configured to automatically place the generated JAR at:
`../testbed-core/composeApp/plugins/test-sample/test-sample.jar`

### 2. Building the Target Android App

To build the sample Android application for testing on a device:

`./gradlew :apps:target-test-app:assemble` (Use assemble not assembleDebug)

The output APK will be located at:
`apps/target-test-app/build/outputs/apk/debug/target-test-app-debug.apk`

### 3. Building the Agent

`./gradlew :tools:mutton-agent:bundleDebugAar`

`./gradlew :tools:mutton-agent:assembleAndroidTest`

## Artifacts and Packaging

The final artifacts of this project are deployed to the `testbed-core` project's `resources` and `plugins` directories. 
To package these artifacts into a single ZIP file for distribution, run:

```bash
./gradlew zipPluginsAndResources
```

This will generate a ZIP file at `build/distributions/plugins-and-resources.zip` containing:
* `plugins/`: Test plugin JARs (e.g., `test-sample.jar`).
* `resources/`: Target APKs and other resource files.

### Resource Copying
* **Automatic Copy**: The build script now automatically copies contents from `resources` directories of subprojects (e.g., `apps/openurl/resources/badssl.com-client.p12`) to the core resources directory during the `zipPluginsAndResources` task execution via `copyProjectResourcesToCore` task.

## JUnitBridge Specification

The `JUnitBridge` is provided by the TestBed Core to facilitate communication and resource access for test plugins. Tests running on the host side use this bridge to interact with the system and access environment details.

### Features & Provided Information
* **Logging Interface**: Provides standard logging methods (`logi`, `logp`, etc.) to report test progress and results back to the core app.
* **Path Resolution**: Provides absolute paths to critical directories and files:
  * `baseDir`: The base directory of the testbed execution.
  * `resourcesDir`: Directory containing resources (APKs, certs, etc.).
  * `resultsDir`: Directory where test results and reports should be saved.
  * `configFilePath`: Path to the active configuration file.
* **File Operations**: Tests use this information to reference files or write outputs on the host side, ensuring cross-platform compatibility.
* **Progress Callbacks**: Supports callbacks to report real-time progress and status updates to the UI.

Test developers should refer to `reference/TEST_CONVENTIONS.md` and existing samples for detailed usage of `JUnitBridge`.


## Development Notes

* **Dependency Management**: Plugins reference compiled classes from TestBed Core via local file paths. Ensure the core app has been compiled at least once.
* **JAR Configuration**: The `jar` task uses `DuplicatesStrategy.EXCLUDE` to safely bundle dependencies into the plugin JAR.
* **Directory Conventions**: Do not create directories that are not part of the standard Gradle project structure or defined in this project's conventions. For example, avoid creating `bin` directories containing source files; all sources must reside in `src/main/kotlin` or `src/main/java`. This is to prevent AI hallucinations or manual errors from cluttering the project.
