#!/usr/bin/env bash
# Abre el entrenador (app de escritorio; todo en esta PC).
#   bash entrenar.sh                 la app
#   bash entrenar.sh --web           la versión en el navegador (http://127.0.0.1:8765)
#   bash entrenar.sh --web --puerto 9000
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || { echo "Primero instala:  bash instalar.sh"; exit 1; }
. .venv/bin/activate
if [ "${1:-}" = "--web" ]; then
  shift
  exec python -m nitido_entrenador.servidor "$@"
fi
exec python -m nitido_entrenador.app "$@"
