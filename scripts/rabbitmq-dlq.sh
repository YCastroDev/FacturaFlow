#!/usr/bin/env bash
# Operación de la DLQ del consumidor de SaleConfirmed en invoicing (ADR 0014).
#
# Uso (desde la raíz, con el entorno levantado):
#   ./scripts/rabbitmq-dlq.sh status         # mensajes en la cola, sus esperas y su DLQ
#   ./scripts/rabbitmq-dlq.sh peek           # hasta 5 mensajes de la DLQ (con su motivo) SIN sacarlos
#   ./scripts/rabbitmq-dlq.sh requeue        # mueve TODOS los mensajes de la DLQ a la cola para reprocesarlos
#   ./scripts/rabbitmq-dlq.sh purge --yes    # BORRA los mensajes de la DLQ (no se pueden recuperar)
#
# requeue usa un shovel dinámico de RabbitMQ (plugin rabbitmq_shovel): mueve los mensajes que hay en ese
# momento, con confirmación (ack-mode on-confirm: ninguno se pierde), y se borra solo (src-delete-after
# queue-length). Es lo mismo que el botón "Move messages" de la consola, en la página de la DLQ. Los mensajes
# vuelven sin la cabecera x-attempt: el consumidor les da todos sus intentos otra vez. Reprocesar solo tiene
# sentido tras corregir la causa (proveedor de vuelta, datos corregidos); si no, vuelven a la DLQ.
#
# Se ejecuta DENTRO del contenedor de RabbitMQ con el usuario administrador de su entorno: este script no lee
# el .env ni muestra credenciales.
set -euo pipefail

QUEUE="invoicing.sale-confirmed"
DLQ="$QUEUE.dlq"
VHOST="facturaflow"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose=(docker compose --env-file "$repo_root/.env" -f "$repo_root/infra/docker-compose.yml")

# rabbitmqadmin <argumentos...>: las credenciales viajan como variables de entorno del proceso, no como argumentos.
rabbitmqadmin() {
  "${compose[@]}" exec -T rabbitmq sh -c \
    'RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS" \
     exec rabbitmqadmin --non-interactive --vhost "$0" "$@"' "$VHOST" "$@"
}

case "${1:-}" in
  status)
    "${compose[@]}" exec -T rabbitmq rabbitmqctl -q list_queues -p "$VHOST" name messages consumers \
      | grep -E "^(name|$QUEUE)"
    ;;
  peek)
    rabbitmqadmin get messages --queue "$DLQ" --count 5 --ack-mode ack_requeue_true
    ;;
  requeue)
    name="requeue-$(date +%s)"
    rabbitmqadmin declare parameter --component shovel --name "$name" --value "$(cat <<JSON
{"src-protocol": "amqp091", "src-uri": "amqp:///$VHOST", "src-queue": "$DLQ", "src-delete-after": "queue-length",
 "dest-protocol": "amqp091", "dest-uri": "amqp:///$VHOST", "dest-queue": "$QUEUE", "ack-mode": "on-confirm"}
JSON
)"
    echo "Shovel $name: moviendo los mensajes de $DLQ a $QUEUE (se borra solo al terminar)."
    ;;
  purge)
    if [[ "${2:-}" != "--yes" ]]; then
      echo "purge BORRA los mensajes de $DLQ para siempre. Repite con: $0 purge --yes" >&2
      exit 1
    fi
    rabbitmqadmin purge queue --name "$DLQ"
    echo "DLQ $DLQ vaciada."
    ;;
  *)
    echo "Uso: $0 status|peek|requeue|purge --yes" >&2
    exit 1
    ;;
esac
