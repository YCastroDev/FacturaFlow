package com.facturaflow.catalog.repository;

import com.facturaflow.catalog.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    /** Nombre del índice único sobre {@code lower(code)} (V1); identifica la violación de unicidad. */
    String UNIQUE_CODE = "uq_products_code";

    /** Sin distinguir mayúsculas, con {@code lower()} para usar el índice único (V1). */
    @Query("select p from Product p where lower(p.code) = lower(:code)")
    Optional<Product> findByCodeIgnoringCase(@Param("code") String code);
}
