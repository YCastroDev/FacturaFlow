package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.domain.model.InvoiceFixtures.pendingInvoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.InvoiceNotFoundException;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.domain.exception.InvalidReferenceCodeException;
import com.facturaflow.invoicing.domain.model.Invoice;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetInvoiceServiceTest {

    private final InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    private final GetInvoiceService service = new GetInvoiceService(repository);
    private final Invoice stored = repository.saveNew(pendingInvoice(), new RequestHash("a".repeat(64)));

    @Test
    @DisplayName("una factura existente se consulta por su id")
    void findsById() {
        assertThat(service.byId(stored.id().value()).referenceCode()).isEqualTo(stored.referenceCode());
    }

    @Test
    @DisplayName("una factura existente se consulta por el código de referencia de su venta")
    void findsByReferenceCode() {
        assertThat(service.byReferenceCode(stored.referenceCode().value()).id()).isEqualTo(stored.id());
    }

    @Test
    @DisplayName("consultar un id inexistente indica que la factura no existe")
    void unknownIdIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.byId(unknown))
                .isInstanceOf(InvoiceNotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    @DisplayName("consultar un código inexistente indica que la factura no existe")
    void unknownReferenceCodeIsNotFound() {
        assertThatThrownBy(() -> service.byReferenceCode("SALE-404"))
                .isInstanceOf(InvoiceNotFoundException.class)
                .hasMessageContaining("SALE-404");
    }

    @Test
    @DisplayName("un código de referencia con formato inválido se rechaza por regla de negocio")
    void invalidReferenceCodeIsRejected() {
        assertThatThrownBy(() -> service.byReferenceCode("con espacios"))
                .isInstanceOf(InvalidReferenceCodeException.class);
    }
}
