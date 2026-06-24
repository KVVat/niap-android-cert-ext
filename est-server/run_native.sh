#!/bin/bash
set -euo pipefail

WORK_DIR="$(pwd)/local_run"
CA_DIR="${WORK_DIR}/estCA"
PRIV_DIR="${CA_DIR}/private"

mkdir -p "${WORK_DIR}"
cd "${WORK_DIR}"

if [ ! -f "${PRIV_DIR}/estservercertandkey.pem" ]; then
    echo "=== Initializing Local Certificate Authority (NIAP Profile) ==="
    mkdir -p "${CA_DIR}/newcerts" "${CA_DIR}/certs" "${CA_DIR}/crl" "${PRIV_DIR}"
    chmod 700 "${PRIV_DIR}"
    touch "${CA_DIR}/index.txt"
    echo "unique_subject = no" > "${CA_DIR}/index.txt.attr"
    echo "01" > "${CA_DIR}/serial"

    cat > estExampleCA.cnf << 'EOF'
[ ca ]
default_ca = CA_default
[ CA_default ]
dir              = ./estCA
certs            = $dir/certs
crl_dir          = $dir/crl
database         = $dir/index.txt
new_certs_dir    = $dir/newcerts
certificate      = $dir/cacert.crt
serial           = $dir/serial
private_key      = $dir/private/cakey.pem
x509_extensions  = usr_cert
default_days     = 365
default_crl_days = 30
default_md       = sha384
preserve         = no
policy           = policy_match
unique_subject   = no
[ policy_match ]
countryName            = optional
stateOrProvinceName    = optional
organizationName       = optional
organizationalUnitName = optional
commonName             = supplied
emailAddress           = optional
[ req ]
default_bits       = 3072
default_md         = sha384
distinguished_name = req_distinguished_name
attributes         = req_attributes
[ req_distinguished_name ]
countryName            = Country Name (2 letter code)
stateOrProvinceName    = State or Province Name
organizationName       = Organization Name
commonName             = Common Name
[ req_attributes ]
[ usr_cert ]
basicConstraints       = CA:FALSE
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid,issuer
keyUsage               = critical,digitalSignature
extendedKeyUsage       = clientAuth
[ v3_ca ]
basicConstraints       = critical,CA:true,pathlen:0
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid:always,issuer
keyUsage               = critical,keyCertSign,cRLSign
EOF

    echo "Generating Root CA..."
    openssl ecparam -name secp384r1 -genkey -noout -out "${PRIV_DIR}/cakey.pem"
    chmod 600 "${PRIV_DIR}/cakey.pem"
    openssl req -new -x509 -sha384 -days 3650 -key "${PRIV_DIR}/cakey.pem" \
        -out "${CA_DIR}/cacert.crt" \
        -subj "/C=JP/ST=Tokyo/O=NIAP Test Lab/CN=EST Validation Root CA" \
        -extensions v3_ca -config estExampleCA.cnf

    echo "Generating Server Certificate..."
    openssl ecparam -name secp384r1 -genkey -noout -out "${PRIV_DIR}/estserver.key"
    chmod 600 "${PRIV_DIR}/estserver.key"

    cat > server_ext.cnf << 'EOF'
[ server_cert ]
basicConstraints        = CA:FALSE
subjectKeyIdentifier    = hash
authorityKeyIdentifier  = keyid,issuer
keyUsage                = critical,digitalSignature
extendedKeyUsage        = serverAuth,1.3.6.1.5.5.7.3.28
subjectAltName          = @san_names
authorityInfoAccess      = OCSP;URI:http://localhost:8082
[ san_names ]
DNS.1 = est-server
DNS.2 = localhost
IP.1  = 127.0.0.1
IP.2  = 10.0.2.2
[ req ]
distinguished_name = req_dn
[ req_dn ]
EOF

    openssl req -new -sha384 -key "${PRIV_DIR}/estserver.key" \
        -out "${CA_DIR}/estserver.csr" \
        -subj "/C=JP/ST=Tokyo/O=NIAP Test Lab/CN=est-server"

    openssl x509 -req -sha384 -days 397 -in "${CA_DIR}/estserver.csr" \
        -CA "${CA_DIR}/cacert.crt" -CAkey "${PRIV_DIR}/cakey.pem" \
        -CAcreateserial -out "${PRIV_DIR}/estserver.crt" \
        -extfile server_ext.cnf -extensions server_cert

    cat "${PRIV_DIR}/estserver.crt" "${PRIV_DIR}/estserver.key" > "${PRIV_DIR}/estservercertandkey.pem"
    cp "${CA_DIR}/cacert.crt" trustedcerts.crt
fi

# Serve CA cert over HTTP on port 8080
mkdir -p html
cp "${CA_DIR}/cacert.crt" html/cacert.pem
echo "Serving CA cert at http://localhost:8080/cacert.pem"
cd html
python3 -m http.server 8080 &
HTTP_PID=$!
cd ..

# Start OpenSSL OCSP responder on port 8082
echo "Starting OpenSSL OCSP responder on port 8082..."
openssl ocsp -index "${CA_DIR}/index.txt" -port 8082 \
    -rsigner "${CA_DIR}/cacert.crt" -rkey "${PRIV_DIR}/cakey.pem" \
    -CA "${CA_DIR}/cacert.crt" -text &
OCSP_PID=$!

export EST_TRUSTED_CERTS="$(pwd)/trustedcerts.crt"
export EST_CACERTS_RESP="$(pwd)/estCA/cacert.crt"
export EST_OPENSSL_CACONFIG="$(pwd)/estExampleCA.cnf"
export LD_LIBRARY_PATH="$(pwd)/../build/libest/src/est/.libs"

echo "Starting libest server directly on port 8443 (no NGINX needed)"
../build/libest/example/server/.libs/estserver \
    -p 8443 \
    -c "estCA/private/estservercertandkey.pem" \
    -k "estCA/private/estservercertandkey.pem" \
    -r "estrealm" \
    -v &
EST_PID=$!

function cleanup {
    echo "Shutting down servers..."
    kill $HTTP_PID
    kill $EST_PID
    kill $MTLS_PID
    kill $OCSP_PID
}
trap cleanup EXIT

# Start python mTLS server on 8081
cat > mtls_server.py << 'EOF'
import ssl
from http.server import HTTPServer, BaseHTTPRequestHandler

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-type', 'text/plain')
        self.end_headers()
        self.wfile.write(b"mTLS OK\n")

httpd = HTTPServer(('127.0.0.1', 8081), Handler)
context = ssl.create_default_context(ssl.Purpose.CLIENT_AUTH)
context.load_cert_chain(certfile="estCA/private/estservercertandkey.pem")
context.load_verify_locations(cafile="estCA/cacert.crt")
context.verify_mode = ssl.CERT_REQUIRED
httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
print("Starting python mTLS server on port 8081...")
httpd.serve_forever()
EOF

python3 mtls_server.py &
MTLS_PID=$!

echo "Servers are running! Press Ctrl+C to stop."
wait
