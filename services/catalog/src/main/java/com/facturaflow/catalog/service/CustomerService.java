package com.facturaflow.catalog.service;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.CustomerDocument;
import com.facturaflow.catalog.model.CustomerProfile;
import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.repository.CatalogSearch;
import com.facturaflow.catalog.repository.CustomerRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Casos de uso de los clientes; las mismas decisiones que {@link ProductService} (ADR 0011). */
@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    /** 409 si ya existe un cliente con ese tipo y número de documento, incluso con dos peticiones a la vez. */
    @Transactional
    public Customer create(CustomerDocument document, CustomerProfile profile) {
        if (customers.findByDocumentTypeAndDocumentNumber(document.type(), document.number()).isPresent()) {
            throw new DuplicateCustomerDocumentException(document);
        }
        Customer customer = new Customer(document, profile, clock.instant());
        try {
            return customers.saveAndFlush(customer);
        } catch (DataIntegrityViolationException e) {
            if (UniqueViolations.isViolationOf(e, CustomerRepository.UNIQUE_DOCUMENT)) {
                throw new DuplicateCustomerDocumentException(document);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException("No existe un cliente con id " + id + "."));
    }

    /** El número se normaliza igual que al guardar (sin espacios, en mayúsculas). */
    @Transactional(readOnly = true)
    public Customer getByDocument(DocumentType type, String number) {
        String normalized = CustomerDocument.normalizeNumber(number);
        return customers.findByDocumentTypeAndDocumentNumber(type, normalized)
                .orElseThrow(() -> new CustomerNotFoundException("No existe un cliente con "
                        + type.description() + " " + normalized + "."));
    }

    /** {@code text} y {@code active} nulos = sin ese filtro. */
    @Transactional(readOnly = true)
    public Page<Customer> search(String text, Boolean active, CatalogPage page) {
        return customers.findAll(CatalogSearch.customers(text, active), PageRequest.of(page.number(), page.size()));
    }

    /** Reemplaza los datos editables si {@code expectedVersion} sigue siendo la actual; si no, 412. */
    @Transactional
    public Customer update(UUID id, long expectedVersion, CustomerDocument document, CustomerProfile profile) {
        Customer customer = get(id);
        if (customer.version() != expectedVersion) {
            throw new StaleVersionException(subject(customer));
        }
        if (customer.update(document, profile, clock.instant())) {
            flush(customer, true);
        }
        return customer;
    }

    /** Idempotente. {@code expectedVersion} es opcional: nulo = sin If-Match. */
    @Transactional
    public Customer deactivate(UUID id, Long expectedVersion) {
        Customer customer = getChecked(id, expectedVersion);
        if (customer.deactivate(clock.instant())) {
            flush(customer, expectedVersion != null);
        }
        return customer;
    }

    /** Idempotente. {@code expectedVersion} es opcional: nulo = sin If-Match. */
    @Transactional
    public Customer activate(UUID id, Long expectedVersion) {
        Customer customer = getChecked(id, expectedVersion);
        if (customer.activate(clock.instant())) {
            flush(customer, expectedVersion != null);
        }
        return customer;
    }

    private Customer getChecked(UUID id, Long expectedVersion) {
        Customer customer = get(id);
        if (expectedVersion != null && !expectedVersion.equals(customer.version())) {
            throw new StaleVersionException(subject(customer));
        }
        return customer;
    }

    /** Ver {@code ProductService.flush}: bloqueo optimista traducido dentro de la transacción. */
    private void flush(Customer customer, boolean withPrecondition) {
        try {
            customers.saveAndFlush(customer);
        } catch (OptimisticLockingFailureException e) {
            throw withPrecondition ? new StaleVersionException(subject(customer))
                    : new ConcurrentUpdateException(subject(customer));
        }
    }

    private static String subject(Customer customer) {
        CustomerDocument document = customer.document();
        return "El cliente con " + document.type().description() + " " + document.number();
    }
}
