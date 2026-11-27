#!/usr/bin/env bash
# Nítido · Entrenador: instala (una sola vez) el kit de Qualcomm para crear la versión NPU de tus modelos
# en esta PC, igual que la hace el GitHub de Nítido. Ocupa unos 3 GB. Necesita Linux de 64 bits (x86_64).
#   bash instalar_npu.sh
set -euo pipefail
cd "$(dirname "$0")"
QV=2.42.0.251225          # la misma versión que el runtime de la app (si no, el teléfono rechaza el modelo)

[ "$(uname -m)" = "x86_64" ] || { echo "El kit de Qualcomm solo funciona en PCs x86_64."; exit 1; }

echo "== Kit de Qualcomm (QAIRT $QV)"
if ! ls -d qairt/qairt/* >/dev/null 2>&1; then
  for i in 1 2 3 4 5; do
    curl -fL --retry 5 --retry-delay 5 -C - -o qairt.zip \
      "https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QV/v$QV.zip" && break
    echo "Descarga interrumpida, reintento $i"; sleep 10
  done
  [ -s qairt.zip ] || { echo "No se pudo bajar el kit."; exit 1; }
  echo "Descomprimiendo…"
  rm -rf qairt && unzip -q qairt.zip -d qairt && rm -f qairt.zip
fi
RAIZ="$(ls -d qairt/qairt/* | tail -1)"
echo "Kit en $RAIZ"

echo "== Python 3.10 (las herramientas del kit están hechas para esa versión)"
if ! command -v python3.10 >/dev/null || ! ls /usr/lib/x86_64-linux-gnu/libpython3.10.so.1.0 >/dev/null 2>&1; then
  if ! apt-cache show python3.10 >/dev/null 2>&1; then
    echo "Tu sistema no trae Python 3.10: lo agrego desde el PPA deadsnakes (pide tu contraseña)."
    sudo apt-get install -y software-properties-common
    sudo add-apt-repository -y ppa:deadsnakes/ppa
    sudo apt-get update
  fi
  sudo apt-get install -y python3.10 python3.10-venv libpython3.10
fi

echo "== libc++ (la usan las herramientas del kit)"
if ! ldconfig -p | grep -q 'libc++.so.1 '; then
  sudo apt-get install -y libc++1 libc++abi1 || sudo apt-get install -y libc++1-18 libc++abi1-18 libunwind-18 || true
fi
ldconfig -p | grep -q 'libc++.so.1 ' || echo "Aviso: sigue sin aparecer libc++; la conversión podría fallar."

echo "== Entorno de Python del kit (.venvq)"
[ -d .venvq ] || python3.10 -m venv .venvq
.venvq/bin/python -m pip install -q --upgrade pip
.venvq/bin/python -m pip install -q "numpy==1.26.4" "onnx==1.16.1" "protobuf==3.20.3" pyyaml packaging pandas

echo "== Prueba"
export PYTHONPATH="$RAIZ/lib/python"
export LD_LIBRARY_PATH="$RAIZ/lib/x86_64-linux-clang:/usr/lib/llvm-18/lib:/usr/local/lib:${LD_LIBRARY_PATH:-}"
falta=$(ldd "$RAIZ/lib/x86_64-linux-clang/libQnnHtp.so" | grep "not found" || true)
[ -z "$falta" ] || { echo "Faltan librerías:"; echo "$falta"; }
if .venvq/bin/python "$RAIZ/bin/x86_64-linux-clang/qairt-converter" --help >/dev/null 2>&1; then
  echo "Listo. En el entrenador, paso 4: «Crear versión NPU»."
else
  echo "El convertidor no arrancó. Para ver el error:"
  echo "  PYTHONPATH=$RAIZ/lib/python LD_LIBRARY_PATH=$LD_LIBRARY_PATH .venvq/bin/python $RAIZ/bin/x86_64-linux-clang/qairt-converter --help"
  exit 1
fi
