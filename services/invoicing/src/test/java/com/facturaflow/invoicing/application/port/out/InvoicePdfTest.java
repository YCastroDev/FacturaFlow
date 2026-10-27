package com.facturaflow.invoicing.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InvoicePdfTest {

    @Test
    @DisplayName("dos PDF con el mismo nombre y el mismo contenido son iguales aunque sean arreglos distintos")
    void equalityComparesContent() {
        InvoicePdf pdf = new InvoicePdf("FV100.pdf", new byte[] {1, 2, 3});

        assertThat(pdf).isEqualTo(new InvoicePdf("FV100.pdf", new byte[] {1, 2, 3}))
                .hasSameHashCodeAs(new InvoicePdf("FV100.pdf", new byte[] {1, 2, 3}))
                .isNotEqualTo(new InvoicePdf("FV100.pdf", new byte[] {1, 2, 4}))
                .isNotEqualTo(new InvoicePdf("FV101.pdf", new byte[] {1, 2, 3}));
    }

    @Test
    @DisplayName("la representación en texto muestra el tamaño del PDF, no su contenido")
    void toStringShowsOnlySize() {
        assertThat(new InvoicePdf("FV100.pdf", new byte[] {1, 2, 3}))
                .hasToString("InvoicePdf[fileName=FV100.pdf, content=3 bytes]");
    }
}
