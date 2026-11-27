"""
La página del entrenador (opción en navegador; la app de escritorio es app.py):
un servidor solo para esta PC (127.0.0.1) y su API.
    python -m nitido_entrenador.servidor [--puerto 8765] [--no-abrir]
"""
import argparse
import base64
import io
import json
import os
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


from . import datos, nucleo

WEB = os.path.join(nucleo.AQUI, "web")
PROY, MODELOS, carpeta, leer, guardar = nucleo.PROY, nucleo.MODELOS, nucleo.carpeta, nucleo.leer, nucleo.guardar


def permitido(ruta):
    """Solo se sirven fotos de las carpetas de tus proyectos (nada más del disco)."""
    r = os.path.realpath(ruta)
    raices = [os.path.realpath(PROY)]
    for m in MODELOS:
        p = leer(m)
        raices += [os.path.realpath(p[k]) for k in ("antes_dir", "despues_dir", "prueba_dir") if p.get(k)]
    return any(r == x or r.startswith(x + os.sep) for x in raices)


def jpeg(im, q=85):
    b = io.BytesIO(); im.save(b, "JPEG", quality=q); return b.getvalue()


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)

    def _bytes(self, b, tipo):
        self.send_response(200); self.send_header("Content-Type", tipo); self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(b)

    def _cuerpo(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            if u.path in ("/", "/index.html"):
                return self._bytes(open(os.path.join(WEB, "index.html"), "rb").read(), "text/html; charset=utf-8")
            if u.path == "/api/estado":
                gpu, info = nucleo.tarjeta()
                return self._json({"gpu": gpu, "gpu_info": info,
                                   "proyectos": {m: nucleo.resumen(m) for m in MODELOS},
                                   "corridas": {m: c.estado for m, c in nucleo.corridas.items()}})
            if u.path == "/api/proyecto":
                return self._json(leer(q["modelo"]))
            if u.path == "/mini":
                f = q["f"]
                if not permitido(f):
                    return self._json({"error": "fuera de tus carpetas"}, 403)
                im = datos.abrir(f, int(q.get("lado", 220)))
                return self._bytes(jpeg(im), "image/jpeg")
            if u.path == "/api/muestra":
                f = nucleo.ultima_muestra(q["modelo"])
                if not f:
                    return self._json({"error": "aún no hay"}, 404)
                return self._bytes(open(f, "rb").read(), "image/jpeg")
        except Exception as e:
            return self._json({"error": str(e)}, 500)
        self._json({"error": "no existe"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        try:
            b = self._cuerpo(); m = b.get("modelo")
            if m not in MODELOS:
                return self._json({"error": "modelo: estilo o piel"}, 400)
            if u.path == "/api/pares":
                return self._json(nucleo.revisar_pares(m, b["antes"], b["despues"]))
            if u.path == "/api/usar":
                nucleo.usar(m, b["nombre"], b["usar"]); return self._json({"ok": True})
            if u.path == "/api/aumentos":
                return self._json(nucleo.poner_aumentos(m, b["aumentos"]))
            if u.path == "/api/vista_aumentos":
                nombre, hojas = nucleo.vista_aumentos(m, b.get("nombre"))
                return self._json({"nombre": nombre, "vistas": ["data:image/jpeg;base64," + base64.b64encode(jpeg(h, 80)).decode() for h in hojas]})
            if u.path == "/api/entrenar":
                nucleo.entrenar(m, b.get("vueltas"), b.get("desde_cero")); return self._json({"ok": True})
            if u.path in ("/api/pausar", "/api/seguir", "/api/detener"):
                nucleo.control(m, u.path.rsplit("/", 1)[1]); return self._json({"ok": True})
            if u.path == "/api/probar":
                return self._json({"hechos": nucleo.probar(m, b["carpeta"])})
            if u.path == "/api/exportar":
                return self._json(nucleo.exportar(m))
        except nucleo.Aviso as e:
            return self._json({"error": str(e)}, 400)
        except Exception as e:
            import traceback
            traceback.print_exc()
            return self._json({"error": str(e)}, 500)
        self._json({"error": "no existe"}, 404)


def main():
    ap = argparse.ArgumentParser(description="Nítido · Entrenador")
    ap.add_argument("--puerto", type=int, default=8765)
    ap.add_argument("--no-abrir", action="store_true")
    a = ap.parse_args()
    os.makedirs(PROY, exist_ok=True)
    srv = ThreadingHTTPServer(("127.0.0.1", a.puerto), H)
    url = f"http://127.0.0.1:{a.puerto}/"
    print(f"Nítido · Entrenador en {url}  (Ctrl+C para cerrar; si estaba entrenando, sigue desde la última vuelta al volver)")
    if not a.no_abrir:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nCerrando…")
        nucleo.detener_todo()


if __name__ == "__main__":
    main()
