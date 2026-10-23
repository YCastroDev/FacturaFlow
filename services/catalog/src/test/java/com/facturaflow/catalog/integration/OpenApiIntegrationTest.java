package com.facturaflow.catalog.integration;

import static com.facturaflow.catalog.integration.ApiClient.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.TestcontainersConfiguration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;

/** La documentación de Swagger explica cómo enviar If-Match: sin ella nadie puede probar un PUT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class OpenApiIntegrationTest {

    @Autowired
    private RestTestClient restClient;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"products", "customers"})
    @DisplayName("el PUT documenta If-Match como obligatorio, de dónde sale el ETag y un ejemplo con comillas")
    void putDocumentsIfMatch(String resource) {
        EntityExchangeResult<String> docs = new ApiClient(restClient).get("/v3/api-docs");
        String put = "$.paths['/api/v1/" + resource + "/{id}'].put";

        List<Map<String, Object>> ifMatch = json(docs, put + ".parameters[?(@.name == 'If-Match')]");
        assertThat(ifMatch).hasSize(1);
        assertThat(ifMatch.getFirst()).containsEntry("in", "header").containsEntry("required", true);
        assertThat((String) ifMatch.getFirst().get("description")).contains("ETag", "GET /{id}", "\"0\"", "412", "428");
        assertThat(((Map<String, Object>) ifMatch.getFirst().get("schema"))).containsEntry("example", "\"0\"");
        assertThat((String) json(docs, put + ".description")).contains("Cómo probarlo en Swagger UI", "etag");
        assertThat((Map<String, Object>) json(docs, put + ".responses['200'].headers")).containsKey("ETag");
        assertThat((Map<String, Object>) json(docs, "$.paths['/api/v1/" + resource + "/{id}'].get.responses['200'].headers"))
                .containsKey("ETag");
    }
}
