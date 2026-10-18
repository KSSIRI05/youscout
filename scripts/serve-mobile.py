"""Serve the built Flutter web app locally, including the OIDC callback route."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1] / "apps/mobile/build/web"


class FlutterHandler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        # Le navigateur garde code/state dans son URL ; Flutter traite le callback.
        if urlsplit(path).path == "/auth/callback":
            path = "/index.html"
        return super().translate_path(path)

    def log_message(self, format, *args):
        # Les URL de callback peuvent contenir un code : ne jamais les journaliser.
        pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=7357)
    options = parser.parse_args()
    if not (ROOT / "index.html").exists():
        parser.error("Build Flutter web first: flutter build web --release")
    print(f"YouScout: http://localhost:{options.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", options.port), partial(FlutterHandler, directory=str(ROOT))).serve_forever()
