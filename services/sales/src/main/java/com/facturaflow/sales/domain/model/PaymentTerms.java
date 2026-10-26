package com.facturaflow.sales.domain.model;

import com.facturaflow.sales.domain.exception.InvalidPaymentTermsException;
import java.time.LocalDate;

/**
 * Forma de pago: de contado o a crédito. Al ser {@code sealed}, el compilador
 * conoce todos los casos y un {@code switch} sobre ella no necesita {@code default}.
 */
public sealed interface PaymentTerms {

    static PaymentTerms cash() {
        return new Cash();
    }

    static PaymentTerms credit(LocalDate dueDate) {
        return new Credit(dueDate);
    }

    record Cash() implements PaymentTerms {
    }

    /** La regla "vencimiento posterior a la fecha de la venta" la valida {@link Sale}, que conoce ambas fechas. */
    record Credit(LocalDate dueDate) implements PaymentTerms {

        public Credit {
            if (dueDate == null) {
                throw InvalidPaymentTermsException.missingDueDate();
            }
        }
    }
}
