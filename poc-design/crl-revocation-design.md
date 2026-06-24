# Design: Out-of-band CRL Fetching & Cleartext Whitelist Bypass

This document details the architectural design for executing out-of-band Certificate Revocation List (CRL) fetching over HTTP while maintaining application-level cleartext blocking (`cleartextTrafficPermitted="false"`).

---

## 1. Background & Problem Statement

Under the strict NIAP validation profile, online revocation verification (OCSP or CRL) is mandatory. However, CRL files are historically distributed over unencrypted HTTP (to avoid circular dependency loops during TLS establishment).

If an application enforces strict network security by setting `cleartextTrafficPermitted="false"`, the standard Java security platform blocks all HTTP traffic from the application process. This directly prevents standard CRL retrieval, causing valid connections to fail with revocation status unknown errors.

### Key Benefits of this Design
* **Compatibility with Legacy Servers**: Many enterprise and legacy server infrastructures support CRL-based revocation checks but are not yet configured for OCSP. By enabling safe CRL retrieval, we prevent these servers from being incorrectly blocked under the `niap-strict` profile.
* **Intranet & Air-gapped Environment Support**: In closed internal networks (common in government or defense sectors), local CRL distribution servers are heavily utilized instead of public OCSP responders. The whitelist configuration allows administrators to target local HTTP CRL repositories safely.

---

## 2. Proposed Architecture

To bypass the application-level restriction safely without exposing the app to arbitrary cleartext risks, we delegate the CRL downloading to the **privileged daemon service (`cert-manager-system`)**, which is exempt from the app-level network security policy.

Furthermore, to prevent the daemon from being abused as a proxy for arbitrary cleartext exfiltration, a **declarative CRL Whitelist** is configured per-domain.

### Declarative XML Schema (`network-security-config.xml`)

We introduce two new elements inside `<domain-config>`, aligned at the same hierarchy level as `<est-server>`:

```xml
<network-security-config>
    <domain-config>
        <domain includeSubdomains="true">secure.service.gov</domain>
        <security-profile name="niap-strict" />
        
        <!-- Toggle CRL revocation checks -->
        <crl-checking-enabled>true</crl-checking-enabled>
        
        <!-- List of HTTP domains explicitly permitted for CRL downloads -->
        <crl-endpoint-whitelist>
            <domain includeSubdomains="true">crl.service.gov</domain>
            <domain includeSubdomains="false">crl-backup.internal.net</domain>
        </crl-endpoint-whitelist>
    </domain-config>
</network-security-config>
```

---

## 3. Execution & Data Flow

```
+---------------+              +------------------+             +-----------------------+
|  Application  |              |    Conscrypt     |             |  cert-manager-system  |
|  (TLS Socket) |              |  (TrustManager)  |             |   (Privileged App)    |
+-------+-------+              +--------+---------+             +-----------+-----------+
        |                               |                                   |
        |  1. Connect (TLS Handshake)   |                                   |
        +------------------------------>+                                   |
        |                               | 2. Parse Server Cert              |
        |                               |    Extract CDP HTTP URL           |
        |                               |                                   |
        |                               | 3. Match URL against              |
        |                               |    crl-endpoint-whitelist         |
        |                               |                                   |
        |                               | 4. Fetch Request (IPC)            |
        |                               +---------------------------------->+
        |                               |                                   | 5. Check cache validity
        |                               |                                   |    (nextUpdate timestamp)
        |                               |                                   |
        |                               |                                   | 6. HTTP GET (Out-of-band)
        |                               |                                   |    (Bypasses app network policy)
        |                               |                                   +-----+
        |                               |                                   |     |
        |                               |                                   |<----+
        |                               |                                   |
        |                               | 7. Return File Descriptor         |
        |                               |    (ParcelFileDescriptor)         |
        |                               +<----------------------------------+
        |                               |
        |                               | 8. Verify Chain with CRL
        |  9. Handshake Successful      |
        +<------------------------------+
```

### Performance & Memory Optimizations
* **Caching (daemon-side)**: The `cert-manager-system` stores downloaded CRL binaries in secure device storage (`/data/system/`). When a CRL fetch is requested, the daemon reads the `nextUpdate` field of the cached CRL. If the current time is before `nextUpdate`, the cached file is returned immediately without executing any HTTP request.
* **IPC Streaming**: To transfer potentially large CRL files (exceeding 1MB Binder transaction limits), the daemon returns a `ParcelFileDescriptor` pointing to the cached file. Conscrypt streams the data directly from the kernel space.

---

## 4. Expected POC Modifications

### Module: `frameworks/base`

#### File: `packages/NetworkSecurityConfig/platform/.../XmlConfigSource.java`
* Add parsers for `<crl-checking-enabled>` and `<crl-endpoint-whitelist>`.
* Extract subdomain matching properties (`includeSubdomains`) from whitelist nodes.

#### File: `packages/NetworkSecurityConfig/platform/.../NetworkSecurityConfig.java`
* Add internal member variables, builders, and getter accessors for:
  * `boolean mCrlCheckingEnabled`
  * `Set<Domain> mCrlEndpointWhitelist`

---

### Module: `external/conscrypt`

#### File: `nsc/.../android/security/net/config/NetworkSecurityTrustManager.java`
* Modify the `checkRevocation` method to intercept verification paths:
  1. Retrieve the `NetworkSecurityConfig` matching the peer hostname.
  2. If `crl-checking-enabled` is true, extract the CRL Distribution Points (CDP) from the leaf certificate.
  3. Validate that the CDP host matches the domain patterns defined in `crl-endpoint-whitelist`.
  4. If validation fails, abort the handshake immediately.
  5. Connect to `cert-manager-system` via AIDL (`INiapCertManager`) and call a new method:
     `ParcelFileDescriptor fetchCrl(String urlString)`

#### File: `nsc/.../com/android/niap/cert/manager/INiapCertManager.aidl`
* Add a new AIDL method declaration to support out-of-band CRL fetching:
  ```aidl
  ParcelFileDescriptor fetchCrl(String url);
  ```
