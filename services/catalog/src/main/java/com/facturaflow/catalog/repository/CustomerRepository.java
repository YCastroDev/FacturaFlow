package com.facturaflow.catalog.repository;

import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.DocumentType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CustomerRepository extends JpaRepository<Customer, UUID>, JpaSpecificationExecutor<Customer> {

    /** Nombre de la restricción única (document_type, document_number) de V1. */
    String UNIQUE_DOCUMENT = "uq_customers_document";

    /** {@code number} ya normalizado ({@code CustomerDocument.normalizeNumber}). */
    Optional<Customer> findByDocumentTypeAndDocumentNumber(DocumentType documentType, String documentNumber);
}
