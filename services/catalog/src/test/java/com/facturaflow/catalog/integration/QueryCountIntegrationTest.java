package com.facturaflow.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.TestcontainersConfiguration;
import com.facturaflow.catalog.model.CatalogFixtures;
import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.CustomerDocument;
import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.model.ProductDetails;
import com.facturaflow.catalog.model.ProductTax;
import com.facturaflow.catalog.model.TaxType;
import com.facturaflow.catalog.model.UnitOfMeasure;
import com.facturaflow.catalog.service.CatalogPage;
import com.facturaflow.catalog.service.CustomerService;
import com.facturaflow.catalog.service.ProductService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Un listado no hace una consulta por fila (N+1): las colecciones de la página se cargan por lotes. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(TestcontainersConfiguration.class)
class QueryCountIntegrationTest {

    @Autowired
    private ProductService products;

    @Autowired
    private CustomerService customers;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcClient jdbc;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        CatalogDatabase.clean(jdbc);
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("una página de 20 productos con impuestos: 3 sentencias (página, conteo e impuestos de toda la página)")
    void productPage() {
        for (int i = 0; i < 25; i++) {
            products.create("P-" + i, new ProductDetails("Producto " + i, BigDecimal.TEN, UnitOfMeasure.UNIT, false,
                    List.of(ProductTax.of(TaxType.IVA, "19"), ProductTax.of(TaxType.INC, "8"))));
        }
        statistics.clear();

        Page<Product> page = products.search(null, null, new CatalogPage(0, 20));

        assertThat(page.getContent()).hasSize(20).allSatisfy(p -> assertThat(p.details().taxes()).hasSize(2));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("la consulta por lote de 10 productos: 2 sentencias (productos e impuestos de todos)")
    void productLookup() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            products.create("L-" + i, new ProductDetails("Producto " + i, BigDecimal.TEN, UnitOfMeasure.UNIT, false,
                    List.of(ProductTax.of(TaxType.IVA, "19"))));
            codes.add("l-" + i);
        }
        statistics.clear();

        List<Product> found = products.lookup(codes);

        assertThat(found).hasSize(10).allSatisfy(p -> assertThat(p.details().taxes()).hasSize(1));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("una página de clientes con responsabilidades: 3 sentencias")
    void customerPage() {
        for (int i = 0; i < 15; i++) {
            customers.create(CustomerDocument.of(DocumentType.CITIZENSHIP_CARD, "100" + i),
                    CatalogFixtures.person("Persona " + i));
        }
        statistics.clear();

        Page<Customer> page = customers.search("persona", true, new CatalogPage(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(15);
        assertThat(page.getContent()).allSatisfy(c -> assertThat(c.profile().fiscalResponsibilities()).hasSize(1));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
    }
}
