package com.facturaflow.sales.infrastructure.adapter.in.rest;

import static com.facturaflow.sales.domain.model.SaleFixtures.excludedLine;
import static com.facturaflow.sales.domain.model.SaleFixtures.line;
import static com.facturaflow.sales.domain.model.SaleFixtures.saleWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.exception.InvalidCatalogReferencesException;
import com.facturaflow.sales.application.exception.ReferenceCodeConflictException;
import com.facturaflow.sales.application.exception.SaleNotFoundException;
import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.application.port.in.CreateSaleResult;
import com.facturaflow.sales.application.port.in.CreateSaleUseCase;
import com.facturaflow.sales.application.port.in.GetSaleUseCase;
import com.facturaflow.sales.application.port.in.ListSalesQuery;
import com.facturaflow.sales.application.port.in.ListSalesUseCase;
import com.facturaflow.sales.application.port.out.SalePage;
import com.facturaflow.sales.domain.exception.InvalidQuantityException;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleId;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.infrastructure.config.SecurityConfiguration;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Capa web aislada: controlador + manejador de errores + Jackson + Bean Validation, con los casos de uso
 * simulados. Verifica códigos HTTP, forma del JSON y Problem Details. Con la seguridad real y un usuario
 * {@code vendedor}; la matriz de permisos se prueba en {@link SaleSecurityTest}.
 */
@WebMvcTest(SaleController.class)
@Import(SecurityConfiguration.class)
@WithMockUser(roles = "vendedor")
class SaleControllerTest {

    private static final String URL = "/api/v1/sales";
    private static final String BODY = """
            { "referenceCode": "VENTA-1",
              "customer": { "documentType": "NIT", "documentNumber": "890903938" },
              "lines": [ { "productCode": "CAFE-500", "quantity": 2, "discountRate": 10 },
                         { "productCode": "ARROZ-KG", "quantity": 1.5 } ],
              "paymentMethod": "BANK_TRANSFER", "paymentTerms": { "type": "CREDIT", "dueDate": "2030-01-31" },
              "observation": "Entregar en portería" }""";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private CreateSaleUseCase createSale;

    @MockitoBean
    private GetSaleUseCase getSale;

    @MockitoBean
    private ListSalesUseCase listSales;

    /** 2 x 50.000 - 10 % con IVA 19 % + INC 8 %, y una excluida de 20.000. */
    private static Sale sale() {
        return saleWith(List.of(line("2", "50000.00", "10", LineTax.of(TaxType.IVA, "19"), LineTax.of(TaxType.INC, "8")),
                excludedLine("1", "20000.00")));
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
    @DisplayName("crear responde 201 con Location, la copia de catalog y los totales")
    void create() {
        Sale sale = sale();
        given(createSale.create(any())).willReturn(CreateSaleResult.created(sale));

        MvcTestResult result = post(BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "http://localhost/api/v1/sales/" + sale.id().value());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("CONFIRMED");
        assertThat(result).bodyJson().extractingPath("$.customer.checkDigit").isEqualTo(8);
        assertThat(result).bodyJson().extractingPath("$.lines[0].lineNumber").isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.lines[0].taxes[1].amount").isEqualTo(7200.00);
        assertThat(result).bodyJson().extractingPath("$.totals.totalIva").isEqualTo(17100.00);
        assertThat(result).bodyJson().extractingPath("$.totals.total").isEqualTo(134300.00);
    }

    @Test
    @DisplayName("el cuerpo se traduce al comando: sin precios ni nombres, solo códigos, cantidades y pago")
    void requestIsMapped() {
        given(createSale.create(any())).willReturn(CreateSaleResult.created(sale()));

        post(BODY);

        ArgumentCaptor<CreateSaleCommand> command = ArgumentCaptor.forClass(CreateSaleCommand.class);
        verify(createSale).create(command.capture());
        assertThat(command.getValue().customerDocumentType()).isEqualTo(DocumentType.NIT);
        assertThat(command.getValue().lines()).containsExactly(
                new CreateSaleCommand.Line("CAFE-500", new BigDecimal("2"), new BigDecimal("10")),
                new CreateSaleCommand.Line("ARROZ-KG", new BigDecimal("1.5"), null));
        assertThat(command.getValue().paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(command.getValue().paymentTerms()).isEqualTo(new CreateSaleCommand.CreditTerms(LocalDate.of(2030, 1, 31)));
    }

    @Test
    @DisplayName("un reintento idéntico responde 200 con la venta existente")
    void replayIs200() {
        given(createSale.create(any())).willReturn(CreateSaleResult.replayed(sale()));

        assertThat(post(BODY)).hasStatusOk().doesNotContainHeader("Location");
    }

    @Test
    @DisplayName("un precio en la petición es un campo que no existe: 400 (los precios salen de catalog)")
    void priceInRequestIs400() {
        assertProblem(post(BODY.replace("\"quantity\": 2,", "\"quantity\": 2, \"unitPrice\": 1,")), HttpStatus.BAD_REQUEST,
                "malformed-request");
        assertProblem(post("{ \"referenceCode\": \"V\" }"), HttpStatus.BAD_REQUEST, "invalid-request");
        verifyNoInteractions(createSale);
    }

    @Test
    @DisplayName("el mismo código con otro contenido es 409")
    void conflictIs409() {
        given(createSale.create(any())).willThrow(new ReferenceCodeConflictException("VENTA-1"));

        assertProblem(post(BODY), HttpStatus.CONFLICT, "reference-code-conflict");
    }

    @Test
    @DisplayName("cliente o productos inexistentes o inactivos: 422 con los problemas también como datos")
    void invalidCatalogReferencesIs422() {
        given(createSale.create(any())).willThrow(new InvalidCatalogReferencesException(
                "El cliente con NIT 890903938 está inactivo en catalog.", List.of("NO-EXISTE"), List.of("DEMO-CAFE")));

        MvcTestResult result = post(BODY);

        assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "catalog-reference-invalid");
        assertThat(result).bodyJson().extractingPath("$.missingProducts").isEqualTo(List.of("NO-EXISTE"));
        assertThat(result).bodyJson().extractingPath("$.inactiveProducts").isEqualTo(List.of("DEMO-CAFE"));
        assertThat(result).bodyJson().extractingPath("$.customerProblem").asString().contains("inactivo");
    }

    @Test
    @DisplayName("una regla del dominio (cantidad en cero) es 422")
    void domainRuleIs422() {
        given(createSale.create(any())).willThrow(InvalidQuantityException.notPositive(BigDecimal.ZERO));

        assertProblem(post(BODY), HttpStatus.UNPROCESSABLE_CONTENT, "business-rule-violation");
    }

    @Test
    @DisplayName("catalog caído: 503 con Retry-After y reason; con el circuito abierto, el tiempo que falta")
    void catalogUnavailableIs503() {
        given(createSale.create(any()))
                .willThrow(new CatalogUnavailableException("x", CatalogUnavailableException.Reason.FAILURE,
                        Duration.ofSeconds(5), null))
                .willThrow(new CatalogUnavailableException("x", CatalogUnavailableException.Reason.CIRCUIT_OPEN,
                        Duration.ofMillis(12_300), null));

        MvcTestResult failure = post(BODY);
        assertProblem(failure, HttpStatus.SERVICE_UNAVAILABLE, "catalog-unavailable");
        assertThat(failure).hasHeader("Retry-After", "5");
        assertThat(failure).bodyJson().extractingPath("$.reason").isEqualTo("failure");
        assertThat(failure).bodyJson().extractingPath("$.detail").asString().contains("La venta no se creó");

        MvcTestResult open = post(BODY);
        assertThat(open).hasHeader("Retry-After", "13");
        assertThat(open).bodyJson().extractingPath("$.reason").isEqualTo("circuit-open");
    }

    @Test
    @DisplayName("consultar por id y por código de referencia; inexistente es 404 y un id que no es UUID 400")
    void get() {
        Sale sale = sale();
        given(getSale.byId(sale.id())).willReturn(sale);
        given(getSale.byReferenceCode("VENTA-0001")).willReturn(sale);
        UUID missing = UUID.randomUUID();
        given(getSale.byId(new SaleId(missing))).willThrow(new SaleNotFoundException("No existe"));

        assertThat(mvc.get().uri(URL + "/{id}", sale.id().value()).exchange()).hasStatusOk();
        assertThat(mvc.get().uri(URL + "/by-reference/VENTA-0001").exchange()).hasStatusOk()
                .bodyJson().extractingPath("$.referenceCode").isEqualTo("VENTA-0001");
        assertProblem(mvc.get().uri(URL + "/{id}", missing).exchange(), HttpStatus.NOT_FOUND, "sale-not-found");
        assertProblem(mvc.get().uri(URL + "/no-es-uuid").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
    }

    @Test
    @DisplayName("el listado usa el mismo formato de página que catalog y valida page y size")
    void list() {
        given(listSales.list(any())).willReturn(new SalePage(List.of(sale()), 1, 5, 6, 2));

        MvcTestResult result = mvc.get().uri(URL + "?status=CONFIRMED&page=1&size=5").exchange();

        assertThat(result).hasStatusOk();
        assertThat(result).bodyJson().extractingPath("$.items[0].referenceCode").isEqualTo("VENTA-0001");
        assertThat(result).bodyJson().extractingPath("$.totalItems").isEqualTo(6);
        assertThat(result).bodyJson().extractingPath("$.totalPages").isEqualTo(2);
        verify(listSales).list(new ListSalesQuery(SaleStatus.CONFIRMED, 1, 5));
        assertProblem(mvc.get().uri(URL + "?size=101").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
        assertProblem(mvc.get().uri(URL + "?status=CANCELLED").exchange(), HttpStatus.BAD_REQUEST, "invalid-parameter");
    }
}
