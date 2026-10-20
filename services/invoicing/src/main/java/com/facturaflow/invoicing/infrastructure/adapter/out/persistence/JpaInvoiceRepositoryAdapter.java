package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.InvoiceRepository;
import com.facturaflow.invoicing.domain.model.Invoice;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: implementa {@link InvoiceRepository} con Spring Data JPA.
 * Cada {@code save} es una transacción que guarda el agregado completo. Ninguna
 * excepción de Spring o Hibernate sale de aquí: se traducen a las del puerto.
 */
@Component
class JpaInvoiceRepositoryAdapter implements InvoiceRepository {

    static final String REFERENCE_CODE_CONSTRAINT = "uk_invoices_reference_code";

    private final InvoiceJpaRepository jpaRepository;
    private final InvoicePersistenceMapper mapper;

    JpaInvoiceRepositoryAdapter(InvoiceJpaRepository jpaRepository, InvoicePersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Invoice save(Invoice invoice) {
        try {
            InvoiceEntity entity = invoice.version().isEmpty() ? insert(invoice) : update(invoice);
            // flush() DENTRO del try: si no, el UNIQUE o el conflicto de versión saltarían en el
            // commit, fuera de este método, y no podríamos traducirlos (ADR 0005).
            jpaRepository.flush();
            return mapper.toDomain(entity);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, REFERENCE_CODE_CONSTRAINT)) {
                throw new DuplicateReferenceCodeException(invoice.referenceCode().value(), e);
            }
            throw e;
        } catch (OptimisticLockingFailureException e) {
            throw new InvoiceConcurrentModificationException(invoice.id().value(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findById(InvoiceId id) {
        return jpaRepository.findWithLinesById(id.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findByReferenceCode(ReferenceCode referenceCode) {
        return jpaRepository.findWithLinesByReferenceCode(referenceCode.value()).map(mapper::toDomain);
    }

    private InvoiceEntity insert(Invoice invoice) {
        InvoiceEntity entity = mapper.toNewEntity(invoice);
        jpaRepository.save(entity);   // versión nula => persist; Hibernate la inicializa en 0
        return entity;
    }

    /**
     * Bloqueo optimista en dos niveles: (1) la versión que trae el agregado (la que se leyó)
     * debe ser la actual; (2) el UPDATE lleva "WHERE version = ?", que cubre una carrera
     * entre esta lectura y el flush. Se carga con el entity graph porque el agregado devuelto
     * necesita líneas e impuestos: sin él, cada línea dispararía su propia consulta (N+1).
     */
    private InvoiceEntity update(Invoice invoice) {
        InvoiceEntity entity = jpaRepository.findWithLinesById(invoice.id().value())
                .orElseThrow(() -> new InvoiceConcurrentModificationException(invoice.id().value(), null));
        if (!entity.version.equals(invoice.version().orElseThrow())) {
            throw new InvoiceConcurrentModificationException(invoice.id().value(), null);
        }
        mapper.applyMutableState(invoice, entity);
        return entity;
    }

    private static boolean violates(Throwable error, String constraintName) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
