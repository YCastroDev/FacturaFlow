package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.VALID_CUFE;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.electronicDocument;
import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ElectronicInvoiceProviderUnavailableException;
import com.facturaflow.invoicing.application.exception.InvoiceAlreadyRejectedException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.in.IssueInvoiceCommand;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.model.ElectronicDocument;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.Money;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IssueInvoiceServiceTest {

    private static final ElectronicDocument DOCUMENT = electronicDocument("119000.00");
    private static final ElectronicDocument OTHER_DOCUMENT = new ElectronicDocument("SETP990000002", VALID_CUFE,
            URI.create("https://dian.example/qr?key=2"), URI.create("https://sandbox.example/documents/2"),
            Instant.parse("2026-10-20T15:31:00Z"),
            Money.of("119000.00"));

    private final InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    private final StubElectronicInvoiceProvider provider = new StubElectronicInvoiceProvider();
    private final IssueInvoiceService service = new IssueInvoiceService(repository, provider);
    private final Invoice pending = repository.saveNew(pendingInvoice(), new RequestHash("a".repeat(64)));

    private static StubElectronicInvoiceProvider issuing(ElectronicDocument document) {
        StubElectronicInvoiceProvider stub = new StubElectronicInvoiceProvider();
        stub.willIssue(document);
        return stub;
    }

    private static StubElectronicInvoiceProvider rejecting(String reason) {
        StubElectronicInvoiceProvider stub = new StubElectronicInvoiceProvider();
        stub.willReject(reason);
        return stub;
    }

    private Invoice issue() {
        return service.issue(new IssueInvoiceCommand(pending.id().value()));
    }

    private Invoice stored() {
        return repository.findById(pending.id()).orElseThrow();
    }

    @Test
    @DisplayName("si el proveedor la emite, la factura queda ISSUED con su documento electrónico y se guarda")
    void issued() {
        provider.willIssue(DOCUMENT);

        Invoice result = issue();

        assertThat(result.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(result.electronicDocument()).contains(DOCUMENT);
        assertThat(result.version()).contains(1L);
        assertThat(stored().status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("si el proveedor la rechaza, la factura queda REJECTED con el motivo: es un resultado, no un error")
    void rejected() {
        provider.willReject("Regla FAD06: NIT del adquirente inválido");

        Invoice result = issue();

        assertThat(result.status()).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(result.rejectionReason()).hasValue("Regla FAD06: NIT del adquirente inválido");
        assertThat(stored().status()).isEqualTo(InvoiceStatus.REJECTED);
    }

    @Test
    @DisplayName("si el proveedor falla técnicamente, la factura sigue PENDING sin cambios y se puede reintentar")
    void providerUnavailable() {
        provider.willBeUnavailable();

        assertThatThrownBy(this::issue).isInstanceOf(ElectronicInvoiceProviderUnavailableException.class);
        assertThat(stored().status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(stored().version()).contains(0L);
        assertThat(repository.saveCount()).isZero();

        provider.willIssue(DOCUMENT);
        assertThat(issue().status()).isEqualTo(InvoiceStatus.ISSUED);
    }

    @Test
    @DisplayName("emitir una factura ya ISSUED la devuelve sin volver a llamar al proveedor")
    void alreadyIssuedIsReturnedWithoutCallingProvider() {
        provider.willIssue(DOCUMENT);
        issue();

        Invoice again = issue();

        assertThat(again.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(again.electronicDocument()).contains(DOCUMENT);
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("emitir una factura REJECTED es un conflicto y no llama al proveedor")
    void alreadyRejectedIsConflict() {
        provider.willReject("motivo");
        issue();

        assertThatThrownBy(this::issue)
                .isInstanceOf(InvoiceAlreadyRejectedException.class)
                .hasMessageContaining(pending.id().toString());
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    @DisplayName("emitir una factura inexistente indica que no existe")
    void unknownInvoice() {
        assertThatThrownBy(() -> service.issue(new IssueInvoiceCommand(UUID.randomUUID())))
                .isInstanceOf(InvoiceNotFoundException.class);
        assertThat(provider.calls()).isZero();
    }

    @Test
    @DisplayName("si otra emisión simultánea la dejó ISSUED, se devuelve la guardada y se descarta el documento propio")
    void concurrentIssueEndingIssuedReturnsStored() {
        IssueInvoiceService other = new IssueInvoiceService(repository, issuing(OTHER_DOCUMENT));
        provider.willIssue(DOCUMENT);
        provider.whileCalling(() -> other.issue(new IssueInvoiceCommand(pending.id().value())));

        Invoice result = issue();

        assertThat(result.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(result.electronicDocument()).contains(OTHER_DOCUMENT);
        assertThat(stored().electronicDocument()).contains(OTHER_DOCUMENT);
    }

    @Test
    @DisplayName("si otra emisión simultánea la dejó REJECTED mientras a nosotros nos la emitieron, es un conflicto")
    void concurrentIssueEndingRejectedIsConflict() {
        IssueInvoiceService other = new IssueInvoiceService(repository, rejecting("rechazo de la otra petición"));
        provider.willIssue(DOCUMENT);
        provider.whileCalling(() -> other.issue(new IssueInvoiceCommand(pending.id().value())));

        assertThatThrownBy(this::issue).isInstanceOf(InvoiceConcurrentModificationException.class);
        assertThat(stored().status()).isEqualTo(InvoiceStatus.REJECTED);
    }

    @Test
    @DisplayName("si a nosotros nos la rechazaron pero otra emisión simultánea la dejó ISSUED, es un conflicto")
    void ourRejectionLosingToIssuedIsConflict() {
        IssueInvoiceService other = new IssueInvoiceService(repository, issuing(OTHER_DOCUMENT));
        provider.willReject("rechazo propio");
        provider.whileCalling(() -> other.issue(new IssueInvoiceCommand(pending.id().value())));

        assertThatThrownBy(this::issue).isInstanceOf(InvoiceConcurrentModificationException.class);
        assertThat(stored().electronicDocument()).contains(OTHER_DOCUMENT);
    }
}
