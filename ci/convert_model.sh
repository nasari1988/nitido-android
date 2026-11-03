#!/usr/bin/env bash
# Converts one model to MNN and leaves the files to publish in ./out
# usage: ci/convert_model.sh <model_id> [model link] [8|16] [lora link] [lora weight]
set -euo pipefail
cd "$(dirname "$0")/.."
ID="$1"
LINK="${2:-}"
export PRECISION="${3:-8}"
LORA="${4:-}"
LORA_W="${5:-0.8}"

if [ "$ID" = "restauracion" ]; then
  # Not a Stable Diffusion model: the pack of old-photo restoration models.
  echo "::group::Python"
  source ci/venv.sh
  .venv/bin/pip install --quiet "torch==2.4.1" "torchvision==0.19.1" --index-url https://download.pytorch.org/whl/cpu
  .venv/bin/pip install --quiet "onnx==1.16.2" "numpy<2" "einops" "safetensors" "timm"
  .venv/bin/pip install --quiet "onnxsim" "ml_dtypes" "onnxruntime" || echo "onnxsim no disponible"
  .venv/bin/pip install --quiet "spandrel==0.4.1" "spandrel_extra_arches==0.2.0" \
    || .venv/bin/pip install --quiet spandrel spandrel_extra_arches
  echo "::endgroup::"
  echo "::group::Convertidor MNN"
  rm -rf mnn
  git clone -q --depth 1 --branch 3.4.0 https://github.com/alibaba/MNN.git mnn
  cmake -S mnn -B mnn/build-host -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_CONVERTER=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.10
  cmake --build mnn/build-host --target MNNConvert -j"$(nproc)"
  echo "::endgroup::"
  echo "::group::Exportar modelos de restauración"
  # the link field may name only some models (e.g. "rest_deblur"); the rest stay as published
  ONLY=""; case "$LINK" in rest_*) ONLY="$LINK" ;; esac
  .venv/bin/python ci/export_restore.py onnx "$ONLY"
  echo "::endgroup::"
  echo "::group::Convertir a MNN"
  mkdir -p out
  for f in onnx/*.onnx; do
    case "$f" in *.alt.onnx) continue ;; esac
    n=$(basename "$f" .onnx)
    ok=0
    # simplified model first, then the plain export; if the optimizer crashes, fewer optimizations
    for src in "$f" "onnx/$n.alt.onnx"; do
      [ -e "$src" ] || continue
      for opts in "--fp16" "--fp16 --optimizeLevel 0"; do
        echo "-- $n: $(basename "$src") $opts"
        if mnn/build-host/MNNConvert -f ONNX --modelFile "$src" --MNNModel "out/$n.mnn" --bizCode MNN $opts; then ok=1; break 2; fi
      done
    done
    [ $ok = 1 ] || { echo "::warning::MNN no pudo convertir $n"; rm -f "out/$n.mnn"; }
  done
  ls out/*.mnn >/dev/null
  printf "mode=restore\nversion=2\n" > out/info.txt
  (cd out && for f in *; do [ "$f" = files.txt ] || echo "$f $(stat -c %s "$f")"; done > files.txt)
  if [ -n "$ONLY" ] && [ -n "${GITHUB_REPOSITORY:-}" ]; then
    # keep the models already published that weren't rebuilt this time
    curl -fsSL "https://github.com/$GITHUB_REPOSITORY/releases/download/modelo-restauracion/files.txt" -o old_files.txt || true
    if [ -s old_files.txt ]; then
      while read -r name size; do
        [ -n "$name" ] && [ ! -e "out/$name" ] && echo "$name $size" >> out/files.txt
      done < old_files.txt
    fi
  fi
  cat out/files.txt
  echo "::endgroup::"
  exit 0
fi

echo "::group::Memoria extra (swap)"
# SDXL needs ~14 GB of RAM to export; the runner has 16 GB, so add swap.
SWAPDIR=/mnt; [ -w /mnt ] || SWAPDIR=/
sudo fallocate -l 20G "$SWAPDIR/swapfile" && sudo chmod 600 "$SWAPDIR/swapfile" && sudo mkswap "$SWAPDIR/swapfile" && sudo swapon "$SWAPDIR/swapfile" || echo "no se pudo crear swap"
free -h; df -h / /mnt || true
echo "::endgroup::"

echo "::group::Python"
source ci/venv.sh
.venv/bin/pip install --quiet "torch==2.4.1" --index-url https://download.pytorch.org/whl/cpu
.venv/bin/pip install --quiet "diffusers==0.30.3" "transformers==4.44.2" "huggingface_hub==0.25.2" \
  "onnx==1.16.2" "safetensors==0.4.5" "accelerate==0.34.2" "peft==0.12.0" "omegaconf" "numpy<2"
echo "::endgroup::"

echo "::group::Convertidor MNN"
rm -rf mnn
git clone -q --depth 1 --branch 3.4.0 https://github.com/alibaba/MNN.git mnn
cmake -S mnn -B mnn/build-host -DCMAKE_BUILD_TYPE=Release -DMNN_BUILD_CONVERTER=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.10
cmake --build mnn/build-host --target MNNConvert -j"$(nproc)"
echo "::endgroup::"

echo "::group::Exportar $ID a ONNX"
.venv/bin/python ci/export_model.py "$ID" onnx "$LINK" "$LORA" "$LORA_W"
df -h .
echo "::endgroup::"

ARCH=$(grep '^arch=' onnx/info.txt | cut -d= -f2)
if [ "$ARCH" = "sdxl" ] && [ "$PRECISION" = "16" ]; then
  echo "SDXL en 16 bits no cabe bien en la memoria del teléfono: se convierte en 8 bits."
  PRECISION=8
  sed -i 's/^precision=.*/precision=8/' onnx/info.txt
fi
if [ "$PRECISION" = "16" ]; then QUANT="--fp16"; else QUANT="--weightQuantBits=8"; fi
echo "Arquitectura: $ARCH · Precisión: $PRECISION bits"

echo "::group::Convertir a MNN"
mkdir -p out
for m in text_encoder unet vae_decoder vae_encoder; do
  mnn/build-host/MNNConvert -f ONNX --modelFile "onnx/$m/model.onnx" --MNNModel "out/$m.mnn" \
    --bizCode MNN --saveExternalData=1 $QUANT --transformerFuse
  rm -rf "onnx/$m"
  test -s "out/$m.mnn"
done
cp onnx/vocab.json onnx/merges.txt onnx/info.txt out/
# GitHub allows at most 2 GB per file: cut bigger ones into pieces; the app joins them again.
for f in out/*; do
  if [ "$(stat -c %s "$f")" -gt 1900000000 ]; then
    split -b 1800M -d -a 2 "$f" "$f.split"
    rm "$f"
  fi
done
(cd out && for f in *; do [ "$f" = files.txt ] || echo "$f $(stat -c %s "$f")"; done > files.txt)
cat out/files.txt
echo "::endgroup::"
