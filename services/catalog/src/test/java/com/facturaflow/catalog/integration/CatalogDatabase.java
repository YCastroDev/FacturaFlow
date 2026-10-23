package com.facturaflow.catalog.integration;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Deja la base vacía entre tests (el contexto de Spring y el contenedor se reutilizan). */
final class CatalogDatabase {

    private CatalogDatabase() {
    }

    static void clean(JdbcClient jdbc) {
        jdbc.sql("TRUNCATE product_taxes, products, customer_fiscal_responsibilities, customers").update();
    }
}
