package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio Spring Data, interno al adaptador. Los métodos {@code findWithLines...} usan un entity
 * graph: venta, líneas e impuestos en UNA consulta. El listado no lo usa (paginar con JOIN de colecciones
 * paginaría en memoria): carga líneas e impuestos por lotes ({@code default_batch_fetch_size}).
 */
interface SaleJpaRepository extends JpaRepository<SaleEntity, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<SaleEntity> findWithLinesById(UUID id);

    @EntityGraph(attributePaths = {"lines", "lines.taxes"})
    Optional<SaleEntity> findWithLinesByReferenceCode(String referenceCode);

    Page<SaleEntity> findAllBy(Pageable pageable);

    Page<SaleEntity> findByStatus(String status, Pageable pageable);
}
