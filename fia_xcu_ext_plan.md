# FIA_XCU_EXT / FIA_X509_EXT Implementation & Verification Plan

This document outlines the plan for addressing the `FIA_XCU_EXT` and `FIA_X509_EXT` requirements on Android, separating responsibilities between library implementation and verification/testing.

---

## Requirements Breakdown

### 1. FIA_XCU_EXT.1.1 : X.509 Function Implementation
* **Focus**: Library Implementation & Testing
* **Status**: Pending
* **Details**:
    * **Library**: Verify that server identities (SAN or CN) match the connected host.
    * **Testing**: Verify that a mismatch triggers an error and rejects the connection.

### 2. FIA_X509_EXT.1.1 : Certificate Validation Rules
* **Focus**: Library Implementation & Testing
* **Status**: Investigation finished
* **Details**:
    * **Library**: Rely on Conscrypt for standard path validation. **However, since Conscrypt does not restrict algorithms to RFC 8603 by default, the library will implement explicit checks using `conn.getServerCertificates()` (or equivalent) to verify the signature algorithm and key size after the handshake to ensure strict compliance.**
    * **Testing**: Use custom servers to verify that expired certificates, certificates with UniqueIDs, and prohibited algorithms are correctly rejected.

### 3. FIA_X509_EXT.1.2 : Processing of Extensions
* **Focus**: Investigation & Testing
* **Status**: Investigation finished
* **Details**:
    * **Testing**: Investigate Conscrypt's behavior to see if it automatically processes and validates these extensions (AKID, SKID, KeyUsage). If Conscrypt handles it, rely on the platform. If not, implement supplementary checks in the library.
    * **Current Findings**:
        1. **Existence Check**: Conscrypt generally does **not** check for the presence of extensions. Certificates missing mandatory extensions (like AKID, SKID, KeyUsage, SAN) are accepted. This suggests that existence checks must be followed up by the library.
        2. **Evaluation of Mandatory Fields**: When mandatory fields are present, they are evaluated (e.g., Basic Constraints, EKU, SAN).
        3. **Evaluated Additional Fields**: The following fields are evaluated or processed:
            * **Basic Constraints** (Enforced)
            * **Extended Key Usage** (Enforced)
            * **Subject Alternative Name** (Enforced for DNS mismatch, with loopback exception)
            * **Certificate Policies** (Rejected when critical and unrecognized)
            * **CRL Distribution Points (CDP)** (Rejected when critical, ignored when non-critical)
            * **Authority Info Access (AIA)** (Rejected when critical, ignored when non-critical)
            * *Note*: **Name Constraints** were **not** enforced (both DNS and Email constraints were ignored).
    * **Strategy**: Since Conscrypt does not enforce the presence of required extensions and fails to enforce Name Constraints, the library **must** implement supplementary checks using `conn.getServerCertificates()` after the handshake to verify presence and correctness of these fields.

### 4. FIA_X509_EXT.1.3 : Revocation Status Validation
* **Focus**: Testing & Strategy
* **Status**: Investigation finished
* **Details**:
    * **Library**: Rely on Conscrypt's native OCSP Stapling support for automatic revocation checks.
    * **Testing**: Verified using custom OpenSSL servers that Conscrypt correctly rejects connections when a revoked OCSP response is stapled (SSL alert 46), and accepts when a valid response is stapled.
    * **Current Status & Strategy on Revocation Methods**:
        * **CRL**: Although it can be enabled, it does not work properly due to Android platform limitations (e.g., requiring plain text connections or failing on intermediate certs). To support CRLs without patching Conscrypt, an "Out-of-Band" fetcher using a standard HTTP client (like OkHttp) to download CRLs over HTTP (plaintext is secure as CRLs are digitally signed) and validating them via a custom `TrustManager` is considered. Another option is integrating with compressed lists like Chrome's CRLSet if feasible.
        * **OCSP**: Conscrypt does not perform network fetches for OCSP responses by default.
        * **OCSP Stapling**: Supported and works fully on Conscrypt. By default, it accepts SHA-256 (which is not allowed by strict CNSA requirements), but this can be restricted by applying custom `AlgorithmConstraints` to the `SSLSocket`.

### 5. FIA_X509_EXT.1.4 : Revocation Status Information Retrieval
* **Focus**: Testing (Evidence Gathering)
* **Status**: Investigation finished
* **Details**:
    * **Library**: Rely on Conscrypt to obtain revocation status via OCSP stapling.
    * **Testing**: Verified that OCSP Stapling works and Conscrypt honors it. Confirmed support for SHA-384 and ECDSA with P-384 signatures in OCSP responses. Verified that SHA-256 can be restricted using `AlgorithmConstraints`. PCAP analysis can be used to show the `status_request` extension in the TLS handshake as evidence.

### 6. FIA_X509_EXT.1.5 : Context and Usage Validation
* **Focus**: Testing
* **Status**: Verified
* **Details**:
    * **Handshake Verification**: Confirmed that Conscrypt strictly enforces EKU during the TLS handshake. Using a certificate lacking `serverAuth` resulted in a connection failure (HTTP 525).
    * **Storage/Import Behavior**: Clarified that EKU OIDs are not checked when certificates are stored or imported into the Android KeyStore. The check is strictly at runtime during the handshake.
    * **OCSP Signing OID**: Verified that the OCSP responder certificate (`responder.crt`) contains the OID `1.3.6.1.5.5.7.3.9` (recognized as OCSP Signing by OpenSSL).
    * **Scope**: Focused on Server Auth, Client Auth, and OCSP Signer EKUs. Other EKUs (Email, Code Signing, etc.) are considered out of scope for this project's HTTPS/TLS context.


### 7. FIA_X509_EXT.1.6 : Trust Store Management
* **Focus**: Library Implementation & Testing
* **Status**: Verified
* **Details**:
    * **Library Implementation**: Verified that `NetworkUtils.kt` supports loading a custom CA certificate from a file path passed via intent extra `trustpath`, creating an isolated `KeyStore` for that connection.
    * **Testing**: Confirmed trust store isolation and positive verification in `FiaX509TrustStoreTest`.
        *   **Isolation**: When configured to trust a fake CA, connection to a server using a valid cert (but not signed by the fake CA) failed with SSL error (HTTP 525).
        *   **Positive Verification**: When configured to trust the correct Root CA, connection succeeded (HTTP 200).


### 8. FIA_X509_EXT.2.1 : Certificate Support for Functions
* **Focus**: Testing
* **Status**: Defined
* **Details**:
    * **Selections**: The TSF shall **invoke the TOE platform to determine** (or use platform functionality) to support **Server Authentication** for **HTTPS communication** using **TLS**.
    * **Scope**: Only supports Server Authentication via HTTPS. Does not support code signing or other protocols like SSH/IPsec.

### 9. FIA_X509_EXT.2.2 : Behavior when Revocation Status Cannot be Obtained
* **Focus**: Testing
* **Status**: Verified
* **Details**:
    * **Selections**: The TSF shall **invoke the TOE platform to determine** whether the **certificate is accepted** when valid certificate revocation status information cannot be obtained.
    * **Behavior**: The TSF allows the connection when revocation status cannot be obtained (consistent with default Conscrypt behavior).
    * **Testing**: Verified by `FiaX509RevocationUnreachableTest`. When the OCSP responder was unreachable, the TLS connection succeeded (HTTP 200).

### 10. FIA_X509_EXT.3.1 : Generation of Certificate Requests
* **Focus**: Library Implementation & Testing
* **Status**: Pending
* **Details**:
    * **Library**: Implement PKCS-10 CSR generation supporting custom Subject DN and SAN using Bouncy Castle.
    * **Testing**: Parse the generated CSR to verify that the specified fields are correctly encoded.

### 11. FIA_X509_EXT.3.2 : Validation of Received Certificates
* **Focus**: Library Implementation & Testing
* **Status**: Pending
* **Details**:
    * **Library**: Verify that the certificate returned by the CA matches the intended usage.
    * **Testing**: Verify the entire flow in an integration test.

### 12. FIA_XCU_EXT.2.1 : Certificate Acquisition
* **Focus**: Library Implementation & Testing
* **Status**: Pending
* **Details**:
    * **Library**: Implement a client using protocols like EST to send CSRs and acquire certificates from a CA.
    * **Testing**: Set up a mock CA server to verify that the acquisition flow completes successfully.

---

## Real-World Reference & Justification

### SHA-384 Usage in the Wild
While investigating the prevalence of SHA-384 certificates on the public web, we identified that **`whitehouse.gov`** uses a certificate issued by Let's Encrypt's **`E7`** intermediate CA. This CA uses **ECDSA with SHA-384** for signatures.

This confirms that while SHA-384 is rare compared to the standard SHA-256, it is actively used by high-profile US government sites. This justifies the requirement in `FIA_X509_EXT.1.1` to support and verify RFC 8603 (CNSA) compliant certificates, and supports our plan to verify this behavior using custom servers or specific real-world endpoints.

### The Shift from OCSP to CRL-based Solutions

While OCSP Stapling is currently the most effective mechanism on Android (due to platform limitations on standard CRL fetching), industry trends are shifting away from OCSP entirely.

1. **Deprecation of OCSP by Major CAs**: Organizations like Let's Encrypt have announced plans to deprecate OCSP. The reasons include privacy concerns (OCSP responders can track user browsing) and reliability/performance issues (OCSP responders can be a single point of failure).
2. **Limitations of OCSP Stapling Alone**: 
   - OCSP Stapling depends on the server correctly fetching and attaching the response. If a server is compromised or unable to reach the responder, it cannot provide the status.
   - Clients often "soft-fail" when OCSP responses are missing, meaning they proceed with the connection, creating a security gap.
3. **The Need for CRL and Advanced CRLs (CRLite/CRLSets)**:
   - To ensure robust security in a post-OCSP world, libraries must consider CRL-based solutions.
   - Modern browsers are adopting compressed CRL structures (like Firefox's CRLite or Chrome's CRLSet) to allow local revocation checks without network latency or privacy leaks.
   - Therefore, for broader compatibility and future-proofing, investigating ways to overcome Android's CRL limitations (e.g., via custom TrustManagers and Out-of-Band fetching) is justified.

## Future Development Tasks

Based on our investigation and verification so far, the following tasks need to be designed and developed to fully satisfy the `FIA_X509_EXT` and `FIA_XCU_EXT` requirements.

### 1. Implementation of Certificate Management Functions
*   **CSR (Certificate Signing Request) Generation (`FIA_X509_EXT.3.1`)**:
    *   Implement PKCS#10 CSR generation supporting custom Subject DN and SAN using libraries like Bouncy Castle.
*   **Certificate Acquisition (`FIA_XCU_EXT.2.1`)**:
    *   Implement a client using protocols like EST to send CSRs and acquire certificates from a CA.
*   **Validation of Received Certificates (`FIA_X509_EXT.3.2`)**:
    *   Implement logic to verify that the certificate returned by the CA matches the requested keys and intended usage.

### 2. Additional Strict Verification Rules (Complementing Conscrypt)
*   **Mandatory Extension Checks (`FIA_X509_EXT.1.2`)**:
    *   Since Conscrypt is lenient, add explicit checks after the handshake to ensure mandatory extensions (AKID, SKID, KeyUsage) are present.
*   **Enforcing Algorithm Constraints for OCSP (`FIA_X509_EXT.1.4`)**:
    *   Implement custom `AlgorithmConstraints` on the `SSLSocket` to enforce strict signature algorithms (e.g., restricting to SHA-384 to comply with CNSA profiles) for accepted OCSP responses, rather than accepting Conscrypt's default (which may allow SHA-256).

### 3. Advanced Revocation Verification on Android
*   **Custom CRL Implementation for Modern Android (`FIA_X509_EXT.1.3`)**:
    *   To overcome Conscrypt's platform limitations (e.g., failure of automatic HTTP fetching), implement an "Out-of-Band" verification mechanism that downloads CRLs from CDPs and checks revocation status via a custom `TrustManager`.
*   **Strict Rejection on Revocation Unreachable (Fail-Closed) (`FIA_X509_EXT.2.2`)**:
    *   To align with strict requirements (or Google ST's claim), consider and implement logic to reject connections when OCSP Stapling is not provided or revocation status cannot be obtained (e.g., enforcing OCSP).

## Proposed Architecture for Future Development

To ensure high reusability and decoupling from specific application contexts (like `SecureURL`), the following architecture is proposed for implementing the future tasks.

### Core Concept: `NiapCertValidator`
Instead of hardcoding validation logic inside a specific `SSLSocket` or coupling it with a URL wrapper, all X.509 validation rules (extensions, revocation, algorithms) should be encapsulated into an independent, transport-agnostic class.

*   **`NiapCertValidator` (Independent Validation Core)**:
    *   **Inputs**: `X509Certificate` and `hostname` (String).
    *   **Responsibilities**: Performs all required checks (AKID/SKID, EKU, Revocation via OCSP/CRL, Algorithm Constraints).
    *   **Independence**: Does not depend on `SecureURL` or `SecureConfig`.

### Reusability Across Layers
This independent validator can be utilized in different layers depending on the project requirements:

1.  **Socket Layer (`ValidatableSSLSocket`)**:
    *   The socket owns an instance of `NiapCertValidator`.
    *   After the TLS handshake, it extracts the server certificate and delegates the validation to `NiapCertValidator.validate(cert, hostname)`.
2.  **HTTP Layer (`OkHttp Interceptor` or `X509TrustManager`)**:
    *   The interceptor or custom trust manager also owns an instance of `NiapCertValidator`.
    *   It calls `NiapCertValidator.validate(...)` during the certificate chain validation phase or post-connection check.

This design allows us to support legacy `java.net` code while providing a clean, reusable component for modern Android apps using OkHttp.



