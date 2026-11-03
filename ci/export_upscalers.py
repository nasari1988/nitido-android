"""Converts community ESRGAN upscalers (.pth) to ONNX with a fixed 192x192 input tile.

usage: python export_upscalers.py <out_dir>
Writes <out_dir>/<name>.onnx for every model it could download and load. Failures are
reported and skipped, so the app still builds without them.
"""
import os
import sys
import urllib.request

import torch

MODELS = {
    # name: (url, description)
    "up_ultrasharp": "https://huggingface.co/lokCX/4x-Ultrasharp/resolve/main/4x-UltraSharp.pth",
    "up_siax": "https://huggingface.co/gemasai/4x_NMKD-Siax_200k/resolve/main/4x_NMKD-Siax_200k.pth",
    "up_anime": "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.2.4/RealESRGAN_x4plus_anime_6B.pth",
}
TILE = 192


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        return
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "nitido-build"})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest + ".part", "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(dest + ".part", dest)
            return
        except Exception as e:  # noqa: BLE001
            print(f"  intento {attempt + 1} falló: {e}")
            import time
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"no se pudo descargar {url}")


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    import spandrel

    ok = 0
    for name, url in MODELS.items():
        print(f"== {name}")
        try:
            pth = os.path.join(out, name + ".pth")
            download(url, pth)
            desc = spandrel.ModelLoader().load_from_file(pth)
            print(f"  arquitectura {desc.architecture.name}, escala x{desc.scale}")
            model = desc.model.eval().float()
            x = torch.rand(1, 3, TILE, TILE)
            with torch.no_grad():
                y = model(x)
            print(f"  salida {tuple(y.shape)}")
            torch.onnx.export(
                model, (x,), os.path.join(out, name + ".onnx"),
                input_names=["input"], output_names=["output"], opset_version=17,
            )
            os.remove(pth)
            ok += 1
        except Exception as e:  # noqa: BLE001
            print(f"::warning::No se pudo preparar {name}: {e}")
    print(f"{ok} de {len(MODELS)} upscalers listos")


if __name__ == "__main__":
    main()
