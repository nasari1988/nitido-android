"""Exports the old-photo restoration models to ONNX, each with ONE fixed-size input:

  rest_denoise  SCUNet (real photos)     in [1,3,256,256] RGB 0..1        -> [1,3,256,256] RGB 0..1
  rest_deblur   Restormer (out of focus) in [1,3,256,256] RGB 0..1        -> [1,3,256,256] RGB 0..1
  rest_faces    CodeFormer               in [1,4,512,512] RGB -1..1 + w   -> [1,3,512,512] RGB -1..1
                (4th channel holds the fidelity weight w, 0..1, the same value everywhere)
  rest_lama     LaMa (big-lama)          in [1,4,512,512] RGB 0..1 + mask -> [1,3,512,512] RGB 0..1
  rest_color    DDColor (tiny)           in [1,1,512,512] gray 0..1       -> [1,3,512,512] RGB 0..1

usage: python export_restore.py <out_dir> [rest_a,rest_b,...]   (only those models)
Each model is checked against the original PyTorch code before export. A model that fails
is skipped (with a warning) so the others are still published.
"""
import os
import sys
import time
import urllib.request

import numpy as np
import torch
import torch.nn as nn

URLS = {
    "rest_denoise": "https://github.com/cszn/KAIR/releases/download/v1.0/scunet_color_real_psnr.pth",
    "rest_deblur": "https://github.com/swz30/Restormer/releases/download/v1.0/single_image_defocus_deblurring.pth",
    "rest_faces": "https://github.com/sczhou/CodeFormer/releases/download/v0.1.0/codeformer.pth",
    "rest_lama": "https://github.com/Sanster/models/releases/download/add_big_lama/big-lama.pt",
    "rest_color": "https://huggingface.co/piddnad/DDColor-models/resolve/main/ddcolor_paper_tiny.pth",
}


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1_000_000:
        return
    for attempt in range(5):
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
            time.sleep(10 * (attempt + 1))
    raise RuntimeError(f"no se pudo descargar {url}")


def load(path):
    import spandrel
    return spandrel.ModelLoader().load_from_file(path).model.eval().float()


def export(model, x, path):
    """Writes <name>.onnx (simplified when possible, which MNN converts more reliably) and keeps
    the plain export as <name>.alt.onnx for a second try."""
    alt = path[:-5] + ".alt.onnx"
    torch.onnx.export(model, (x,), alt, input_names=["input"], output_names=["output"], opset_version=17)
    try:
        import onnx
        from onnxsim import simplify
        m, ok = simplify(onnx.load(alt))
        if not ok:
            raise RuntimeError("onnxsim no pudo validar el modelo")
        onnx.save(m, path)
        print("  ONNX simplificado")
    except Exception as e:  # noqa: BLE001
        print(f"  sin simplificar ({e})")
        os.replace(alt, path)


def check(name, a, b, tol):
    d = (a - b).abs().max().item()
    print(f"  diferencia con el original: {d:.5f}")
    if not d < tol:
        raise RuntimeError(f"{name}: la versión exportable no coincide con el original ({d})")


# ------------------------------------------------------------------ SCUNet
def denoise(pth, out):
    model = load(pth)
    x = torch.rand(1, 3, 256, 256)
    with torch.no_grad():
        y = model(x)
    print(f"  salida {tuple(y.shape)}")
    export(model, x, out)


# ------------------------------------------------------------------ Restormer
def deblur(pth, out):
    """Restormer trained on single-image defocus blur (photos that were out of focus)."""
    model = load(pth)
    x = torch.rand(1, 3, 256, 256)
    with torch.no_grad():
        y = model(x)
    print(f"  salida {tuple(y.shape)}")
    export(model, x, out)


# ------------------------------------------------------------------ CodeFormer
class CodeFormerExport(nn.Module):
    """CodeFormer's forward with the fidelity weight as part of the input and export-friendly ops
    (argmax + gather instead of topk + one-hot scatter)."""

    def __init__(self, m, mod):
        super().__init__()
        self.m = m
        self.adain = mod.adaptive_instance_normalization

    def forward(self, x4):
        m = self.m
        x = x4[:, :3]
        w = x4[:, 3:4, :1, :1]
        enc = {}
        out_list = [m.fuse_encoder_block[f] for f in m.connect_list]
        h = x
        for i, block in enumerate(m.encoder.blocks):
            h = block(h)
            if i in out_list:
                enc[str(int(h.shape[-1]))] = h
        lq = h
        pos = m.position_emb.unsqueeze(1).repeat(1, h.shape[0], 1)
        q = m.feat_emb(lq.flatten(2).permute(2, 0, 1))
        for layer in m.ft_layers:
            q = layer(q, query_pos=pos)
        logits = m.idx_pred_layer(q).permute(1, 0, 2)
        idx = torch.argmax(logits, dim=2).reshape(-1)
        z = torch.index_select(m.quantize.embedding.weight, 0, idx)
        z = z.reshape(1, 16, 16, -1).permute(0, 3, 1, 2)
        z = self.adain(z, lq)
        fuse_list = [m.fuse_generator_block[f] for f in m.connect_list]
        for i, block in enumerate(m.generator.blocks):
            z = block(z)
            if i in fuse_list:
                f = str(int(z.shape[-1]))
                z = m.fuse_convs_dict[f](enc[f], z, w)
        return z


def faces(pth, out):
    model = load(pth)
    mod = sys.modules[type(model).__module__]
    # the scripted swish does not trace well; a plain function computes the same
    mod.swish = lambda t: t * torch.sigmoid(t)
    x = torch.rand(1, 3, 512, 512) * 2 - 1
    with torch.no_grad():
        ref = model(x, weight=0.7)[0]
        wrap = CodeFormerExport(model, mod).eval()
        x4 = torch.cat([x, torch.full((1, 1, 512, 512), 0.7)], 1)
        y = wrap(x4)
    check("CodeFormer", y, ref, 2e-2)
    export(wrap, x4, out)


# ------------------------------------------------------------------ LaMa
def _dft_mats(n_h, n_w):
    """Real matrices for an orthonormal 2-D real FFT of an n_h x n_w map, and its inverse."""
    wr = n_w // 2 + 1
    w = np.arange(n_w)
    l = np.arange(wr)
    h = np.arange(n_h)
    s = 1.0 / np.sqrt(n_h * n_w)
    cw = np.cos(2 * np.pi * np.outer(w, l) / n_w)            # [W, Wr]
    sw = -np.sin(2 * np.pi * np.outer(w, l) / n_w)
    ch = np.cos(2 * np.pi * np.outer(h, h) / n_h)            # [H, H] (symmetric)
    sh = np.sin(2 * np.pi * np.outer(h, h) / n_h)
    c = np.full(wr, 2.0)
    c[0] = 1.0
    if n_w % 2 == 0:
        c[-1] = 1.0
    ci = c[:, None] * np.cos(2 * np.pi * np.outer(l, w) / n_w)   # [Wr, W]
    si = -c[:, None] * np.sin(2 * np.pi * np.outer(l, w) / n_w)
    t = lambda a: torch.tensor(a, dtype=torch.float32)
    return t(cw * s), t(sw * s), t(ch), t(sh), t(ci * s), t(si * s)


def _along_h(a, m):
    # multiply along the H axis by a constant matrix, as a plain right-multiplication
    return (a.transpose(-1, -2) @ m.T).transpose(-1, -2)


def fourier_forward(self, x):
    """FourierUnit.forward with the FFTs written as matrix products (ONNX has no usable FFT)."""
    assert self.spatial_scale_factor is None and not self.spectral_pos_encoding
    assert not self.use_se and not self.ffc3d and self.fft_norm == "ortho"
    b, c = int(x.shape[0]), int(x.shape[1])
    n_h, n_w = int(x.shape[2]), int(x.shape[3])
    cw, sw, ch, sh, ci, si = _dft_mats(n_h, n_w)
    a = x @ cw
    bb = x @ sw
    re = _along_h(a, ch) + _along_h(bb, sh)
    im = _along_h(bb, ch) - _along_h(a, sh)
    ff = torch.stack((re, im), dim=2).reshape(b, 2 * c, n_h, n_w // 2 + 1)
    ff = self.relu(self.bn(self.conv_layer(ff)))
    ff = ff.reshape(b, -1, 2, n_h, n_w // 2 + 1)
    zr, zi = ff[:, :, 0], ff[:, :, 1]
    yr = _along_h(zr, ch) - _along_h(zi, sh)
    yi = _along_h(zi, ch) + _along_h(zr, sh)
    return yr @ ci + yi @ si


class LamaExport(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x4):
        return self.m(x4[:, :3], x4[:, 3:4])


def lama(pth, out):
    model = load(pth)
    img = torch.rand(1, 3, 512, 512)
    mask = torch.zeros(1, 1, 512, 512)
    mask[:, :, 200:300, 150:380] = 1
    with torch.no_grad():
        ref = model(img, mask)
    patched = 0
    for mod in model.modules():
        if type(mod).__name__ == "FourierUnit":
            mod.forward = fourier_forward.__get__(mod)
            patched += 1
    print(f"  {patched} capas de Fourier reescritas")
    wrap = LamaExport(model).eval()
    x4 = torch.cat([img, mask], 1)
    with torch.no_grad():
        y = wrap(x4)
    check("LaMa", y, ref, 2e-3)
    export(wrap, x4, out)


# ------------------------------------------------------------------ DDColor
def color(pth, out):
    """Only the network: gray photo as RGB (0..1) in, the Lab color channels a, b out.
    The color-space math (which crashed the MNN converter) is done in the app."""
    model = load(pth)
    probe = torch.rand(1, 1, 256, 256).repeat(1, 3, 1, 1)
    with torch.no_grad():
        ref = model(probe)
    # 1) bake spectral / weight normalization into plain weights
    baked = 0
    for mod in model.modules():
        for fn in (torch.nn.utils.remove_spectral_norm, torch.nn.utils.remove_weight_norm):
            try:
                fn(mod)
                baked += 1
            except Exception:  # noqa: BLE001
                pass
    print(f"  {baked} normalizaciones fijadas")
    # 2) the color decoder's einsum("bqc,bchw->bqhw") as a plain matrix product
    real_einsum = torch.einsum

    def einsum(eq, *ops):
        if eq.replace(" ", "") == "bqc,bchw->bqhw":
            a, f = ops[0] if len(ops) == 1 else ops
            b_, c_, h_, w_ = (int(v) for v in f.shape)
            return torch.matmul(a, f.reshape(b_, c_, h_ * w_)).reshape(b_, -1, h_, w_)
        return real_einsum(eq, *ops)

    torch.einsum = einsum
    with torch.no_grad():
        check("DDColor", model(probe), ref, 5e-2)
    for size in (512, 256):
        try:
            g = torch.rand(1, 1, size, size).repeat(1, 3, 1, 1)
            with torch.no_grad():
                y = model(g)
            print(f"  modelo a {size}, salida {tuple(y.shape)}")
            break
        except Exception as e:  # noqa: BLE001
            print(f"  {size} no funcionó: {e}")
    try:
        export(model, g, out)
    finally:
        torch.einsum = real_einsum


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    import spandrel_extra_arches
    spandrel_extra_arches.install()
    torch.set_grad_enabled(False)
    steps = {"rest_denoise": denoise, "rest_deblur": deblur, "rest_faces": faces, "rest_lama": lama, "rest_color": color}
    if len(sys.argv) > 2 and sys.argv[2].strip():
        only = {n.strip() for n in sys.argv[2].split(",")}
        steps = {k: v for k, v in steps.items() if k in only}
        print(f"Solo: {', '.join(steps)}")
    ok = []
    for name, fn in steps.items():
        print(f"== {name}", flush=True)
        try:
            pth = os.path.join(out, name + os.path.splitext(URLS[name])[1])
            download(URLS[name], pth)
            fn(pth, os.path.join(out, name + ".onnx"))
            os.remove(pth)
            ok.append(name)
        except Exception as e:  # noqa: BLE001
            import traceback
            traceback.print_exc()
            print(f"::warning::No se pudo preparar {name}: {e}")
    print(f"Listos: {', '.join(ok) or 'ninguno'}")
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
