package com.facturaflow.invoicing.application.service;

import static com.facturaflow.invoicing.application.service.CreateInvoiceCommands.REFERENCE_CODE;
import static com.facturaflow.invoicing.application.service.CreateInvoiceCommands.command;
import static com.facturaflow.invoicing.application.service.CreateInvoiceCommands.creditCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.facturaflow.invoicing.application.exception.ReferenceCodeConflictException;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceCommand;
import com.facturaflow.invoicing.application.port.in.CreateInvoiceResult;
import com.facturaflow.invoicing.domain.exception.DomainException;
import com.facturaflow.invoicing.domain.exception.InvalidIdentificationException;
import com.facturaflow.invoicing.domain.exception.InvalidPaymentTermsException;
import com.facturaflow.invoicing.domain.model.DocumentType;
import com.facturaflow.invoicing.domain.model.FiscalResponsibility;
import com.facturaflow.invoicing.domain.model.InvoiceStatus;
import com.facturaflow.invoicing.domain.model.Money;
import com.facturaflow.invoicing.domain.model.PaymentMethod;
import com.facturaflow.invoicing.domain.model.PersonType;
import com.facturaflow.invoicing.domain.model.ReferenceCode;
import com.facturaflow.invoicing.domain.model.TaxScheme;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CreateInvoiceServiceTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    /** 03:00 UTC del 21 de octubre = 22:00 del 20 de octubre en Bogotá. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T03:00:00Z"), BOGOTA);

    private final InMemoryInvoiceRepository repository = new InMemoryInvoiceRepository();
    private final CreateInvoiceService service = new CreateInvoiceService(repository, CLOCK);

    @Test
    @DisplayName("un código de referencia nuevo crea una factura PENDING con la fecha de Colombia del reloj")
    void createsPendingInvoice() {
        CreateInvoiceResult result = service.create(command());

        assertThat(result.created()).isTrue();
        assertThat(result.invoice().status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(result.invoice().issueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(result.invoice().version()).contains(0L);
        assertThat(result.invoice().total()).isEqualTo(Money.of("119000.00"));
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("guarda la huella de la petición junto con la factura")
    void storesRequestHash() {
        service.create(command());

        assertThat(repository.findStoredByReferenceCode(new ReferenceCode(REFERENCE_CODE)).orElseThrow().requestHash())
                .isEqualTo(InvoiceRequestHasher.hash(InvoiceContentMapper.toContent(command())));
    }

    @Test
    @DisplayName("repetir la misma petición devuelve la factura existente sin crear otra")
    void identicalRetryReturnsExisting() {
        CreateInvoiceResult first = service.create(command());

        CreateInvoiceResult retry = service.create(command());

        assertThat(retry.created()).isFalse();
        assertThat(retry.invoice().id()).isEqualTo(first.invoice().id());
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("un reintento que solo difiere en formato (espacios, decimales) se reconoce como el mismo")
    void equivalentRetryReturnsExisting() {
        CreateInvoiceResult first = service.create(command(REFERENCE_CODE, "Entregar en portería"));

        CreateInvoiceResult retry = service.create(command(" " + REFERENCE_CODE + " ", "  Entregar en portería  "));

        assertThat(retry.created()).isFalse();
        assertThat(retry.invoice().id()).isEqualTo(first.invoice().id());
    }

    @Test
    @DisplayName("el mismo código de referencia con otro contenido es un conflicto y no modifica la existente")
    void differentContentIsConflict() {
        CreateInvoiceResult first = service.create(command(REFERENCE_CODE, "Entregar en portería"));

        assertThatThrownBy(() -> service.create(command(REFERENCE_CODE, "Otra observación")))
                .isInstanceOf(ReferenceCodeConflictException.class)
                .hasMessageContaining(REFERENCE_CODE);
        assertThat(repository.size()).isEqualTo(1);
        assertThat(repository.findById(first.invoice().id()).orElseThrow().observation())
                .hasValue("Entregar en portería");
    }

    @Test
    @DisplayName("si otra petición idéntica gana la carrera al insertar, se devuelve la suya en vez de fallar")
    void raceWithIdenticalRequestReturnsWinner() {
        CreateInvoiceService competitor = new CreateInvoiceService(repository, CLOCK);
        CreateInvoiceResult[] winner = new CreateInvoiceResult[1];
        repository.beforeNextSaveNew(() -> winner[0] = competitor.create(command()));

        CreateInvoiceResult result = service.create(command());

        assertThat(winner[0].created()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.invoice().id()).isEqualTo(winner[0].invoice().id());
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("si otra petición con contenido distinto gana la carrera al insertar, es un conflicto")
    void raceWithDifferentRequestIsConflict() {
        CreateInvoiceService competitor = new CreateInvoiceService(repository, CLOCK);
        repository.beforeNextSaveNew(() -> competitor.create(command(REFERENCE_CODE, "Otra observación")));

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(ReferenceCodeConflictException.class);
        assertThat(repository.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("reintentar al día siguiente una venta a crédito devuelve la factura aunque el vencimiento ya no sea futuro")
    void retryOnLaterDayIsStillReplay() {
        LocalDate dueDate = LocalDate.of(2026, 10, 21);
        CreateInvoiceResult first = service.create(creditCommand(dueDate));
        Clock twoDaysLater = Clock.offset(CLOCK, Duration.ofDays(2));

        CreateInvoiceResult retry = new CreateInvoiceService(repository, twoDaysLater).create(creditCommand(dueDate));

        assertThat(retry.created()).isFalse();
        assertThat(retry.invoice().id()).isEqualTo(first.invoice().id());
        assertThat(retry.invoice().issueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    @DisplayName("una venta a crédito con vencimiento no posterior a la emisión se rechaza por regla de negocio")
    void creditDueDateMustBeAfterIssueDate() {
        assertThatThrownBy(() -> service.create(creditCommand(LocalDate.of(2026, 10, 20))))
                .isInstanceOf(InvalidPaymentTermsException.class);
        assertThat(repository.size()).isZero();
    }

    @Test
    @DisplayName("los datos que violan reglas de negocio se rechazan con la excepción del dominio y no se guarda nada")
    void businessRulesAreValidatedByDomain() {
        CreateInvoiceCommand wrongCheckDigit = new CreateInvoiceCommand(REFERENCE_CODE,
                new CreateInvoiceCommand.Customer(DocumentType.NIT, "890903938", 1, PersonType.LEGAL_ENTITY, "Cliente",
                        null, TaxScheme.IVA, List.of(FiscalResponsibility.LARGE_TAXPAYER), null, null, null, null),
                command().lines(), PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), null);
        CreateInvoiceCommand noLines = new CreateInvoiceCommand(REFERENCE_CODE, command().customer(), List.of(),
                PaymentMethod.CASH, new CreateInvoiceCommand.CashTerms(), null);
        CreateInvoiceCommand badReference = command("código con espacios", null);

        assertThatThrownBy(() -> service.create(wrongCheckDigit)).isInstanceOf(InvalidIdentificationException.class);
        assertThatThrownBy(() -> service.create(noLines)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.create(badReference)).isInstanceOf(DomainException.class);
        assertThat(repository.size()).isZero();
    }
}
