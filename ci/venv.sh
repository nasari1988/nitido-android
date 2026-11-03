#!/usr/bin/env bash
# Creates ./.venv with Python 3.12, whatever Python the build server has (the pinned torch,
# diffusers and numpy<2 have no wheels for newer Pythons). Usage: source ci/venv.sh
set -euo pipefail
rm -rf .venv
PY=""
for c in python3.12 python3; do
  if command -v "$c" >/dev/null && "$c" -c 'import sys; sys.exit(0 if sys.version_info[:2] == (3, 12) else 1)'; then PY="$c"; break; fi
done
if [ -n "$PY" ]; then
  "$PY" -m venv .venv
else
  echo "Python 3.12 no está instalado en el servidor: se descarga con uv"
  command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$HOME/.cargo/bin:$PATH"
  uv venv --seed -p 3.12 .venv
fi
.venv/bin/python --version
.venv/bin/python -m pip install --quiet --upgrade pip
