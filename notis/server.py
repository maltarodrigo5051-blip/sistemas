"""Notis: servidor local (archivos estáticos + proxy de estado en vivo).

Uso:  python -I server.py   ->  http://127.0.0.1:5600
El navegador no puede consultar Kick/Twitch/YouTube directo (CORS), así que este
proxy lo hace desde tu PC. No usa claves privadas.
"""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131 Safari/537.36"
USER_RE = re.compile(r"^[A-Za-z0-9_.\-]{1,40}$")  # bloquea rutas/URLs raras antes de armar la petición


def fetch(url, accept="application/json"):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept, "Accept-Language": "es"})
    with urllib.request.urlopen(req, timeout=12) as r:
        return r.read().decode("utf-8", "replace")


def kick(user):
    try:
        data = json.loads(fetch(f"https://kick.com/api/v2/channels/{user}"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return {"live": False, "error": "Canal no encontrado en Kick"}
        raise
    ls = data.get("livestream") or {}
    live = bool(ls.get("is_live"))
    return {
        "live": live,
        "title": ls.get("session_title") if live else None,
        "viewers": ls.get("viewer_count") if live else None,
        "url": f"https://kick.com/{data.get('slug', user)}",
        "name": (data.get("user") or {}).get("username"),
    }


def twitch(user):
    # shortcut: usa decapi.me (servicio público de terceros); si se cae, cambiar a la API Helix con tu propio Client ID
    txt = fetch(f"https://decapi.me/twitch/uptime/{user}", accept="text/plain").strip()
    low = txt.lower()
    if "not found" in low or "no user" in low:
        return {"live": False, "error": "Canal no encontrado en Twitch"}
    if "offline" in low:
        return {"live": False, "url": f"https://www.twitch.tv/{user}"}
    if "error" in low or len(txt) > 120:
        return {"live": False, "error": "Twitch no respondió bien"}
    title = fetch(f"https://decapi.me/twitch/title/{user}", accept="text/plain").strip()
    return {"live": True, "title": title, "uptime": txt, "url": f"https://www.twitch.tv/{user}"}


def youtube(user):
    handle = user.lstrip("@")
    try:
        html = fetch(f"https://www.youtube.com/@{handle}/live", accept="text/html")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return {"live": False, "error": "Canal no encontrado en YouTube"}
        raise
    canon = re.search(r'<link rel="canonical" href="([^"]+)"', html)
    canon = canon.group(1) if canon else ""
    # /live solo apunta a un video cuando hay directo (o uno programado); isLiveNow distingue el programado
    live = "watch?v=" in canon and '"isLiveNow":true' in html
    title = re.search(r'<meta name="title" content="([^"]*)"', html)
    return {
        "live": live,
        "title": title.group(1) if live and title else None,
        "url": canon if live else f"https://www.youtube.com/@{handle}",
    }


CHECKERS = {"kick": kick, "twitch": twitch, "youtube": youtube}


def avatar(platform, user):
    """URL de la foto de perfil, o None."""
    if platform == "kick":
        return (json.loads(fetch(f"https://kick.com/api/v2/channels/{user}")).get("user") or {}).get("profile_pic")
    if platform == "twitch":
        txt = fetch(f"https://decapi.me/twitch/avatar/{user}", accept="text/plain").strip()
        ok = txt.startswith("https://static-cdn.jtvnw.net/") and "user-default" not in txt  # la genérica no sirve
        return txt if ok else None
    html = fetch(f"https://www.youtube.com/@{user.lstrip('@')}", accept="text/html")
    m = re.search(r'<meta property="og:image" content="(https://yt3\.[^"]+)"', html)
    return m.group(1) if m else None


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=str(ROOT / "public"), **kw)

    def log_message(self, fmt, *args):
        if "/api/" in (args[0] if args else ""):
            return  # no llenar la consola con cada revisión
        super().log_message(fmt, *args)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path not in ("/api/status", "/api/avatar"):
            return super().do_GET()
        q = urllib.parse.parse_qs(url.query)
        platform, user = q.get("platform", [""])[0], q.get("user", [""])[0]
        if platform not in CHECKERS or not USER_RE.match(user.lstrip("@")):
            return self.send_json(400, {"error": "Plataforma o usuario inválido"})
        if url.path == "/api/avatar":
            try:
                return self.send_json(200, {"avatar": avatar(platform, user)})
            except Exception:
                return self.send_json(200, {"avatar": None})
        try:
            result = CHECKERS[platform](user)
        except Exception as e:  # red caída, Cloudflare, cambio de formato...
            result = {"live": False, "error": f"No se pudo consultar ({type(e).__name__})"}
        self.send_json(200, {"platform": platform, "user": user, **result})

    def send_json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5600
    print(f"Notis en http://127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
