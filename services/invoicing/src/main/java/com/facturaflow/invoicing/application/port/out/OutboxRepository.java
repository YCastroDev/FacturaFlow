package com.facturaflow.invoicing.application.port.out;

import java.util.List;
import java.util.function.Function;

/**
 * Puerto de salida: el outbox de eventos (ADR 0015). Los eventos ENTRAN con {@link InvoiceRepository#save}, en
 * la misma transacción que el cambio de estado de la factura, o con {@link InvoiceEventStore#append}; aquí solo se
 * publican y se consultan.
 */
public interface OutboxRepository {

    /**
     * En UNA transacción: bloquea hasta {@code batchSize} eventos pendientes, del más antiguo al más
     * nuevo, saltándose los que otro publicador ya tiene bloqueados ({@code FOR UPDATE SKIP LOCKED});
     * se los entrega a {@code publisher}; marca como publicados SOLO los que este confirmó, y a los demás
     * les suma un intento con el motivo de la falla. Así dos publicadores simultáneos nunca toman el
     * mismo evento.
     *
     * @return los mensajes del lote y lo que el broker confirmó (lote vacío si no había pendientes)
     */
    OutboxBatch publishPending(int batchSize, Function<List<OutboxMessage>, PublicationResult> publisher);

    OutboxStatus status();
}
