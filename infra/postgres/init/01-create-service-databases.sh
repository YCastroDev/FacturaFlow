#!/usr/bin/env bash
# Crea una base y un usuario por servicio (patrón Database per Service, ADR 0003).
# Lo ejecuta la imagen oficial de PostgreSQL SOLO la primera vez, cuando el
# volumen de datos está vacío. Para re-ejecutarlo: borrar el volumen
# (docker compose ... down -v), lo que elimina TODOS los datos.
#
# Las contraseñas llegan por variables de entorno (desde el .env de la raíz);
# nunca se escriben aquí.
set -euo pipefail

# create_service_db <base> <usuario> <contraseña>
# El usuario queda como dueño de su base (puede crear tablas con Flyway) y se
# revoca el acceso público, así que ningún otro usuario de servicio puede conectarse.
create_service_db() {
  local db="$1" user="$2" password="$3"
  if [[ -z "$password" ]]; then
    echo "init: falta la contraseña para $user" >&2
    exit 1
  fi
  echo "init: creando base $db y usuario $user"
  # Variables de psql (:'x' literal, :"x" identificador) => sin inyección SQL.
  psql -v ON_ERROR_STOP=1 \
       --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
CREATE ROLE :"usr" WITH LOGIN PASSWORD :'pwd';
CREATE DATABASE :"db" OWNER :"usr";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"usr";
SQL
}

# --- Un bloque por servicio. Para un servicio nuevo, copia la línea y
# --- agrega su variable de contraseña al .env, .env.example y docker-compose.yml.
create_service_db invoicing_db invoicing_user "${INVOICING_DB_PASSWORD:-}"
