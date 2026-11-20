"""
Probar el modelo con fotos nuevas y exportarlo para el teléfono (ONNX), revisado contra PyTorch.
Solo el modelo sale de la PC; las fotos no.
"""
import json
import os
import time

import numpy as np
import torch
import torch.nn.functional as F
from PIL import Image

from . import datos
from .entrenar import a_imagen, a_tensor
from .modelos import LUT, MiEstilo, MiPiel, TablaParaTelefono

PIEZA = 512


def cargar(proyecto, modelo, cual="mejor"):
    s = torch.load(os.path.join(proyecto, f"{modelo}_{cual}.pt"), map_location="cpu", weights_only=True)
    red = MiEstilo() if modelo == "estilo" else MiPiel()
    red.load_state_dict(s["red"]); red.eval()
    return red, s


@torch.no_grad()
def piel_en_piezas(red, im, mascara, dev="cpu", intensidad=1.0):
    """Toda la piel de [im], en piezas de 512 px que se enciman y se mezclan suave (sin cortes)."""
    x = a_tensor(im)[None]
    H, W = x.shape[-2:]
    paso = PIEZA * 3 // 4
    out = torch.zeros_like(x); peso = torch.zeros(1, 1, H, W)
    v = torch.hann_window(PIEZA, periodic=False).clamp_min(0.05)
    ventana = (v[:, None] * v[None, :])[None, None]
    m = torch.from_numpy(mascara)[None, None]
    ys = list(range(0, max(1, H - PIEZA) + 1, paso)); xs = list(range(0, max(1, W - PIEZA) + 1, paso))
    if ys[-1] != max(0, H - PIEZA): ys.append(max(0, H - PIEZA))
    if xs[-1] != max(0, W - PIEZA): xs.append(max(0, W - PIEZA))
    for y in ys:
        for x0 in xs:
            if float(m[..., y:y + PIEZA, x0:x0 + PIEZA].mean()) < 0.02:
                continue
            p = x[..., y:y + PIEZA, x0:x0 + PIEZA]
            ph, pw = p.shape[-2:]
            if (ph, pw) != (PIEZA, PIEZA):
                p = F.pad(p, (0, PIEZA - pw, 0, PIEZA - ph), mode="replicate")
            r = red(p.to(dev)).cpu()[..., :ph, :pw]
            win = ventana[..., :ph, :pw]
            out[..., y:y + ph, x0:x0 + pw] += r * win
            peso[..., y:y + ph, x0:x0 + pw] += win
    hecho = peso > 0
    res = torch.where(hecho, out / peso.clamp_min(1e-6), x)
    k = (m * intensidad).clamp(0, 1)
    return a_imagen((x * (1 - k) + res * k)[0])


@torch.no_grad()
def probar(proyecto, modelo, carpeta, salida, lado=2048):
    red, s = cargar(proyecto, modelo)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    red.to(dev)
    piel = datos.Piel() if modelo == "piel" else None
    os.makedirs(salida, exist_ok=True)
    hechos = []
    for nombre, ruta in datos.listar(carpeta).items():
        im = datos.abrir(ruta, lado)
        t0 = time.time()
        if modelo == "estilo":
            x = a_tensor(im)[None].to(dev)
            mini = F.interpolate(x, size=(256, 256), mode="bilinear", align_corners=False)
            r = a_imagen(red(x, mini)[0])
        else:
            r = piel_en_piezas(red, im, piel.mascara(im), dev)
        ms = int((time.time() - t0) * 1000)
        lado_a_lado = Image.new("RGB", (im.width * 2, im.height))
        lado_a_lado.paste(im, (0, 0)); lado_a_lado.paste(r, (im.width, 0))
        f = os.path.join(salida, f"{nombre}.jpg")
        lado_a_lado.save(f, quality=90)
        hechos.append({"nombre": nombre, "archivo": f, "ms": ms})
    return hechos


def exportar(proyecto, modelo):
    """-> carpeta exportado/<modelo>_vN con el .onnx, info.json y (estilo) la tabla .cube de ejemplo."""
    import onnx
    red, s = cargar(proyecto, modelo)
    base = os.path.join(proyecto, "exportado")
    os.makedirs(base, exist_ok=True)
    n = 1 + sum(1 for d in os.listdir(base) if d.startswith(modelo + "_v"))
    dst = os.path.join(base, f"{modelo}_v{n}")
    os.makedirs(dst)
    if modelo == "estilo":
        m = TablaParaTelefono(red).eval()
        ent = torch.rand(1, 3, 256, 256)
        nombre, ins, outs = "mi_estilo.onnx", ["mini"], ["tabla"]
    else:
        m = red
        ent = torch.rand(1, 3, PIEZA, PIEZA)
        nombre, ins, outs = "mi_piel.onnx", ["pieza"], ["retocada"]
    f = os.path.join(dst, nombre)
    try:   # the classic exporter (simple graph for the NPU); newer PyTorch asks for it by name
        torch.onnx.export(m, (ent,), f, input_names=ins, output_names=outs, opset_version=17, do_constant_folding=True, dynamo=False)
    except TypeError:
        torch.onnx.export(m, (ent,), f, input_names=ins, output_names=outs, opset_version=17, do_constant_folding=True)
    onnx.checker.check_model(onnx.load(f))
    # revisión: el ONNX da lo mismo que PyTorch
    diff = None
    try:
        import onnxruntime as ort
        y = ort.InferenceSession(f, providers=["CPUExecutionProvider"]).run(None, {ins[0]: ent.numpy()})[0]
        with torch.no_grad():
            diff = float(np.abs(y - m(ent).numpy()).max())
    except Exception as e:
        print("Aviso: no pude revisar con onnxruntime:", e)
    info = {
        "tipo": "mi_estilo" if modelo == "estilo" else "mi_piel",
        "version": n, "vuelta": s.get("vuelta"), "parecido": s.get("parecido"),
        "archivo": nombre, "entrada": ins[0], "salida": outs[0],
        "diferencia_onnx": diff, "fecha": time.strftime("%Y-%m-%d %H:%M"),
    }
    if modelo == "estilo":
        info.update({"mini": [256, 256], "tabla": [3, LUT, LUT, LUT],
                     "orden_tabla": "tabla[canal R,G,B][azul][verde][rojo], valores 0..1, interpolación trilineal"})
        with torch.no_grad():
            t = red.tabla(torch.full((1, 3, 256, 256), 0.5))[0].numpy()
        with open(os.path.join(dst, "mi_estilo_ejemplo.cube"), "w") as c:
            c.write(f"TITLE \"Mi estilo v{n}\"\nLUT_3D_SIZE {LUT}\n")
            for b in range(LUT):
                for g in range(LUT):
                    for r in range(LUT):
                        c.write("%.6f %.6f %.6f\n" % tuple(np.clip(t[:, b, g, r], 0, 1)))
    else:
        info.update({"pieza": [PIEZA, PIEZA], "valores": "0..1 RGB", "uso": "piezas de 512 px sobre la piel, encimadas y mezcladas"})
    json.dump(info, open(os.path.join(dst, "info.json"), "w"), ensure_ascii=False, indent=1)
    tam = os.path.getsize(f) + sum(os.path.getsize(os.path.join(dst, x)) for x in os.listdir(dst) if x.endswith(".data"))
    info["mb"] = round(tam / 1e6, 1)
    info["carpeta"] = dst
    return info
