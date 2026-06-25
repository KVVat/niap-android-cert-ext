# NIAP Android Certificate Extension & Lifecycle Validator

A comprehensive Android security framework and service designed to enforce strict **NIAP (National Information Assurance Partnership)** Common Criteria requirements for X.509 certificate validation and secure certificate lifecycle management.

---

## 📖 Background & Problem

By default, Android's core security provider (**Conscrypt**) is optimized for general web traffic and does not enforce the stringent constraints defined in the [NIAP X.509 Functional Package (FIA_X509_EXT.1 / FIA_XCU_EXT.1)](https://www.niap-ccevs.org/static_html/protection-profile/511/Functional%20Package%20for%20X.509_v1.0.html). For example:
* It permits signature algorithms and key lengths weaker than SHA-384 / 3072-bit RSA.
* It does not mandate essential extensions like Authority Key Identifier (AKID), Subject Key Identifier (SKID), or specific Key Usages.
* It does not strictly restrict wildcard SAN alignments on critical Top-Level Domains (TLDs).

This framework addresses these gaps through a dual-mode integration architecture:

1. **OS-Level Platform Integration (Preferred / Custom OS)**:
   * By applying the platform patches provided in the [poc_patch_for_26q2/](file:///usr/local/google/home/wkouki/AndroidStudioProjects/niap-android-cert-ext/poc_patch_for_26q2/) directory to AOSP 26Q2, the validation constraints and client EST certificate selection/signing are handled **automatically and transparently by the Android OS** (Conscrypt).
   * Third-party applications simply configure the `<security-profile name="niap-strict">` and `<est-server>` parameters in their standard `network-security-config.xml`. Zero source code changes or SDK integrations are needed.
2. **App-Level Fallback Library (`cert-lib` / Stock OS)**:
   * When deploying on standard, uncustomized Android devices (where modifying the OS/APEX is not possible), apps can bundle the `cert-lib` client library.
   * `cert-lib` manually implements the same strict validation criteria and provides standard JSSE wrappers (`NiapKeyManager`, `NiapX509TrustManager`) to enforce the rules and route signing operations.

---

## 🏛️ Repository Architecture & Modular Layout

This repository is structured into two distinct tracks corresponding to the two integration modes. It is critical to understand their organization to avoid building, running, or referencing the wrong modules.

```
niap-android-cert-ext/
├── [Mode 1: OS-Level Platform Integration (Patched OS / POC)]
│   ├── poc_patch_for_26q2/       # Git patches for AOSP Conscrypt & Frameworks Base
│   ├── cert-manager-system/      # Privileged system app daemon (signed with platform cert)
│   ├── cert-testapp-system/      # System-level test app utilizing OS-level mTLS/EST
│   └── strict-browser-app/       # Third-party web browser demo configured with <security-profile name="niap-strict">
│
├── [Mode 2: App-Level Fallback Library (Stock OS / Standalone)]
│   ├── cert-lib/                 # Standalone client library (Validator, Custom JSSE Trust/KeyManagers)
│   ├── cert-manager/             # Foreground service & signing oracle for unpatched OS
│   ├── validator-test-app/       # Test application to verify cert-lib behaviors (strict/relaxed flavors)
│   └── cert-test-app/            # Interactive Compose application for standalone fallback verification
│
└── [Shared Infrastructure]
    ├── agent-test/               # JUnit automated test suites for TestBed Core
    └── est-server/               # Pre-configured local EST test environment (Cisco libest)
```

> [!WARNING]
> **Build Artifact Name Collision Warning**:
> Both `cert-manager` and `cert-manager-system` compile into an APK copied to the same automated runner destination (`cert-manager-debug.apk`). Similarly, both `cert-test-app` and `cert-testapp-system` copy to `cert-test-app-debug.apk`. 
> Make sure you are building and deploying the correct module for your target testing context!

---

## 🛠️ Module Reference by Integration Mode

### 1. OS-Level Platform Integration (Patched OS)
This mode implements deep platform-level enforcement. The validation rules and mTLS client enrollment are performed transparently by the OS.

* **`poc_patch_for_26q2/`**: Contains AOSP git patches for Conscrypt and Frameworks Base. These patches introduce the XML parsing of `<security-profile name="niap-strict">` and `<est-server>` and hook the JCA signing mechanisms via Binder IPC.
* **`cert-manager-system/`**: A privileged system daemon application (`com.android.niap.cert.service`) designed to be preinstalled in `/system_ext/priv-app/`. It acts as the secure certificate repository and signing oracle for the patched Conscrypt layer. It depends directly on the platform changes in `poc_patch_for_26q2`.
* **`cert-testapp-system/`**: A system-level test application designed to verify and interact with the privileged `cert-manager-system` daemon when running on the patched OS.
* **`strict-browser-app/`**: A fully-functional third-party web browser application used as a practical demonstration. It configures the strict profile via standard `<security-profile name="niap-strict">` in its `network_security_config.xml`, showcasing how a standard app's TLS connections are intercepted and strictly validated by the patched Conscrypt provider under the hood.

### 2. App-Level Fallback Library (Stock OS)
This mode provides a pure application-space integration, allowing apps to achieve NIAP-compliant validation and mTLS on stock, unmodified Android devices.

* **`cert-lib/`**: A client library that manually implements the strict NIAP validation rules (`NiapCertValidator`) and custom JSSE wrappers (`NiapX509TrustManager`, `NiapKeyManager`).
* **`cert-manager/`**: A standard foreground service and signing oracle that emulates the system certificate management daemon on an unpatched OS.
* **`validator-test-app/`**: A WorkManager-based test utility used to run automated network validation tests against relaxed and strict configurations using `cert-lib`.
* **`cert-test-app/`**: An interactive Compose application that demonstrates standalone cert-lib and cert-manager flows without platform patches.

### 3. Shared Infrastructure
* **`agent-test/`**: A JUnit test suite designed to register and execute device-level test cases in collaboration with the **TestBed Core** automation system:
  * **`FiaX509ValidatorTest`** (`NiapValidatorTest` class): Installs strict/relaxed flavors of `validator-test-app` and tests web targets to verify that non-compliant SHA-256 endpoints are successfully blocked in strict mode and accepted in relaxed mode.
  * **`CertManagerTest`**: Automates full-cycle local EST enrollment, setups ADB reverse tunnels, validates state transitions, and conducts mutual TLS authentications against local mock services.
* **`est-server/`**: The local compliance mock environment using Cisco libest and NGINX to serve compliant ECDSA P-384 certificates signed with SHA-384.

---

## ⚙️ Declarative Security Policy Configuration

Validation rules are loaded declaratively from an XML resource (typically `res/xml/niap_security_config.xml`). This design allows administrators to configure strict security policies without altering any source code.

```xml
<?xml version="1.0" encoding="utf-8"?>
<niap-security-config>
    <!-- Enforce strict signature algorithm constraints (SHA-384 or stronger) as per RFC 8603 (CNSA). -->
    <niap-rfc8603-strict-sigalg>true</niap-rfc8603-strict-sigalg>
    
    <!-- List of TLDs for which wildcard SANs are prohibited. -->
    <prohibited-tld-wildcards>
        <tld>*.COM</tld>
        <tld>*.NET</tld>
        <tld>*.ORG</tld>
        <tld>*.GOV</tld>
        <tld>*.EDU</tld>
    </prohibited-tld-wildcards>
    
    <!-- List of allowed cipher suites. If empty, platform defaults are used. -->
    <allowed-cipher-suites>
        <cipher>TLS_AES_128_GCM_SHA256</cipher>
        <cipher>TLS_AES_256_GCM_SHA384</cipher>
        <cipher>TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256</cipher>
        <cipher>TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256</cipher>
        <cipher>TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384</cipher>
        <cipher>TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384</cipher>
    </allowed-cipher-suites>
    
    <!-- Trust anchor policy (SYSTEM_CA_ONLY, USER_CA_ONLY, BOTH_CA). -->
    <trust-anchor-policy>BOTH_CA</trust-anchor-policy>
    
    <!-- Enforce TLS 1.2 or higher. -->
    <enforce-tls-1-2>true</enforce-tls-1-2>
    
    <!-- Enforce checks for mandatory extensions (AKID, SKID, KeyUsage). Required by FIA_X509_EXT.1.2. -->
    <enforce-mandatory-extensions>true</enforce-mandatory-extensions>
    
    <!-- Enforce check for Extended Key Usage (EKU) in the leaf. -->
    <enforce-eku>true</enforce-eku>
    
    <!-- Required Extended Key Usage (EKU) aliases ('serverAuth', 'clientAuth') or raw OIDs. -->
    <required-eku>serverAuth</required-eku>
    
    <!-- Enforce CA constraints (End-entity must not be a CA). -->
    <enforce-ca-constraints>true</enforce-ca-constraints>
</niap-security-config>
```

---

## 🚀 Getting Started

### Prerequisites
* **JDK 17** configured in your development environment.
* **Android SDK** with Platform-34 tools.
* **Docker** & **Docker Compose** (if running the local mock EST server).

### 🛠️ 1. Building the Modules

To build the core library AAR:
```bash
./gradlew :cert-lib:assembleRelease
```

To build the background service APK:
```bash
./gradlew :cert-manager:assembleDebug
```

To build the automation test modules:
```bash
# Build the test app (strict & relaxed flavors)
./gradlew :validator-test-app:assembleDebug

# Compile the automated JUnit test plugin
./gradlew :agent-test:jar
```
*Note: The `assembleDebug` tasks for `cert-manager`, `validator-test-app`, and `cert-test-app` are pre-configured to automatically copy their target debug APK outputs into the TestBed Core resources workspace (`../testbed-core/composeApp/resources/`) for automated installer stages.*

---

## 💻 Integration Guide

### 1. Custom HTTPS Client Connections (using the library directly)

Initialize the custom helper and apply it to network connections in your app code:

#### For OkHttp Clients:
```kotlin
val helper = NiapCertHelper.getInstance(context)
val okHttpClient = helper.configureOkHttp(OkHttpClient.Builder()).build()
```

#### For Standard HttpsURLConnection:
```kotlin
val url = URL("https://example.gov")
val helper = NiapCertHelper.getInstance(context)
val connection = helper.openConnection(url)

connection.hostnameVerifier = HostnameVerifier { hostname, session ->
    helper.checkHostname(hostname, session)
    true
}
connection.connect()
```

### 2. Consuming Hardware-Backed Credentials (using the service)

Connect to the background lifecycle service to retrieve standard JCA network artifacts:

```kotlin
// Initialize manager proxy (lazily binds background IPC service)
val certManager = NiapCertManager(context)

// Verify enrollment states
if (certManager.hasEnrollment("my_secure_identity")) {
    // Retrieve a standard X509KeyManager which delegates signing operations
    val keyManager = certManager.getKeyManager("my_secure_identity")
    
    // Create an SSLContext loaded with the secure key manager
    val sslContext = certManager.getSslContext("my_secure_identity", trustedCaPem = serverRootPem)
    
    // Wire standard clients directly (OkHttp, HttpsURLConnection, etc.)
    val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .build()
}
```

---

## 🔬 Mock EST & Testbed Verification

For comprehensive local testing or official Common Criteria evaluations, setup the environment using the provided local tools.

### 1. Start the Local Compliance EST Server
The `est-server` directory contains a pre-packaged environment executing **Cisco libest** and **NGINX**. It generates compliant ECDSA P-384 certificates signed with SHA-384.

```bash
cd est-server
# Configure EST_CERT_PROFILE=niap in docker-compose.yml to enable full compliance mode
docker compose up -d
```

### 2. Configure Port Forwarding for Testing
When testing on standard physical hardware or external systems, run the ADB reverse utility to forward network requests:
```bash
adb reverse tcp:8443 tcp:8443   # EST HTTPS Endpoint (libest/NGINX)
adb reverse tcp:8081 tcp:8081   # Strict mTLS test endpoint
adb reverse tcp:8080 tcp:8080   # HTTP admin / CA cert download endpoint
```

### 3. Run Automated TestBed Suite
Ensure the **TestBed Core** desktop application and MCP server is launched on `localhost:11452`, then run the JUnit test plugins:
* Execute `NiapValidatorTest` in the `agent-test` module to verify that non-compliant and compliant signature contexts are correctly categorized on actual devices.
* Execute `CertManagerTest` to launch an automated flow: installing test apps, establishing adb reversals, performing EST registrations, validating KeyStore operations, and testing mTLS endpoints recursively.

---

## 📄 License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.

