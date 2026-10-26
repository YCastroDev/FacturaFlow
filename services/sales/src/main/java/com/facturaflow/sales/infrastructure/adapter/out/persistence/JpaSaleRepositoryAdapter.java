package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import com.facturaflow.sales.application.exception.DuplicateReferenceCodeException;
import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.application.port.out.RequestHash;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.application.port.out.SaleRepository;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.model.ReferenceCode;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptador de salida: implementa {@link SaleRepository} con Spring Data JPA. Cada operación es una
 * transacción corta; ninguna excepción de Spring o Hibernate sale de aquí. Es el único lugar del
 * servicio con transacciones: las llamadas a catalog ocurren siempre fuera de la base.
 *
 * <p>Transactional outbox (ADR 0013): {@link #saveNew} inserta la venta y sus eventos pendientes en la
 * misma transacción, así nunca queda una venta sin su evento ni un evento sin su venta.
 */
@Component
class JpaSaleRepositoryAdapter implements SaleRepository {

    static final String REFERENCE_CODE_CONSTRAINT = "uk_sales_reference_code";
    /** Orden estable del listado: de la más reciente a la más antigua, con el id como desempate. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final SaleJpaRepository jpaRepository;
    private final SalePersistenceMapper mapper;
    private final EntityManager entityManager;
    private final SaleEventMessages messages;

    JpaSaleRepositoryAdapter(SaleJpaRepository jpaRepository, SalePersistenceMapper mapper,
                             EntityManager entityManager, SaleEventMessages messages) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.messages = messages;
    }

    @Override
    @Transactional
    public Sale saveNew(Sale sale, RequestHash requestHash) {
        if (sale.version().isPresent()) {
            throw new IllegalArgumentException("La venta " + sale.id().value() + " ya fue guardada.");
        }
        try {
            SaleEntity entity = mapper.toNewEntity(sale, requestHash);
            jpaRepository.save(entity);   // versión nula => persist; Hibernate la inicializa en 0
            // persist (no save): el id del evento ya viene asignado y save() haría un merge con SELECT previo.
            sale.pendingEvents().forEach(event -> {
                OutboxMessage message = messages.toMessage(event, sale);
                entityManager.persist(new OutboxEventEntity(message.eventId(), SaleEventMessages.AGGREGATE_TYPE,
                        sale.id().value(), message.eventType(), message.eventVersion(), message.routingKey(),
                        message.payload(), message.occurredAt()));
            });
            // flush() DENTRO del try: si no, el UNIQUE saltaría en el commit y no podríamos traducirlo.
            jpaRepository.flush();
            return mapper.toDomain(entity);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, REFERENCE_CODE_CONSTRAINT)) {
                throw new DuplicateReferenceCodeException(sale.referenceCode().value(), e);
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Sale> findById(SaleId id) {
        return jpaRepository.findWithLinesById(id.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Sale> findByReferenceCode(ReferenceCode referenceCode) {
        return jpaRepository.findWithLinesByReferenceCode(referenceCode.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredSale> findStoredByReferenceCode(ReferenceCode referenceCode) {
        return jpaRepository.findWithLinesByReferenceCode(referenceCode.value()).map(mapper::toStored);
    }

    /** Página, conteo, líneas e impuestos de toda la página: 4 sentencias (sin N+1). */
    @Override
    @Transactional(readOnly = true)
    public SalePage findPage(SaleStatus status, int page, int size) {
        PageRequest request = PageRequest.of(page, size, NEWEST_FIRST);
        Page<SaleEntity> result = status == null ? jpaRepository.findAllBy(request)
                : jpaRepository.findByStatus(status.name(), request);
        return new SalePage(result.getContent().stream().map(mapper::toDomain).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
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
