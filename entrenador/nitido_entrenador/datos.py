"""
Tus pares (foto antes / tu edición), su revisión, los aumentos y los recortes de piel.
Todo se queda en tu PC.
"""
import io
import json
import math
import os
import random
from functools import lru_cache

import numpy as np
from PIL import Image, ImageFilter, ImageOps

EXT = {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff", ".bmp"}
AQUI = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def abrir(ruta, lado=None):
    """La foto girada como se ve (EXIF), en RGB; con [lado], el lado largo a ese tamaño como máximo."""
    im = ImageOps.exif_transpose(Image.open(ruta)).convert("RGB")
    if lado and max(im.size) > lado:
        k = lado / max(im.size)
        im = im.resize((max(1, round(im.width * k)), max(1, round(im.height * k))), Image.LANCZOS)
    return im


def listar(carpeta):
    """{nombre sin extensión: ruta} de las fotos de una carpeta (sin subcarpetas)."""
    out = {}
    for n in sorted(os.listdir(carpeta)):
        base, ext = os.path.splitext(n)
        if ext.lower() in EXT:
            out[base] = os.path.join(carpeta, n)
    return out


def _gris(im, n=64):
    a = np.asarray(im.convert("L").resize((n, n), Image.BILINEAR), dtype=np.float32)
    a = a - a.mean()
    return a / (np.linalg.norm(a) + 1e-6)


def emparejar(antes, despues):
    """Los pares por nombre de archivo, con avisos: sin pareja, tamaño distinto, desalineado."""
    a, d = listar(antes), listar(despues)
    pares, sin = [], sorted(set(a) ^ set(d))
    for nombre in sorted(set(a) & set(d)):
        p = {"nombre": nombre, "antes": a[nombre], "despues": d[nombre], "avisos": [], "usar": True}
        try:
            ia, idd = abrir(a[nombre], 512), abrir(d[nombre], 512)
            wa, ha = Image.open(a[nombre]).size
            wd, hd = Image.open(d[nombre]).size
            p["tam"] = [wa, ha]
            if abs(wa / ha - wd / hd) > 0.01:
                p["avisos"].append("forma distinta (¿recortaste?)")
                p["usar"] = False
            elif (wa, ha) != (wd, hd):
                p["avisos"].append("tamaño distinto: se iguala solo")
            parecido = float((_gris(ia) * _gris(idd.resize(ia.size))).sum())
            p["alineado"] = round(parecido, 3)
            if parecido < 0.6:
                p["avisos"].append("parece desalineado")
                p["usar"] = False
        except Exception as e:
            p["avisos"].append(f"no se pudo abrir: {e}")
            p["usar"] = False
        pares.append(p)
    return pares, sin


# ---------------- aumentos ----------------
AUMENTOS_BASE = {
    "voltear": True,            # a los dos lados igual
    "recorte_min": 0.6,          # recortes al azar: de 60 % a 100 % de la foto
    "girar": 4.0,                # ± grados
    "zoom": [0.8, 1.2],
    "exposicion": 0.7,           # solo en el «antes»: ± pasos
    "temperatura": 900,          # ± kelvin aproximados
    "contraste": 0.2,            # ± 20 %
    "saturacion": 0.2,
    "ruido": True,
    "jpeg": False,
    "variantes": 20,             # cuántas versiones de cada par por vuelta
}


def _ganancias_temperatura(k):
    """Ganancias R, G, B para mover el balance de blancos ~k kelvin (k>0 más cálido)."""
    t = k / 1000.0
    return np.array([1 + 0.06 * t, 1.0, 1 - 0.08 * t], dtype=np.float32)


def aumentar(a, d, cfg, lado, rnd=random):
    """
    Un par aumentado de [lado]×[lado] px (PIL, mismos tamaños): el encuadre cambia igual en los dos;
    color, luz, ruido y compresión solo en el «antes». Tu «después» nunca cambia de color.
    """
    w, h = a.size
    # mismo recorte, giro y zoom a los dos lados
    ang = rnd.uniform(-cfg["girar"], cfg["girar"]) if cfg["girar"] else 0.0
    if ang:
        a = a.rotate(ang, Image.BICUBIC, expand=False)
        d = d.rotate(ang, Image.BICUBIC, expand=False)
        # quita las esquinas vacías del giro
        m = math.sin(math.radians(abs(ang)))
        mx, my = int(h * m) + 2, int(w * m) + 2
        a = a.crop((mx, my, w - mx, h - my)); d = d.crop((mx, my, w - mx, h - my))
        w, h = a.size
    frac = rnd.uniform(cfg["recorte_min"], 1.0) / rnd.uniform(*cfg["zoom"])
    lado_rec = max(16, min(w, h, int(min(w, h) * min(1.0, frac))))
    x0 = rnd.randint(0, w - lado_rec); y0 = rnd.randint(0, h - lado_rec)
    box = (x0, y0, x0 + lado_rec, y0 + lado_rec)
    a = a.crop(box).resize((lado, lado), Image.BICUBIC)
    d = d.crop(box).resize((lado, lado), Image.BICUBIC)
    if cfg["voltear"] and rnd.random() < 0.5:
        a = a.transpose(Image.FLIP_LEFT_RIGHT); d = d.transpose(Image.FLIP_LEFT_RIGHT)
    # solo el «antes»
    x = np.asarray(a, dtype=np.float32) / 255.0
    if cfg["exposicion"]:
        x = x * (2.0 ** rnd.uniform(-cfg["exposicion"], cfg["exposicion"]))
    if cfg["temperatura"]:
        x = x * _ganancias_temperatura(rnd.uniform(-cfg["temperatura"], cfg["temperatura"]))
    if cfg["contraste"]:
        c = 1 + rnd.uniform(-cfg["contraste"], cfg["contraste"])
        x = (x - x.mean()) * c + x.mean()
    if cfg["saturacion"]:
        s = 1 + rnd.uniform(-cfg["saturacion"], cfg["saturacion"])
        g = x.mean(axis=2, keepdims=True)
        x = g + (x - g) * s
    if cfg["ruido"] and rnd.random() < 0.5:
        x = x + np.random.normal(0, rnd.uniform(0.003, 0.02), x.shape).astype(np.float32)
    x = np.clip(x, 0, 1)
    a = Image.fromarray((x * 255 + 0.5).astype(np.uint8))
    if cfg["jpeg"] and rnd.random() < 0.5:
        buf = io.BytesIO(); a.save(buf, "JPEG", quality=rnd.randint(55, 90)); a = Image.open(io.BytesIO(buf.getvalue())).convert("RGB")
    return a, d


# ---------------- piel ----------------
PIEL_CUERPO, PIEL_CARA = 2, 3      # clases del modelo de MediaPipe (0 fondo, 1 pelo, 2 cuerpo, 3 cara, 4 ropa, 5 otros)


class Piel:
    """Encuentra la piel (cara y cuerpo) con MediaPipe; si no está, con el color (menos preciso)."""

    def __init__(self):
        self.seg = None
        ruta = os.path.join(AQUI, "modelos_base", "selfie_multiclass.tflite")
        try:
            import mediapipe as mp
            from mediapipe.tasks.python import BaseOptions
            from mediapipe.tasks.python.vision import ImageSegmenter, ImageSegmenterOptions
            self.mp = mp
            self.seg = ImageSegmenter.create_from_options(ImageSegmenterOptions(
                base_options=BaseOptions(model_asset_path=ruta), output_category_mask=True))
        except Exception as e:
            print("Aviso: no pude abrir MediaPipe; uso el color de piel (menos preciso):", e)

    def mascara(self, im, zona="toda"):
        """0..1 por pixel: piel de [zona] («toda», «cara», «cuerpo»)."""
        if self.seg is not None:
            r = self.seg.segment(self.mp.Image(image_format=self.mp.ImageFormat.SRGB, data=np.asarray(im)))
            c = r.category_mask.numpy_view()
            if c.ndim == 3:
                c = c[..., 0]
            quiero = {"toda": (PIEL_CUERPO, PIEL_CARA), "cara": (PIEL_CARA,), "cuerpo": (PIEL_CUERPO,)}[zona]
            m = np.isin(c, quiero).astype(np.uint8) * 255
            mi = Image.fromarray(m).resize(im.size, Image.BILINEAR)
        else:
            ycc = np.asarray(im.convert("YCbCr"), dtype=np.int16)
            cb, cr = ycc[..., 1], ycc[..., 2]
            m = ((cb > 77) & (cb < 127) & (cr > 133) & (cr < 173)).astype(np.uint8) * 255
            mi = Image.fromarray(m)
        return np.asarray(mi.filter(ImageFilter.GaussianBlur(2)), dtype=np.float32) / 255.0


def recortes_piel(pares, cache, lado=512, piel=None, max_lado=2048, minimo=0.25):
    """
    Dónde hay piel en cada par: cuadros de [lado] px (la foto a [max_lado] como máximo) con al menos
    [minimo] de piel. Se guarda en [cache] para no repetirlo. -> [(i_par, x, y)]
    """
    os.makedirs(cache, exist_ok=True)
    f = os.path.join(cache, f"recortes_{lado}_{max_lado}.json")
    viejo = json.load(open(f)) if os.path.exists(f) else {}
    piel = piel or Piel()
    out = []
    for i, p in enumerate(pares):
        k = p["nombre"]
        if k not in viejo:
            im = abrir(p["antes"], max_lado)
            m = piel.mascara(im)
            Image.fromarray((m * 255).astype(np.uint8)).save(os.path.join(cache, f"piel_{k}.png"))
            cajas = []
            paso = lado // 2
            for y in range(0, max(1, im.height - lado + 1), paso):
                for x in range(0, max(1, im.width - lado + 1), paso):
                    if m[y:y + lado, x:x + lado].mean() >= minimo:
                        cajas.append([x, y])
            viejo[k] = cajas
            json.dump(viejo, open(f, "w"))
        out += [(i, x, y) for x, y in viejo[k]]
    return out


@lru_cache(maxsize=48)
def cargado(ruta, max_lado):
    return abrir(ruta, max_lado)
