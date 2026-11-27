"""
Versión NPU de un modelo exportado, en esta PC, igual que la hace el GitHub de Nítido:
ONNX -> DLC (qairt-converter) -> W8A16 calibrado con TUS fotos (qairt-quantizer)
     -> binario de contexto HTP para el teléfono (qnn-context-binary-generator)
     -> envoltura EPContext ONNX que carga ONNX Runtime con QNN.
El kit de Qualcomm se instala una vez con:  bash instalar_npu.sh
"""
import glob
import json
import os
import random
import subprocess
import sys
import time

import numpy as np

from . import datos

AQUI = datos.AQUI
KIT = os.path.join(AQUI, "qairt")
VENVQ = os.path.join(AQUI, ".venvq")
QV = "2.42.0.251225"          # la misma versión que el runtime de la app (ONNX Runtime 1.29 / QNN 2.42)
DSP_ARCH = "v73"              # la compilación «8 Gen 2» (Hexagon v73) que usa la app en el 8s Gen 4
SOC_ID = 43
N_CALIB = 24


class FaltaKit(Exception):
    pass


def kit():
    """Rutas y entorno del kit de Qualcomm, o FaltaKit con lo que falta."""
    raiz = sorted(glob.glob(os.path.join(KIT, "qairt", "*")))
    if not raiz:
        raise FaltaKit("Falta el kit de Qualcomm. Instálalo una vez con:  bash instalar_npu.sh")
    raiz = raiz[-1]
    py = os.path.join(VENVQ, "bin", "python")
    if not os.path.exists(py):
        raise FaltaKit("Falta el Python 3.10 del kit. Corre otra vez:  bash instalar_npu.sh")
    env = dict(os.environ)
    env["QNN_SDK_ROOT"] = raiz
    env["PYTHONPATH"] = os.path.join(raiz, "lib", "python")
    lib = os.path.join(raiz, "lib", "x86_64-linux-clang")
    env["LD_LIBRARY_PATH"] = ":".join(x for x in (lib, "/usr/lib/llvm-18/lib", "/usr/local/lib", env.get("LD_LIBRARY_PATH", "")) if x)
    return {"raiz": raiz, "bin": os.path.join(raiz, "bin", "x86_64-linux-clang"), "lib": lib, "py": py, "env": env}


def listo():
    try:
        kit()
        return True
    except FaltaKit:
        return False


# ---------------------------------------------------------------- calibración con tus fotos
def calibracion(proyecto, modelo, carpeta, n=N_CALIB, rnd=None):
    """Entradas reales para que el cuantizador elija bien sus rangos: tus fotos «antes», preparadas
    igual que en el teléfono. -> ruta de list.txt"""
    rnd = rnd or random.Random(7)
    P = json.load(open(os.path.join(proyecto, "proyecto.json")))
    pares = [p for p in P["pares"] if p.get("usar", True)]
    if not pares:
        raise RuntimeError("No hay pares para calibrar")
    os.makedirs(carpeta, exist_ok=True)
    entradas = []
    if modelo == "estilo":
        for p in rnd.sample(pares, min(n, len(pares))):
            im = datos.abrir(p["antes"], 1024).resize((256, 256), datos.Image.BILINEAR)   # «mini» 1×3×256×256
            entradas.append(np.asarray(im, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0)
    else:
        from .exportar import PIEZA
        cajas = []
        try:
            cajas = datos.recortes_piel(pares, os.path.join(proyecto, "cache"), PIEZA, None, 2048)
        except Exception as e:  # noqa: BLE001
            print("Aviso: sin cajas de piel, uso el centro de las fotos:", e)
        if cajas:
            elegidas = rnd.sample(cajas, min(n, len(cajas)))
        else:
            elegidas = [(i, None, None) for i in rnd.sample(range(len(pares)), min(n, len(pares)))]
        for i, x, y in elegidas:
            im = datos.abrir(pares[i]["antes"], 2048)
            if x is None:
                x, y = max(0, (im.width - PIEZA) // 2), max(0, (im.height - PIEZA) // 2)
            pieza = im.crop((x, y, x + PIEZA, y + PIEZA))          # «pieza» 1×3×512×512
            entradas.append(np.asarray(pieza, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0)
    lineas = []
    for k, x in enumerate(entradas):
        f = os.path.join(carpeta, f"{k:02d}.raw")
        x.astype(np.float32).tofile(f)
        lineas.append(f)
    lst = os.path.join(carpeta, "list.txt")
    open(lst, "w").write("\n".join(lineas) + "\n")
    return lst


# ---------------------------------------------------------------- la conversión
def _correr(cmd, env, log, avance, paso, cwd=None):
    if avance:
        avance(paso)
    log.write(f"\n$ {' '.join(cmd)}\n"); log.flush()
    r = subprocess.run(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, cwd=cwd or os.path.dirname(os.path.abspath(log.name)))   # los kits a veces escriben junto a donde corren
    return r.returncode == 0


def crear(proyecto, modelo, carpeta_exp=None, avance=None):
    """Crea la versión NPU de la última exportación (o de [carpeta_exp]).
    -> {"carpeta", "bin", "ctx", "mb", "segundos"}"""
    K = kit()
    if carpeta_exp is None:
        vs = sorted(glob.glob(os.path.join(proyecto, "exportado", f"{modelo}_v*")), key=lambda d: int(d.rsplit("_v", 1)[1]))
        if not vs:
            raise RuntimeError("Primero exporta el modelo.")
        carpeta_exp = vs[-1]
    info_f = os.path.join(carpeta_exp, "info.json")
    info = json.load(open(info_f))
    N = "mi_estilo" if modelo == "estilo" else "mi_piel"
    onnx_f = os.path.join(carpeta_exp, info["archivo"])
    trabajo = os.path.join(carpeta_exp, "npu_trabajo")
    os.makedirs(trabajo, exist_ok=True)
    t0 = time.time()
    with open(os.path.join(carpeta_exp, "npu.log"), "w") as log:
        if avance:
            avance("Preparando las fotos de calibración…")
        lst = calibracion(proyecto, modelo, os.path.join(trabajo, "calib"))
        cfg = os.path.join(trabajo, f"htp_config_{N}.json")
        ext = os.path.join(trabajo, f"htp_ext_{N}.json")

        def config(o):
            json.dump({"graphs": [{"graph_names": [N], "O": o, "vtcm_mb": 0}],
                       "devices": [{"dsp_arch": DSP_ARCH, "cores": [{"core_id": 0, "perf_profile": "burst", "rpc_control_latency": 100}]}]},
                      open(cfg, "w"))
        config(3)
        json.dump({"backend_extensions": {"shared_library_path": os.path.join(K["lib"], "libQnnHtpNetRunExtensions.so"),
                                          "config_file_path": cfg}}, open(ext, "w"))
        dlc_f, dlc_q = os.path.join(trabajo, f"{N}_f.dlc"), os.path.join(trabajo, f"{N}.dlc")
        B, env, py = K["bin"], K["env"], K["py"]
        if not _correr([py, os.path.join(B, "qairt-converter"), "--input_network", onnx_f, "--output_path", dlc_f],
                       env, log, avance, "1/4 Convirtiendo el modelo…"):
            raise RuntimeError("Falló qairt-converter (mira npu.log)")
        if not _correr([py, os.path.join(B, "qairt-quantizer"), "--input_dlc", dlc_f, "--input_list", lst, "--output_dlc", dlc_q,
                        "--act_bitwidth", "16", "--weights_bitwidth", "8", "--bias_bitwidth", "32", "--use_per_channel_quantization"],
                       env, log, avance, "2/4 Cuantizando con tus fotos (W8A16)…"):
            raise RuntimeError("Falló qairt-quantizer (mira npu.log)")
        gen = [os.path.join(B, "qnn-context-binary-generator"), "--backend", os.path.join(K["lib"], "libQnnHtp.so"),
               "--model", os.path.join(K["lib"], "libQnnModelDlc.so"), "--dlc_path", dlc_q, "--binary_file", N,
               "--output_dir", carpeta_exp, "--config_file", ext]
        if not _correr(gen, env, log, avance, "3/4 Compilando para la NPU del teléfono…"):
            config(2)
            if not _correr(gen, env, log, avance, "3/4 Compilando otra vez (optimización 2)…"):
                raise RuntimeError("Falló qnn-context-binary-generator (mira npu.log)")
        bin_f = os.path.join(carpeta_exp, f"{N}.bin")
        ctx_json = os.path.join(trabajo, f"ctx_{N}.json")
        if not _correr([os.path.join(B, "qnn-context-binary-utility"), "--context_binary", bin_f, "--json_file", ctx_json],
                       env, log, avance, "4/4 Envolviendo para ONNX Runtime…"):
            raise RuntimeError("Falló qnn-context-binary-utility (mira npu.log)")
        ctx_f = os.path.join(carpeta_exp, f"{N}_ctx.onnx")
        # la envoltura usa el Python del entrenador (tiene onnx)
        if not _correr([sys.executable, "-m", "nitido_entrenador.npu_epctx", ctx_json, ctx_f, f"{N}.bin", QV],
                       dict(os.environ, PYTHONPATH=AQUI), log, None, ""):
            raise RuntimeError("Falló la envoltura EPContext (mira npu.log)")
    # limpieza: los DLC y la calibración ya no hacen falta
    for f in glob.glob(os.path.join(trabajo, "**", "*"), recursive=True):
        if os.path.isfile(f) and not f.endswith(".json"):
            os.remove(f)
    info.update({"npu": 1, "npu_precision": "w8a16", "npu_qnn": QV, "npu_dsp_arch": DSP_ARCH, "npu_soc_id": SOC_ID,
                 "npu_format": "ctx", "npu_bin": f"{N}.bin", "npu_ctx": f"{N}_ctx.onnx", "npu_calibracion": "tus fotos"})
    json.dump(info, open(info_f, "w"), ensure_ascii=False, indent=1)
    seg = int(time.time() - t0)
    return {"carpeta": carpeta_exp, "bin": bin_f, "ctx": ctx_f, "mb": round(os.path.getsize(bin_f) / 1e6, 1), "segundos": seg}
