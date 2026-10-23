package com.facturaflow.catalog.integration;

import static com.facturaflow.catalog.integration.ApiClient.etag;
import static com.facturaflow.catalog.integration.ApiClient.json;
import static com.facturaflow.catalog.integration.ApiClient.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.catalog.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
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

/** Punta a punta de los clientes, contra PostgreSQL (Testcontainers). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class CustomerApiIntegrationTest {

    private static final String URL = "/api/v1/customers";
    private static final String COMPANY = """
            { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
              "personType": "LEGAL_ENTITY", "companyName": "Bancolombia S.A.",
              "taxScheme": "IVA", "fiscalResponsibilities": [ "LARGE_TAXPAYER", "SELF_WITHHOLDER" ],
              "municipalityCode": "05001", "address": "Carrera 48 # 26-85", "phone": "+57 (604) 510-9000",
              "email": "facturas@banco.example" }""";

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

    private static String person(String type, String number, String names, String email) {
        return """
                { "documentType": "%s", "documentNumber": "%s", "personType": "NATURAL_PERSON", "names": "%s",
                  "taxScheme": "NOT_APPLICABLE", "fiscalResponsibilities": [ "NOT_RESPONSIBLE" ], "email": "%s" }"""
                .formatted(type, number, names, email);
    }

    @Test
    @DisplayName("ciclo completo: crear, consultar por documento, actualizar con If-Match y desactivar")
    void fullLifecycle() {
        EntityExchangeResult<String> created = api.post(URL, COMPANY);
        assertThat(status(created)).as(created.getResponseBody()).isEqualTo(201);
        assertThat((String) json(created, "$.phone")).isEqualTo("+576045109000");
        assertThat((List<String>) json(created, "$.fiscalResponsibilities"))
                .containsExactly("LARGE_TAXPAYER", "SELF_WITHHOLDER");
        String id = json(created, "$.id");

        EntityExchangeResult<String> byDocument = api.get(URL + "/by-document/NIT/890903938");
        assertThat((String) json(byDocument, "$.id")).isEqualTo(id);

        String moved = COMPANY.replace("\"05001\"", "\"11001\"").replace("[ \"LARGE_TAXPAYER\", \"SELF_WITHHOLDER\" ]",
                "[ \"LARGE_TAXPAYER\" ]");
        EntityExchangeResult<String> updated = api.put(URL + "/" + id, etag(byDocument), moved);
        assertThat(status(updated)).isEqualTo(200);
        assertThat(etag(updated)).isEqualTo("\"1\"");
        EntityExchangeResult<String> reread = api.get(URL + "/{id}", id);
        assertThat((String) json(reread, "$.municipalityCode")).isEqualTo("11001");
        assertThat((List<String>) json(reread, "$.fiscalResponsibilities")).containsExactly("LARGE_TAXPAYER");

        assertThat(status(api.put(URL + "/" + id, "\"0\"", COMPANY))).as("ETag viejo").isEqualTo(412);
        assertThat(status(api.put(URL + "/" + id, "\"1\"", COMPANY.replace("890903938", "800197268")
                .replace("\"checkDigit\": 8", "\"checkDigit\": 4")))).as("otro documento").isEqualTo(422);

        assertThat((Boolean) json(api.post(URL + "/" + id + "/deactivate"), "$.active")).isFalse();
        assertThat(status(api.get(URL + "/{id}", id))).as("no se borra").isEqualTo(200);
    }

    @Test
    @DisplayName("único por tipo + número: el mismo número con otro tipo es otro cliente")
    void uniquePerTypeAndNumber() {
        assertThat(status(api.post(URL, person("CITIZENSHIP_CARD", "1020304050", "Ana Pérez", "ana@correo.example"))))
                .isEqualTo(201);

        EntityExchangeResult<String> duplicate = api.post(URL,
                person("CITIZENSHIP_CARD", "1020304050", "Otra Persona", "otra@correo.example"));
        assertThat(status(duplicate)).isEqualTo(409);
        assertThat((String) json(duplicate, "$.type")).isEqualTo("urn:facturaflow:problem:customer-document-conflict");
        assertThat(status(api.post(URL, person("FOREIGNER_ID_CARD", "1020304050", "Ana Pérez", "ana@correo.example"))))
                .isEqualTo(201);
    }

    @Test
    @DisplayName("un documento alfanumérico se guarda en mayúsculas y se encuentra en minúsculas")
    void alphanumericDocumentIsNormalized() {
        api.post(URL, person("PASSPORT", "ab123456", "John Smith", "john@mail.example"));

        assertThat(status(api.post(URL, person("PASSPORT", "AB123456", "Otro", "otro@mail.example")))).isEqualTo(409);
        assertThat((String) json(api.get(URL + "/by-document/PASSPORT/ab123456"), "$.documentNumber"))
                .isEqualTo("AB123456");
    }

    @Test
    @DisplayName("la búsqueda cubre documento, razón social, nombres y email; el orden es por nombre visible")
    void search() {
        api.post(URL, COMPANY);
        api.post(URL, person("CITIZENSHIP_CARD", "1020304050", "Ana Pérez", "ana@correo.example"));
        api.post(URL, person("CITIZENSHIP_CARD", "79000111", "Zoe Ruiz", "zoe@banco.example"));

        assertThat((List<String>) json(api.get(URL), "$.items[*].displayName"))
                .containsExactly("Ana Pérez", "Bancolombia S.A.", "Zoe Ruiz");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "BANCO"), "$.items[*].displayName"))
                .as("razón social y email").containsExactly("Bancolombia S.A.", "Zoe Ruiz");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "3040"), "$.items[*].displayName"))
                .as("número de documento").containsExactly("Ana Pérez");
        assertThat((List<String>) json(api.get(URL + "?q={q}", "pérez"), "$.items[*].displayName"))
                .as("nombres").containsExactly("Ana Pérez");
    }

    @Test
    @DisplayName("las restricciones de la base respaldan las reglas aunque alguien se salte la API")
    void databaseConstraintsBackTheRules() {
        String id = json(api.post(URL, COMPANY), "$.id");

        assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE customers SET names = 'Ana' WHERE id = :id").param("id", UUID.fromString(id)).update())
                .as("jurídica con nombres").hasMessageContaining("ck_customers_person_name");
        assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE customers SET check_digit = NULL WHERE id = :id").param("id", UUID.fromString(id)).update())
                .as("NIT sin DV").hasMessageContaining("ck_customers_check_digit");
    }
}
