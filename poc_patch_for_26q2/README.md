# NIAP Strict TLS Profile Implementation Patches (AOSP 26Q2)

This directory contains the git patch files to implement the strict TLS security profile (`niap-strict`) and EST client certificate integration in Android 26Q2 (Baklava).

## Patch Files

1. **[frameworks_base.patch](file:///usr/local/google/home/wkouki/AndroidStudioProjects/niap-android-cert-ext/poc_patch_for_26q2/frameworks_base.patch)**: Patches for the platform network security configuration module.
2. **[external_conscrypt.patch](file:///usr/local/google/home/wkouki/AndroidStudioProjects/niap-android-cert-ext/poc_patch_for_26q2/external_conscrypt.patch)**: Patches for the Conscrypt JSSE security provider.

---

## Technical Details by Module

### 1. frameworks/base (Network Security Config)
The changes in this repository implement the parser and validator rules for the strict profile.

* **`XmlConfigSource.java`**:
  * Extends the XML parser to recognize and parse the `<security-profile name="niap-strict" />` tag under `<domain-config>`.
* **`NetworkSecurityConfig.java`**:
  * Stores the parsed security profile name (`niap-strict`) as part of the configuration properties for a specific domain.
* **`NetworkSecurityTrustManager.java`**:
  * Intercepts `checkServerTrusted` verification pathways.
  * When the `niap-strict` profile is active for the target domain, it enforces strict validation checks:
    1. **Key Lengths**: Verifies leaf and CA keys satisfy cryptographic minimums (e.g., EC >= 256 bits, RSA >= 2048 bits).
    2. **Mandatory Extensions**: Ensures standard X.509 extensions (Authority Key Identifier, Subject Key Identifier, Key Usage) are present.
    3. **Field Enforcement**: Checks that Extended Key Usage (EKU) contains `serverAuth` (OID `1.3.6.1.5.5.7.3.1`), and Basic Constraints confirms the leaf cert is not a CA.
    4. **Prohibited Wildcards**: Blocks wildcard certificates for top-level domains (e.g., `*.com`, `*.net`, `*.org`).
    5. **Revocation Checks**: Enforces online revocation validation (OCSP/CRL) with `Option.NO_FALLBACK` (meaning validation fails immediately if revocation status cannot be retrieved or verified).

---

### 2. external/conscrypt (Conscrypt Engine & JCA Provider)
The changes in this repository implement the client-side mTLS automation and the custom signing bridge.

* **`NetworkSecurityKeyManager.java` (New File)**:
  * Implements `X509ExtendedKeyManager` to intercept the client certificate selection path during TLS handshakes.
  * When a target domain has a configured `<est-server>` in its network security configuration:
    1. It binds to the privileged system daemon service (`com.android.niap.cert.service` / `INiapCertManager`) via Binder IPC.
    2. If no valid client certificate is enrolled for the alias, it dynamically triggers `/simpleenroll` over EST to fetch one.
    3. Returns the certificate chain and a custom `RemotePrivateKey` representation.
* **`RemotePrivateKey.java`, `RemoteSignatureSpi.java`, `RemoteSigningProvider.java` (New Files)**:
  * Implements a custom JCA Key and Signature engine (`NONEwithECDSA`) to bridge signing operations.
  * Instead of storing raw private key bytes in the application memory space, signing requests are securely routed via Binder IPC back to the privileged `cert-manager-system` daemon, which interfaces with KeyStore.
* **`NetworkSecurityConfigProvider.java`**:
  * Registers the platform-private JCA provider `NiapRemoteSigning` at position 1 during initialization so Conscrypt's `CryptoUpcalls` can resolve it when executing TLS client identity handshakes.

---

## 3. How to Apply Patches and Restore Environment

To reconstruct this proof-of-concept environment on a standard Android 26Q2 (Baklava) source tree, follow these steps:

### Step 1: Place the Prebuilt Daemon APK
Because the `cert-manager-system` privileged application is an external daemon, its precompiled APK must be placed in the Conscrypt directory so Soong can import it as a prebuilt target:
1. Copy the packaged **`CertManagerGoogle.apk`** from this patch folder.
2. Paste it directly into the target AOSP repository at:
   `/external/conscrypt/CertManagerGoogle.apk`

### Step 2: Apply Git Patches
Apply the changes to their respective modules within the AOSP source tree:
1. **Conscrypt Engine & NSC Parser**:
   * Navigate to `<aosp-root>/external/conscrypt/`
   * Apply the patch: `git apply <path_to_this_directory>/external_conscrypt.patch`
2. **Framework Base (if applicable)**:
   * Navigate to `<aosp-root>/frameworks/base/`
   * Apply the patch: `git apply <path_to_this_directory>/frameworks_base.patch`

### Step 3: Run Platform Build & Flash
Compile the platform as staging staging userdebug and flash to target hardware:
```bash
source build/envsetup.sh
lunch stallion-trunk_staging-userdebug
m dist
```
Once the update zip file is packaged, flash the bootloader, radio, and update images onto the `stallion` device. The `CertManagerGoogle` system daemon will be preinstalled dynamically into `/system_ext/priv-app/`.
