# Project Knowledge for Agent Execution

This document contains architectural context, workflows, and guidelines for LLM agents to develop, run, and verify security test cases in this repository.

---

## 1. Starting and Stopping TestBed Core (MCP Server)
This project integrates with a desktop-based automation runner and MCP server hosted by the `testbed-core` application on port `11452` (`localhost:11452`). The server must be active for the agent to interface with physical devices or emulators.

### How to Start the Server
Open a separate terminal window, navigate to the `testbed-core` repository directory, and start the desktop program:

```bash
cd ../testbed-core
./gradlew :composeApp:run
```

### How to Stop (Kill) the Server
To prevent port collisions, dual-instance conflicts, or general socket errors (`Address already in use`), identify and force-kill any running processes holding port `11452`. It is recommended to perform this cleanup before launching automated runs:

```bash
# Force-kill processes listening on port 11452
lsof -t -i :11452 | xargs kill -9
```

---

## 2. Referencing Security Documents (MDFPP-CC)
To ensure test accuracy and compliance with security requirements, review the target criteria documented under the `docs/` folder (such as [FIA_XCU_EXT.1.md](docs/FIA_XCU_EXT.1.md) or related Common Criteria files). 

If the document references are empty or need to be synchronized with the remote requirements repository, run:

```bash
git submodule update --init --recursive
```

---

## 3. Document-Driven Test Development (Supreme Directive)
The core operating principle of this project is **strict adherence of test implementations to the security requirements documents**:
* Before generating or modifying any JUnit test suites, the LLM agent **must** read the relevant functional requirements and test case evaluation criteria from the target files in the `docs/` folder.
* The written JUnit test assertions must align perfectly with the Pass/Fail decision boundaries defined in the criteria.

---

## 4. Test Development and Execution Workflow
When developing and running security test suites, follow this structured workflow (refer to the main tool references for deeper API mechanics):

1. **Requirements Reference**: Retrieve target specifications from the target documents in `docs/`.
2. **Test Generation**: Author the Kotlin/Java test suites inside the `agent-test` module (e.g., under `org.example.plugin.fiax509`). Ensure all log entries adhere to the logging design patterns (using helpers like `logi` or `logp`).
3. **Compilation and Loading**:
   * Compile the JUnit plugin JAR:
     ```bash
     ./gradlew :agent-test:jar
     ```
   * Invoke the `junit_test_reload` MCP tool to notify the server and load the newly compiled test definitions.
4. **Execution and Monitoring**:
   * Trigger execution using the `junit_test_execute` MCP tool (passing target class and method arguments).
   * Poll `junit_test_receive` to stream log messages in real-time and capture structural exit states (Pass/Fail reports and stack traces).

---

## 5. Automation MCP Tools & Specifications
The following MCP tools are exposed by the `testbed-core` interface for automated workflow execution:

* **`junit_test_reload`**: Reloads and updates the JUnit plugin JAR context on the server.
* **`junit_test_list`**: Returns a list of all current tests loaded in the environment (as a JSON array).
* **`junit_test_execute`**: Accepts a class name and optional method identifier to begin execution.
* **`junit_test_receive`**: Retrieves current logs (`status: "Running"`) or finalized structural details (`status: "Finished"`, Pass/Fail indicator, exception details).

> [!NOTE]
> `junit_test_receive` supports active streaming. For long-running operations (e.g., processes taking over 2 minutes), safely poll `junit_test_receive` to track task execution progress.

---

## 6. Utilizing Timers for Passive Wait States
When the agent is required to enter a wait state (e.g. waiting for a compilation step, a background script, or multi-stage device deployments to complete), **always schedule a 60-second timer using the `schedule` tool** rather than generating repeated active prompt-and-response loops. This saves token budgets and resources.

---

## 7. Configuring the Local EST Test Server (libest)
For testing security certificate acquisitions (EST protocol) and client-side mutual TLS actions, run a local mock container based on **Cisco libest** and NGINX (configured under `est-server/`).
* See [est-server/README.md](est-server/README.md) for full docker deployment guidelines.
* Ensure the environment signature algorithm is configured to **SHA-384** (via `EST_CERT_PROFILE=niap`) to properly satisfy Common Criteria / NIAP verification criteria during automated runs.

---

*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****
*** DONT USE AOSP BUILD ****

## 8. Custom Framework Build & Flash (Stallion)
When testing framework modifications (like `NetworkSecurityConfig` parsing) on a physical Pixel 10a (`stallion`), building the full OS using the correct internal target is required to prevent "Your device is corrupt" (AVB dm-verity) errors:

1. **Correct Lunch Target**: Always use `stallion-trunk_staging-userdebug` (or equivalent Google internal target). **Never use `aosp_stallion`**, as pure AOSP builds lack proprietary Vendor configs and signatures, causing boot failures when flashed over official internal builds.
2. **Build Command**: Use `m dist` to package all images:
   ```bash
   source build/envsetup.sh
   lunch stallion-trunk_staging-userdebug
   m dist
   ```
3. **Flashing & Publishing**: The agent **must never** execute fastboot commands or copy OS images back into the Git repository workspace. Upon a successful build, the agent's role is to copy the images from `out/dist/` directly to the Google Drive mount at `/usr/local/google/home/wkouki/DriveFileStream/My Drive/android_security/stallion/<yyyyMMdd_HHmmss>/`. Flashing the physical device via USB is the sole responsibility of the user, executing the flash script locally.

---

## 9. POC Proposal & Reviewer Feedback Insights
When designing and discussing the `niap-strict` NetworkSecurityConfig profile with Android platform reviewers, remember these critical architectural decisions:

1. **Per-App Scoping**: The `<security-profile name="niap-strict">` and `<est-server>` settings are strictly scoped **per-app** via the `network-security-config.xml`. They do NOT globally override the Android OS TLS behavior.
2. **Dynamic Revocation Fetching vs. Static Resources**: MDF PP (`FIA_X509_EXT.1.2`, `1.4`) requires the platform to dynamically read the exact CRL/OCSP endpoints (CDP/AIA extensions) from the server's certificate during the TLS handshake and fetch them over the network. Shipping static revocation files inside the app's `res/raw/` is non-compliant because revocation lists have very short lifespans.
3. **Out-of-band Plaintext CRL Fetching**: Fetching CRL over HTTP is an industry standard (RFC 6960) to avoid HTTPS chicken-and-egg loops. Because an app setting `cleartextTrafficPermitted="false"` blocks standard Java CRL fetches (tracked in b/218682652), we MUST fetch CRL out-of-band via a system service to safely bypass the app's restriction.
4. **Trust Anchors (`@raw` vs `user`)**: While user-installed certificates (`src="user"`) are blocked by the strict profile to prevent local MITM risks, developer-embedded certificates (`src="@raw/..."`) are treated as "Embedded CA" under MDF PP and are fully permitted alongside system trust anchors.
5. **Exception Handling Design**: We DO NOT create new API exception subclasses (like `NiapCertificateException`) because standard HTTP clients (like OkHttp) wrap them in `SSLHandshakeException` anyway. Instead, we throw standard `CertPathValidatorException` with extremely detailed String messages so developers can clearly identify NIAP rule violations in Logcat.

---

## 10. System Apps vs Third-Party Apps (`cert-manager-system`)
**CRITICAL RULE**: The `cert-manager-system` is a Privileged System App. You must **NOT** configure its `network_security_config.xml` to use `<security-profile name="niap-strict">`. The NIAP strict TLS profile is intended strictly for third-party/user-installed applications (like `cert-test-app`). System services should maintain their standard network security posture.

---

## 11. Required Verification Environment Combination
When validating Phase 3 and beyond, based on the `niap_poc_proposal_draft.md`, the verification MUST be performed with the following specific combination:
1. **Patched OS**: The POC build flashed with the custom `NetworkSecurityConfig` modifications.
2. **`cert-manager-system`**: Must be explicitly installed as a Privileged System App (located in `/system/priv-app/`).
3. **`cert-test-app`**: Installed as a standard third-party application.
All tests must ensure this exact combination is in place.
