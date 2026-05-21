# EST Validation Server — Setup Guide

> **Purpose**: Local EST (Enrollment over Secure Transport) server for testing NIAP / Common Criteria
> Android certificate enrollment. Runs entirely in Docker — no external services or cloud costs required.
>
> **Architecture**: Cisco libest + NGINX in a single self-contained Docker container.

---

## Architecture Overview

```
Android device / emulator
    │
    │ HTTPS (mTLS) :8443
    ▼
┌─────────────────────────────────────────┐
│ Docker container                        │
│                                         │
│  NGINX :8443 (HTTPS + mTLS)             │
│    └─ proxy ──▶ libest :8085            │
│                   └─ issues certs (CA)  │
│                                         │
│  NGINX :8080 (HTTP)                     │
│    └─ /cacert.pem  ← CA cert download   │
│    └─ /health      ← health check       │
└─────────────────────────────────────────┘
    │
    │ HTTP :8080
    ▼
Developer browser (CA cert download)
```

### CA Certificate Role

A throwaway Root CA is auto-generated on first container start. This CA:
1. Signs the server's TLS certificate (used by NGINX)
2. Signs CSRs from Android clients (used by libest)

---

## Prerequisites

| Tool | Version | Check |
|------|---------|-------|
| Docker | 20.x+ | `docker --version` |
| Docker Compose | v2+ | `docker compose version` |
| curl | any | `curl --version` |

Internet access is required at image build time (to clone libest). After startup the server runs fully offline.

---

## Certificate Profiles

Set `EST_CERT_PROFILE` in `docker-compose.yml`:

| Profile | Purpose | CA key | Server key | Signature | NIAP validator |
|---------|---------|--------|-----------|-----------|----------------|
| `general` | Basic EST verification | RSA-4096 | RSA-2048 | SHA-256 | ❌ not compliant |
| `niap` | NIAP/CC validation | ECDSA P-384 | ECDSA P-384 | SHA-384 | ✅ all checks pass |

**NIAP profile server certificate extensions:**
- AKID (2.5.29.35), SKID (2.5.29.14), KeyUsage (2.5.29.15) — FIA_X509_EXT.1.2
- EKU: `serverAuth` (1.3.6.1.5.5.7.3.1) — NiapCertValidator.kt
- EKU: `id-kp-cmcRA` (1.3.6.1.5.5.7.3.28) — FIA_XCU_EXT.1.md §5 EST-specific requirement
- basicConstraints: CA:FALSE

**Client certificates issued by libest (both profiles):**
- SHA-384 signature, AKID, SKID, KeyUsage, EKU (clientAuth)

---

## Setup

### 1. Check out the repository

```bash
git clone <this-repo>
cd niap-android-cert-ext/est-server
```

### 2. Build the Docker image

Clones and compiles libest from source. **First build takes 5–10 minutes.**

```bash
docker compose build
```

Verify the build log output:
```
=== SHA setting verification ===
default_md     = sha384        ← NIAP SHA-384 requirement in place
=== Build artifact ===
estserver: ELF 64-bit LSB executable  ← binary built successfully
```

> **Reproducibility**: After startup, check the libest commit used with:
> `docker exec est-validation-server cat /opt/estserver/LIBEST_COMMIT`
> Record this in your test report.

### 3. Start the server

```bash
docker compose up -d
```

Wait for the health check to pass:
```bash
docker compose ps
# est-validation-server ... healthy
```

Watch the CA initialization log:
```bash
docker compose logs -f
```

Expected output:
```
[est-entrypoint] CA initialization complete. CA certificate info:
[est-entrypoint] EST server startup confirmed.
[est-entrypoint] Starting NGINX [profile: general]
[est-entrypoint]   EST  (HTTPS + mTLS): https://localhost:8443/.well-known/est/
[est-entrypoint]   CA cert:             http://localhost:8080/cacert.pem
```

### 4. Smoke test (curl)

```bash
# Health check
curl http://localhost:8080/health
# → OK

# CA cert info
curl -s http://localhost:8080/cacert.pem | openssl x509 -noout -subject -dates
# → subject=C=JP, ST=Tokyo, O=NIAP Test Lab, CN=EST Validation Root CA

# EST /cacerts endpoint
curl -ks --cacert <(curl -s http://localhost:8080/cacert.pem) \
    https://localhost:8443/.well-known/est/cacerts \
    | openssl base64 -d | openssl pkcs7 -inform DER -print_certs -noout

# EST /simpleenroll
openssl req -newkey rsa:2048 -nodes -keyout /tmp/client.key \
    -subj "/CN=test-client" -out /tmp/client.csr 2>/dev/null
curl -sk \
    --cacert <(curl -s http://localhost:8080/cacert.pem) \
    -u "estuser:estpwd" \
    -H "Content-Type: application/pkcs10" \
    --data-binary @/tmp/client.csr \
    https://localhost:8443/.well-known/est/simpleenroll \
    | openssl base64 -d | openssl pkcs7 -inform DER -print_certs -noout
# → subject=CN=test-client
```

---

## Switching to NIAP Profile

```bash
# Edit docker-compose.yml: set EST_CERT_PROFILE=niap, then restart
docker compose down
docker compose up -d
docker compose logs -f  # watch NIAP cert generation

# Verify server cert NIAP extensions
docker exec est-validation-server \
    openssl x509 -in /opt/estserver/estCA/private/estserver.crt -noout -text \
    | grep -A 5 "Extended Key Usage"
# → Server Authentication (1.3.6.1.5.5.7.3.1) ✓
# → 1.3.6.1.5.5.7.3.28 (cmcRA) ✓
```

---

## Android App Integration

### 5. Download and deploy the CA cert

The Android emulator reaches the host Mac at `10.0.2.2`. For a real device on USB,
use the Mac's LAN IP or set up ADB port forwarding.

```bash
# Download CA cert
curl -o cacert.pem http://localhost:8080/cacert.pem

# Place in app's raw resources
cp cacert.pem \
    ../cert-test-app/src/main/res/raw/est_validation_ca.pem
```

### 6. Configure the Android app

| Setting | Value |
|---------|-------|
| EST server URL (emulator) | `https://10.0.2.2:8443/.well-known/est/` |
| EST server URL (real device) | `https://<host-LAN-IP>:8443/.well-known/est/` |
| Trust anchor | downloaded `cacert.pem` |
| Auth | `estuser:estpwd` |

### 7. Connectivity check from the device

```bash
# Emulator
adb shell nc -w 2 10.0.2.2 8443 < /dev/null; echo $?   # 0 = connected

# Real device via ADB port forward
adb reverse tcp:8443 tcp:8443
adb reverse tcp:8080 tcp:8080
# then use https://localhost:8443/.well-known/est/ in the app
```

---

## Troubleshooting

### Build fails

**Symptom**: errors during `autogen.sh` or `make`

- Docker memory allocation must be ≥ 4 GB (Docker Desktop → Settings → Resources)
- Internet access required for libest clone

```bash
# Rebuild without cache
docker compose build --no-cache
```

### `estserver` exits immediately

**Symptom**: log shows "EST server exited immediately"

**Known root cause (ARM64 / Apple Silicon)**: libest's `estserver.c` declares `char c` for the
getopt return value. On ARM64 Linux, `char` is unsigned by default, so getopt's end-of-options
sentinel (-1) becomes 255, falls through to `default:`, and calls `show_usage_and_exit()`.
Fixed by building with `CFLAGS="-fsigned-char"` (already in the Dockerfile).

Other causes:
- `libest.so` link error → `ldd /usr/local/bin/estserver`
- CA directory permission issue → `ls -la /opt/estserver/estCA/`

```bash
# Manual test inside container
docker exec -it est-validation-server bash
cd /opt/estserver
estserver -p 8085 \
    -c estCA/private/estservercertandkey.pem \
    -k estCA/private/estservercertandkey.pem \
    -r estrealm -v
```

### Cannot connect from Android

**Symptom**: TLS handshake error or timeout

1. Check TCP connectivity: `adb shell nc -w 2 10.0.2.2 8443 < /dev/null`
2. Test with curl: `curl -v --cacert cacert.pem https://10.0.2.2:8443/.well-known/est/cacerts`
3. Verify the `cacert.pem` in the app matches the current container CA

> **Note**: `docker compose down` → `up` generates a **new CA**. Re-download `cacert.pem`
> and update the app's trust anchor each time you recreate the container.

### Persist CA across restarts

```bash
docker run -d \
    -v est-ca-data:/opt/estserver/estCA \
    -p 8443:8443 -p 8080:8080 \
    est-validation-server:local
```

---

## Stop and Clean Up

```bash
docker compose down

# Remove image too (requires rebuild)
docker compose down --rmi local
```

---

## Cloud Run Deployment (optional)

> **Note**: Cloud Run does not natively support mTLS (TLS is terminated by Cloud Run).
> For mTLS, use Cloud Load Balancer's mTLS feature (additional cost).
> For basic EST flow verification, `ssl_verify_client optional` is sufficient.

```bash
gcloud auth login
gcloud config set project <YOUR_PROJECT_ID>

gcloud run deploy est-validation-server \
    --source . \
    --region asia-northeast1 \
    --allow-unauthenticated \
    --max-instances 1 \
    --memory 512Mi \
    --port 8080
```

After deployment:
- Only NGINX port 8080 (HTTP) is exposed by Cloud Run (Cloud Run adds HTTPS)
- CA cert available at `https://<service-url>/cacert.pem`
- Basic `/cacerts` and `/simpleenroll` flows work without mTLS

---

## File Layout

```
est-server/
├── Dockerfile          Multi-stage build: compiles Cisco libest from source
├── docker-compose.yml  Local dev/test Compose definition
├── nginx.conf          mTLS reverse proxy (ports 8443/8080)
├── entrypoint.sh       CA init → estserver start → NGINX start
└── README.md           This guide
```

---

## Version Info

| Component | Version |
|-----------|---------|
| Base image | ubuntu:22.04 |
| libest | main branch (commit pinned at build time) |
| NGINX | Ubuntu 22.04 package |
| OpenSSL | Ubuntu 22.04 package (libssl3) |
| Signature algorithm | SHA-384 (NIAP requirement) |
| ARM64 fix | `CFLAGS=-fsigned-char` (unsigned char getopt bug) |
