# Architectural Proposal: Native NIAP Integration via NetworkSecurityConfig (Android 17)

Based on the initial investigation regarding the addition of a `(domain-config)` element to the Mainline `NetworkSecurityConfig`, we have outlined a highly feasible architecture that embeds the core features of the [niap-android-cert-ext](https://github.com/KVVat/niap-android-cert-ext) project natively into Android.

> **Background on niap-android-cert-ext:**
> [niap-android-cert-ext](https://github.com/KVVat/niap-android-cert-ext) is a standalone project designed to fulfill the strict National Information Assurance Partnership (NIAP) Mobile Device Fundamentals Protection Profile (MDF PP) requirements. It provides a background service for Enrollment over Secure Transport (EST) provisioning and advanced X.509 certificate validation (including strict EKU checks, revocation checking, and cryptography limits).

The primary advantage of this design is **Middleware Transparency**: Application developers and third-party libraries (like `OkHttp3` or `HttpsURLConnection`) will require **zero code changes**. They will automatically inherit NIAP-compliant behaviors simply by defining the appropriate XML network security configuration.

---

## Proposed Architecture: The 3-Layer Separation

To ensure a clean separation of concerns within AOSP, the integration will be divided into three distinct roles: Configuration, Server Validation, and Client Identity Provisioning.

### 1. Configuration Layer (NetworkSecurityConfig)
Instead of forcing developers to manually configure every strict NIAP parameter (which would bloat `network-security-config.xml`), we propose a **Preset + Custom** configuration model.

Developers can choose from ~3 predefined profiles (e.g., `niap-strict`, `niap-relaxed`) to cover 90% of use cases. If granular control is needed, they can specify `name="custom"` and explicitly declare individual `<niap-*>` tags. 

`NetworkSecurityConfig` operates strictly in the app's memory space and acts merely as the **state holder and factory** for the downstream managers.

#### Decoupled Policy Resolution (Dynamic XML Loader)
To prevent policy rules (such as permitted cipher suites and wildcard bans) from being hardcoded inside the Mainline Conscrypt module, the default parameters for the `niap-strict` profile are loaded dynamically from a system-wide XML asset:
1. The default strict rules are packaged inside the privileged system app `cert-manager-system` (`com.android.niap.cert.service`) under standard NetworkSecurityConfig schema: `res/xml/niap_strict_network_security_config.xml`.
2. When an application requests `<security-profile name="niap-strict" />`, the Conscrypt parsing engine calls `context.createPackageContext("com.android.niap.cert.service", Context.CONTEXT_IGNORE_SECURITY)` to retrieve the target resources.
3. The parser resolves and parses that XML configuration, dynamically mapping those strict presets onto the calling application's config builder. This ensures 100% decoupling, allowing administrators to modify OS-level presets by simply updating the privileged daemon app package.

**Note on Scope:** Because this architecture relies on `network-security-config.xml`, the `<security-profile>` and `<est-server>` URL definitions are applied strictly on a **per-app basis** by the application developer. They do not globally override the Android OS TLS behavior. Each app can define its own distinct EST server to provision its identity for its specific enterprise backend.
**Example 1: Using a Preset Profile**
```xml
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">example.com</domain>
        <!-- Automatically applies all NIAP strict validation rules -->
        <security-profile name="niap-strict" />
        <est-server url="https://est.example.com" />
    </domain-config>
</network-security-config>
```

**Example 2: Using Custom Overrides**
```xml
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">custom.example.com</domain>
        <security-profile name="custom" />
        
        <!-- Fine-grained configuration under 'custom' -->
        <niap-rfc8603-strict-sigalg>true</niap-rfc8603-strict-sigalg>
        <niap-prohibited-tld-wildcards>
            <tld>*.COM</tld>
            <tld>*.NET</tld>
        </niap-prohibited-tld-wildcards>
        <niap-allowed-cipher-suites>
            <cipher>TLS_AES_256_GCM_SHA384</cipher>
            <cipher>TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384</cipher>
        </niap-allowed-cipher-suites>
        <niap-trust-anchor-policy>BOTH_CA</niap-trust-anchor-policy>
        <niap-limit-tls-version>1.2</niap-limit-tls-version>
        <niap-enforce-mandatory-extensions>true</niap-enforce-mandatory-extensions>
        <niap-enforce-eku>true</niap-enforce-eku>
        <niap-required-eku>serverAuth</niap-required-eku>
        <niap-enforce-ca-constraints>true</niap-enforce-ca-constraints>
        
        <est-server url="https://est.custom.com" />
    </domain-config>
</network-security-config>
```

#### Resolving Conflicts with Existing Domain Settings (Augment, Don't Override)
Introducing a broad `<security-profile>` raises the question of overlapping with existing `<domain-config>` rules (e.g., `<trust-anchors>`, `cleartextTrafficPermitted`). To ensure stability, the profile acts as an **Augmenter and Restrictor**, not a destructive override:
* **Cleartext Traffic:** The profile does *not* force `cleartextTrafficPermitted="true"` for the app. The necessary plaintext HTTP fetches for CRL/OCSP are performed out-of-band by the `cert-manager` System Service (or via a secure bypass within the TrustManager), preserving the app's strict TLS boundaries.
* **Trust Anchors:** If an app specifies `<certificates src="user" />` (User-installed certs), their inclusion is governed by the `<niap-trust-anchor-policy>` setting. If this policy is explicitly configured as `SYSTEM_CA_ONLY` (or resolved as such under strict customized domain overrides), the `NetworkSecurityConfig.Builder` will dynamically filter out user-installed root CAs during compilation, ensuring strict environments cannot be MITM'ed. Under default `niap-strict` profile resolution (which inherits `BOTH_CA` configuration from the system daemon resource), user CAs are trusted alongside system anchors unless otherwise overridden by the developer. Note that certificates explicitly embedded by the developer via `<certificates src="@raw/..." />` act as "embedded CA" and remain trusted under all policies.
* **Pinning:** Existing `<pin-set>` configurations remain fully functional. The strict NIAP validations operate as a prerequisite (AND condition) before the app's custom pinning checks are evaluated.

### 1b. Detailed Custom Tuning Overrides & Security Implications (MDF PP TLS Compliance)

To address real-world deployment challenges where public resources (like NIST or Google APIs) must coexist with strict internal network endpoints, we support a `<security-profile name="custom">` configuration. The following sections describe the behavior, default values, and safety boundaries for each override parameter.

#### XML Tuning Parameters & `niap-strict` Defaults

When `<security-profile name="niap-strict" />` is declared, the engine dynamically loads its rules from the privileged daemon resources. These properties resolve to the following default configurations unless overridden inside a `"custom"` configuration block:

| XML Tag | Type | Default `niap-strict` Value | Purpose |
|---|---|---|---|
| `<security-profile name="custom"/>` | String | `"niap-strict"` | Activates fine-grained rule evaluation (overrides presets). |
| `<niap-rfc8603-strict-sigalg>` | Boolean | `true` | If `true`, restricts signature algs to SHA-384/512 (CNSA). |
| `<niap-prohibited-tld-wildcards>` | List (Strings) | `[*.COM, *.NET, *.ORG, *.GOV, *.EDU]` | Suffixes for which wildcard certificates are prohibited. |
| `<niap-allowed-cipher-suites>` | List (Strings) | `[TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384, TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256, TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384, TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384]` | Allowed TLS cipher suites. |
| `<niap-trust-anchor-policy>` | String | `"BOTH_CA"` | Specifies trust store anchors: `"SYSTEM_CA_ONLY"` or `"BOTH_CA"`. |
| `<niap-limit-tls-version>` | String | `"1.3"` | If `"1.2"`, restricts connection strictly to TLS 1.2 only. |
| `<niap-enforce-mandatory-extensions>` | Boolean | `true` | Enforces mandatory extensions (AKID, SKID, KeyUsage). |
| `<niap-enforce-eku>` | Boolean | `true` | Enforces check for Extended Key Usage. |
| `<niap-required-eku>` | String | `"serverAuth"` | Specific EKU value or raw OID to enforce. |
| `<niap-enforce-ca-constraints>` | Boolean | `true` | Enforces that end-entity certificates must not be CAs. |
| `<niap-pkix-revocation-mode>` | String | `"strict"` | Path validation mode: `"strict"`, `"lax"`, or `"none"`. |

---

#### A. Prohibited TLD Wildcards (`<niap-prohibited-tld-wildcards>`)
* **Behavior:** Defines a list of TLD suffixes for which wildcard certificates (e.g. `*.com`, `*.net`) are strictly prohibited.
* **Strictness Boundary:** **The more items declared in this list, the stricter the policy.** More domains will be barred from using wildcards, mitigating DNS spoofing risks.
* **Default/Strict Preset:** When `niap-strict` is active, the engine mandates a hardcoded list containing `*.COM`, `*.NET`, `*.ORG`, `*.GOV`, and `*.EDU`. Developers using the `custom` profile can choose to tighten or loosen this list.

#### B. Allowed Cipher Suites (`<niap-allowed-cipher-suites>`)
* **Behavior:** Restricts the active TLS handshake parameters to the specified cipher suites.
* **Strictness Boundary:** **The more suites declared, the looser/more backward-compatible the connection is.** If no suites are declared (default), the verification engine falls back to the Conscrypt platform default list (maximum compatibility).
* **MDF PP Alignment:** To satisfy strict MDF PP / CNSA (Commercial National Security Algorithm) criteria, the application should restrict this list to only SHA-384 based suites (e.g., `TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384`).

#### C. PKIX Revocation Check Mode (`<niap-pkix-revocation-mode>`)
* **Behavior:** Governs how the native path validator asserts certificate revocation status (OCSP and CRL checks).
* **Supported Modes:**
  1. `"strict"` (Default / `niap-strict` preset): Chains the entire path from leaf to root using `Option.NO_FALLBACK`. If any intermediate CA certificate lacks defined OCSP responders or CRL endpoints, the connection is instantly severed. **Required for MDF PP FCS_TLSC_EXT.1 / FIA_X509_EXT.1.2 compliance.**
  2. `"lax"`: Restricts validation to the end-entity (leaf) certificate only (`Option.ONLY_END_ENTITY`) and allows warning fallback (`Option.SOFT_FAIL`) if validation servers are temporarily offline. Intended for connecting to public services while maintaining basic leaf safety.
  3. `"none"`: Bypasses revocation checks entirely.
* **Security Warning:** **`"none"` and `"lax"` modes must only be used in internal development/staging environments.** For official Common Criteria / NIAP compliance audits on production builds, this mode must resolve to `"strict"`.

### 1c. Official Configuration Resolution Specification (Preserved Behavior Cases)

To guarantee predictable behavior and ensure security compliance without disrupting standard applications, the NIAP strict validation engine resolves the XML configuration according to four distinct cases:

| Case | Configuration State | NIAP Validation Behavior | Use Case / Rationale |
|---|---|---|---|
| **Case 1** | `<base-config>` explicitly specifies `<security-profile name="niap-strict">`. | **Enforced Globally on Out-of-Bounds.** Strict validation is applied to all out-of-bounds (unlisted) domains. Any out-of-bounds domain failing strict rules is blocked. | Standard high-security enterprise app deployment. Out-of-bounds domains are blocked by default. |
| **Case 2** | `<base-config>` does **not** specify `niap-strict` (or specifies something else / is empty). | **Enforced Only on Specific Domains.** Out-of-bounds domains bypass strict validation (connect normally). Any `<domain-config>` explicitly specifying `niap-strict` or `custom` is still strictly validated. | Hybrid app deployment. Out-of-bounds public resources are allowed, while internal endpoints are strictly secured. |
| **Case 3** | There is **no** `<base-config>` element present in the XML. | **Enforced Only on Specific Domains.** Same behavior as Case 2. Out-of-bounds domains connect normally, while explicitly configured domain profiles are enforced. | Developer convenience. Allows securing specific endpoints without needing a global base configuration. |
| **Case 4** | The application has **no** `network_security_config.xml` (or it is not declared in the manifest). | **Completely Bypassed.** The NIAP validation processing itself does not occur. Normal platform TLS behavior is maintained. | Legacy or standard consumer applications. Ensures zero performance or security footprint on apps that do not opt-in. |

### 2. Server Validation Layer (NetworkSecurityTrustManager)
**Role:** Replacing the standalone `cert-lib` validator library.
Currently, Android's `NetworkSecurityConfig` automatically instantiates and provides a `NetworkSecurityTrustManager` to the JSSE layer. We will embed the strict NIAP validation logic directly into this class.

* **How it works:** When standard HTTP clients initiate a TLS handshake, the JSSE layer invokes `checkServerTrusted()`. Because the `NetworkSecurityTrustManager` holds a reference to the XML configuration, it knows if `<security-profile name="niap-strict">` is active.
* **Execution:** It enforces signature strength (RSA-3072 / SHA-384), wildcard restrictions, and performs **OCSP/CRL revocation checking** natively. The HTTP client remains completely unaware, receiving a standard `CertificateException` if validation fails. Instead of introducing a new custom subclass (which would simply be wrapped by `SSLHandshakeException` in clients like OkHttp), we throw a standard `CertPathValidatorException` with extremely detailed string messages (e.g., `"NIAP strict profile: Server RSA key < 3072 bits"`). This ensures developers immediately see the specific rule violation in Logcat without requiring new public APIs.

### 3. Client Identity & EST Layer (NetworkSecurityKeyManager + cert-manager-system)
**Role:** Handling EST communication natively while securely delegating hardware key generation.

Acquiring a certificate via EST (RFC 7030) requires network communication and interaction with the hardware KeyStore. To maintain perfect isolation, this layer is split across two processes:

* **`NetworkSecurityKeyManager` (Executes in the App's Process / PID)**
  * **Role:** Orchestrates the EST enrollment flow and handles **all actual network communication**. Because it runs in the app's PID, its HTTPS requests to the EST server are naturally governed by the app's own `network_security_config.xml`.
* **`cert-manager-system` (Executes in a Privileged System Process / PID)**
  * **Role:** Acts as a secure, network-isolated proxy to the AndroidKeyStore. It **never performs network communication**. It only generates hardware-backed keys and creates Certificate Signing Requests (CSRs).

* **How it works (Binder IPC Flow):** 
  1. When the server requests mTLS, the JSSE layer asks `NetworkSecurityKeyManager` for a client certificate.
  2. If none exists, `NetworkSecurityKeyManager` calls `cert-manager-system` via Binder: *"Generate a KeyPair and return a CSR for this alias."*
  3. `cert-manager-system` executes the KeyStore operations and returns the raw CSR bytes.
  4. `NetworkSecurityKeyManager` (App PID) executes the HTTPS POST to the EST server using the CSR, and receives the signed certificate.
  5. `NetworkSecurityKeyManager` calls `cert-manager-system` via Binder: *"Store this signed certificate in the KeyStore."*
  6. The `KeyManager` completes the mTLS handshake using the populated KeyStore alias.

---

## Architectural Considerations (Addressing b/218682652)

Based on prior discussions among the Android Security team, we have incorporated two critical safety mechanisms into this design to prevent unintended side effects on standard applications:

### 1. Handling Plaintext HTTP for CRL/OCSP Fetches
Many government CRL/OCSP endpoints historically operate over unencrypted HTTP. However, Android's network security defaults to blocking cleartext traffic (`cleartextTrafficPermitted="false"`), creating a catch-22 for in-framework revocation checks. 
**Solution:** By delegating the physical fetching of revocation lists to the `cert-manager-system` daemon, we avoid violating the app's primary cleartext boundaries. To prevent arbitrary cleartext exfiltration exploits, this bypass is strictly guarded by a declarative `<crl-endpoint-whitelist>` defined inside the application's XML configuration. 

During the handshake, the `NetworkSecurityTrustManager` matches the extracted CRL Distribution Points (CDP) URL against this whitelist. The daemon then performs a double-check on the calling package's configuration and fetches the CRL only if it matches. 

Furthermore, MDF PP (`FIA_X509_EXT.1.2`, `1.4`) dictates that precise CRL/OCSP endpoints must be dynamically obtained by reading the AIA or CDP extensions from the server's certificate during the TLS handshake. Shipping static CRLs as `res/raw/` resources is non-compliant due to their short lifespans. Out-of-band dynamic fetching over HTTP (RFC 6960) via this secure whitelist mechanism remains the only compliant solution that prevents HTTPS chicken-and-egg loops.

---

### 2. Gating and MDM Independence (Prioritizing Developer Configuration)
To facilitate testing and development without requiring expensive MDM/enterprise management infrastructure, the strict profile is designed to be independent of the active DevicePolicyManager (DPM) state.
**Solution:** If an application explicitly declares `<security-profile name="niap-strict">` in its `network-security-config.xml`, the validation engine (Conscrypt) will **always enforce the strict profile rules** (including revocation checking and signature bounds), regardless of whether the device has enabled Common Criteria mode. 

If the device policy manager *does* globally enforce Common Criteria mode (`DevicePolicyManager.isCommonCriteriaModeEnabled()`), the system will globally mandate these checks for all target apps, effectively overriding or upgrading default profiles. This hybrid design ensures secure enterprise deployment while permitting standalone testing on stock/unmanaged devices.

---

### 3. IPC Security and Signature Oracle Prevention (Binder Layer)
Because `cert-manager-system` executes as a privileged system process (`priv-app`) with access to the Android KeyStore, we must prevent unauthorized third-party applications from using the service to generate signatures (acting as a "Signature Oracle").
**Solution:** 
1. **UID/Alias Ownership Mapping**: The daemon service maintains a secure runtime mapping between the calling application's UID (obtained via `Binder.getCallingUid()`) and the public keys/aliases generated during its corresponding EST enrollment.
2. **Access Control Enforcement**: When an application calls the `sign(alias, digest)` IPC endpoint, the daemon resolves the calling UID to its package name, inspects its network configuration, and verifies that the requesting package is indeed the legitimate owner of the target alias and public key. If the UID does not own the key, the request is immediately rejected, preventing malicious apps from exfiltrating cryptographic proof.

---

### 4. Package Visibility Constraints (AppsFilter Interaction Block)
* **Challenge:** In Android 11+ (API 30+), strict package visibility rules are enforced. When an unprivileged third-party application (such as `cert-test-app` or `strict-browser-app`) attempts to load the strict profile parameters by creating a package context for the system daemon `com.android.niap.cert.service`, the Package Manager's `AppsFilter` blocks the query:
  ```
  AppsFilter: interaction: PackageSetting{... manager/10345} -> PackageSetting{... strictbrowser/10369} BLOCKED
  ```
  This causes `createPackageContext` to throw a `PackageManager.NameNotFoundException`, preventing the application from dynamically resolving the NIAP configuration parameters from the system daemon.
* **Solution**: To allow applications to dynamically query the daemon for security profile resolution without requiring manual manifest edits in client applications, the platform must explicitly declare the manager daemon package as globally visible. This is achieved by adding the package to the platform's force-queryable whitelist inside the platform's system configuration (`sysconfig` XML):
  ```xml
  <configuration>
      <force-queryable name="com.android.niap.cert.service" />
  </configuration>
  ```
  This ensures that the platform-level `resolveNiapStrictProfile` call (which executes under the calling application's context) can successfully create the package context for the privileged daemon.

---

## Target AOSP Source Files for Modification

To implement the architecture above, the following AOSP files within the `NetworkSecurityConfig` Mainline module and Framework will require modifications or additions:

### 1. Configuration Layer
* **`XmlConfigSource.java`** (`external/conscrypt/nsc/src/android/security/net/config/XmlConfigSource.java`)
  * **Modifications:** Extend the `parseConfigEntry()` method to recognize and parse `<security-profile>`, `<est-server>`, and `<niap-*>` custom tags within the `<domain-config>` loop.
* **`NetworkSecurityConfig.java`** (`external/conscrypt/nsc/src/android/security/net/config/NetworkSecurityConfig.java`)
  * **Modifications:** Add corresponding fields (e.g., `mSecurityProfile`, `mEstServerUrl`) to `NetworkSecurityConfig` and its `Builder`. Propagate these values during the `build()` phase.

### 2. Server Validation Layer
* **`NetworkSecurityTrustManager.java`** (`external/conscrypt/nsc/src/android/security/net/config/NetworkSecurityTrustManager.java`)
  * **Modifications:** Inject the NIAP validator logic into the `checkServerTrusted()` method. If the `mNetworkSecurityConfig` contains a `niap-strict` profile (or custom NIAP rules), execute the strict X.509 validations (RSA-3072/SHA-384 enforcement, EKU checks, wildcard constraints) and perform the OCSP/CRL revocation check before returning successful validation.

### 3. Client Identity & EST Layer
* **`NetworkSecurityKeyManager.java`** (New File: `external/conscrypt/nsc/src/android/security/net/config/NetworkSecurityKeyManager.java`)
  * **Additions:** Create a new `X509ExtendedKeyManager` running in the **App's PID**. In `chooseClientAlias()`, it orchestrates the EST HTTPS request and triggers Binder IPC calls for key generation and storage.
* **System API AIDL** (e.g., `frameworks/base/core/java/android/security/ICertificateManager.aidl`)
  * **Additions:** Define the Binder interface (e.g., `generateCsr()`, `storeCertificate()`) for the framework to communicate with the background `cert-manager-system`.
* **`packages/apps/CertManager/`** (New AOSP Directory)
  * **Additions:** Pre-install the `cert-manager-system` module as a Privileged System App (running in an isolated **System PID**). It must be stripped of any network capabilities or `network_security_config.xml` to enforce its role as a pure KeyStore proxy.

---

## Future Considerations & Proposed Roadmaps

To transition this PoC into a production-grade AOSP feature, we have identified three core security and architectural challenges, along with their proposed roadmaps:

### 1. Plaintext CRL Fetching Bypass (b/218682652)
* **Challenge:** Applications blocking cleartext traffic (`cleartextTrafficPermitted="false"`) cannot fetch CRLs over standard HTTP.
* **Roadmap:** Implement out-of-band fetching delegated to `cert-manager-system`. To prevent exfiltration exploits, the bypass is strictly restricted via an XML-declared `<crl-endpoint-whitelist>`. The daemon performs double-verification on the calling package before initiating downloads.

### 2. Binder API Security (Signature Oracle Prevention)
* **Challenge:** Unauthorized malicious applications might call the privileged service's `sign()` IPC endpoint to sign data using hardware keys owned by other enterprise applications.
* **Roadmap:** The daemon service enforces ownership mapping by resolving the caller's package using `Binder.getCallingUid()`. It rejects any signing request unless the calling application legitimately owns the target alias and public key.

### 3. Client Certificate Lifecycle & Expiration Management
* **Challenge:** Syncing EST enrollment inside the TLS handshake loop can introduce latency, potentially triggering connection timeouts. CA manual approvals (`HTTP 202 Accepted`) cannot be resolved synchronously.
* **Roadmap:** Retain the activation-style trigger (e.g., initial user-initiated setup) for first-time provisioning. For lifecycle maintenance, expose a central `checkAndRefreshCredentials(alias)` interface in the daemon. Client applications call this asynchronously at startup or via periodic system background tasks (WorkManager) to handle validation, caching, and automatic renew (`/simplereenroll`) asynchronously.



## Conclusion
This architecture perfectly aligns with the goal of *"allowing HTTP libraries to start reading from that element"* without requiring app-level modifications. By separating the TrustManager (Validation) and KeyManager (EST Provisioning), we keep the AOSP Mainline framework lightweight while delegating heavy protocol implementations to a dedicated System Service.
