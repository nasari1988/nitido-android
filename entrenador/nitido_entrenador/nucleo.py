"""
Lo que hace el entrenador, sin pantalla: tus proyectos, pares, aumentos, entrenar, probar y exportar.
Lo usan la app de escritorio (app.py) y la página (servidor.py).
"""
import glob
import json
import os
import random
import threading

from PIL import Image

from . import datos

AQUI = datos.AQUI
PROY = os.path.join(AQUI, "proyectos")
MODELOS = ("estilo", "piel")
NOMBRES = {"estilo": "Mi estilo", "piel": "Mi retoque de piel"}
corridas = {}          # modelo -> Entrenamiento
_cand = threading.Lock()


class Aviso(Exception):
    """Un problema que se le explica a la persona (carpeta que no existe, etc.)."""


def carpeta(m):
    d = os.path.join(PROY, m)
    os.makedirs(d, exist_ok=True)
    return d


def leer(m):
    f = os.path.join(carpeta(m), "proyecto.json")
    return json.load(open(f)) if os.path.exists(f) else {"pares": [], "aumentos": dict(datos.AUMENTOS_BASE)}


def guardar(m, p):
    json.dump(p, open(os.path.join(carpeta(m), "proyecto.json"), "w"), ensure_ascii=False, indent=1)


def tarjeta():
    """(nombre de la tarjeta NVIDIA o None, {temp, vram, vram_total})."""
    import torch
    from .entrenar import _gpu
    return (torch.cuda.get_device_name(0) if torch.cuda.is_available() else None), _gpu()


def resumen(m):
    pares = leer(m)["pares"]
    return {"pares": len(pares), "usar": sum(1 for p in pares if p.get("usar", True)),
            "mejor": os.path.exists(os.path.join(carpeta(m), f"{m}_mejor.pt")),
            "exportados": sorted(os.path.basename(d) for d in glob.glob(os.path.join(carpeta(m), "exportado", "*")))}


def corrida(m):
    c = corridas.get(m)
    return c.estado if c else None


def activo(m):
    c = corridas.get(m)
    return bool(c and c.hilo.is_alive())


def revisar_pares(m, antes, despues, avance=None):
    for k in (antes, despues):
        if not os.path.isdir(k):
            raise Aviso(f"No existe la carpeta: {k}")
    P = leer(m)
    P["antes_dir"], P["despues_dir"] = os.path.abspath(antes), os.path.abspath(despues)
    excl = {p["nombre"] for p in P["pares"] if not p.get("usar", True)}
    pares, sin = datos.emparejar(P["antes_dir"], P["despues_dir"], os.path.join(carpeta(m), "alineados"), avance)
    for p in pares:
        if p["nombre"] in excl:
            p["usar"] = False
    P["pares"], P["sin_pareja"] = pares, sin
    guardar(m, P)
    return P


def usar(m, nombre, si):
    P = leer(m)
    for p in P["pares"]:
        if p["nombre"] == nombre:
            p["usar"] = bool(si)
    guardar(m, P)
    return P


def poner_aumentos(m, a):
    P = leer(m)
    P["aumentos"] = dict(datos.AUMENTOS_BASE, **a)
    guardar(m, P)
    return P["aumentos"]


def vista_aumentos(m, nombre=None, n=8, lado=256):
    """(nombre del par, [hojas antes|después]); la primera hoja es el par original."""
    P = leer(m)
    usar_ = [p for p in P["pares"] if p.get("usar", True)]
    if not usar_:
        raise Aviso("Primero revisa tus pares: no hay ninguno para usar.")
    p = next((x for x in usar_ if x["nombre"] == nombre), random.choice(usar_))
    a = datos.abrir(p["antes"], 1024)
    d = datos.abrir(p["despues"], 1024).resize(a.size)
    cfg = dict(datos.AUMENTOS_BASE, **P.get("aumentos", {}))
    hojas = []
    for i in range(n):
        aa, dd = (a.resize((lado, lado)), d.resize((lado, lado))) if i == 0 else datos.aumentar(a, d, cfg, lado)
        h = Image.new("RGB", (lado * 2, lado))
        h.paste(aa, (0, 0)); h.paste(dd, (lado, 0))
        hojas.append(h)
    return p["nombre"], hojas


def entrenar(m, vueltas=None, desde_cero=False):
    with _cand:
        if activo(m):
            raise Aviso("Ya está entrenando.")
        if any(c.hilo.is_alive() for c in corridas.values()):
            raise Aviso("La tarjeta ya está ocupada con el otro modelo.")
        if not any(p.get("usar", True) for p in leer(m)["pares"]):
            raise Aviso("Primero revisa tus pares: no hay ninguno para usar.")
        from .entrenar import Entrenamiento
        if desde_cero:
            for f in glob.glob(os.path.join(carpeta(m), f"{m}_*.pt")):
                os.remove(f)
        if vueltas:
            P = leer(m)
            P.setdefault("config", {}).setdefault(m, {})["vueltas"] = int(vueltas)
            guardar(m, P)
        corridas[m] = Entrenamiento(carpeta(m), m)
        corridas[m].iniciar()


def control(m, accion):
    """accion: pausar, seguir o detener."""
    c = corridas.get(m)
    if not c:
        raise Aviso("No está entrenando.")
    {"pausar": c.pausa.set, "seguir": c.pausa.clear, "detener": c.alto.set}[accion]()


def detener_todo():
    for c in corridas.values():
        c.alto.set()


def ultima_muestra(m):
    fs = sorted(glob.glob(os.path.join(carpeta(m), "muestras", "vuelta_*.jpg")))
    return fs[-1] if fs else None


def probar(m, dir_fotos):
    if not os.path.isdir(dir_fotos):
        raise Aviso("No existe esa carpeta.")
    if not resumen(m)["mejor"]:
        raise Aviso("Primero entrena: aún no hay un modelo guardado.")
    from .exportar import probar as _probar
    P = leer(m)
    P["prueba_dir"] = os.path.abspath(dir_fotos)
    guardar(m, P)
    return _probar(carpeta(m), m, P["prueba_dir"], os.path.join(carpeta(m), "pruebas"))


def exportar(m):
    if not resumen(m)["mejor"]:
        raise Aviso("Primero entrena: aún no hay un modelo guardado.")
    from .exportar import exportar as _exportar
    return _exportar(carpeta(m), m)


def historial(m):
    """Lo guardado de la última corrida (para mostrarlo al abrir la app): vuelta, curvas y mejor vuelta."""
    f = os.path.join(carpeta(m), f"{m}_ultimo.pt")
    if not os.path.exists(f):
        return None
    import torch
    s = torch.load(f, map_location="cpu", weights_only=True)
    vueltas = leer(m).get("config", {}).get(m, {}).get("vueltas") or s.get("vuelta", 0)
    mejor = s.get("mejor") or None
    return {"modelo": m, "fase": "guardado", "vuelta": s.get("vuelta", 0), "vueltas": max(vueltas, s.get("vuelta", 0)),
            "paso": 0, "pasos": 0, "falta_s": None, "perdida": s.get("perdida", []), "prueba": s.get("prueba", []),
            "parecido": mejor.get("parecido") if mejor else None, "mejor": mejor,
            "mensaje": f"Guardado en la vuelta {s.get('vuelta', 0)}. Puedes seguir entrenando, probar o exportar.", "gpu": {}}
