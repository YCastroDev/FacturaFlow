#!/usr/bin/env bash
# Crea una base y un usuario por servicio (patrón Database per Service, ADR 0003).
#
# La imagen oficial de PostgreSQL lo ejecuta SOLA la primera vez, cuando el volumen de datos está vacío.
# Es IDEMPOTENTE: crea solo lo que falta (y no toca lo que ya existe, ni cambia contraseñas), así que
# también sirve para agregar un servicio nuevo a un volumen que ya tiene datos, sin borrarlos:
#   docker compose --env-file .env -f infra/docker-compose.yml up -d postgres   # recibe la contraseña nueva
#   docker compose --env-file .env -f infra/docker-compose.yml exec postgres \
#       bash /docker-entrypoint-initdb.d/01-create-service-databases.sh
#
# Las contraseñas llegan por variables de entorno (desde el .env de la raíz); nunca se escriben aquí ni se
# imprimen (psql no repite las sentencias).
set -euo pipefail

# create_service_db <base> <usuario> <contraseña>
# El usuario queda como dueño de su base (puede crear tablas con Flyway) y se revoca el acceso público,
# así que ningún otro usuario de servicio puede conectarse.
create_service_db() {
  local db="$1" user="$2" password="$3"
  if [[ -z "$password" ]]; then
    echo "init: falta la contraseña para $user" >&2
    exit 1
  fi
  echo "init: base $db y usuario $user (se crean solo si faltan)"
  # Variables de psql (:'x' literal, :"x" identificador) y format(%I, %L) => sin inyección SQL.
  # \gexec ejecuta la sentencia generada solo si el SELECT devolvió una fila (es decir, si falta).
  psql -v ON_ERROR_STOP=1 --quiet \
       --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v usr="$user" -v pwd="$password" <<'SQL'
SELECT format('CREATE ROLE %I WITH LOGIN PASSWORD %L', :'usr', :'pwd')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'usr') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db', :'usr')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db') \gexec
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"usr";
SQL
}

# --- Un bloque por servicio. Para un servicio nuevo, copia la línea y
# --- agrega su variable de contraseña al .env, .env.example y docker-compose.yml.
create_service_db invoicing_db invoicing_user "${INVOICING_DB_PASSWORD:-}"
create_service_db catalog_db   catalog_user   "${CATALOG_DB_PASSWORD:-}"
create_service_db sales_db     sales_user     "${SALES_DB_PASSWORD:-}"
# No es un servicio de FacturaFlow, pero sigue la misma regla: Keycloak tiene su propia base (ADR 0017).
create_service_db keycloak_db  keycloak_user  "${KEYCLOAK_DB_PASSWORD:-}"
