package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.domain.model.CreditNote;
import java.util.Objects;

/** Una nota crédito tal como está guardada, junto con la huella de la petición que la creó. */
public record StoredCreditNote(CreditNote creditNote, RequestHash requestHash) {

    public StoredCreditNote {
        Objects.requireNonNull(creditNote, "creditNote");
        Objects.requireNonNull(requestHash, "requestHash");
    }
}
