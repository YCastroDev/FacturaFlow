#!/usr/bin/env bash
# Siembra en catalog los clientes y productos que usan los ejemplos de Swagger de sales (ADR 0012).
# Usa la API pública de catalog (pasa por todas sus validaciones; no toca la base) y es IDEMPOTENTE:
# un 409 significa "ya existe" y se toma como éxito. No borra ni modifica nada existente.
#
# Crear productos exige el rol admin (ADR 0018): el token sale de FACTURAFLOW_TOKEN o, si no está, de
# ./scripts/keycloak-token.sh admin.demo (pide la contraseña sin eco, o la toma de KC_PASSWORD). El token nunca va en
# los argumentos de curl (no aparece en "ps"): la cabecera se le pasa por un descriptor de archivo.
#
# Uso: ./scripts/seed-catalog.sh [URL de catalog]     (por defecto http://localhost:8082)
#   ej: FACTURAFLOW_TOKEN=$(./scripts/keycloak-token.sh admin.demo) ./scripts/seed-catalog.sh
set -euo pipefail

catalog_url="${1:-http://localhost:8082}"
token="${FACTURAFLOW_TOKEN:-}"
if [[ -z "$token" ]]; then
  token="$("$(dirname "$0")/keycloak-token.sh" admin.demo)"
fi

# create <recurso> <descripción> <json>
create() {
  local resource="$1" description="$2" body="$3" status
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$catalog_url/api/v1/$resource" \
    -H 'Content-Type: application/json' -H @<(printf 'Authorization: Bearer %s\n' "$token") \
    -d "$body") || status="sin respuesta"
  case "$status" in
    201) echo "  creado:    $description" ;;
    409) echo "  ya existe: $description" ;;
    401) echo "  ERROR (401): token ausente, vencido o inválido (los tokens duran 5 min)" >&2; exit 1 ;;
    403) echo "  ERROR (403): el token no es de admin.demo (crear productos exige el rol admin)" >&2; exit 1 ;;
    *)   echo "  ERROR ($status): $description" >&2; exit 1 ;;
  esac
}

echo "Sembrando catalog en $catalog_url"

echo "Clientes:"
create customers "NIT 890903938 (Cliente Ejemplo S.A.S., gran contribuyente)" '{
  "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
  "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
  "taxScheme": "IVA", "fiscalResponsibilities": ["LARGE_TAXPAYER", "SELF_WITHHOLDER"],
  "municipalityCode": "05001", "address": "Carrera 48 # 10-45", "phone": "+57 604 444 5555",
  "email": "facturacion@cliente.example" }'
create customers "Cédula 1020304050 (Ana María Pérez, no responsable de IVA)" '{
  "documentType": "CITIZENSHIP_CARD", "documentNumber": "1020304050",
  "personType": "NATURAL_PERSON", "names": "Ana María Pérez",
  "taxScheme": "NOT_APPLICABLE", "fiscalResponsibilities": ["NOT_RESPONSIBLE"],
  "municipalityCode": "11001", "email": "ana.perez@correo.example" }'

echo "Productos:"
create products "CAFE-500 (IVA 5 %)" '{
  "code": "CAFE-500", "name": "Café molido 500 g", "unitPrice": 18500, "unit": "UNIT",
  "taxes": [ { "type": "IVA", "rate": 5 } ] }'
create products "ARROZ-KG (excluido de IVA)" '{
  "code": "ARROZ-KG", "name": "Arroz blanco", "unitPrice": 4200, "unit": "KILOGRAM",
  "ivaExcluded": true, "taxes": [] }'
create products "CERVEZA-330 (IVA 19 % + INC 8 %)" '{
  "code": "CERVEZA-330", "name": "Cerveza 330 ml", "unitPrice": 3500, "unit": "UNIT",
  "taxes": [ { "type": "IVA", "rate": 19 }, { "type": "INC", "rate": 8 } ] }'

echo "Listo."
