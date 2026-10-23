package com.facturaflow.invoicing.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio Spring Data, interno al adaptador. Los métodos {@code findWithLines...}
 * usan un entity graph: factura, líneas e impuestos en UNA consulta (sin N+1).
 */
interface InvoiceJpaRepository extends JpaRepository<InvoiceEntity, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<InvoiceEntity> findWithLinesById(UUID id);

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<InvoiceEntity> findWithLinesByReferenceCode(String referenceCode);

    /**
     * Sube la versión de la factura solo si sigue siendo {@code expectedVersion}, sin cargarla.
     * Es el bloqueo optimista que protege el saldo acreditable al insertar una nota crédito
     * (ADR 0009). El UPDATE toma el bloqueo de la fila: una segunda transacción con la misma
     * versión espera a la primera y, al reevaluar el WHERE con la versión nueva, no actualiza nada.
     *
     * @return filas actualizadas: 1 si la versión coincidía, 0 si la factura cambió o no existe
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE InvoiceEntity i SET i.version = i.version + 1 WHERE i.id = :id AND i.version = :expectedVersion")
    int incrementVersion(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion);
}
