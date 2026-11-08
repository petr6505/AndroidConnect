"""TLS identity and pairing cryptography.

Pairing uses a commit/reveal short authentication string (like Bluetooth
numeric comparison) so a man-in-the-middle cannot make both screens show
the same code except by a 1-in-a-million guess:

  tablet -> laptop : commit = SHA256(nonce_c)
  laptop -> tablet : nonce_s
  tablet -> laptop : nonce_c          (laptop checks the commitment)
  both             : code = SHA256("AC-SAS" | server_cert_sha256 | nonce_c | nonce_s) mod 10^6

Each side uses the certificate fingerprint *it* sees, so an attacker that
substitutes its own certificate changes the code shown on the tablet.
After the user accepts on the laptop, the laptop issues a random 256-bit
token. Later connections pin the certificate and present the token.
"""

import datetime
import hashlib
import hmac
import os
import secrets
import ssl

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID


def ensure_identity(cert_path: str, key_path: str, common_name: str) -> None:
    if os.path.exists(cert_path) and os.path.exists(key_path):
        return
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365 * 20))
        .sign(key, hashes.SHA256())
    )
    fd = os.open(key_path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))


def cert_fingerprint(cert_path: str) -> bytes:
    with open(cert_path, "rb") as f:
        cert = x509.load_pem_x509_certificate(f.read())
    return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).digest()


def server_ssl_context(cert_path: str, key_path: str) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_3
    ctx.load_cert_chain(cert_path, key_path)
    return ctx


def new_nonce() -> bytes:
    return secrets.token_bytes(32)


def check_commit(commit: bytes, nonce_c: bytes) -> bool:
    return hmac.compare_digest(hashlib.sha256(nonce_c).digest(), commit)


def pairing_code(server_fp: bytes, nonce_c: bytes, nonce_s: bytes) -> str:
    digest = hashlib.sha256(b"AC-SAS" + server_fp + nonce_c + nonce_s).digest()
    return f"{int.from_bytes(digest[:8], 'big') % 1_000_000:06d}"


def new_token() -> bytes:
    return secrets.token_bytes(32)


def hash_token(token: bytes) -> str:
    return hashlib.sha256(token).hexdigest()


def token_matches(token: bytes, stored_hash: str) -> bool:
    return hmac.compare_digest(hash_token(token), stored_hash)
