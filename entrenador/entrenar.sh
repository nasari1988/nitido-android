#!/usr/bin/env bash
# Abre el entrenador en tu navegador (solo en esta PC: http://127.0.0.1:8765).
#   bash entrenar.sh            abre la página
#   bash entrenar.sh --puerto 9000
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || { echo "Primero instala:  bash instalar.sh"; exit 1; }
. .venv/bin/activate
exec python -m nitido_entrenador.servidor "$@"
