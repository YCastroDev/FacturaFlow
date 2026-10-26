package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;

import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import java.util.UUID;

/**
 * Qué hacer con un mensaje ya procesado (ADR 0014). Sellado: el listener decide con un {@code switch}
 * exhaustivo. {@code referenceCode} puede ser nulo si el mensaje ni siquiera se pudo leer.
 */
sealed interface SaleConfirmedOutcome {

    /** Resultado de negocio (ISSUED o REJECTED, nuevo o ya existente): ack, sin reintentos. */
    record Completed(UUID invoiceId, String referenceCode, InvoiceStatus status, boolean duplicate)
            implements SaleConfirmedOutcome {
    }

    /** Falla técnica: reintentar más tarde (cola de espera) o, agotados los intentos, a la DLQ. */
    record RetryLater(String referenceCode, String reason) implements SaleConfirmedOutcome {
    }

    /** Mensaje inválido (venenoso): directo a la DLQ, porque reintentarlo daría el mismo resultado. */
    record Invalid(String referenceCode, String reason) implements SaleConfirmedOutcome {
    }
}
