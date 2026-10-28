package com.facturaflow.catalog.web;

import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.coffee;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.facturaflow.catalog.config.SecurityConfiguration;
import com.facturaflow.catalog.model.CatalogRuleException;
import com.facturaflow.catalog.model.Product;
import com.facturaflow.catalog.model.ProductDetails;
import com.facturaflow.catalog.model.TaxType;
import com.facturaflow.catalog.model.UnitOfMeasure;
import com.facturaflow.catalog.service.CatalogPage;
import com.facturaflow.catalog.service.DuplicateProductCodeException;
import com.facturaflow.catalog.service.ProductNotFoundException;
import com.facturaflow.catalog.service.ProductService;
import com.facturaflow.catalog.service.StaleVersionException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Capa web aislada: controlador + manejador de errores + Jackson + Bean Validation, con el servicio
 * simulado. Verifica códigos HTTP, headers (ETag, If-Match), forma del JSON y Problem Details.
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = "admin")
class ProductControllerTest {

    private static final String URL = "/api/v1/products";
    private static final String BODY = """
            { "code": "CAFE-500", "name": "Café molido 500 g", "unitPrice": 18500.00, "unit": "UNIT",
              "taxes": [ { "type": "IVA", "rate": 5 } ] }""";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private ProductService service;

    private static Product product(long version) {
        Product product = new Product("CAFE-500", coffee(), NOW);
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    private MvcTestResult put(UUID id, String ifMatch, String body) {
        var request = mvc.put().uri(URL + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return request.exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:facturaflow:problem:" + slug);
        assertThat(result).bodyText().doesNotContain("trace", "Exception", "hibernate", "jackson");
    }

    @Test
    @DisplayName("crear responde 201 con Location, ETag \"0\" y el producto")
    void create() {
        Product product = product(0);
        given(service.create(eq("CAFE-500"), any())).willReturn(product);

        MvcTestResult result = mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange();

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("ETag", "\"0\"")
                .hasHeader("Location", "http://localhost/api/v1/products/" + product.id());
        assertThat(result).bodyJson().extractingPath("$.unitPrice").isEqualTo(18500.00);
        assertThat(result).bodyJson().extractingPath("$.taxes[0].type").isEqualTo("IVA");
        assertThat(result).bodyJson().extractingPath("$.version").isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.active").isEqualTo(true);
    }

    @Test
    @DisplayName("el cuerpo se traduce al modelo: ivaExcluded ausente es false y los impuestos van con su tarifa")
    void requestIsMapped() {
        given(service.create(anyString(), any())).willReturn(product(0));

        mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON).content("""
                { "code": "ARROZ", "name": "Arroz", "unitPrice": 4200, "unit": "KILOGRAM", "taxes": [ { "type": "INC", "rate": 8 } ] }""")
                .exchange();

        ArgumentCaptor<ProductDetails> details = ArgumentCaptor.forClass(ProductDetails.class);
        verify(service).create(eq("ARROZ"), details.capture());
        assertThat(details.getValue().ivaExcluded()).isFalse();
        assertThat(details.getValue().unit()).isEqualTo(UnitOfMeasure.KILOGRAM);
        assertThat(details.getValue().taxes().getFirst().type()).isEqualTo(TaxType.INC);
        assertThat(details.getValue().taxes().getFirst().rate()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("un campo faltante es 400 con la lista de errores, sin llamar al servicio")
    void missingFieldIs400() {
        MvcTestResult result = mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                .content("{ \"code\": \"X\", \"name\": \"Y\", \"unit\": \"UNIT\" }").exchange();

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyText().contains("unitPrice", "taxes");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("una unidad desconocida o un campo inexistente es 400")
    void unknownValuesAre400() {
        assertProblem(mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"UNIT\"", "\"DOZEN\"")).exchange(), HttpStatus.BAD_REQUEST, "malformed-request");
        assertProblem(mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"code\"", "\"precio\": 1, \"code\"")).exchange(),
                HttpStatus.BAD_REQUEST, "malformed-request");
    }

    @Test
    @DisplayName("un precio con más de 2 decimales es 422 (lo decide el modelo, no Bean Validation)")
    void tooManyDecimalsIs422() {
        MvcTestResult result = mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("18500.00", "18500.005")).exchange();

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("2 decimales");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("un código repetido es 409")
    void duplicateIs409() {
        given(service.create(anyString(), any())).willThrow(new DuplicateProductCodeException("CAFE-500"));

        assertProblem(mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange(),
                HttpStatus.CONFLICT, "product-code-conflict");
    }

    @Test
    @DisplayName("consultar por id responde el ETag; un id que no es UUID es 400 y uno inexistente 404")
    void getById() {
        Product product = product(7);
        given(service.get(product.id())).willReturn(product);
        UUID missing = UUID.randomUUID();
        given(service.get(missing)).willThrow(new ProductNotFoundException("No existe un producto con id " + missing));

        assertThat(mvc.get().uri(URL + "/{id}", product.id()).exchange()).hasStatusOk().hasHeader("ETag", "\"7\"");
        assertProblem(mvc.get().uri(URL + "/no-es-uuid").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertProblem(mvc.get().uri(URL + "/{id}", missing).exchange(), HttpStatus.NOT_FOUND, "product-not-found");
    }

    @Test
    @DisplayName("consultar por código pasa el código al servicio")
    void getByCode() {
        given(service.getByCode("cafe-500")).willReturn(product(0));

        assertThat(mvc.get().uri(URL + "/by-code/cafe-500").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.code").isEqualTo("CAFE-500");
    }

    @Test
    @DisplayName("el listado responde una página propia y pasa filtros y paginación al servicio")
    void list() {
        given(service.search(eq("café"), eq(true), any()))
                .willReturn(new PageImpl<>(List.of(product(0)), PageRequest.of(1, 5), 6));

        MvcTestResult result = mvc.get().uri(URL + "?q=café&active=true&page=1&size=5").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].code").isEqualTo("CAFE-500");
        assertThat(result).bodyJson().extractingPath("$.page").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.size").isEqualTo(5);
        assertThat(result).bodyJson().extractingPath("$.totalItems").isEqualTo(6);
        assertThat(result).bodyJson().extractingPath("$.totalPages").isEqualTo(2);
        verify(service).search("café", true, new CatalogPage(1, 5));
    }

    @Test
    @DisplayName("sin parámetros: página 0 de 20, sin filtros")
    void listDefaults() {
        given(service.search(isNull(), isNull(), any())).willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(mvc.get().uri(URL).exchange()).hasStatusOk();
        verify(service).search(null, null, new CatalogPage(0, 20));
    }

    @Test
    @DisplayName("size fuera de 1..100, página negativa o texto muy largo son 400 (no se recortan en silencio)")
    void pagingLimits() {
        assertProblem(mvc.get().uri(URL + "?size=101").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertProblem(mvc.get().uri(URL + "?size=0").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertProblem(mvc.get().uri(URL + "?page=-1").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
        MvcTestResult longText = mvc.get().uri(URL + "?q=" + "x".repeat(101)).exchange();
        assertProblem(longText, HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertThat(longText).bodyJson().extractingPath("$.errors[0].field").isEqualTo("q");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT con If-Match pasa la versión al servicio y responde el ETag nuevo")
    void updateWithIfMatch() {
        Product product = product(4);
        given(service.update(eq(product.id()), eq(3L), eq("CAFE-500"), any())).willReturn(product);

        MvcTestResult result = put(product.id(), "\"3\"", BODY);

        assertThat(result).hasStatusOk().hasHeader("ETag", "\"4\"");
    }

    @Test
    @DisplayName("PUT sin If-Match es 428 y no llama al servicio")
    void updateWithoutIfMatchIs428() {
        MvcTestResult result = put(UUID.randomUUID(), null, BODY);

        assertProblem(result, HttpStatus.PRECONDITION_REQUIRED, "precondition-required");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("ETag");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT con If-Match '*' o débil es 400")
    void updateWithInvalidIfMatchIs400() {
        assertProblem(put(UUID.randomUUID(), "*", BODY), HttpStatus.BAD_REQUEST, "invalid-if-match");
        assertProblem(put(UUID.randomUUID(), "W/\"1\"", BODY), HttpStatus.BAD_REQUEST, "invalid-if-match");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PUT con una versión vieja es 412")
    void staleUpdateIs412() {
        given(service.update(any(), anyLong(), anyString(), any()))
                .willThrow(new StaleVersionException("El producto CAFE-500"));

        assertProblem(put(UUID.randomUUID(), "\"1\"", BODY), HttpStatus.PRECONDITION_FAILED, "precondition-failed");
    }

    @Test
    @DisplayName("PUT que cambia el código es 422")
    void codeChangeIs422() {
        given(service.update(any(), anyLong(), anyString(), any()))
                .willThrow(new CatalogRuleException("No se puede cambiar el código del producto"));

        assertProblem(put(UUID.randomUUID(), "\"1\"", BODY.replace("CAFE-500", "OTRO")),
                HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
    }

    @Test
    @DisplayName("desactivar y reactivar aceptan If-Match opcional")
    void deactivateAndActivate() {
        Product product = product(5);
        given(service.deactivate(product.id(), null)).willReturn(product);
        given(service.activate(product.id(), 5L)).willReturn(product);

        assertThat(mvc.post().uri(URL + "/{id}/deactivate", product.id()).exchange()).hasStatusOk()
                .hasHeader("ETag", "\"5\"");
        assertThat(mvc.post().uri(URL + "/{id}/activate", product.id()).header("If-Match", "\"5\"").exchange())
                .hasStatusOk();
    }


    @Test
    @DisplayName("la consulta por lote devuelve los productos encontrados en una lista")
    void lookup() {
        given(service.lookup(List.of("CAFE-500", "arroz"))).willReturn(List.of(product(2)));

        MvcTestResult result = mvc.post().uri(URL + "/lookup").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"codes\": [ \"CAFE-500\", \"arroz\" ] }").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].code").isEqualTo("CAFE-500");
        assertThat(result).bodyJson().extractingPath("$.items[0].version").isEqualTo(2);
    }

    @Test
    @DisplayName("la consulta por lote exige de 1 a 100 códigos no vacíos")
    void lookupLimits() {
        String tooMany = "{ \"codes\": [" + "\"A\",".repeat(100) + "\"A\"] }";
        assertProblem(mvc.post().uri(URL + "/lookup").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"codes\": [] }").exchange(), HttpStatus.BAD_REQUEST, "invalid-request");
        assertProblem(mvc.post().uri(URL + "/lookup").contentType(MediaType.APPLICATION_JSON)
                .content(tooMany).exchange(), HttpStatus.BAD_REQUEST, "invalid-request");
        assertProblem(mvc.post().uri(URL + "/lookup").contentType(MediaType.APPLICATION_JSON)
                .content("{ \"codes\": [ \" \" ] }").exchange(), HttpStatus.BAD_REQUEST, "invalid-request");
        verifyNoInteractions(service);
    }
}
