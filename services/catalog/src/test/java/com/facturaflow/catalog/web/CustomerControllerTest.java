package com.facturaflow.catalog.web;

import static com.facturaflow.catalog.model.CatalogFixtures.NOW;
import static com.facturaflow.catalog.model.CatalogFixtures.company;
import static com.facturaflow.catalog.model.CatalogFixtures.nit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.facturaflow.catalog.config.SecurityConfiguration;
import com.facturaflow.catalog.model.CatalogRuleException;
import com.facturaflow.catalog.model.Customer;
import com.facturaflow.catalog.model.CustomerDocument;
import com.facturaflow.catalog.model.CustomerProfile;
import com.facturaflow.catalog.model.DocumentType;
import com.facturaflow.catalog.model.FiscalResponsibility;
import com.facturaflow.catalog.model.PersonType;
import com.facturaflow.catalog.service.CatalogPage;
import com.facturaflow.catalog.service.ConcurrentUpdateException;
import com.facturaflow.catalog.service.CustomerNotFoundException;
import com.facturaflow.catalog.service.CustomerService;
import com.facturaflow.catalog.service.DuplicateCustomerDocumentException;
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

@WebMvcTest(CustomerController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = "admin")
class CustomerControllerTest {

    private static final String URL = "/api/v1/customers";
    private static final String BODY = """
            { "documentType": "NIT", "documentNumber": "890903938", "checkDigit": 8,
              "personType": "LEGAL_ENTITY", "companyName": "Cliente Ejemplo S.A.S.",
              "taxScheme": "IVA", "fiscalResponsibilities": [ "SELF_WITHHOLDER", "LARGE_TAXPAYER" ],
              "municipalityCode": "05001", "address": "Carrera 48 # 10-45", "phone": "(604) 444-5555",
              "email": "facturacion@cliente.example" }""";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CustomerService service;

    private static Customer customer(long version) {
        Customer customer = new Customer(nit(), company(), NOW);
        ReflectionTestUtils.setField(customer, "version", version);
        return customer;
    }

    private MvcTestResult post(String body) {
        return mvc.post().uri(URL).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static void assertProblem(MvcTestResult result, HttpStatus status, String slug) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.type").isEqualTo("urn:facturaflow:problem:" + slug);
        assertThat(result).bodyText().doesNotContain("trace", "Exception", "hibernate", "jackson");
    }

    @Test
    @DisplayName("crear responde 201 con ETag y los datos normalizados (teléfono, nombre visible)")
    void create() {
        Customer customer = customer(0);
        given(service.create(any(), any())).willReturn(customer);

        MvcTestResult result = post(BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED).hasHeader("ETag", "\"0\"")
                .hasHeader("Location", "http://localhost/api/v1/customers/" + customer.id());
        assertThat(result).bodyJson().extractingPath("$.displayName").isEqualTo("Cliente Ejemplo S.A.S.");
        assertThat(result).bodyJson().extractingPath("$.phone").isEqualTo("6044445555");
        assertThat(result).bodyJson().extractingPath("$.checkDigit").isEqualTo(8);
        assertThat(result).bodyJson().extractingPath("$.fiscalResponsibilities")
                .isEqualTo(List.of("LARGE_TAXPAYER", "SELF_WITHHOLDER"));
    }

    @Test
    @DisplayName("el cuerpo se traduce a documento y perfil del modelo")
    void requestIsMapped() {
        given(service.create(any(), any())).willReturn(customer(0));

        post(BODY);

        ArgumentCaptor<CustomerDocument> document = ArgumentCaptor.forClass(CustomerDocument.class);
        ArgumentCaptor<CustomerProfile> profile = ArgumentCaptor.forClass(CustomerProfile.class);
        verify(service).create(document.capture(), profile.capture());
        assertThat(document.getValue()).isEqualTo(CustomerDocument.nit("890903938", 8));
        assertThat(profile.getValue().personType()).isEqualTo(PersonType.LEGAL_ENTITY);
        assertThat(profile.getValue().fiscalResponsibilities())
                .containsExactlyInAnyOrder(FiscalResponsibility.SELF_WITHHOLDER, FiscalResponsibility.LARGE_TAXPAYER);
    }

    @Test
    @DisplayName("un DV incorrecto o razón social con nombres es 422, sin llamar al servicio")
    void businessRulesAre422() {
        MvcTestResult wrongCheckDigit = post(BODY.replace("\"checkDigit\": 8", "\"checkDigit\": 7"));
        assertProblem(wrongCheckDigit, HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
        assertThat(wrongCheckDigit).bodyJson().extractingPath("$.detail").asString().contains("es 8, no 7");
        assertProblem(post(BODY.replace("\"companyName\"", "\"names\": \"Ana\", \"companyName\"")),
                HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("faltan campos obligatorios: 400")
    void missingFieldsAre400() {
        MvcTestResult result = post("{ \"documentType\": \"NIT\" }");

        assertProblem(result, HttpStatus.BAD_REQUEST, "invalid-request");
        assertThat(result).bodyText().contains("documentNumber", "personType", "taxScheme", "fiscalResponsibilities");
    }

    @Test
    @DisplayName("un documento repetido es 409")
    void duplicateIs409() {
        given(service.create(any(), any())).willThrow(new DuplicateCustomerDocumentException(nit()));

        assertProblem(post(BODY), HttpStatus.CONFLICT, "customer-document-conflict");
    }

    @Test
    @DisplayName("consultar por documento: el tipo va en la ruta; uno desconocido es 400, uno inexistente 404")
    void getByDocument() {
        given(service.getByDocument(DocumentType.NIT, "890903938")).willReturn(customer(2));
        given(service.getByDocument(DocumentType.PASSPORT, "X1"))
                .willThrow(new CustomerNotFoundException("No existe un cliente con pasaporte X1."));

        assertThat(mvc.get().uri(URL + "/by-document/NIT/890903938").exchange()).hasStatusOk()
                .hasHeader("ETag", "\"2\"");
        assertProblem(mvc.get().uri(URL + "/by-document/RUT/890903938").exchange(), HttpStatus.BAD_REQUEST,
                "invalid-parameter");
        assertProblem(mvc.get().uri(URL + "/by-document/PASSPORT/X1").exchange(), HttpStatus.NOT_FOUND,
                "customer-not-found");
    }

    @Test
    @DisplayName("el listado pasa filtros y paginación al servicio")
    void list() {
        given(service.search(eq("ejemplo"), eq(false), any()))
                .willReturn(new PageImpl<>(List.of(customer(0)), PageRequest.of(0, 10), 1));

        MvcTestResult result = mvc.get().uri(URL + "?q=ejemplo&active=false&size=10").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].documentNumber").isEqualTo("890903938");
        verify(service).search("ejemplo", false, new CatalogPage(0, 10));
        assertProblem(mvc.get().uri(URL + "?size=1000").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
    }

    @Test
    @DisplayName("PUT exige If-Match (428) y responde 412 con una versión vieja")
    void updatePreconditions() {
        UUID id = UUID.randomUUID();
        assertProblem(mvc.put().uri(URL + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(BODY).exchange(),
                HttpStatus.PRECONDITION_REQUIRED, "precondition-required");

        given(service.update(eq(id), anyLong(), any(), any())).willThrow(new StaleVersionException("El cliente"));
        assertProblem(mvc.put().uri(URL + "/{id}", id).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange(),
                HttpStatus.PRECONDITION_FAILED, "precondition-failed");
    }

    @Test
    @DisplayName("PUT con la versión actual responde el ETag nuevo; cambiar el documento es 422")
    void update() {
        Customer customer = customer(1);
        given(service.update(eq(customer.id()), eq(0L), any(), any())).willReturn(customer);
        UUID other = UUID.randomUUID();
        given(service.update(eq(other), anyLong(), any(), any()))
                .willThrow(new CatalogRuleException("No se puede cambiar el documento del cliente"));

        assertThat(mvc.put().uri(URL + "/{id}", customer.id()).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange())
                .hasStatusOk().hasHeader("ETag", "\"1\"");
        assertProblem(mvc.put().uri(URL + "/{id}", other).header("If-Match", "0")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY).exchange(),
                HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
    }

    @Test
    @DisplayName("desactivar en carrera con otra petición, sin If-Match, es 409")
    void deactivateConcurrentlyIs409() {
        UUID id = UUID.randomUUID();
        given(service.deactivate(id, null)).willThrow(new ConcurrentUpdateException("El cliente"));

        assertProblem(mvc.post().uri(URL + "/{id}/deactivate", id).exchange(), HttpStatus.CONFLICT,
                "concurrent-modification");
    }
}
