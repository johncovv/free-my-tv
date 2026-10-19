"""Connects your Spotify account to Free My TV, once, from the computer.

    python3 -I tools/spotify_login.py

Needs your app's Client ID in config.properties (spotify_client_id=...), from developer.spotify.com/dashboard, with
the Redirect URI http://127.0.0.1:8888/callback. Opens the browser to authorize (read-only: your top items, recently
played, playlists and saved albums), then writes spotify_refresh_token to config.properties. Nothing is printed.
Uses only Python's standard library. Authorization Code with PKCE: no client secret.
"""
import base64
import hashlib
import http.server
import json
import os
import re
import secrets
import sys
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG = os.path.join(ROOT, "config.properties")
REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-top-read user-read-recently-played playlist-read-private user-library-read"


def config_value(key):
    if not os.path.exists(CONFIG):
        return ""
    for line in open(CONFIG, encoding="utf-8"):
        m = re.match(r"\s*" + re.escape(key) + r"\s*=(.*)", line)
        if m:
            return m.group(1).strip()
    return ""


def set_config(key, value):
    lines = open(CONFIG, encoding="utf-8").read().splitlines() if os.path.exists(CONFIG) else []
    for i, line in enumerate(lines):
        if re.match(r"\s*" + re.escape(key) + r"\s*=", line):
            lines[i] = key + "=" + value
            break
    else:
        lines.append(key + "=" + value)
    with open(CONFIG, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    client_id = config_value("spotify_client_id")
    if not client_id:
        sys.exit("Put spotify_client_id=<your Client ID> in config.properties first (developer.spotify.com/dashboard).")
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES,
        "code_challenge_method": "S256", "code_challenge": challenge, "state": state})
    result = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if q.get("state", [""])[0] == state:
                result["code"] = q.get("code", [""])[0]
                result["error"] = q.get("error", [""])[0]
            ok = bool(result.get("code"))
            body = ("<h2>Pronto! Pode fechar esta aba e voltar ao terminal.</h2>" if ok
                    else "<h2>Não autorizado. Feche esta aba e tente de novo.</h2>").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 8888), Callback)
    print("Abrindo o navegador para você autorizar no Spotify…")
    webbrowser.open(url)
    while "code" not in result and "error" not in result:
        server.handle_request()
    server.server_close()
    if not result.get("code"):
        sys.exit("Spotify não autorizou: " + (result.get("error") or "sem código"))
    data = urllib.parse.urlencode({"grant_type": "authorization_code", "code": result["code"], "redirect_uri": REDIRECT,
                                   "client_id": client_id, "code_verifier": verifier}).encode()
    req = urllib.request.Request("https://accounts.spotify.com/api/token", data=data,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    tokens = json.load(urllib.request.urlopen(req, timeout=20))
    set_config("spotify_refresh_token", tokens["refresh_token"])
    print("Conta do Spotify conectada. Salvo em config.properties (spotify_refresh_token). Rode ./build.sh e instale.")


if __name__ == "__main__":
    main()
