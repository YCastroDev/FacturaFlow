#!/bin/sh
# Crea en RabbitMQ un usuario por servicio con permisos mínimos (ADR 0013, como PostgreSQL en el ADR 0003).
#
# Lo ejecuta el contenedor "rabbitmq-init" de docker compose cada vez que se levanta el entorno, cuando
# RabbitMQ ya está sano. Usa la API HTTP de administración (rabbitmqadmin, incluido en la imagen) con el
# usuario administrador. Es IDEMPOTENTE: cada "declare" es un PUT, así que crea lo que falta y deja la
# contraseña de cada servicio igual a la del .env (si la cambias en el .env, se actualiza aquí).
#
# Ninguna contraseña aparece en la línea de comandos ni se imprime:
#   - la del administrador llega en RABBITMQADMIN_PASSWORD (variable que rabbitmqadmin lee sola);
#   - la de cada servicio se convierte aquí en su hash salado (el formato de RabbitMQ) y solo el hash
#     viaja como argumento.
set -eu

: "${RABBITMQADMIN_USERNAME:?falta el usuario administrador}"
: "${RABBITMQADMIN_PASSWORD:?falta la contraseña del administrador}"
VHOST="${RABBITMQ_VHOST:?falta el virtual host}"

# Hash de RabbitMQ (rabbit_password_hashing_sha256, el algoritmo por defecto del servidor):
# base64(sal de 4 bytes + SHA-256(sal + contraseña)).
password_hash() {
  tmp="$(mktemp -d)"
  head -c 4 /dev/urandom > "$tmp/salt"
  { cat "$tmp/salt"; printf '%s' "$1"; } | openssl dgst -sha256 -binary > "$tmp/digest"
  cat "$tmp/salt" "$tmp/digest" | base64 | tr -d '\n'
  rm -rf "$tmp"
}

# create_service_user <usuario> <contraseña> <configure> <write> <read>
# Los tres últimos son expresiones regulares sobre nombres de exchanges y colas del vhost.
create_service_user() {
  user="$1" password="$2"
  if [ -z "$password" ]; then
    echo "init: falta la contraseña de $user" >&2
    exit 1
  fi
  echo "init: usuario $user en el vhost $VHOST (configure='$3', write='$4', read='$5')"
  rabbitmqadmin --non-interactive --quiet declare user --name "$user" \
      --password-hash "$(password_hash "$password")" --tags ""
  rabbitmqadmin --non-interactive --quiet --vhost "$VHOST" declare permissions --username "$user" \
      --configure "$3" --write "$4" --read "$5"
}

echo "init: vhost $VHOST"
rabbitmqadmin --non-interactive --quiet declare vhost --name "$VHOST"

# --- Un bloque por servicio. Para un servicio nuevo, copia la línea y agrega su variable de contraseña
# --- al .env, .env.example y docker-compose.yml.
# sales: solo declara y publica en SU exchange; no lee ninguna cola ('^$' no coincide con ningún nombre).
create_service_user sales "${SALES_RABBITMQ_PASSWORD:-}" '^sales\.events$' '^sales\.events$' '^$'

echo "init: listo"
