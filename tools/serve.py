#!/usr/bin/env python3
"""
Field-mapping server + static file host for the WebXR client.

Serves `web/` over HTTP (or HTTPS with a self-signed cert) and exposes a
small JSON API the WebXR page uses to push samples and pull back the
reconstructed field:

    POST /api/session                 → { id }
    POST /api/session/<id>/samples    → ingest a batch
    GET  /api/session/<id>/field      → latest reconstruction
    POST /api/session/<id>/config     → set bias mode
    POST /api/session/<id>/reset      → wipe and start over
    POST /log                         → free-form debug line (handy on phone)

By default the server serves plain HTTP on :8080. Mac Chrome treats
`http://localhost` as a secure context so camera/sensors work out of the
box on the desktop. For Android, use `--https` to enable a self-signed
HTTPS cert (or front the server with `cloudflared` / `ngrok` / `adb reverse`).

Usage:
    python3 tools/serve.py                # HTTP on :8080
    python3 tools/serve.py --https        # HTTPS on :8443
    python3 tools/serve.py --port 9000
"""
from __future__ import annotations

import argparse
import http.server
import ipaddress
import json
import os
import re
import socket
import ssl
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Optional

# Allow importing field_compute from this same directory regardless of CWD.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from field_compute import (  # noqa: E402
    FieldSession, Sample, representation_to_json,
)


WEB_DIR = Path(__file__).resolve().parent.parent / "web"
DEFAULT_CERT_DIR = Path.home() / ".fieldmapper" / "cert"


# ---------------------------------------------------------------------------
# Session registry
# ---------------------------------------------------------------------------

_SESSIONS: dict[str, FieldSession] = {}
_SESSIONS_LOCK = threading.Lock()
SESSION_TTL_S = 3600  # drop sessions idle for >1h


def get_or_create_session(session_id: Optional[str]) -> FieldSession:
    with _SESSIONS_LOCK:
        _evict_idle_locked()
        if session_id and session_id in _SESSIONS:
            sess = _SESSIONS[session_id]
            sess.last_seen = time.time()
            return sess
        sid = session_id or uuid.uuid4().hex[:12]
        sess = FieldSession(sid)
        _SESSIONS[sid] = sess
        return sess


def find_session(session_id: str) -> Optional[FieldSession]:
    with _SESSIONS_LOCK:
        _evict_idle_locked()
        sess = _SESSIONS.get(session_id)
        if sess is not None:
            sess.last_seen = time.time()
        return sess


def _evict_idle_locked() -> None:
    now = time.time()
    stale = [sid for sid, s in _SESSIONS.items() if now - s.last_seen > SESSION_TTL_S]
    for sid in stale:
        s = _SESSIONS.pop(sid, None)
        if s is not None:
            s.shutdown()


# ---------------------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------------------

API_PREFIX = "/api/session"


class FieldHandler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".wasm": "application/wasm",
        ".mjs":  "application/javascript",
    }

    # --- generic plumbing -------------------------------------------------

    def end_headers(self) -> None:
        # No caching during dev so a refresh always picks up new code.
        self.send_header("Cache-Control", "no-store")
        # Permissive CORS so a phone on the same Wi-Fi (or via tunnel) can hit us.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:  # type: ignore[override]
        sys.stderr.write(f"[{self.address_string()}] {fmt % args}\n")

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or "0")
        return self.rfile.read(length) if length else b""

    def _send_json(self, code: int, payload: dict | list) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_text(self, code: int, text: str, *, ctype: str = "text/plain; charset=utf-8") -> None:
        body = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self) -> None:  # noqa: N802
        self.send_response(204)
        self.end_headers()

    # --- routing ----------------------------------------------------------

    def do_POST(self) -> None:  # noqa: N802
        path = self.path.rstrip("/")
        if path == "/log":
            self._handle_log()
            return
        if path == API_PREFIX:
            sess = get_or_create_session(None)
            self._send_json(200, {"id": sess.id})
            return
        m = re.match(rf"^{re.escape(API_PREFIX)}/([A-Za-z0-9_-]+)/(samples|reset|config)$", path)
        if m:
            self._handle_session_post(m.group(1), m.group(2))
            return
        self._send_text(404, "not found")

    def do_GET(self) -> None:  # noqa: N802
        path = self.path
        # Strip query
        bare = path.split("?", 1)[0].rstrip("/") or "/"
        m = re.match(rf"^{re.escape(API_PREFIX)}/([A-Za-z0-9_-]+)/field$", bare)
        if m:
            self._handle_field_get(m.group(1))
            return
        # Default: static file from web/.
        super().do_GET()

    # --- handlers ---------------------------------------------------------

    def _handle_log(self) -> None:
        body = self._read_body()
        try:
            text = body.decode("utf-8", errors="replace").strip()
        except Exception:
            text = repr(body)
        sys.stderr.write(f"[{self.address_string()}] LOG {text}\n")
        self.send_response(204)
        self.end_headers()

    def _handle_session_post(self, sid: str, action: str) -> None:
        sess = get_or_create_session(sid)
        body = self._read_body()
        try:
            data = json.loads(body or b"{}")
        except json.JSONDecodeError as e:
            self._send_text(400, f"bad json: {e}")
            return

        if action == "samples":
            raw = data.get("samples")
            if not isinstance(raw, list):
                self._send_text(400, "samples: expected list")
                return
            parsed: list[Sample] = []
            for r in raw:
                try:
                    t = float(r.get("t", 0.0))
                    p = r["p"]; b = r["b"]
                    if not (isinstance(p, list) and len(p) == 3 and
                            isinstance(b, list) and len(b) == 3):
                        continue
                    parsed.append(Sample(
                        t=t,
                        p=(float(p[0]), float(p[1]), float(p[2])),
                        b=(float(b[0]), float(b[1]), float(b[2])),
                    ))
                except (KeyError, TypeError, ValueError):
                    continue
            n = sess.add_samples(parsed) if parsed else sess.store.count
            self._send_json(200, {
                "accepted": len(parsed),
                "totalSamples": n,
                "generation": sess.store.generation,
            })
            return

        if action == "reset":
            sess.reset()
            self._send_json(200, {"ok": True, "id": sess.id})
            return

        if action == "config":
            bias = data.get("bias")
            if bias not in (None, "none", "earth"):
                self._send_text(400, "bias: expected 'none' or 'earth'")
                return
            if bias is not None:
                sess.set_bias(bias)
            self._send_json(200, {"ok": True, "bias": sess.bias})
            return

    def _handle_field_get(self, sid: str) -> None:
        # The client persists its session ID in localStorage and reuses it
        # across page loads, but this server holds sessions in memory and
        # loses them on restart. Auto-create the session under the given
        # ID instead of 404'ing — the alternative is a poll loop spamming
        # the server forever after every server restart.
        sess = get_or_create_session(sid)
        # Optional `since=<gen>` short-circuit: if the client already has
        # this generation, skip the JSON encode and just say "no change".
        q = self.path.split("?", 1)[1] if "?" in self.path else ""
        params = dict(p.split("=", 1) for p in q.split("&") if "=" in p) if q else {}
        rep = sess.representation()
        if rep is None:
            self._send_json(200, {
                "generation":   sess.store.generation,
                "sampleCount":  sess.store.count,
                "bias":         sess.bias,
                "lines":        [],
                "suggestions":  [],
                "pending":      True,
            })
            return
        try:
            since = int(params.get("since", "-1"))
        except ValueError:
            since = -1
        if rep.generation == since:
            self._send_json(200, {
                "generation": rep.generation,
                "unchanged":  True,
            })
            return
        self._send_json(200, representation_to_json(rep))


# ---------------------------------------------------------------------------
# HTTPS / self-signed cert plumbing — copied from building-to-be (same author,
# same use case: opening the page on an Android phone over Wi-Fi).
# ---------------------------------------------------------------------------

def local_ipv4_addresses() -> list[str]:
    addrs: list[str] = []
    try:
        out = subprocess.check_output(["ifconfig"], text=True, stderr=subprocess.DEVNULL)
    except (FileNotFoundError, subprocess.CalledProcessError):
        return addrs
    for m in re.finditer(r"\binet (\d+\.\d+\.\d+\.\d+)\b", out):
        ip = m.group(1)
        try:
            if ipaddress.IPv4Address(ip).is_loopback:
                continue
        except ValueError:
            continue
        addrs.append(ip)
    return addrs


def primary_ipv4() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        pass
    addrs = local_ipv4_addresses()
    return addrs[0] if addrs else "127.0.0.1"


def hostname_local() -> Optional[str]:
    try:
        name = socket.gethostname()
    except OSError:
        return None
    if not name:
        return None
    return name if name.endswith(".local") else name + ".local"


def ensure_cert(cert_dir: Path, sans: list[str]) -> tuple[Path, Path]:
    cert_dir.mkdir(parents=True, exist_ok=True)
    cert = cert_dir / "cert.pem"
    key = cert_dir / "key.pem"
    meta = cert_dir / "sans.txt"
    sans_line = "\n".join(sorted(sans))
    if cert.exists() and key.exists() and meta.exists() and meta.read_text() == sans_line:
        return cert, key
    print(f"Generating self-signed cert in {cert_dir} ...", file=sys.stderr)
    san_parts: list[str] = []
    for s in sans:
        try:
            ipaddress.ip_address(s); san_parts.append(f"IP:{s}")
        except ValueError:
            san_parts.append(f"DNS:{s}")
    san_ext = "subjectAltName=" + ",".join(san_parts)
    try:
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-keyout", str(key), "-out", str(cert), "-days", "397",
             "-subj", "/CN=localhost",
             "-addext", san_ext,
             "-addext", "keyUsage=critical,digitalSignature,keyEncipherment",
             "-addext", "extendedKeyUsage=serverAuth",
             "-addext", "basicConstraints=critical,CA:FALSE"],
            check=True,
        )
    except FileNotFoundError:
        print("ERROR: openssl not found. Install it (`brew install openssl`) "
              "or use a tunnel instead.", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as e:
        print(f"ERROR: openssl failed (exit {e.returncode}).", file=sys.stderr)
        sys.exit(1)
    meta.write_text(sans_line)
    return cert, key


def build_ssl_context(cert: Path, key: Path) -> ssl.SSLContext:
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))
    try:
        ctx.set_alpn_protocols(["http/1.1"])
    except (AttributeError, NotImplementedError):
        pass
    return ctx


def print_qr_to_stderr(url: str) -> None:
    for args in ([
        "qrencode", "-t", "ANSIUTF8", "-m", "1", url,
    ], [
        "qrencode", "-t", "UTF8", url,
    ]):
        try:
            subprocess.run(args, check=True, stdout=sys.stderr)
            return
        except FileNotFoundError:
            break
        except subprocess.CalledProcessError:
            continue
    try:
        import qrcode  # type: ignore
        qr = qrcode.QRCode(border=1)
        qr.add_data(url); qr.make(fit=True)
        qr.print_ascii(out=sys.stderr, invert=True)
        return
    except ImportError:
        pass
    sys.stderr.write("(Install `brew install qrencode` for a scannable QR code here.)\n")


def run_server(port: int, use_https: bool, cert_dir: Path) -> None:
    if not WEB_DIR.is_dir():
        print(f"ERROR: {WEB_DIR} not found.", file=sys.stderr)
        sys.exit(1)
    os.chdir(WEB_DIR)

    ip = primary_ipv4()
    host = hostname_local()
    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", port), FieldHandler)

    def say(s: str = "") -> None:
        sys.stderr.write(s + "\n"); sys.stderr.flush()

    scheme = "https" if use_https else "http"
    phone_url = f"{scheme}://{ip}:{port}/"
    say(f"\nServing {WEB_DIR} over {scheme.upper()}")
    say(f"  On this Mac:            {scheme}://localhost:{port}/")
    if host:
        say(f"  On the same Wi-Fi:       {scheme}://{host}:{port}/")
    say(f"  On the same Wi-Fi (IP):  {phone_url}")
    say("\nField API:")
    say(f"  POST {scheme}://…/api/session                 — create session")
    say(f"  POST {scheme}://…/api/session/<id>/samples    — ingest a batch")
    say(f"  GET  {scheme}://…/api/session/<id>/field      — current reconstruction")
    say("\nScan this QR code on your phone:\n")
    print_qr_to_stderr(phone_url)

    if use_https:
        sans = ["localhost", "127.0.0.1", ip]
        if host: sans.append(host)
        sans = sorted(set(sans))
        cert, key = ensure_cert(cert_dir, sans)
        ctx = build_ssl_context(cert, key)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        say("\nHTTPS uses a self-signed cert. Chrome will show a warning — tap")
        say("'Advanced → Proceed'. The Generic Sensor API + WebXR both require a")
        say("secure context, so plain HTTP over Wi-Fi will not work.")
    else:
        say("\nHTTP is fine on this Mac (localhost is a secure context). For your")
        say("phone, use --https, `cloudflared tunnel --url http://localhost:%d`," % port)
        say("`ngrok http %d`, or `adb reverse tcp:%d tcp:%d`." % (port, port, port))

    say("\nCtrl-C to stop.\n")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--https", action="store_true", help="serve HTTPS with a self-signed cert")
    ap.add_argument("--port", type=int, default=None,
                    help="port to listen on (default: 8080 HTTP / 8443 HTTPS)")
    ap.add_argument("--cert-dir", default=str(DEFAULT_CERT_DIR),
                    help="where to store the generated cert/key (HTTPS only)")
    args = ap.parse_args()
    port = args.port if args.port is not None else (8443 if args.https else 8080)
    run_server(port=port, use_https=args.https, cert_dir=Path(args.cert_dir).expanduser())


if __name__ == "__main__":
    main()
