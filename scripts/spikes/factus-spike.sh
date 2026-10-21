#!/usr/bin/env bash
# Spike contra el sandbox de Factus (docs/spikes/0001-factus-sandbox.md).
#
# Ejecuta factus/factus_spike.py dentro de un contenedor de Python (solo biblioteca estándar):
# no hay que instalar nada. Las credenciales FACTUS_* las inyecta Docker desde el .env de la
# raíz (--env-file): el script nunca lee el archivo ni imprime secretos; los tokens se enmascaran.
#
# Uso (desde la raíz del repo):
#   ./scripts/spikes/factus-spike.sh help
#   ./scripts/spikes/factus-spike.sh auth
#   ./scripts/spikes/factus-spike.sh bill payloads/cash.json
#
# Las respuestas sanitizadas quedan en scripts/spikes/output/ (ignorado por Git).
set -euo pipefail

PYTHON_IMAGE="python:3.13.16-alpine3.24"

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
env_file="${FACTUS_ENV_FILE:-$repo_root/.env}"
out_dir="$repo_root/scripts/spikes/output"

if [[ ! -f "$env_file" ]]; then
  echo "No existe $env_file (copia .env.example y llena FACTUS_*)." >&2
  exit 1
fi
mkdir -p "$out_dir"

# -I: modo aislado de Python (no carga módulos del directorio actual ni variables PYTHON*).
exec docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env-file "$env_file" \
  -v "$repo_root/scripts/spikes/factus:/spike:ro" \
  -v "$out_dir:/out" \
  -w /spike \
  "$PYTHON_IMAGE" python -I /spike/factus_spike.py "$@"
