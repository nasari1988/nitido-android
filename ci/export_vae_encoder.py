"""Export the Stable Diffusion 1.5 VAE encoder to ONNX (input 'sample' 1x3x512x512 in [-1,1],
output 'latent' 1x4x64x64 = mean of the latent distribution, before the 0.18215 scaling)."""
import sys
import torch
import safetensors.torch  # noqa: F401  (diffusers expects this submodule loaded)
from diffusers import AutoencoderKL

out = sys.argv[1]

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

vae = with_retries(lambda: AutoencoderKL.from_pretrained(
    "stable-diffusion-v1-5/stable-diffusion-v1-5", subfolder="vae", torch_dtype=torch.float32), "VAE")
vae.eval()


class Encoder(torch.nn.Module):
    def __init__(self, v):
        super().__init__()
        self.encoder = v.encoder
        self.quant_conv = v.quant_conv

    def forward(self, sample):
        moments = self.quant_conv(self.encoder(sample))
        return moments[:, :4]


with torch.no_grad():
    torch.onnx.export(
        Encoder(vae).eval(),
        torch.randn(1, 3, 512, 512),
        out,
        input_names=["sample"],
        output_names=["latent"],
        opset_version=17,
        do_constant_folding=True,
    )
print("exported", out)
