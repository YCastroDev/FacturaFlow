package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.CreditNoteFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.CreditNoteReason;
import com.facturaflow.invoicing.domain.model.CreditedQuantity;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CreditNoteRequestHasherTest {

    private static final InvoiceId INVOICE = new InvoiceId(UUID.fromString("7f1c2a4e-0000-4000-8000-0000000000c1"));
    private static final List<CreditedQuantity> LINES = List.of(credit(1, "1"), credit(2, "0.5"));

    private static RequestHash base() {
        return CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN, LINES, "Devolución");
    }

    @Test
    @DisplayName("la huella es estable: el orden de las líneas y la escala de las cantidades no cambian nada")
    void stable() {
        RequestHash reordered = CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN,
                List.of(credit(2, "0.50"), credit(1, "1.00")), "Devolución");

        assertThat(reordered).isEqualTo(base());
        assertThat(CreditNoteRequestHasher.canonicalForm(INVOICE, CreditNoteReason.PARTIAL_RETURN, LINES, null))
                .startsWith("22:" + CreditNoteRequestHasher.FORMAT_VERSION + ";");
    }

    static Stream<Arguments> changes() {
        return Stream.of(
                Arguments.of("otra factura", CreditNoteRequestHasher.hash(InvoiceId.generate(),
                        CreditNoteReason.PARTIAL_RETURN, LINES, "Devolución")),
                Arguments.of("otro motivo", CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.TOTAL_ANNULMENT,
                        LINES, "Devolución")),
                Arguments.of("otra cantidad", CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN,
                        List.of(credit(1, "2"), credit(2, "0.5")), "Devolución")),
                Arguments.of("otra línea", CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN,
                        List.of(credit(1, "1")), "Devolución")),
                Arguments.of("otra observación", CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN,
                        LINES, "Otra")),
                Arguments.of("sin observación", CreditNoteRequestHasher.hash(INVOICE, CreditNoteReason.PARTIAL_RETURN,
                        LINES, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("changes")
    @DisplayName("cualquier campo de la petición cambia la huella")
    void everyFieldChangesTheHash(String description, RequestHash changed) {
        assertThat(changed).isNotEqualTo(base());
    }
}
