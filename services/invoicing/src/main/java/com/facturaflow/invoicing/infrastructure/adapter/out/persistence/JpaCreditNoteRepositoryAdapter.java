package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import com.facturaflow.invoicing.application.exception.CreditNoteConcurrentModificationException;
import com.facturaflow.invoicing.application.exception.DuplicateCreditNoteReferenceCodeException;
import com.facturaflow.invoicing.application.exception.InvoiceConcurrentModificationException;
import com.facturaflow.invoicing.application.port.out.CreditNoteRepository;
import com.facturaflow.invoicing.application.port.out.RequestHash;
import com.facturaflow.invoicing.application.port.out.StoredCreditNote;
import com.facturaflow.invoicing.domain.model.CreditNote;
import com.facturaflow.invoicing.domain.model.CreditNoteId;
import com.facturaflow.invoicing.domain.model.InvoiceId;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: implementa {@link CreditNoteRepository} con Spring Data JPA, con las
 * mismas reglas que {@link JpaInvoiceRepositoryAdapter} (ADR 0005): cada escritura es una
 * transacción, {@code flush()} dentro del {@code try} y ninguna excepción de Spring o Hibernate
 * sale de aquí.
 */
@Component
class JpaCreditNoteRepositoryAdapter implements CreditNoteRepository {

    static final String REFERENCE_CODE_CONSTRAINT = "uk_credit_notes_reference_code";

    private final CreditNoteJpaRepository jpaRepository;
    private final InvoiceJpaRepository invoiceJpaRepository;
    private final CreditNotePersistenceMapper mapper;

    JpaCreditNoteRepositoryAdapter(CreditNoteJpaRepository jpaRepository, InvoiceJpaRepository invoiceJpaRepository,
                                   CreditNotePersistenceMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.invoiceJpaRepository = invoiceJpaRepository;
        this.mapper = mapper;
    }

    /**
     * Primero sube la versión de la factura (bloqueo optimista: solo si sigue siendo la leída) y
     * después inserta la nota, todo en una transacción. El UPDATE va primero para tomar el
     * bloqueo de la fila de la factura: dos notas simultáneas sobre la misma factura quedan en
     * fila, y la segunda ve la versión nueva y falla sin insertar nada (ADR 0009, decisión 3).
     */
    @Override
    @Transactional
    public CreditNote saveNew(CreditNote creditNote, RequestHash requestHash, long invoiceVersionRead) {
        if (creditNote.version().isPresent()) {
            throw new IllegalArgumentException("La nota crédito " + creditNote.id() + " ya fue guardada: use save().");
        }
        if (invoiceJpaRepository.incrementVersion(creditNote.invoiceId().value(), invoiceVersionRead) == 0) {
            throw new InvoiceConcurrentModificationException(creditNote.invoiceId().value(), null);
        }
        try {
            CreditNoteEntity entity = mapper.toNewEntity(creditNote, requestHash);
            jpaRepository.save(entity);   // versión nula => persist; Hibernate la inicializa en 0
            jpaRepository.flush();        // dentro del try: el UNIQUE salta aquí y se traduce (ADR 0005)
            return mapper.toDomain(entity);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, REFERENCE_CODE_CONSTRAINT)) {
                throw new DuplicateCreditNoteReferenceCodeException(creditNote.referenceCode().value(), e);
            }
            throw e;
        }
    }

    /** Bloqueo optimista en dos niveles, como en las facturas: versión leída y "WHERE version = ?". */
    @Override
    @Transactional
    public CreditNote save(CreditNote creditNote) {
        long readVersion = creditNote.version().orElseThrow(() -> new IllegalArgumentException(
                "La nota crédito " + creditNote.id() + " nunca se ha guardado: use saveNew()."));
        try {
            CreditNoteEntity entity = jpaRepository.findWithLinesById(creditNote.id().value())
                    .orElseThrow(() -> new CreditNoteConcurrentModificationException(creditNote.id().value(), null));
            if (entity.version != readVersion) {
                throw new CreditNoteConcurrentModificationException(creditNote.id().value(), null);
            }
            mapper.applyMutableState(creditNote, entity);
            jpaRepository.flush();
            return mapper.toDomain(entity);
        } catch (OptimisticLockingFailureException e) {
            throw new CreditNoteConcurrentModificationException(creditNote.id().value(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CreditNote> findById(CreditNoteId id) {
        return jpaRepository.findWithLinesById(id.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditNote> findByInvoiceId(InvoiceId invoiceId) {
        return jpaRepository.findWithLinesByInvoiceIdOrderByCreatedAtAscIdAsc(invoiceId.value()).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredCreditNote> findStoredByReferenceCode(ReferenceCode referenceCode) {
        return jpaRepository.findWithLinesByReferenceCode(referenceCode.value()).map(mapper::toStored);
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
