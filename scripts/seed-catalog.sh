#!/usr/bin/env bash
# Siembra en catalog los clientes y productos que usan los ejemplos de Swagger de sales (ADR 0012).
# Usa la API pública de catalog (pasa por todas sus validaciones; no toca la base) y es IDEMPOTENTE:
# un 409 significa "ya existe" y se toma como éxito. No borra ni modifica nada existente.
#
# Uso: ./scripts/seed-catalog.sh [URL de catalog]     (por defecto http://localhost:8082)
set -euo pipefail

catalog_url="${1:-http://localhost:8082}"

# create <recurso> <descripción> <json>
create() {
  local resource="$1" description="$2" body="$3" status
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$catalog_url/api/v1/$resource" \
    -H 'Content-Type: application/json' -d "$body") || status="sin respuesta"
  case "$status" in
    201) echo "  creado:    $description" ;;
    409) echo "  ya existe: $description" ;;
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
