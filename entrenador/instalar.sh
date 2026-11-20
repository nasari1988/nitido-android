#!/usr/bin/env bash
# Nítido · Entrenador: instala todo en un entorno virtual (.venv) dentro de esta carpeta.
# No toca el Python del sistema. Para borrarlo todo basta con borrar la carpeta .venv.
#   bash instalar.sh
set -euo pipefail
cd "$(dirname "$0")"

echo "== Revisando la tarjeta NVIDIA"
if command -v nvidia-smi >/dev/null; then
  nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader || true
else
  echo "No encontré nvidia-smi. En Pop!_OS instala el controlador con:"
  echo "    sudo apt install system76-driver-nvidia"
  echo "y reinicia. (Sin él se puede entrenar en el procesador, pero es mucho más lento.)"
fi

PY=${PYTHON:-python3}
"$PY" -c 'import sys; assert sys.version_info >= (3, 10), "Hace falta Python 3.10 o más nuevo"'
if ! "$PY" -c 'import venv, ensurepip' 2>/dev/null; then
  echo "Falta el módulo venv. Instálalo con:  sudo apt install python3-venv"
  exit 1
fi

echo "== Creando el entorno virtual (.venv)"
[ -d .venv ] || "$PY" -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip wheel

echo "== PyTorch con CUDA (para la GTX 1650)"
# las ruedas de PyTorch traen su propio CUDA: solo hace falta el controlador de NVIDIA
pip install "torch==2.5.1" "torchvision==0.20.1" --index-url https://download.pytorch.org/whl/cu121

echo "== El resto"
pip install -r requirements.txt

echo "== Modelo que encuentra la piel (MediaPipe, Apache-2.0)"
mkdir -p modelos_base
if [ ! -s modelos_base/selfie_multiclass.tflite ]; then
  curl -fL --retry 3 -o modelos_base/selfie_multiclass.tflite \
    "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite"
fi

echo "== Prueba rápida"
python - <<'PY'
import torch
ok = torch.cuda.is_available()
print("PyTorch", torch.__version__, "· tarjeta:", torch.cuda.get_device_name(0) if ok else "no (se usará el procesador)")
PY
echo
echo "Listo. Para abrir el entrenador:  bash entrenar.sh"
