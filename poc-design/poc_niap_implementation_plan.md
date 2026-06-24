# NIAP POC Environment Implementation Plan

This document organizes the concrete phases of work required to natively integrate the NIAP strict validation features into the POC environment, based on `niap_poc_proposal_draft.md`.

## Correct Verification Environment Combination
Before validating the behaviors of any phase, the physical device (or emulator) MUST be set up with the following strict combination:

1. **Patched OS (`stallion-trunk_staging-userdebug`)**
   - The device must be flashed with a custom build that includes the `NetworkSecurityConfig` modifications.
2. **`cert-manager-system` (Privileged System App)**
   - Must be installed in `/system/priv-app/`.
   - Cannot contain `<security-profile name="niap-strict">` in its `network_security_config.xml` (must use standard TLS posture).
3. **`cert-testapp-system` (System-Level Test App for POC Verification)**
   - Configured to use `<security-profile name="niap-strict">` to trigger the POC framework's native validation layer and verify system-level traffic.

***DON'T NEVER REFER cert-manager and cert-testapp in this Task!!***

## Build & Flash Protocol (Agent vs User)
> [!CRITICAL]
> **STRICT SECURITY & SANDBOX BOUNDARY - DO NOT VIOLATE**
>
> 1. **Agent's Role (Build & Publish ONLY)**:
>    - The AI agent's responsibility is solely to compile the POC OS (`m dist`), monitor the build, and publish the output.
>    - **NO LOCAL COPYING**: The agent **must never** copy built OS image zip files, bootloader, or radio images to the Git workspace directory (e.g., `poc-design/`, `cert-poc-design/`). These are multi-gigabyte binary files that will corrupt/pollute the repository.
>    - **DIRECT DRIVE PUBLISHING**: Upon a successful build, the agent must copy the images *directly* from `/usr/local/google/home/wkouki/android-26Q2/out/dist/` to the Google Drive mount at `/usr/local/google/home/wkouki/DriveFileStream/My Drive/android_security/stallion/<yyyyMMdd_HHmmss>/`, where `<yyyyMMdd_HHmmss>` is the current timestamp.
>    - **NO FASTBOOT/FLASH COMMANDS**: The agent runs in a remote server/SSH environment where the physical USB port is not accessible. The agent **must never** execute `fastboot` or any flashing commands (`fastboot flash`, `fastboot update`, etc.).
> 
> 2. **User's Role (Download & Local Flash ONLY)**:
>    - The physical Pixel 10a (`stallion`) device is connected via USB *only* to the user's local physical machine.
>    - The user is responsible for downloading the compiled images from the Google Drive timestamped directory onto their local machine and running the flashing script locally.

---

## Phase Definitions and Tasks

### Phase 1: Workspace & Sandbox Setup (Completed)
* **Goal**: Establish a safe environment to edit framework files before injecting them into the actual tree.
* **Tasks**:
  - Pull `XmlConfigSource_conscrypt.java`, `XmlConfigSource_platform.java`, `NetworkSecurityConfig.java`, and `NetworkSecurityTrustManager.java` into `aosp_sandbox/`.

### Phase 2: Configuration Layer Parser (Completed)
* **Goal**: Enable the POC framework to parse the new `<security-profile>` tag from an app's `network_security_config.xml`.
* **Tasks**:
  - Modify `XmlConfigSource` parsers to recognize `<security-profile name="niap-strict">`.
  - Log `NIAP strict profile loaded` when successfully parsed.
  - Flash the OS and verify the log output via `cert-testapp-system`.

### Phase 3: Server Validation Layer (Completed)
* **Goal**: 
  - `NetworkSecurityConfig`でパースされたXMLのプロファイル情報（`niap-strict`）を、TLS接続を実際に検証する `NetworkSecurityTrustManager` に伝播させ、OSのネイティブレベルで振る舞いを変化させることが最終的なゴール（Goal状態）です。
  - **何ができるようになるか**: これが完了すると、アプリ側で複雑な `X509TrustManager` や `OkHttp` のカスタマイズを一切行わなくとも、マニフェストで `<security-profile name="niap-strict">` を指定するだけで、OSが自動的に政府基準（NIAP MDF PP）の厳格な証明書検証を強制するようになります。
* **テストアプリ(`cert-testapp-system`)での具体的な表示**:
  - `mTLS` ボタンなどを押して通信した際、サーバー証明書が要件（RSA-3072以上など）を満たさない場合、アプリ側には通常の「通信エラー（`SSLHandshakeException`）」として表示され、通信が完全にブロックされます。
  - 要件を満たす正当なサーバーであれば、エラーなく通信が成功し「ステータス: 成功」や取得データがUIに表示されます。
* **出力して確認すべき具体的なLogcatログ**:
  - 通信開始時: `NetworkSecurityTrustManager: Enforcing NIAP strict validation for connection`
  - 違反検知時（ブロック時）: `NetworkSecurityTrustManager: NIAP Violation: RSA key size 2048 is less than required 3072` または `Missing mandatory extension: BasicConstraints`
  - 検証成功時: `NetworkSecurityTrustManager: NIAP strict validation passed`
* **Tasks**:
  - Modify `NetworkSecurityConfig.java` to hold the parsed `mSecurityProfile` value.
  - Modify `NetworkSecurityTrustManager.java`'s `checkServerTrusted()` method.
  - Inject the native implementations for:
    - Signature algorithm strength (RSA-3072 / SHA-384 / EC-384).
    - Mandatory Extension OIDs (2.5.29.35, .14, .15).
    - Basic Constraints & EKU field enforcement.
    - TLD Wildcard restrictions.
    - Native `CertPathValidator` revocation checking (OCSP/CRL).
  - Throw detailed `CertPathValidatorException` on violations.
  - **Compile (`m dist`), Flash, and Verify using the strict environment combination.**

### Phase 4: Client Identity & EST Layer (Completed)
* **Goal**: Implement native EST client support and Binder IPC for hardware-backed keys.
* **Verification Steps**:
  1. **Publish Built Artifacts (Agent Task)**:
     - The agent compiles the OS and copies the build outputs (`stallion-img-*.zip`, `bootloader.img`, `radio.img`) from the build directory directly to a new timestamped directory:
       `/usr/local/google/home/wkouki/DriveFileStream/My Drive/android_security/stallion/<timestamp>_dist/`
  2. **Flash the Device (User Task)**:
     - The user downloads the update zip and images from the Google Drive directory onto their local physical machine.
     - The user connects the device via USB and executes the flash script `flash_poc_stallion.sh` locally.
  3. **daemon verification**:
     - Ensure the privileged app `cert-manager-system` is present in `/system/priv-app/CertManagerGoogle`.
  4. **Setup Port Forward**:
     - Establish the reverse tunnel for the EST server port:
       ```bash
       adb reverse tcp:8443 tcp:8443
       ```
  5. **Execute Test**:
     - Open `cert-test-app` and trigger **「mTLS」**.
  6. **Expected Logcat Logs**:
     - Run the log query:
       ```bash
       adb logcat -v time | grep -E "NetworkSecurityKeyManager|RemoteSigningProvider"
       ```
     - During enrollment:
       - `NetworkSecurityKeyManager: Enrolling client identity via EST for alias: <alias_name>`
       - `NetworkSecurityKeyManager: EST enrollment succeeded for alias: <alias_name>`
     - The TLS handshake completes successfully without any modifications to client-side HTTP/OkHttp libraries.

### Phase 5: Exception and Revocation Bypass Mechanisms (Upcoming)
* **Goal**: Address the catch-22 of `cleartextTrafficPermitted="false"` blocking native HTTP CRL fetches.
* **Tasks**:
  - Implement the secure bypass in the framework to fetch CRL/OCSP out-of-band via the system service, preventing `SSLHandshakeException` loops while strictly adhering to the app's standard cleartext boundaries.

### Phase 6: Custom Security Profiles & Revocation Tuning (Completed)
* **Goal**: Support domain-specific overrides via `<security-profile name="custom">` to selectively loosen cryptography limits (like EC-256 or SHA-256) and manage PKIX revocation checking behavior.
* **Tasks**:
  - Implement parsing for `<niap-min-ec-key-size>`, `<niap-min-rsa-key-size>`, `<niap-rfc8603-strict-sigalg>`, and `<niap-pkix-revocation-mode>`.
  - Add PKIX revocation check mode switches: `"strict"` (Option.NO_FALLBACK), `"lax"` (allows Option.ONLY_END_ENTITY and Option.SOFT_FAIL), and `"none"` (skips revocation validation entirely).
  - Verify behavior by executing connection tests to public sites (`whitehouse.gov`, `csrc.nist.gov`) under `"none"` / `"lax"` overrides, confirming handshakes succeed and pages render in WebView.

---

## Security Considerations & Design Notes

### PKIX Revocation Check Trust Anchors
* **Issue**: The original sandbox implementation of the `checkRevocation()` PKIX path validator was hardcoded to load and use `AndroidCAStore` (the system trust store). This completely ignored any custom developer-embedded CA certificates configured via `<certificates src="@raw/..."/>` in the app's `network_security_config.xml`.
* **Fix/Design Decision**: We updated `checkRevocation()` to dynamically load the trust anchors directly from the active `NetworkSecurityConfig` (`mNetworkSecurityConfig.getTrustAnchors()`). This allows local developer-embedded CAs to be trusted during the revocation check, supporting private server environments (e.g., mock local EST servers) while strictly adhering to the CC/MDF PP specification which allows "Embedded CA" as trust anchors.
* **Mitigation**: To prevent attackers from exploiting user-installed certificates to MITM the strict validation flow, we implemented explicit filtering in `NetworkSecurityConfig.Builder.build()` to block and strip out any user-installed root CAs (`UserCertificateSource`) if `<security-profile name="niap-strict">` is active.

### Conscrypt Integration vs. App-Space Custom Conscrypt (APEX Module)
* **Design Decision**: Natively modifying Conscrypt (the platform's JSSE provider) to intercept default key manager initializations.
* **Tradeoffs**:
  * **App-Space Bundling (Dismissed)**: Bundling a custom Conscrypt jar/aar inside the client application (using `Security.insertProviderAt`) would avoid OS platform-level modifications. However, this is strongly discouraged because:
    1. **FIPS/CC Certification Overhead**: Any application carrying its own cryptographic implementation must undergo independent, high-cost FIPS 140-3 and Common Criteria certifications. By keeping the cryptography inside the OS native provider, the client app inherits the platform-level FIPS/MDF PP certifications.
    2. **Middleware Transparency Defeated**: Third-party pre-compiled binary SDKs (like GMS Core, Firebase, or external API client libs) might bypass or ignore app-space JSSE provider overrides, leading to inconsistent security enforcement. Platform-level interception guarantees 100% compliance of all network traffic out-of-the-box.
  * **Native Platform Integration (Adopted)**: By editing Conscrypt's initialization code in `SSLParametersImpl.java` and routing signing operations via the custom JCA `RemoteSigningProvider`, we achieve clean, transparent compliance.
  * **Compilation/Classpath Resolution**: Since Conscrypt is built as both a native Android platform library and a host-based OpenJDK library (`conscrypt-unbundled`), we avoided classpath errors (from referencing Android-specific package `android.security.net.config` in shared common source) by abstracting the wrapping into `Platform.java`. We created class-path specific `wrapKeyManager()` delegates in `platform/`, `android/` and `openjdk/` versions of the `Platform` class.

### Package Visibility Constraints (AppsFilter Interaction Block)
* **Issue**: In Android 11+ (API 30+), strict package visibility rules are enforced. When an unprivileged third-party application (such as `cert-test-app` or `strict-browser-app`) attempts to load the strict profile parameters by creating a package context for the system daemon `com.android.niap.cert.service`, the Package Manager's `AppsFilter` blocks the query, throwing a `PackageManager.NameNotFoundException`. This causes the strict validation parameters to silently fail to resolve, resulting in a validation bypass where standard platform parameters are used instead of strict rules.
* **Resolution (Optimal Design)**: To maintain zero-code transparency for client applications without modifying static OS-level configuration files (`sysconfig` XML), the system daemon app `cert-manager-system` declares itself as globally queryable. This is achieved by adding `android:forceQueryable="true"` to the `<application>` tag in the daemon's [AndroidManifest.xml](file:///usr/local/google/home/wkouki/AndroidStudioProjects/niap-android-cert-ext/cert-manager-system/src/main/AndroidManifest.xml). Because the daemon runs as a privileged system app signed with the platform certificate, the Package Manager respects this attribute, ensuring that the framework-level `resolveNiapStrictProfile` call (executing under the client app's context) can always successfully query and create the package context for the daemon to read its XML configuration.



