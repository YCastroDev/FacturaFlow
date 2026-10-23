package com.facturaflow.catalog.service;

import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.company;
import static com.facturaflow.catalog.model.CatalogFixtures.nit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.CustomerDocument;
import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.repository.CustomerRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class CustomerServiceTest {

    private final CustomerRepository repository = mock(CustomerRepository.class);
    private final CustomerService service = new CustomerService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Customer stored(long version) {
        Customer customer = new Customer(nit(), company(), NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(customer, "version", version);
        return customer;
    }

    @Test
    @DisplayName("crear un cliente con un documento que ya existe es un conflicto")
    void createDuplicateDocument() {
        given(repository.findByDocumentTypeAndDocumentNumber(DocumentType.NIT, "890903938"))
                .willReturn(Optional.of(stored(0)));

        assertThatThrownBy(() -> service.create(nit(), company()))
                .isInstanceOf(DuplicateCustomerDocumentException.class).hasMessageContaining("NIT 890903938");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("la carrera entre dos creaciones con el mismo documento también es 409")
    void createRaceOnUniqueConstraint() {
        given(repository.findByDocumentTypeAndDocumentNumber(DocumentType.NIT, "890903938")).willReturn(Optional.empty());
        given(repository.saveAndFlush(any(Customer.class))).willThrow(new DataIntegrityViolationException("dup",
                new ConstraintViolationException("dup", new SQLException("23505"), "uq_customers_document")));

        assertThatThrownBy(() -> service.create(nit(), company())).isInstanceOf(DuplicateCustomerDocumentException.class);
    }

    @Test
    @DisplayName("buscar por documento normaliza el número igual que al guardar")
    void getByDocumentNormalizes() {
        Customer customer = new Customer(CustomerDocument.of(DocumentType.PASSPORT, "AB123456"), company(), NOW);
        given(repository.findByDocumentTypeAndDocumentNumber(DocumentType.PASSPORT, "AB123456"))
                .willReturn(Optional.of(customer));

        assertThat(service.getByDocument(DocumentType.PASSPORT, " ab123456 ")).isSameAs(customer);
        assertThatThrownBy(() -> service.getByDocument(DocumentType.NIT, "1"))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    @DisplayName("actualizar con una versión vieja es 412 y no modifica nada")
    void updateWithStaleVersion() {
        Customer customer = stored(2);
        given(repository.findById(customer.id())).willReturn(Optional.of(customer));

        assertThatThrownBy(() -> service.update(customer.id(), 1, nit(), company("Otro S.A.S.")))
                .isInstanceOf(StaleVersionException.class).hasMessageContaining("If-Match");
        assertThat(customer.profile()).isEqualTo(company());
    }

    @Test
    @DisplayName("actualizar con la versión actual guarda; reactivar uno activo no escribe")
    void updateAndIdempotentActivate() {
        Customer customer = stored(2);
        given(repository.findById(customer.id())).willReturn(Optional.of(customer));

        service.update(customer.id(), 2, nit(), company("Otro S.A.S."));
        service.activate(customer.id(), null);

        assertThat(customer.profile().companyName()).isEqualTo("Otro S.A.S.");
        verify(repository).saveAndFlush(customer);   // solo la actualización
    }
}
