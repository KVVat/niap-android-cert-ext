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
