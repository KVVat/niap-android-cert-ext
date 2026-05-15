# NIAP Android Certificate Extension Validator

This repository contains an Android library and testing tools to enforce NIAP (National Information Assurance Partnership) requirements for X.509 certificate validation.

## Project Structure

*   **`validator`**: The core Android library providing `NiapCertValidator` and `NiapX509TrustManager` to enforce security constraints (algorithm, EKU, TLD wildcards, etc.).
*   **`validator-test-app`**: A test application with product flavors (`strict`, `relaxed`) to verify the validator's behavior under different configurations.
*   **`agent-test`**: A JUnit-based test plugin designed to work with **TestBed Core** for automated verification on real devices.
*   **`cert-manager`**: A module planned to be configured as an Android background service for certificate management.

## Features

*   Enforces strict signature algorithms (SHA-384/512) as per NIAP requirements (optional in relaxed mode).
*   Verifies mandatory extensions and Extended Key Usage (EKU).
*   Enforces TLD wildcard restrictions.
*   Provides `NiapSecurityHelper` to easily configure `OkHttpClient` and `HttpsURLConnection`.

## Getting Started

### Prerequisites

*   **JDK 17** is required for building the library and apps.
*   **Android SDK** with support for API 34.

### 1. Building the Library

To build the validator library AAR:

```bash
./gradlew :validator:assembleRelease
```

### 2. Using in your Project

You can use `NiapSecurityHelper` to create secure connections.

#### For OkHttp:

```kotlin
val helper = NiapSecurityHelper.getInstance(context)
val client = helper.configureOkHttp(OkHttpClient.Builder()).build()
```

#### For HttpsURLConnection:

```kotlin
val url = URL("https://example.com")
val helper = NiapSecurityHelper.getInstance(context)
val connection = helper.openConnection(url)

connection.hostnameVerifier = HostnameVerifier { hostname, session ->
    helper.checkHostname(hostname, session)
    true
}
connection.connect()
```

### 3. Running Tests

The tests are designed to be run via **TestBed Core**.

1.  Build the test app:
    ```bash
    ./gradlew :validator-test-app:assembleDebug
    ```
2.  Build the test plugin:
    ```bash
    ./gradlew :agent-test:jar
    ```
3.  Run tests using TestBed Core MCP server.

## License

[Specify License here]
