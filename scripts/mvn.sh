#!/usr/bin/env bash
# Ejecuta el Maven Wrapper de un servicio dentro de un contenedor (sin Java local).
# Uso: ./scripts/mvn.sh <servicio> [argumentos de maven...]
#   ej: ./scripts/mvn.sh invoicing test
set -euo pipefail

MAVEN_IMAGE="maven:3.9.16-eclipse-temurin-25"
M2_VOLUME="facturaflow-m2"   # caché de dependencias compartida entre ejecuciones

if [[ $# -lt 1 ]]; then
  echo "Uso: $0 <servicio> [argumentos de maven...]" >&2
  exit 1
fi
service="$1"; shift

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
service_dir="$repo_root/services/$service"
if [[ ! -x "$service_dir/mvnw" ]]; then
  echo "No existe el servicio '$service' (o le falta mvnw) en $service_dir" >&2
  exit 1
fi

uid="$(id -u)"
gid="$(id -g)"
docker_sock="/var/run/docker.sock"
sock_gid="$(stat -c %g "$docker_sock")"

# El volumen nombrado nace con dueño root: se le asigna tu UID una sola vez
# para que Maven (que corre como tu usuario) pueda escribir la caché.
docker volume create "$M2_VOLUME" >/dev/null
docker run --rm -v "$M2_VOLUME:/home/maven" "$MAVEN_IMAGE" \
  sh -c "[ \"\$(stat -c %u /home/maven)\" = \"$uid\" ] || chown -R $uid:$gid /home/maven"

tty_flags=()
[[ -t 0 && -t 1 ]] && tty_flags=(-it)

# --user              : los archivos generados (target/) quedan a tu nombre, no de root.
# --group-add         : permite usar docker.sock (Testcontainers) sin ser root.
# --network host      : el contenedor comparte la red del host, así ve en localhost
#                       los puertos que publican los contenedores de Testcontainers.
# HOME / user.home    : ~/.m2 (dependencias y distribución del wrapper) vive en el volumen.
#                       Java toma user.home de /etc/passwd, no de HOME, por eso se fuerzan ambos.
# MAVEN_CONFIG vacío  : la imagen oficial la define y mvnw la pasaría como argumento.
exec docker run --rm "${tty_flags[@]}" \
  --user "$uid:$gid" \
  --group-add "$sock_gid" \
  --network host \
  -e HOME=/home/maven \
  -e MAVEN_OPTS="-Duser.home=/home/maven" \
  -e MAVEN_CONFIG= \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost \
  -e TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-false}" \
  -v "$M2_VOLUME:/home/maven" \
  -v "$docker_sock:$docker_sock" \
  -v "$service_dir:/workspace" \
  -w /workspace \
  --entrypoint ./mvnw \
  "$MAVEN_IMAGE" "$@"
