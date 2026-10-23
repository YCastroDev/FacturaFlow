package com.facturaflow.invoicing.application.port.out;

import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import java.util.Objects;

/**
 * Respuesta de NEGOCIO del proveedor a una solicitud de emisión (de una factura o de una nota
 * crédito): el documento quedó emitido o fue rechazado. Ambas son respuestas válidas. Las fallas
 * técnicas (proveedor caído, timeout) no son un resultado: son
 * {@code ElectronicInvoiceProviderUnavailableException}.
 *
 * <p>Al ser {@code sealed}, un {@code switch} sobre el resultado no necesita {@code default}
 * y el compilador avisa si algún día aparece un caso nuevo.
 */
public sealed interface IssuanceResult {

    record Issued(ElectronicDocument document) implements IssuanceResult {

        public Issued {
            Objects.requireNonNull(document, "document");
        }
    }

    record Rejected(String reason) implements IssuanceResult {

        public Rejected {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Un rechazo del proveedor requiere un motivo.");
            }
        }
    }
}
