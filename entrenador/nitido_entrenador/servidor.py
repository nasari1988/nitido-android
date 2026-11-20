"""
La página del entrenador: un servidor solo para esta PC (127.0.0.1) y su API.
    python -m nitido_entrenador.servidor [--puerto 8765] [--no-abrir]
"""
import argparse
import base64
import glob
import io
import json
import os
import random
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from PIL import Image

from . import datos

AQUI = datos.AQUI
PROY = os.path.join(AQUI, "proyectos")
WEB = os.path.join(AQUI, "web")
MODELOS = ("estilo", "piel")
corridas = {}          # modelo -> Entrenamiento
cand = threading.Lock()


def carpeta(m):
    d = os.path.join(PROY, m)
    os.makedirs(d, exist_ok=True)
    return d


def leer(m):
    f = os.path.join(carpeta(m), "proyecto.json")
    return json.load(open(f)) if os.path.exists(f) else {"pares": [], "aumentos": dict(datos.AUMENTOS_BASE)}


def guardar(m, p):
    json.dump(p, open(os.path.join(carpeta(m), "proyecto.json"), "w"), ensure_ascii=False, indent=1)


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
                from .entrenar import _gpu
                import torch
                return self._json({
                    "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None, "gpu_info": _gpu(),
                    "proyectos": {m: {"pares": len(leer(m)["pares"]), "usar": sum(1 for p in leer(m)["pares"] if p.get("usar", True)),
                                      "mejor": os.path.exists(os.path.join(carpeta(m), f"{m}_mejor.pt")),
                                      "exportados": sorted(os.path.basename(d) for d in glob.glob(os.path.join(carpeta(m), "exportado", "*")))}
                                  for m in MODELOS},
                    "corridas": {m: c.estado for m, c in corridas.items()},
                })
            if u.path == "/api/proyecto":
                return self._json(leer(q["modelo"]))
            if u.path == "/mini":
                f = q["f"]
                if not permitido(f):
                    return self._json({"error": "fuera de tus carpetas"}, 403)
                im = datos.abrir(f, int(q.get("lado", 220)))
                return self._bytes(jpeg(im), "image/jpeg")
            if u.path == "/api/muestra":
                fs = sorted(glob.glob(os.path.join(carpeta(q["modelo"]), "muestras", "vuelta_*.jpg")))
                if not fs:
                    return self._json({"error": "aún no hay"}, 404)
                return self._bytes(open(fs[-1], "rb").read(), "image/jpeg")
        except Exception as e:
            return self._json({"error": str(e)}, 500)
        self._json({"error": "no existe"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        try:
            b = self._cuerpo(); m = b.get("modelo")
            if m not in MODELOS:
                return self._json({"error": "modelo: estilo o piel"}, 400)
            P = leer(m)
            if u.path == "/api/pares":
                for k in ("antes", "despues"):
                    if not os.path.isdir(b[k]):
                        return self._json({"error": f"No existe la carpeta: {b[k]}"}, 400)
                P["antes_dir"], P["despues_dir"] = os.path.abspath(b["antes"]), os.path.abspath(b["despues"])
                excl = {p["nombre"] for p in P["pares"] if not p.get("usar", True)}
                pares, sin = datos.emparejar(P["antes_dir"], P["despues_dir"])
                for p in pares:
                    if p["nombre"] in excl:
                        p["usar"] = False
                P["pares"], P["sin_pareja"] = pares, sin
                guardar(m, P)
                return self._json(P)
            if u.path == "/api/usar":
                for p in P["pares"]:
                    if p["nombre"] == b["nombre"]:
                        p["usar"] = bool(b["usar"])
                guardar(m, P); return self._json({"ok": True})
            if u.path == "/api/aumentos":
                P["aumentos"] = dict(datos.AUMENTOS_BASE, **b["aumentos"]); guardar(m, P)
                return self._json(P["aumentos"])
            if u.path == "/api/vista_aumentos":
                usar = [p for p in P["pares"] if p.get("usar", True)]
                if not usar:
                    return self._json({"error": "sin pares"}, 400)
                p = next((x for x in usar if x["nombre"] == b.get("nombre")), random.choice(usar))
                a = datos.abrir(p["antes"], 1024); d = datos.abrir(p["despues"], 1024).resize(a.size)
                cfg = dict(datos.AUMENTOS_BASE, **P.get("aumentos", {}))
                out = []
                for i in range(8):
                    aa, dd = (a.resize((256, 256)), d.resize((256, 256))) if i == 0 else datos.aumentar(a, d, cfg, 256)
                    hoja = Image.new("RGB", (512, 256)); hoja.paste(aa, (0, 0)); hoja.paste(dd, (256, 0))
                    out.append("data:image/jpeg;base64," + base64.b64encode(jpeg(hoja, 80)).decode())
                return self._json({"nombre": p["nombre"], "vistas": out})
            if u.path == "/api/entrenar":
                with cand:
                    c = corridas.get(m)
                    if c and c.hilo.is_alive():
                        return self._json({"error": "ya está entrenando"}, 409)
                    if any(x.hilo.is_alive() for x in corridas.values()):
                        return self._json({"error": "la tarjeta ya está ocupada con el otro modelo"}, 409)
                    from .entrenar import Entrenamiento
                    if b.get("desde_cero"):
                        for f in glob.glob(os.path.join(carpeta(m), f"{m}_*.pt")):
                            os.remove(f)
                    if b.get("vueltas"):
                        P.setdefault("config", {}).setdefault(m, {})["vueltas"] = int(b["vueltas"]); guardar(m, P)
                    corridas[m] = Entrenamiento(carpeta(m), m); corridas[m].iniciar()
                return self._json({"ok": True})
            if u.path in ("/api/pausar", "/api/seguir", "/api/detener"):
                c = corridas.get(m)
                if not c:
                    return self._json({"error": "no está entrenando"}, 400)
                {"/api/pausar": c.pausa.set, "/api/seguir": c.pausa.clear, "/api/detener": c.alto.set}[u.path]()
                return self._json({"ok": True})
            if u.path == "/api/probar":
                from .exportar import probar
                if not os.path.isdir(b["carpeta"]):
                    return self._json({"error": "No existe esa carpeta"}, 400)
                P["prueba_dir"] = os.path.abspath(b["carpeta"]); guardar(m, P)
                r = probar(carpeta(m), m, P["prueba_dir"], os.path.join(carpeta(m), "pruebas"))
                return self._json({"hechos": r})
            if u.path == "/api/exportar":
                from .exportar import exportar
                return self._json(exportar(carpeta(m), m))
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
        for c in corridas.values():
            c.alto.set()


if __name__ == "__main__":
    main()
