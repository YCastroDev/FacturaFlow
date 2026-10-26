package com.facturaflow.catalog.integration;

import static com.facturaflow.catalog.integration.ApiClient.etag;
import static com.facturaflow.catalog.integration.ApiClient.json;
import static com.facturaflow.catalog.integration.ApiClient.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** Punta a punta: HTTP → controlador → servicio → JPA → PostgreSQL (Testcontainers). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class ProductApiIntegrationTest {

    private static final String URL = "/api/v1/products";

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private JdbcClient jdbc;

    private ApiClient api;

    @BeforeEach
    void setUp() {
        CatalogDatabase.clean(jdbc);
        api = new ApiClient(restClient);
    }

    private static String product(String code, String name, String price) {
        return """
                { "code": "%s", "name": "%s", "unitPrice": %s, "unit": "UNIT",
                  "taxes": [ { "type": "IVA", "rate": 19 }, { "type": "INC", "rate": 8 } ] }""".formatted(code, name, price);
    }

    private String create(String code, String name) {
        EntityExchangeResult<String> created = api.post(URL, product(code, name, "1000"));
        assertThat(status(created)).as(created.getResponseBody()).isEqualTo(201);
        return json(created, "$.id");
    }

    @Test
    @DisplayName("ciclo completo: crear, consultar, actualizar con If-Match, desactivar y reactivar")
    void fullLifecycle() {
        EntityExchangeResult<String> created = api.post(URL, product("CAFE-500", "Café molido", "18500.5"));
        assertThat(status(created)).isEqualTo(201);
        assertThat(etag(created)).isEqualTo("\"0\"");
        assertThat((Double) json(created, "$.unitPrice")).isEqualTo(18500.50);
        assertThat((List<String>) json(created, "$.taxes[*].type")).containsExactly("IVA", "INC");
        String id = json(created, "$.id");
        assertThat(created.getResponseHeaders().getLocation()).hasPath(URL + "/" + id);

        EntityExchangeResult<String> read = api.get(URL + "/{id}", id);
        assertThat(etag(read)).isEqualTo("\"0\"");

        EntityExchangeResult<String> updated = api.put(URL + "/" + id, etag(read), product("CAFE-500", "Café molido", "19900"));
        assertThat(status(updated)).isEqualTo(200);
        assertThat(etag(updated)).isEqualTo("\"1\"");
        assertThat((Integer) json(updated, "$.version")).isEqualTo(1);
        assertThat((Double) json(api.get(URL + "/{id}", id), "$.unitPrice")).isEqualTo(19900.0);

        EntityExchangeResult<String> deactivated = api.post(URL + "/" + id + "/deactivate");
        assertThat(status(deactivated)).isEqualTo(200);
        assertThat((Boolean) json(deactivated, "$.active")).isFalse();
        assertThat(etag(deactivated)).isEqualTo("\"2\"");
        assertThat(etag(api.post(URL + "/" + id + "/deactivate"))).as("idempotente: no sube la versión")
                .isEqualTo("\"2\"");
        assertThat((Boolean) json(api.get(URL + "/{id}", id), "$.active")).as("desactivado, no borrado").isFalse();

        assertThat((Boolean) json(api.post(URL + "/" + id + "/activate"), "$.active")).isTrue();
    }

    @Test
    @DisplayName("el ETag evita la actualización perdida: el segundo PUT con el mismo ETag es 412 y no pisa nada")
    void lostUpdateIsPrevented() {
        String id = create("CAFE-500", "Café");
        String etag = etag(api.get(URL + "/{id}", id));

        assertThat(status(api.put(URL + "/" + id, etag, product("CAFE-500", "Café de Ana", "1000")))).isEqualTo(200);
        EntityExchangeResult<String> second = api.put(URL + "/" + id, etag, product("CAFE-500", "Café de Luis", "1000"));

        assertThat(status(second)).isEqualTo(412);
        assertThat((String) json(second, "$.type")).isEqualTo("urn:facturaflow:problem:precondition-failed");
        assertThat((String) json(api.get(URL + "/{id}", id), "$.name")).isEqualTo("Café de Ana");
    }

    @Test
    @DisplayName("PUT sin If-Match es 428; un PUT idéntico no sube la versión; cambiar el código es 422")
    void updateRules() {
        String id = create("CAFE-500", "Café");

        assertThat(status(api.put(URL + "/" + id, null, product("CAFE-500", "Otro", "1000")))).isEqualTo(428);
        EntityExchangeResult<String> identical = api.put(URL + "/" + id, "\"0\"", product("CAFE-500", "Café", "1000.00"));
        assertThat(status(identical)).isEqualTo(200);
        assertThat(etag(identical)).isEqualTo("\"0\"");
        assertThat(status(api.put(URL + "/" + id, "\"0\"", product("CAFE-501", "Café", "1000")))).isEqualTo(422);
    }

    @Test
    @DisplayName("el código es único sin distinguir mayúsculas y se consulta igual, activo o no")
    void codeIsUniqueIgnoringCase() {
        String id = create("Cafe-500", "Café");

        EntityExchangeResult<String> duplicate = api.post(URL, product("CAFE-500", "Otro", "1"));
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat((String) json(duplicate, "$.type")).isEqualTo("urn:facturaflow:problem:product-code-conflict");

        api.post(URL + "/" + id + "/deactivate");
        assertThat(status(api.post(URL, product("cafe-500", "Otro", "1")))).as("un inactivo sigue ocupando el código")
                .isEqualTo(409);
        assertThat((String) json(api.get(URL + "/by-code/{code}", "CAFE-500"), "$.code")).isEqualTo("Cafe-500");
        assertThat(status(api.get(URL + "/by-code/{code}", "NO-EXISTE"))).isEqualTo(404);
    }

    @Test
    @DisplayName("la paginación recorre todo sin repetir ni saltar, ordenada por código")
    void pagination() {
        for (int i = 1; i <= 23; i++) {
            create("P-%02d".formatted(i), "Producto " + i);
        }

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            EntityExchangeResult<String> result = api.get(URL + "?size=10&page=" + page);
            assertThat((Integer) json(result, "$.totalItems")).isEqualTo(23);
            assertThat((Integer) json(result, "$.totalPages")).isEqualTo(3);
            seen.addAll(json(result, "$.items[*].code"));
        }

        assertThat(seen).hasSize(23).doesNotHaveDuplicates().isSorted();
        assertThat((List<?>) json(api.get(URL + "?size=10&page=3"), "$.items")).as("más allá del final: vacía").isEmpty();
        assertThat(status(api.get(URL + "?size=101"))).isEqualTo(400);
    }

    @Test
    @DisplayName("la búsqueda encuentra por código o nombre, sin distinguir mayúsculas, y filtra por estado")
    void searchAndActiveFilter() {
        create("CAFE-500", "Café molido");
        String decaf = create("DESCAF-1", "Café descafeinado");
        create("ARROZ", "Arroz 100% blanco");
        api.post(URL + "/" + decaf + "/deactivate");

        assertThat((List<String>) json(api.get(URL + "?q={q}", "CAFÉ"), "$.items[*].code")).containsExactly("CAFE-500", "DESCAF-1");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "cafe-"), "$.items[*].code")).containsExactly("CAFE-500");
        assertThat((List<String>) json(api.get(URL + "?q={q}&active=true", "café"), "$.items[*].code")).containsExactly("CAFE-500");
        assertThat((List<String>) json(api.get(URL + "?active=false"), "$.items[*].code")).containsExactly("DESCAF-1");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "100%"), "$.items[*].code")).as("% literal").containsExactly("ARROZ");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "0_"), "$.items[*].code")).as("_ literal").isEmpty();
    }

    @Test
    @DisplayName("una regla incumplida es 422 y no guarda nada")
    void businessRuleIs422() {
        EntityExchangeResult<String> excludedWithIva = api.post(URL, """
                { "code": "X", "name": "X", "unitPrice": 1, "unit": "UNIT", "ivaExcluded": true,
                  "taxes": [ { "type": "IVA", "rate": 0 } ] }""");

        assertThat(status(excludedWithIva)).isEqualTo(422);
        assertThat((String) json(excludedWithIva, "$.detail")).contains("excluido de IVA");
        assertThat(jdbc.sql("SELECT count(*) FROM products").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("la consulta por lote trae activos e inactivos sin distinguir mayúsculas y omite los inexistentes")
    void lookup() {
        create("CAFE-500", "Café");
        String rice = create("Arroz-KG", "Arroz");
        create("OTRO", "No pedido");
        api.post(URL + "/" + rice + "/deactivate");

        EntityExchangeResult<String> result = api.post(URL + "/lookup",
                "{ \"codes\": [ \"cafe-500\", \"ARROZ-KG\", \"NO-EXISTE\", \"CAFE-500\" ] }");

        assertThat(status(result)).isEqualTo(200);
        assertThat((List<String>) json(result, "$.items[*].code")).containsExactly("Arroz-KG", "CAFE-500");
        assertThat((List<Boolean>) json(result, "$.items[*].active")).containsExactly(false, true);
        assertThat((List<String>) json(result, "$.items[1].taxes[*].type")).containsExactly("IVA", "INC");
    }
}
