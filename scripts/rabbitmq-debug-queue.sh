#!/usr/bin/env bash
# Cola de DEPURACIÓN para ver los eventos de sales mientras no hay consumidores (ADR 0013, Fase 1).
#
# Sin una cola enlazada, el exchange sales.events descarta cada mensaje (RabbitMQ igual lo confirma). Esta
# cola se enlaza a sales.events con '#' (todos los eventos) y es TEMPORAL por diseño:
#   - x-expires 1 h: RabbitMQ la borra sola tras una hora sin uso;
#   - x-max-length 100: nunca acumula más de 100 mensajes (descarta los más viejos).
# Durable solo porque RabbitMQ 4.3 ya no admite colas transitorias no exclusivas.
#
# Uso (desde la raíz, con el entorno levantado):
#   ./scripts/rabbitmq-debug-queue.sh create   # crea la cola y su enlace (idempotente)
#   ./scripts/rabbitmq-debug-queue.sh peek     # muestra hasta 5 mensajes SIN sacarlos de la cola
#   ./scripts/rabbitmq-debug-queue.sh delete   # la borra (idempotente)
# También se ve en la consola: http://localhost:15672 → Queues and Streams → debug.sales-events → Get messages.
#
# Se ejecuta DENTRO del contenedor de RabbitMQ con el usuario administrador de su entorno: este script no
# lee el .env ni muestra credenciales.
set -euo pipefail

QUEUE="debug.sales-events"
EXCHANGE="sales.events"
VHOST="facturaflow"

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
compose=(docker compose --env-file "$repo_root/.env" -f "$repo_root/infra/docker-compose.yml")

# rabbitmqadmin <argumentos...>: lo ejecuta en el contenedor; las credenciales viajan como variables de
# entorno de ese proceso (las que rabbitmqadmin lee solo), nunca como argumentos.
rabbitmqadmin() {
  "${compose[@]}" exec -T rabbitmq sh -c \
    'RABBITMQADMIN_USERNAME="$RABBITMQ_DEFAULT_USER" RABBITMQADMIN_PASSWORD="$RABBITMQ_DEFAULT_PASS" \
     exec rabbitmqadmin --non-interactive --vhost "$0" "$@"' "$VHOST" "$@"
}

case "${1:-}" in
  create)
    # El exchange lo declara sales al conectarse; se declara aquí también (mismas propiedades, idempotente)
    # para que el enlace funcione aunque sales aún no haya publicado nada.
    rabbitmqadmin declare exchange --name "$EXCHANGE" --type topic --durable true --auto-delete false
    rabbitmqadmin declare queue --name "$QUEUE" --durable true --auto-delete false \
      --arguments '{"x-expires": 3600000, "x-max-length": 100}'
    rabbitmqadmin declare binding --source "$EXCHANGE" --destination-type queue --destination "$QUEUE" \
      --routing-key '#'
    echo "Cola $QUEUE enlazada a $EXCHANGE con '#'. Se borra sola tras 1 h sin uso."
    ;;
  peek)
    rabbitmqadmin get messages --queue "$QUEUE" --count 5 --ack-mode ack_requeue_true
    ;;
  delete)
    rabbitmqadmin delete queue --name "$QUEUE" --idempotently
    echo "Cola $QUEUE borrada."
    ;;
  *)
    echo "Uso: $0 create|peek|delete" >&2
    exit 1
    ;;
esac
