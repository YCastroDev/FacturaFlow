package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.exception.InvoiceNotIssuedException;
import com.facturaflow.invoicing.application.port.out.InvoicePdf;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetInvoicePdfServiceTest {

    private static final InvoicePdf PDF = new InvoicePdf("SETP990000001", "%PDF-1.4".getBytes(StandardCharsets.US_ASCII));

    private final InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    private final StubElectronicInvoiceProvider provider = new StubElectronicInvoiceProvider();
    private final GetInvoicePdfService service = new GetInvoicePdfService(repository, provider);
    private final Invoice pending = repository.saveNew(pendingInvoice(), new RequestHash("a".repeat(64)));

    private Invoice issued() {
        pending.markAsIssued(electronicDocument("119000.00"));
        return repository.save(pending);
    }

    @Test
    @DisplayName("el PDF de una factura emitida se pide al proveedor")
    void downloadsPdfOfIssuedInvoice() {
        Invoice issued = issued();
        provider.willReturnPdf(PDF);

        assertThat(service.pdf(issued.id().value())).isSameAs(PDF);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("una factura que no está emitida no tiene PDF y no se consulta al proveedor")
    void pendingInvoiceHasNoPdf() {
        assertThatThrownBy(() -> service.pdf(pending.id().value()))
                .isInstanceOf(InvoiceNotIssuedException.class)
                .hasMessageContaining("PENDING");
        assertThat(provider.calls()).isZero();
    }

    @Test
    @DisplayName("pedir el PDF de una factura inexistente indica que no existe")
    void unknownInvoiceIsNotFound() {
        assertThatThrownBy(() -> service.pdf(UUID.randomUUID())).isInstanceOf(InvoiceNotFoundException.class);
        assertThat(provider.calls()).isZero();
    }

    @Test
    @DisplayName("si el proveedor no responde, la falla técnica se propaga")
    void providerUnavailable() {
        Invoice issued = issued();
        provider.pdfWillBeUnavailable();

        assertThatThrownBy(() -> service.pdf(issued.id().value()))
                .isInstanceOf(ElectronicInvoiceProviderUnavailableException.class);
    }

    @Test
    @DisplayName("el nombre del archivo se normaliza a caracteres seguros y termina en .pdf")
    void normalizesFileName() {
        byte[] content = {1};

        assertThat(new InvoicePdf("fv100\"; x=1", content).fileName()).isEqualTo("fv100___x_1.pdf");
        assertThat(new InvoicePdf("SETP1.PDF", content).fileName()).isEqualTo("SETP1.PDF");
        assertThatThrownBy(() -> new InvoicePdf(" ", content)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvoicePdf("a", new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }
}
