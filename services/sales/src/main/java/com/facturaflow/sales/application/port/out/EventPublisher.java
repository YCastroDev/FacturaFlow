package com.facturaflow.sales.application.port.out;

import java.util.List;

/**
 * Puerto de salida: publica eventos en el broker (ADR 0013) con confirmación. Una implementación solo
 * informa como confirmado lo que el broker aceptó y persistió (publisher confirm con ack); un nack, un
 * timeout o un broker inalcanzable dejan el evento fuera de {@link PublicationResult#confirmed()}.
 *
 * <p>Que el broker confirme y aun así el evento se publique de nuevo (p. ej. se cae el servicio antes de
 * marcarlo) es posible: la entrega es "al menos una vez" y los consumidores descartan duplicados por
 * {@code eventId}.
 */
public interface EventPublisher {

    /** Publica en orden y espera las confirmaciones; nunca lanza por una falla del broker. */
    PublicationResult publish(List<OutboxMessage> messages);
}
