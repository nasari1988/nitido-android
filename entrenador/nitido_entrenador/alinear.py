"""
Alinea cada par antes de entrenar: busca cientos de puntos que coinciden en las dos fotos,
calcula cuánto se movió, giró, escaló o recortó tu edición y acomoda el «antes» encima del
«después» (tu edición no se toca: es lo que el modelo aprende y debe quedar nítida).
Las fotos alineadas se guardan aparte; tus originales no cambian.
"""
import json
import math
import os

import numpy as np
from PIL import Image

try:
    import cv2
except ImportError:          # sin OpenCV: el entrenador solo revisa, como antes
    cv2 = None

LADO_CALC = 1600      # tamaño al que se buscan los puntos
MIN_PUNTOS = 25       # menos que esto: no se confía en la alineación
LADO_GUARDADO = 2560  # lado largo máximo de las fotos alineadas que se guardan


def disponible():
    return cv2 is not None


def _gris(im, lado):
    k = min(1.0, lado / max(im.size))
    g = np.asarray(im.convert("L").resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.BILINEAR))
    # igualar el contraste por zonas: así coinciden los puntos aunque tu edición cambie mucho la luz y el color
    g = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(g)
    return g, k


def transformacion(a, d):
    """Matriz 2×3 que lleva el «antes» (a) al encuadre del «después» (d), en píxeles completos,
    y cuántos puntos la respaldan. None si no se encontró."""
    ga, ka = _gris(a, LADO_CALC)
    gd, kd = _gris(d, LADO_CALC)
    sift = cv2.SIFT_create(nfeatures=4000)
    pa, da = sift.detectAndCompute(ga, None)
    pd, dd = sift.detectAndCompute(gd, None)
    if da is None or dd is None or len(pa) < MIN_PUNTOS or len(pd) < MIN_PUNTOS:
        return None, 0
    pares = cv2.BFMatcher(cv2.NORM_L2).knnMatch(da, dd, k=2)
    buenos = [m for m, n in (x for x in pares if len(x) == 2) if m.distance < 0.75 * n.distance]
    if len(buenos) < MIN_PUNTOS:
        return None, len(buenos)
    src = np.float32([pa[m.queryIdx].pt for m in buenos]) / ka
    dst = np.float32([pd[m.trainIdx].pt for m in buenos]) / kd
    # mover, girar y escalar (sin deformar): es lo que hacen recortar y enderezar
    M, ok = cv2.estimateAffinePartial2D(src, dst, method=cv2.RANSAC, ransacReprojThreshold=3.0 / kd,
                                        maxIters=5000, confidence=0.999)
    n = int(ok.sum()) if ok is not None else 0
    if M is None or n < MIN_PUNTOS:
        return None, n
    return M, n


def describir(M, a_size, d_size):
    """(texto corto, es_casi_igual)."""
    s = math.hypot(M[0, 0], M[1, 0])
    ang = math.degrees(math.atan2(M[1, 0], M[0, 0]))
    # cuánto se mueve el centro del «después» en píxeles del «después»
    cx, cy = d_size[0] / 2, d_size[1] / 2
    Mi = cv2.invertAffineTransform(M)
    ox, oy = Mi[0, 0] * cx + Mi[0, 1] * cy + Mi[0, 2], Mi[1, 0] * cx + Mi[1, 1] * cy + Mi[1, 2]
    esperado_x, esperado_y = a_size[0] / 2, a_size[1] / 2
    mov = math.hypot(ox - esperado_x, oy - esperado_y) * s
    escala_pura = d_size[0] / a_size[0]
    partes = []
    if abs(ang) >= 0.05:
        partes.append(f"girado {ang:+.1f}°")
    if abs(s - 1) >= 0.005 and abs(s / escala_pura - 1) >= 0.005:   # zoom real (no solo exportar más chico)
        partes.append(f"zoom {100 * s:.1f} %")
    if mov >= 1.0:
        partes.append(f"movido {mov:.0f} px")
    recorte = 1 - (d_size[0] * d_size[1]) / (a_size[0] * a_size[1] * s * s)
    if recorte >= 0.005:
        partes.append(f"recortado {100 * recorte:.0f} %")
    casi_igual = abs(ang) < 0.05 and abs(s / escala_pura - 1) < 0.002 and mov < 0.75
    return ", ".join(partes) or "ya estaba alineado", casi_igual


def _recorte_valido(mascara):
    """El rectángulo más grande (aprox.) donde la foto acomodada cubre todo: se quitan las orillas vacías."""
    h, w = mascara.shape
    x0, y0, x1, y1 = 0, 0, w, h
    while x1 - x0 > 8 and y1 - y0 > 8:
        sub = mascara[y0:y1, x0:x1]
        lados = {"arr": (~sub[0]).sum() / sub.shape[1], "aba": (~sub[-1]).sum() / sub.shape[1],
                 "izq": (~sub[:, 0]).sum() / sub.shape[0], "der": (~sub[:, -1]).sum() / sub.shape[0]}
        peor = max(lados, key=lados.get)
        if lados[peor] == 0:
            break
        if peor == "arr":
            y0 += 1
        elif peor == "aba":
            y1 -= 1
        elif peor == "izq":
            x0 += 1
        else:
            x1 -= 1
    return x0, y0, x1, y1


def alinear_par(ruta_a, ruta_d, destino, nombre, abrir):
    """-> dict con 'antes', 'despues' (rutas a usar), 'nota', 'puntos', o 'error'."""
    a, d = abrir(ruta_a), abrir(ruta_d)
    M, n = transformacion(a, d)
    if M is None:
        return {"error": "no encontré puntos que coincidan", "puntos": n}
    nota, casi_igual = describir(M, a.size, d.size)
    if casi_igual:
        return {"antes": ruta_a, "despues": ruta_d, "nota": nota, "puntos": n, "igual": True}
    W, H = d.size
    aa = cv2.warpAffine(np.asarray(a), M, (W, H), flags=cv2.INTER_LANCZOS4, borderMode=cv2.BORDER_CONSTANT)
    # qué parte del «después» cubre el «antes» acomodado (calculado en chico, es rápido)
    k = min(1.0, 800 / max(W, H))
    Mk = M.copy(); Mk[:, 2] *= k          # las dos fotos a escala k: el giro/escala no cambia, el movimiento sí
    wk, hk = max(1, round(W * k)), max(1, round(H * k))
    ak = max(1, round(a.width * k)), max(1, round(a.height * k))
    cub = cv2.warpAffine(np.full((ak[1], ak[0]), 255, np.uint8), Mk, (wk, hk), flags=cv2.INTER_NEAREST) > 0
    cub = cv2.erode(cub.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    x0, y0, x1, y1 = _recorte_valido(cub)
    x0, y0, x1, y1 = [int(round(v / k)) for v in (x0, y0, x1, y1)]
    x0, y0 = min(W - 1, x0 + 1), min(H - 1, y0 + 1)
    x1, y1 = max(x0 + 1, min(W, x1 - 1)), max(y0 + 1, min(H, y1 - 1))
    if (x1 - x0) * (y1 - y0) < 0.5 * W * H:
        return {"error": "tras alinear queda menos de la mitad de la foto", "puntos": n}
    os.makedirs(destino, exist_ok=True)
    fa, fd = os.path.join(destino, f"{nombre}_antes.jpg"), os.path.join(destino, f"{nombre}_despues.jpg")
    ia, idd = Image.fromarray(aa[y0:y1, x0:x1]), d.crop((x0, y0, x1, y1))
    if max(idd.size) > LADO_GUARDADO:   # el entrenamiento no usa más (piel: 2048 px); ahorra disco
        k = LADO_GUARDADO / max(idd.size)
        t = (max(1, round(idd.width * k)), max(1, round(idd.height * k)))
        ia, idd = ia.resize(t, Image.LANCZOS), idd.resize(t, Image.LANCZOS)
    ia.save(fa, quality=97, subsampling=0)
    idd.save(fd, quality=97, subsampling=0)
    return {"antes": fa, "despues": fd, "nota": nota, "puntos": n, "igual": False}


class Cache:
    """Recuerda qué pares ya se alinearon (por ruta, tamaño y fecha) para no repetirlo en cada «Revisar»."""

    def __init__(self, destino):
        self.f = os.path.join(destino, "indice.json")
        self.destino = destino
        try:
            self.d = json.load(open(self.f))
        except Exception:  # noqa: BLE001
            self.d = {}

    @staticmethod
    def _firma(*rutas):
        return "|".join(f"{r}:{os.path.getsize(r)}:{int(os.path.getmtime(r))}" for r in rutas)

    def get(self, ra, rd):
        r = self.d.get(self._firma(ra, rd))
        if r and not r.get("error") and not r.get("igual"):
            if not (os.path.exists(r["antes"]) and os.path.exists(r["despues"])):
                return None
        return r

    def put(self, ra, rd, r):
        self.d[self._firma(ra, rd)] = r

    def guardar(self):
        os.makedirs(self.destino, exist_ok=True)
        json.dump(self.d, open(self.f, "w"), ensure_ascii=False, indent=0)
