# Local EST Server (libest) Setup & Verification Guide

This guide documents how to set up, compile, configure, and run a local **Cisco libest** EST server on macOS for Common Criteria / NIAP verification testing.

---

## 1. System Requirements & Dependencies

The `libest` server requires OpenSSL 3.x and GNU build tools. Install them via Homebrew:

```bash
brew install openssl@3 libtool autoconf automake
```

---

## 2. Clone and Configure

Clone the official Cisco `libest` repository and set up compilation flags targeting Homebrew's OpenSSL library paths:

```bash
git clone https://github.com/cisco/libest.git ~/libest
cd ~/libest

# Configure build environment with OpenSSL 3.x paths
export CFLAGS="-I/opt/homebrew/opt/openssl/include $CFLAGS"
export LDFLAGS="-L/opt/homebrew/opt/openssl/lib $LDFLAGS"

./autogen.sh
./configure --with-ssl-dir=/opt/homebrew/opt/openssl
```

---

## 3. NIAP SHA-384 Cryptographic Configuration

By default, `libest` examples are configured to issue client certificates signed with **SHA-256**. Common Criteria / NIAP validation rules (such as strict signature algorithm constraints) require **SHA-384** or **SHA-512** signatures.

To update the CA to use SHA-384:
1. Open `~/libest/example/server/estExampleCA.cnf`
2. Locate the `[ CA_default ]` section.
3. Change `default_md` to `sha384`:

```ini
[ CA_default ]
...
default_md     =  sha384               # md to use
```

---

## 4. Compile the Server and Libraries

Compile the core library and example servers:

```bash
# Compile core libraries
make

# Compile the example server binaries
cd example/server
make
```

---

## 5. Launching the EST Server

A helper shell script `runserver.sh` is provided to launch the EST server on local port **`8085`**. 

To launch the server:
```bash
cd ~/libest/example/server

# Set dynamic library paths so the loader can find compiled libest.dylib
export DYLD_LIBRARY_PATH="~/libest/src/est/.libs:$DYLD_LIBRARY_PATH"

# Start the server
./runserver.sh
```

The server logs will print:
* `Using OpenSSL 3.6.2`
* `Disabling PoP check`
* `Launching EST server...`

---

## 6. Verification and Integration with Android

1. **Network Visibility**:
   * When running the server on the Mac host, the Android emulator connects to the host's LAN IP (e.g., `192.168.1.4`) on port `8085`.
   * Port connectivity can be verified from the Android shell:
     ```bash
     adb shell nc -w 2 192.168.1.4 8085 < /dev/null
     ```
     (An exit code of `0` indicates a successful TCP handshake).

2. **Trusted CA Anchor**:
   * The local EST server's CA certificate is located at `~/libest/example/server/estCA/cacert.crt`.
   * Put this PEM content into the Android test application's raw resources under `src/main/res/raw/dstcax3.pem` to establish the TLS trust anchor.

3. **Verification logs**:
   * When an enrollment request is sent, the server log will print the request and output the message digest used for signing:
     ```text
     ***EST [INFO][est_handle_simple_enroll:1712]--> DEBUG: body_len=414 body=...
     message digest is sha384
     writing /06.pem
     Data Base Updated
     ```
