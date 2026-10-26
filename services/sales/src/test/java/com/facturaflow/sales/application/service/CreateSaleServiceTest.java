package com.facturaflow.sales.application.service;

import static com.facturaflow.sales.domain.model.SaleFixtures.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.sales.application.exception.CatalogUnavailableException;
import com.facturaflow.sales.application.exception.InvalidCatalogReferencesException;
import com.facturaflow.sales.application.exception.ReferenceCodeConflictException;
import com.facturaflow.sales.application.port.in.CreateSaleCommand;
import com.facturaflow.sales.application.port.in.CreateSaleResult;
import com.facturaflow.sales.application.port.in.ListSalesQuery;
import com.facturaflow.sales.application.port.out.CatalogProduct;
import com.facturaflow.sales.application.port.out.StoredSale;
import com.facturaflow.sales.domain.exception.InvalidPaymentTermsException;
import com.facturaflow.sales.domain.exception.InvalidQuantityException;
import com.facturaflow.sales.domain.exception.InvalidSaleException;
import com.facturaflow.sales.domain.model.DocumentType;
import com.facturaflow.sales.domain.model.LineTax;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.PaymentMethod;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleEvent;
import com.facturaflow.sales.domain.model.SaleLine;
import com.facturaflow.sales.domain.model.SaleStatus;
import com.facturaflow.sales.domain.model.TaxType;
import com.facturaflow.sales.domain.model.UnitOfMeasure;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El caso de uso sin Spring ni red: repositorio en memoria y catalog falso. */
class CreateSaleServiceTest {

    /** 10:00 en Bogotá del 23 de octubre (15:00 UTC). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T15:00:00Z"), ZoneId.of("America/Bogota"));
    private static final UUID COFFEE_ID = UUID.fromString("9f000000-0000-4000-8000-0000000000c1");

    private static CatalogProduct coffee(String price, boolean active) {
        return new CatalogProduct(COFFEE_ID, "CAFE-500", "Café molido 500 g", Money.of(price), UnitOfMeasure.UNIT, false,
                List.of(LineTax.of(TaxType.IVA, "5")), active);
    }

    private static CatalogProduct rice(boolean active) {
        return new CatalogProduct(UUID.randomUUID(), "ARROZ-KG", "Arroz", Money.of("4200"), UnitOfMeasure.KILOGRAM,
                true, List.of(), active);
    }

    private final InMemorySaleRepository repository = new InMemorySaleRepository();
    private final InMemoryCatalog catalog = new InMemoryCatalog()
            .withCustomer(customer(), true)
            .withProduct(coffee("18500", true))
            .withProduct(rice(true));
    private final CreateSaleService service = new CreateSaleService(repository, catalog, CLOCK);

    private static CreateSaleCommand command(String reference, CreateSaleCommand.Terms terms,
                                             CreateSaleCommand.Line... lines) {
        return new CreateSaleCommand(reference, DocumentType.NIT, " 890903938 ", List.of(lines), PaymentMethod.CASH,
                terms, "Entregar en portería");
    }

    private static CreateSaleCommand cash(String reference, CreateSaleCommand.Line... lines) {
        return command(reference, new CreateSaleCommand.CashTerms(), lines);
    }

    private static CreateSaleCommand.Line line(String code, String quantity, String discount) {
        return new CreateSaleCommand.Line(code, new BigDecimal(quantity), discount == null ? null : new BigDecimal(discount));
    }

    @Test
    @DisplayName("confirma la venta con los datos de catalog: precio, nombre, impuestos y cliente salen de allí")
    void confirmsWithCatalogData() {
        CreateSaleResult result = service.create(cash("VENTA-1", line("cafe-500", "2", "10"), line("ARROZ-KG", "1.5", null)));

        Sale sale = result.sale();
        assertThat(result.created()).isTrue();
        assertThat(sale.status()).isEqualTo(SaleStatus.CONFIRMED);
        assertThat(sale.saleDate()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(sale.customer()).isEqualTo(customer());
        SaleLine coffee = sale.lines().getFirst();
        assertThat(coffee.productId()).isEqualTo(COFFEE_ID);
        assertThat(coffee.code()).as("el código de catalog, no el de la petición").isEqualTo("CAFE-500");
        assertThat(coffee.unitPrice()).isEqualTo(Money.of("18500"));
        assertThat(coffee.taxes()).containsExactly(LineTax.of(TaxType.IVA, "5"));
        // 2 x 18.500 = 37.000 - 10% = 33.300 + 5% (1.665) = 34.965; arroz 1,5 x 4.200 = 6.300 excluido.
        assertThat(sale.total()).isEqualTo(Money.of("41265.00"));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("la venta llega al repositorio con su evento SaleConfirmed; un reintento no genera otro evento")
    void savesSaleConfirmedEventOnce() {
        CreateSaleResult created = service.create(cash("VENTA-1", line("cafe-500", "2", "10")));
        service.create(cash("VENTA-1", line("cafe-500", "2", "10")));

        assertThat(repository.savedEvents())
                .containsExactly(new SaleEvent.SaleConfirmed(created.sale().id(), CLOCK.instant()));
        assertThat(created.sale().pendingEvents()).as("la venta devuelta es la guardada, sin eventos").isEmpty();
    }

    @Test
    @DisplayName("una venta de muchas líneas hace 2 llamadas a catalog: el cliente y UN lote de productos (sin N+1)")
    void twoCatalogCallsRegardlessOfLines() {
        service.create(cash("VENTA-1", line("CAFE-500", "1", null), line("ARROZ-KG", "1", null),
                line("cafe-500", "3", "5"), line("Arroz-Kg", "2", null)));

        assertThat(catalog.calls()).isEqualTo(2);
        assertThat(catalog.productLookups()).hasSize(1);
        assertThat(catalog.productLookups().getFirst()).as("sin repetidos de la petición").hasSize(4);
    }

    @Test
    @DisplayName("un reintento idéntico devuelve la venta existente sin llamar a catalog (funciona aunque esté caído)")
    void replayDoesNotCallCatalog() {
        Sale first = service.create(cash("VENTA-1", line("CAFE-500", "2", "10"))).sale();
        catalog.goDown();
        int callsBefore = catalog.calls();

        CreateSaleResult replay = service.create(cash("VENTA-1", line("cafe-500 ", "2.00", "10.0")));

        assertThat(replay.created()).isFalse();
        assertThat(replay.sale().id()).isEqualTo(first.id());
        assertThat(catalog.calls()).isEqualTo(callsBefore);
    }

    @Test
    @DisplayName("si el precio cambió en catalog, el reintento sigue siendo la misma venta, con el precio de entonces")
    void replayKeepsOriginalPrices() {
        service.create(cash("VENTA-1", line("CAFE-500", "1", null)));
        catalog.withProduct(coffee("99999", true));

        Sale replay = service.create(cash("VENTA-1", line("CAFE-500", "1", null))).sale();

        assertThat(replay.lines().getFirst().unitPrice()).isEqualTo(Money.of("18500"));
    }

    @Test
    @DisplayName("el mismo código de referencia con otro contenido es un conflicto")
    void conflictingContent() {
        service.create(cash("VENTA-1", line("CAFE-500", "1", null)));

        assertThatThrownBy(() -> service.create(cash("VENTA-1", line("CAFE-500", "2", null))))
                .isInstanceOf(ReferenceCodeConflictException.class).hasMessageContaining("VENTA-1");
    }

    @Test
    @DisplayName("si otra petición idéntica gana la carrera al guardar, se devuelve la ganadora")
    void raceWithIdenticalRequest() {
        Sale winner = service.create(cash("VENTA-W", line("CAFE-500", "1", null))).sale();
        StoredSale stored = repository.findStoredByReferenceCode(winner.referenceCode()).orElseThrow();
        InMemorySaleRepository racing = new InMemorySaleRepository();
        racing.loseNextRaceTo(stored);
        CreateSaleService racingService = new CreateSaleService(racing, catalog, CLOCK);

        CreateSaleResult result = racingService.create(cash("VENTA-W", line("CAFE-500", "1", null)));

        assertThat(result.created()).isFalse();
        assertThat(result.sale().id()).isEqualTo(winner.id());
    }

    @Test
    @DisplayName("cliente inexistente: 422 y la venta no se crea")
    void missingCustomer() {
        CreateSaleCommand command = new CreateSaleCommand("VENTA-1", DocumentType.CITIZENSHIP_CARD, "1234567",
                List.of(line("CAFE-500", "1", null)), PaymentMethod.CASH, new CreateSaleCommand.CashTerms(), null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(InvalidCatalogReferencesException.class)
                .hasMessageContaining("No existe en catalog un cliente con cédula de ciudadanía 1234567");
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("cliente inactivo y productos inexistentes e inactivos: un solo 422 con todos los problemas")
    void allProblemsTogether() {
        catalog.withCustomer(customer(), false).withProduct(rice(false));

        assertThatThrownBy(() -> service.create(cash("VENTA-1", line("CAFE-500", "1", null), line("arroz-kg", "1", null),
                line("NO-EXISTE", "1", null), line("no-existe", "2", null))))
                .isInstanceOfSatisfying(InvalidCatalogReferencesException.class, e -> {
                    assertThat(e.customerProblem()).hasValueSatisfying(text -> assertThat(text).contains("inactivo"));
                    assertThat(e.missingProducts()).containsExactly("NO-EXISTE", "no-existe");
                    assertThat(e.inactiveProducts()).containsExactly("ARROZ-KG");
                });
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("un dato mal formado es 422 ANTES de consultar a catalog")
    void invalidRequestDoesNotCallCatalog() {
        assertThatThrownBy(() -> service.create(cash("VENTA-1", line("CAFE-500", "0", null))))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> service.create(cash("VENTA-1",
                Collections.nCopies(101, line("CAFE-500", "1", null)).toArray(CreateSaleCommand.Line[]::new))))
                .isInstanceOf(InvalidSaleException.class).hasMessageContaining("100");
        assertThat(catalog.calls()).isZero();
    }

    @Test
    @DisplayName("a crédito, el vencimiento debe ser posterior a hoy (fecha de la venta en Colombia)")
    void creditDueDate() {
        assertThatThrownBy(() -> service.create(command("VENTA-1",
                new CreateSaleCommand.CreditTerms(LocalDate.of(2026, 10, 23)), line("CAFE-500", "1", null))))
                .isInstanceOf(InvalidPaymentTermsException.class);
        assertThat(service.create(command("VENTA-2", new CreateSaleCommand.CreditTerms(LocalDate.of(2026, 11, 22)),
                line("CAFE-500", "1", null))).created()).isTrue();
    }

    @Test
    @DisplayName("catalog caído: 503 y la venta no se crea")
    void catalogDown() {
        catalog.goDown();

        assertThatThrownBy(() -> service.create(cash("VENTA-1", line("CAFE-500", "1", null))))
                .isInstanceOf(CatalogUnavailableException.class);
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("la consulta y el listado devuelven lo guardado")
    void getAndList() {
        Sale sale = service.create(cash("VENTA-1", line("CAFE-500", "1", null))).sale();
        GetSaleService get = new GetSaleService(repository);

        assertThat(get.byId(sale.id()).referenceCode().value()).isEqualTo("VENTA-1");
        assertThat(get.byReferenceCode(" VENTA-1 ").id()).isEqualTo(sale.id());
        assertThat(new ListSalesService(repository).list(
                new ListSalesQuery(null, 0, 10)).totalItems()).isEqualTo(1);
        assertThat(Set.of(sale.status())).containsExactly(SaleStatus.CONFIRMED);
    }
}
