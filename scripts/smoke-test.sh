#!/usr/bin/env bash
# Prueba de humo de la imagen de un servicio (ADR 0016): la arranca con lo mínimo que necesita y espera a que
# /actuator/health responda UP (el healthcheck del compose), con un tiempo máximo. Falla si la imagen no arranca.
# Uso: ./scripts/smoke-test.sh <invoicing|catalog|sales|gateway> <imagen>
#   ej: ./scripts/smoke-test.sh catalog facturaflow-catalog:local
# Variables opcionales:
#   SMOKE_WAIT_TIMEOUT=<s>  segundos máximos de espera del health (por defecto 180).
#
# - Usa infra/docker-compose.yml (mismas variables, init de PostgreSQL y healthchecks que el despliegue) con el
#   override infra/smoke/compose.smoke.yml (sin puertos ni reinicios), en un proyecto aparte (facturaflow-smoke).
# - Credenciales DESECHABLES: un env temporal generado de .env.example con cada CAMBIAME reemplazado por un valor
#   aleatorio; se borra al terminar. Nunca lee .env.
# - Dependencias: solo PostgreSQL (invoicing, catalog y sales). RabbitMQ y Keycloak quedan fuera a propósito: ningún
#   servicio los necesita para arrancar (ADR 0013, 0014 y 0017); el gateway arranca solo.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Uso: $0 <invoicing|catalog|sales|gateway> <imagen>" >&2
  exit 1
fi
service="$1"
image="$2"

case "$service" in
  invoicing|catalog|sales) needs_postgres=true ;;
  gateway) needs_postgres=false ;;
  *) echo "Servicio desconocido: $service" >&2; exit 1 ;;
esac

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
project="facturaflow-smoke"
timeout="${SMOKE_WAIT_TIMEOUT:-180}"

if ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "No existe la imagen local '$image' (constrúyela antes)." >&2
  exit 1
fi

env_file="$(mktemp)"
compose=(docker compose -p "$project" --env-file "$env_file"
  -f "$repo_root/infra/docker-compose.yml" -f "$repo_root/infra/smoke/compose.smoke.yml")

cleanup() {
  "${compose[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  docker image rm "$project-$service" >/dev/null 2>&1 || true
  rm -f "$env_file"
}
trap cleanup EXIT

# Cada CAMBIAME → valor aleatorio (empieza por letra: también sirve de nombre de usuario). Lo demás, tal cual.
while IFS= read -r line; do
  if [[ "$line" == *=CAMBIAME ]]; then
    printf '%s=s%s\n' "${line%%=*}" "$(openssl rand -hex 16)"
  else
    printf '%s\n' "$line"
  fi
done < "$repo_root/.env.example" > "$env_file"

# Compose busca la imagen de un servicio con "build:" como <proyecto>-<servicio>; con --no-build usa esta.
docker tag "$image" "$project-$service"

if [[ "$needs_postgres" == true ]]; then
  echo "Levantando PostgreSQL..."
  "${compose[@]}" up -d --wait --wait-timeout "$timeout" postgres
fi

echo "Arrancando $service ($image); espera máxima del health: ${timeout}s..."
start=$SECONDS
if "${compose[@]}" up -d --no-deps --no-build --wait --wait-timeout "$timeout" "$service"; then
  echo "OK: $service respondió UP en /actuator/health en $((SECONDS - start)) s."
else
  echo "::error::La imagen de $service no arrancó (o su health no llegó a UP en ${timeout}s). Logs del contenedor:" >&2
  "${compose[@]}" logs --no-color --tail 200 "$service" >&2 || true
  exit 1
fi
