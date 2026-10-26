package com.facturaflow.sales.infrastructure.adapter.in.messaging;

/** Qué hacer con un mensaje ya procesado (ADR 0015). {@code referenceCode} puede ser nulo si no se pudo leer. */
sealed interface InvoiceResultOutcome {

    /** Aplicado, duplicado o ignorado (ver {@code RecordInvoiceResultOutcome}): ack, sin reintentos. */
    record Completed(String referenceCode, ConsumerState.Result result, String detail) implements InvoiceResultOutcome {
    }

    /** Falla técnica (conflicto de versión, base caída…): esperar y reintentar; agotados, a la DLQ. */
    record RetryLater(String referenceCode, String reason) implements InvoiceResultOutcome {
    }

    /** Mensaje inválido: directo a la DLQ. */
    record Invalid(String referenceCode, String reason) implements InvoiceResultOutcome {
    }
}
