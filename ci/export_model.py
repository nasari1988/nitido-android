"""Export a Stable Diffusion model to ONNX pieces the app's MNN engine understands.

usage: export_model.py <model_id> <onnx_dir> [model link] [lora link] [lora weight]

Supports the SD 1.5 family (incl. InstructPix2Pix) and SDXL. The architecture is detected.
With a link, any compatible model can be converted: a Hugging Face repo page
(huggingface.co/owner/name) or a single .safetensors/.ckpt file. A LoRA (Hugging Face file or
repo, or any direct download link) can be merged ("baked") into the model at a given weight.

Pieces (image size free, multiples of 64):
  SD 1.5:
    text_encoder  input_ids [2,77] int32 -> last_hidden_state [2,77,768], pooler_output
    unet          sample [2,4,h,w] (pix2pix: [3,8,h,w]), timestep [1], encoder_hidden_states -> out_sample
  SDXL:
    text_encoder  input_ids [2,77] int32 -> prompt_embeds [2,77,2048], pooled [2,1280]
    unet          sample [2,4,h,w], timestep [1], encoder_hidden_states [2,77,2048],
                  text_embeds [2,1280], time_ids [2,6] -> out_sample
  vae_decoder     latent_sample [1,4,h,w] -> sample [1,3,8h,8w]
  vae_encoder     sample [1,3,H,W] -> latent (mean, unscaled)
plus the CLIP tokenizer files (vocab.json, merges.txt) and info.txt.
"""
import gc
import json
import math
import os
import sys
import urllib.error
import urllib.request

import torch
import torch.nn.functional as F
import safetensors.torch  # noqa: F401  (diffusers expects this submodule loaded)
from diffusers import (AutoencoderKL, StableDiffusionInstructPix2PixPipeline,
                       StableDiffusionPipeline, StableDiffusionXLPipeline)
from huggingface_hub import hf_hub_download

SD15 = "stable-diffusion-v1-5/stable-diffusion-v1-5"
SDXL = "stabilityai/stable-diffusion-xl-base-1.0"
MODELS = {
    "dreamshaper8": dict(repo="Lykon/dreamshaper-8"),
    "realistic": dict(repo="SG161222/Realistic_Vision_V5.1_noVAE", vae="stabilityai/sd-vae-ft-mse"),
    "anime": dict(single="https://huggingface.co/Lykon/AnyLoRA/blob/main/AAM_Anylora_AnimeMix.safetensors"),
    "pix2pix": dict(repo="timbrooks/instruct-pix2pix", pix2pix=True),
}


# torch's ONNX exporter can't translate scaled_dot_product_attention when a float
# `scale` is passed (as transformers' CLIP does). Swap in the plain math version,
# which exports cleanly and is numerically the same.
def _sdpa(query, key, value, attn_mask=None, dropout_p=0.0, is_causal=False, scale=None, **_):
    s = scale if scale is not None else 1.0 / math.sqrt(query.size(-1))
    w = torch.matmul(query, key.transpose(-2, -1)) * s
    if is_causal:
        L, S = query.size(-2), key.size(-2)
        causal = torch.ones(L, S, dtype=torch.bool, device=query.device).tril()
        w = w.masked_fill(~causal, float("-inf"))
    if attn_mask is not None:
        if attn_mask.dtype == torch.bool:
            w = w.masked_fill(~attn_mask, float("-inf"))
        else:
            w = w + attn_mask
    return torch.matmul(torch.softmax(w, dim=-1), value)


F.scaled_dot_product_attention = _sdpa
torch.nn.functional.scaled_dot_product_attention = _sdpa

model_id, out = sys.argv[1], sys.argv[2]
link = sys.argv[3].strip() if len(sys.argv) > 3 else ""
lora = sys.argv[4].strip() if len(sys.argv) > 4 else ""
lora_w = float(sys.argv[5]) if len(sys.argv) > 5 and sys.argv[5].strip() else 0.8
os.makedirs(out, exist_ok=True)
f32 = torch.float32


def with_retries(fn, what, tries=6):
    """Hugging Face sometimes answers 429 (too many requests) to anonymous downloads: wait and retry."""
    import time
    for i in range(tries):
        try:
            return fn()
        except Exception as e:  # noqa: BLE001
            msg = str(e)
            if i == tries - 1 or not any(k in msg for k in ("429", "Too Many Requests", "Connection", "Timeout", "502", "503")):
                raise
            wait = 60 * (i + 1)
            print(f"{what}: Hugging Face está ocupado ({msg[:80]}…). Reintento en {wait} s")
            time.sleep(wait)


def hf_parts(url):
    """(repo, filename or None) from a Hugging Face URL."""
    path = url.split("huggingface.co/")[-1]
    p = path.split("/")
    repo = "/".join(p[:2])
    if len(p) > 4 and p[2] in ("blob", "resolve"):
        return repo, "/".join(p[4:])
    return repo, None


CIVITAI_TOKEN = os.environ.get("CIVITAI_TOKEN", "").strip()


def civitai_resolve(url):
    """Turn any Civitai link (model page, version page or download link) into a download URL.
    Returns (download_url, base_model or None, file_name or None)."""
    import re
    m = re.search(r"/api/download/models/(\d+)", url)
    if m:
        return url, None, None
    mid = re.search(r"/models/(\d+)", url)
    vid = re.search(r"modelVersionId=(\d+)", url)
    if not mid:
        sys.exit("No entiendo este enlace de Civitai. Usa el de la página del modelo (civitai.com/models/…).")
    req = urllib.request.Request(f"https://civitai.com/api/v1/models/{mid.group(1)}",
                                 headers={"User-Agent": "Mozilla/5.0", **civitai_auth()})
    info = json.load(with_retries(lambda: urllib.request.urlopen(req), "Civitai"))
    versions = info.get("modelVersions") or []
    if not versions:
        sys.exit("Civitai no mostró versiones descargables de este modelo.")
    ver = next((v for v in versions if vid and str(v.get("id")) == vid.group(1)), versions[0])
    files = ver.get("files") or []
    pick = next((f for f in files if f.get("primary")), None) or next(
        (f for f in files if str(f.get("name", "")).endswith(".safetensors")), None) or (files[0] if files else None)
    if not pick:
        sys.exit("Esta versión de Civitai no tiene archivos para descargar.")
    print(f"Civitai: {info.get('name')} · versión {ver.get('name')} · base {ver.get('baseModel')} · {pick.get('name')}")
    return pick.get("downloadUrl") or f"https://civitai.com/api/download/models/{ver['id']}", ver.get("baseModel"), pick.get("name")


def civitai_auth():
    return {"Authorization": f"Bearer {CIVITAI_TOKEN}"} if CIVITAI_TOKEN else {}


def check_base(base, what):
    """Civitai tells the base model; stop early with a clear message if it can't work."""
    if not base:
        return
    b = base.lower()
    if any(x in b for x in ("flux", "sd 3", "sd3", "hunyuan", "wan", "ltx", "kolors", "aura", "cascade", "pixart", "sd 2")):
        sys.exit(f"{what} es para '{base}', que la app no puede usar. Solo SD 1.5 y SDXL (incluye Pony e Illustrious).")


def fetch_file(url, dest_dir):
    """Download a single file (Hugging Face, Civitai or any direct link) and return its local path."""
    url = url.split("?")[0] if "huggingface.co" in url else url
    if "huggingface.co" in url:
        repo, fname = hf_parts(url)
        return with_retries(lambda: hf_hub_download(repo, fname), fname)
    headers = {"User-Agent": "Mozilla/5.0"}
    if "civitai." in url:
        url, base, _ = civitai_resolve(url)
        check_base(base, "Este archivo de Civitai")
        # Civitai redirects downloads to a signed storage link that rejects extra login headers,
        # so the key goes in the address instead.
        if CIVITAI_TOKEN:
            url += ("&" if "?" in url else "?") + "token=" + CIVITAI_TOKEN
    os.makedirs(dest_dir, exist_ok=True)
    path = os.path.join(dest_dir, "download.safetensors")
    req = urllib.request.Request(url, headers=headers)
    try:
        resp = with_retries(lambda: urllib.request.urlopen(req), "descarga")
    except urllib.error.HTTPError as e:
        if "civitai." in url and e.code in (400, 401, 403):
            sys.exit("Civitai pide iniciar sesión para descargar esto. Agrega tu llave de Civitai en GitHub "
                     "(Settings → Secrets and variables → Actions → New repository secret, nombre CIVITAI_TOKEN).")
        raise
    with resp as r, open(path, "wb") as fh:
        while True:
            chunk = r.read(1 << 22)
            if not chunk:
                break
            fh.write(chunk)
    return path


# ---------- what to convert ----------
if link:
    url = link.split("?")[0].rstrip("/")
    if "civitai." in link:
        cfg = dict(single=link.strip())   # resolved and downloaded by fetch_file
    elif url.endswith((".safetensors", ".ckpt")) or "huggingface.co" not in url:
        cfg = dict(single=url)
    else:
        cfg = dict(repo=hf_parts(url)[0])
    # "noVAE" models ship without their color decoder; use the standard improved one
    if "novae" in url.lower():
        cfg["vae"] = "stabilityai/sd-vae-ft-mse"
else:
    cfg = dict(MODELS[model_id[:-3] if model_id.endswith("-16") else model_id])


def detect_arch():
    if cfg.get("single"):
        cfg["local"] = fetch_file(cfg["single"], "dl_model")
        if cfg["local"].endswith(".safetensors"):
            from safetensors import safe_open
            with safe_open(cfg["local"], "pt") as fh:
                keys = list(fh.keys())
                k0 = "model.diffusion_model.input_blocks.0.0.weight"
                if k0 in keys and fh.get_slice(k0).get_shape()[1] == 9:
                    cfg["single_inpaint"] = True   # needs the inpainting layout to load
            if any(k.startswith("conditioner.embedders.1") for k in keys):
                return "sdxl"
        return "sd15"
    try:
        idx = json.load(open(with_retries(lambda: hf_hub_download(cfg["repo"], "model_index.json"), "model_index.json")))
        if "XL" in idx.get("_class_name", ""):
            return "sdxl"
    except Exception as e:
        print("model_index.json not readable:", e)
    return "sd15"


arch = detect_arch()
print("Arquitectura:", arch)


def load():
    if arch == "sdxl":
        if cfg.get("single"):
            conf = "diffusers/stable-diffusion-xl-1.0-inpainting-0.1" if cfg.get("single_inpaint") else SDXL
            return StableDiffusionXLPipeline.from_single_file(cfg["local"], config=conf, torch_dtype=f32)
        try:
            return StableDiffusionXLPipeline.from_pretrained(cfg["repo"], torch_dtype=f32)
        except Exception as e:
            print("retrying with fp16 variant:", e)
            return StableDiffusionXLPipeline.from_pretrained(cfg["repo"], variant="fp16", torch_dtype=f32)
    if cfg.get("single"):
        conf = "stable-diffusion-v1-5/stable-diffusion-inpainting" if cfg.get("single_inpaint") else SD15
        return StableDiffusionPipeline.from_single_file(
            cfg["local"], config=conf, torch_dtype=f32, load_safety_checker=False)
    cls = StableDiffusionInstructPix2PixPipeline if cfg.get("pix2pix") else StableDiffusionPipeline
    try:
        return cls.from_pretrained(cfg["repo"], torch_dtype=f32, safety_checker=None, requires_safety_checker=False)
    except Exception as e:  # some repos only ship fp16 weights
        print("retrying with fp16 variant:", e)
        return cls.from_pretrained(cfg["repo"], variant="fp16", torch_dtype=f32,
                                   safety_checker=None, requires_safety_checker=False)


pipe = with_retries(load, "modelo")
in_ch = pipe.unet.config.in_channels
cross = pipe.unet.config.cross_attention_dim
if arch == "sd15" and cross != 768:
    sys.exit("Este modelo no es SD 1.5 ni SDXL (parece SD 2, SD 3, Flux u otro). No está soportado.")
if in_ch == 9:
    cfg["inpaint"] = True        # latent + mask + masked photo
    print("Modelo de inpainting")
elif in_ch == 8 and arch == "sd15":
    cfg["pix2pix"] = True
elif in_ch != 4:
    sys.exit(f"Modelo no soportado (in_channels={in_ch}).")

# ---------- LoRA ----------
def check_lora(path):
    """Explain in plain words why a LoRA file can't be used, before diffusers fails with a vague error."""
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        head = fh.read(64)
    if head.lstrip().startswith((b"<", b"{")) or size < 100_000:
        sys.exit("El enlace de la LoRA no descargó un archivo de LoRA, sino una página web o un mensaje "
                 f"({size} bytes). Usa el enlace directo al archivo .safetensors (en Hugging Face: Files and "
                 "versions → el archivo → copia el enlace). Los enlaces de Civitai suelen pedir iniciar sesión.")
    if not path.endswith(".safetensors"):
        return
    from safetensors import safe_open
    with safe_open(path, "pt") as fh:
        keys = list(fh.keys())
        shapes = {k: fh.get_slice(k).get_shape() for k in keys[:4000]}
    print(f"LoRA: {len(keys)} tensores, ejemplo: {keys[:3]}")
    if any(("hada_" in k or "lokr_" in k) for k in keys):
        sys.exit("Esta LoRA es de tipo LyCORIS (LoHa/LoKr), que todavía no se puede mezclar. Busca una LoRA normal.")
    if not any(("lora" in k.lower()) for k in keys):
        sys.exit("El archivo no parece una LoRA (no tiene capas 'lora'). ¿Es un modelo completo? "
                 "En ese caso ponlo en 'enlace', no en 'lora'.")
    xl_keys = any(("lora_te2_" in k or "text_encoder_2" in k or "te2." in k) for k in keys)
    # cross-attention size tells the family: 768 = SD 1.5, 2048 = SDXL
    dims = {s[-1] for k, s in shapes.items() if ("attn2" in k and ("to_k" in k) and "down" in k and len(s) == 2)}
    lora_xl = xl_keys or 2048 in dims
    lora_15 = (not xl_keys) and 768 in dims
    if arch == "sdxl" and lora_15:
        sys.exit("Esta LoRA es de SD 1.5 y el modelo es SDXL. Usa una LoRA de SDXL (o un modelo SD 1.5).")
    if arch == "sd15" and lora_xl:
        sys.exit("Esta LoRA es de SDXL y el modelo es SD 1.5. Usa una LoRA de SD 1.5 (o un modelo SDXL).")


if lora:
    print(f"Mezclando LoRA {lora} con peso {lora_w}")
    lpath = fetch_file(lora, "dl_lora")
    check_lora(lpath)
    try:
        pipe.load_lora_weights(os.path.dirname(lpath), weight_name=os.path.basename(lpath), adapter_name="lora")
    except ValueError as e:
        sys.exit("La LoRA no es compatible con este modelo (" + str(e)[:200] + "). Suele pasar con LoRAs de Pony "
                 "o Illustrious en modelos SDXL normales, o con formatos poco comunes. Prueba otra LoRA.")
    pipe.fuse_lora(lora_scale=lora_w)
    pipe.unload_lora_weights()

# SDXL's own VAE overflows in 16-bit (the phone's GPU math); use the fixed one.
if arch == "sdxl":
    cfg["vae"] = "madebyollin/sdxl-vae-fp16-fix"
if cfg.get("vae"):
    pipe.vae = with_retries(lambda: AutoencoderKL.from_pretrained(cfg["vae"], torch_dtype=f32), "VAE")
pipe.to("cpu")
pipe.tokenizer.save_pretrained(out)
# everything is in memory now; free the disk for the exported files
import shutil  # noqa: E402
shutil.rmtree(os.path.expanduser("~/.cache/huggingface"), ignore_errors=True)
shutil.rmtree("dl_model", ignore_errors=True)
shutil.rmtree("dl_lora", ignore_errors=True)
scale = pipe.vae.config.scaling_factor

with open(os.path.join(out, "info.txt"), "w") as fh:
    fh.write("mode=%d\n" % (1 if cfg.get("pix2pix") else 2 if cfg.get("inpaint") else 0))
    fh.write("dynamic=1\n")
    # converter version: raise it whenever a change is worth re-converting old models for;
    # the app and the weekly check offer an update for models with a lower one.
    fh.write("version=3\n")
    fh.write("arch=%s\n" % arch)
    fh.write("scale=%g\n" % scale)
    fh.write("source=%s\n" % (link or cfg.get("repo") or cfg.get("single") or model_id))
    fh.write("precision=%s\n" % os.environ.get("PRECISION", "8"))
    if lora:
        fh.write("lora=%s\n" % lora)
        fh.write("lora_weight=%g\n" % lora_w)


def export(module, args, name, inputs, outputs, dynamic_axes=None):
    path = os.path.join(out, name, "model.onnx")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(module.eval(), args, path, input_names=inputs, output_names=outputs,
                          opset_version=17, do_constant_folding=True, dynamic_axes=dynamic_axes)
    print("exported", path)


# ---------- text encoder(s) ----------
class TextEncoder(torch.nn.Module):
    def __init__(self, te):
        super().__init__()
        self.te = te

    def forward(self, input_ids):
        o = self.te(input_ids.to(torch.int64))
        return o.last_hidden_state, o.pooler_output


class TextEncoderXL(torch.nn.Module):
    """Both SDXL text encoders: penultimate hidden states side by side, plus the pooled embedding."""

    def __init__(self, te1, te2):
        super().__init__()
        self.te1, self.te2 = te1, te2

    def forward(self, input_ids):
        ids = input_ids.to(torch.int64)
        h1 = self.te1(ids, output_hidden_states=True).hidden_states[-2]
        o2 = self.te2(ids, output_hidden_states=True)
        return torch.cat([h1, o2.hidden_states[-2]], dim=-1), o2.text_embeds


ids = torch.zeros(2, 77, dtype=torch.int32)
if arch == "sdxl":
    export(TextEncoderXL(pipe.text_encoder, pipe.text_encoder_2), (ids,), "text_encoder",
           ["input_ids"], ["prompt_embeds", "pooled"])
    pipe.text_encoder_2 = None
else:
    export(TextEncoder(pipe.text_encoder), (ids,), "text_encoder", ["input_ids"], ["last_hidden_state", "pooler_output"])
pipe.text_encoder = None
gc.collect()


# ---------- unet ----------
class Unet(torch.nn.Module):
    def __init__(self, u):
        super().__init__()
        self.u = u

    def forward(self, sample, timestep, encoder_hidden_states):
        return self.u(sample, timestep, encoder_hidden_states, return_dict=False)[0]


class UnetXL(torch.nn.Module):
    def __init__(self, u):
        super().__init__()
        self.u = u

    def forward(self, sample, timestep, encoder_hidden_states, text_embeds, time_ids):
        return self.u(sample, timestep, encoder_hidden_states,
                      added_cond_kwargs={"text_embeds": text_embeds, "time_ids": time_ids}, return_dict=False)[0]


# height/width free so the app can make vertical and horizontal pictures
spatial = {"sample": {2: "h", 3: "w"}, "out_sample": {2: "h", 3: "w"}}
if arch == "sdxl":
    export(UnetXL(pipe.unet),
           (torch.randn(2, in_ch, 128, 128), torch.tensor([500], dtype=torch.int32), torch.randn(2, 77, 2048),
            torch.randn(2, 1280), torch.tensor([[1024., 1024., 0., 0., 1024., 1024.]] * 2)),
           "unet", ["sample", "timestep", "encoder_hidden_states", "text_embeds", "time_ids"], ["out_sample"], spatial)
else:
    b, c = (3, 8) if cfg.get("pix2pix") else (2, in_ch)
    export(Unet(pipe.unet),
           (torch.randn(b, c, 64, 64), torch.tensor([500], dtype=torch.int32), torch.randn(b, 77, 768)),
           "unet", ["sample", "timestep", "encoder_hidden_states"], ["out_sample"], spatial)
pipe.unet = None
gc.collect()
# The UNet's weights are written as many external files next to model.onnx; MNNConvert reads them as they are.


# ---------- vae ----------
class Decoder(torch.nn.Module):
    def __init__(self, v):
        super().__init__()
        self.v = v

    def forward(self, latent_sample):
        return self.v.decode(latent_sample, return_dict=False)[0]


class Encoder(torch.nn.Module):
    def __init__(self, v):
        super().__init__()
        self.encoder = v.encoder
        self.quant_conv = v.quant_conv

    def forward(self, sample):
        return self.quant_conv(self.encoder(sample))[:, :4]


export(Decoder(pipe.vae), (torch.randn(1, 4, 64, 64),), "vae_decoder", ["latent_sample"], ["sample"],
       dynamic_axes={"latent_sample": {2: "h", 3: "w"}, "sample": {2: "H", 3: "W"}})
export(Encoder(pipe.vae), (torch.randn(1, 3, 512, 512),), "vae_encoder", ["sample"], ["latent"],
       dynamic_axes={"sample": {2: "H", 3: "W"}, "latent": {2: "h", 3: "w"}})
print("done")
