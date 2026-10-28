#!/usr/bin/env bash
# Pide a Keycloak un access token del realm facturaflow para las demos desde la terminal (ADR 0017).
#
#   Usuario de demostración (password grant con el cliente facturaflow-cli, SOLO desarrollo):
#     ./scripts/keycloak-token.sh vendedor.demo            # admin.demo | vendedor.demo | contador.demo
#   Servicio (client credentials con el cliente confidencial sales-service):
#     ./scripts/keycloak-token.sh sales-service
#
# La contraseña (o el secreto) se pide sin eco, o se toma de KC_PASSWORD (o KC_CLIENT_SECRET). Nunca va en los
# argumentos de curl (no aparece en "ps"): se le pasa por la entrada estándar. El script no lee el .env.
#
# Imprime SOLO el access token, para usarlo así:
#   TOKEN=$(./scripts/keycloak-token.sh vendedor.demo)
#   curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/sales/...
# Los tokens duran 5 minutos (accessTokenLifespan del realm): al vencer, el gateway y los servicios responden 401.
# Con --claims imprime en cambio el contenido del token (roles, scope, issuer, audiencia, vencimiento) para inspeccionarlo.
#
# Variables opcionales: KEYCLOAK_URL (por defecto http://localhost:8180).
set -euo pipefail

usage() {
  echo "Uso: $0 [--claims] <admin.demo|vendedor.demo|contador.demo|sales-service>" >&2
  exit 1
}

show_claims=false
if [[ "${1:-}" == "--claims" ]]; then
  show_claims=true
  shift
fi
[[ $# -eq 1 ]] || usage
subject="$1"

token_url="${KEYCLOAK_URL:-http://localhost:8180}/realms/facturaflow/protocol/openid-connect/token"

# read_secret <variable de entorno> <pregunta>
read_secret() {
  local from_env="${!1:-}"
  if [[ -n "$from_env" ]]; then
    printf '%s' "$from_env"
    return
  fi
  local value
  read -r -s -p "$2" value </dev/tty
  echo >&2
  printf '%s' "$value"
}

case "$subject" in
  sales-service)
    secret="$(read_secret KC_CLIENT_SECRET "Secreto de sales-service (SALES_SERVICE_CLIENT_SECRET del .env): ")"
    response="$(printf '%s' "$secret" | curl -sS "$token_url" \
      -d grant_type=client_credentials -d client_id=sales-service --data-urlencode "client_secret@-")"
    ;;
  admin.demo | vendedor.demo | contador.demo)
    password="$(read_secret KC_PASSWORD "Contraseña de $subject (KC_DEMO_*_PASSWORD del .env): ")"
    response="$(printf '%s' "$password" | curl -sS "$token_url" \
      -d grant_type=password -d client_id=facturaflow-cli --data-urlencode "username=$subject" \
      --data-urlencode "password@-")"
    ;;
  *)
    usage
    ;;
esac

# El JSON se procesa con python3 (sin jq). Ante un error, solo el código y la descripción de Keycloak.
python3 -I -c '
import base64, json, sys
show_claims = sys.argv[1] == "true"
try:
    response = json.loads(sys.stdin.read())
except ValueError:
    sys.exit("Keycloak no respondió con JSON (¿está arriba en $KEYCLOAK_URL?)")
token = response.get("access_token")
if not token:
    sys.exit("Keycloak rechazó la petición: %s (%s)" % (response.get("error"), response.get("error_description")))
if not show_claims:
    print(token)
    sys.exit()
payload = token.split(".")[1]
claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
keys = ("iss", "aud", "azp", "preferred_username", "client_id", "scope", "realm_access", "exp")
print(json.dumps({k: claims[k] for k in keys if k in claims}, indent=2, ensure_ascii=False))
' "$show_claims" <<<"$response"
