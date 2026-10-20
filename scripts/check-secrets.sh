#!/usr/bin/env bash
# Escaneo manual de secretos con gitleaks (vía Docker):
#   1) el historial de commits;
#   2) los archivos actuales que Git versiona o versionaría
#      (tracked + nuevos no ignorados). Los archivos ignorados, como .env,
#      nunca se copian al escaneo.
# Uso: ./scripts/check-secrets.sh
set -euo pipefail

GITLEAKS_IMAGE="zricethezav/gitleaks:v8.30.1"
repo_root="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$repo_root"

status=0

echo "==> [1/2] Historial de commits"
if git rev-parse --verify -q HEAD >/dev/null; then
  docker run --rm -v "$repo_root:/repo" -w /repo "$GITLEAKS_IMAGE" \
    git --redact --no-banner --config /repo/.gitleaks.toml /repo || status=1
else
  echo "    (sin commits todavía, se omite)"
fi

echo "==> [2/2] Archivos del árbol de trabajo (sin ignorados)"
scan_dir="$(mktemp -d)"
trap 'rm -rf "$scan_dir"' EXIT
chmod 755 "$scan_dir"
git ls-files -z --cached --others --exclude-standard \
  | tar --null --ignore-failed-read -T - -cf - 2>/dev/null \
  | tar -xf - -C "$scan_dir"
docker run --rm \
  -v "$scan_dir:/scan:ro" \
  -v "$repo_root/.gitleaks.toml:/config/gitleaks.toml:ro" \
  "$GITLEAKS_IMAGE" \
  dir --redact --no-banner --config /config/gitleaks.toml /scan || status=1

if [[ $status -eq 0 ]]; then
  echo "OK: no se encontraron secretos."
else
  echo "ATENCIÓN: gitleaks encontró posibles secretos." >&2
fi
exit $status
