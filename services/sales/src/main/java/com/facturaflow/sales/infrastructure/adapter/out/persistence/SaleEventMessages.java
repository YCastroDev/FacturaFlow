package com.facturaflow.sales.infrastructure.adapter.out.persistence;

import com.facturaflow.sales.application.port.out.OutboxMessage;
import com.facturaflow.sales.domain.model.CustomerSnapshot;
import com.facturaflow.sales.domain.model.Money;
import com.facturaflow.sales.domain.model.PaymentTerms;
import com.facturaflow.sales.domain.model.Sale;
import com.facturaflow.sales.domain.model.SaleEvent;
import com.facturaflow.sales.domain.model.SaleLine;
import com.facturaflow.sales.domain.model.TaxType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Traduce los eventos de dominio de la venta a los mensajes que se publican (ADR 0013). Es el ÚNICO lugar
 * que conoce el contrato {@code docs/events/sale-confirmed.v1.schema.json}; {@code SaleConfirmedSchemaTest}
 * valida contra ese esquema lo que produce. El mensaje se serializa al guardar la venta: lo que se publica
 * (y se republica en cada reintento) es exactamente lo que había en ese momento.
 *
 * <p>Nombres alineados con {@code POST /api/v1/invoices} de invoicing (ADR 0012, D6) para que la Fase 2
 * cree la factura sin traducir nombres; los enums van por nombre (los mismos en los tres servicios).
 * Los opcionales vacíos van como {@code null} explícito: la forma del mensaje es siempre la misma.
 */
@Component
class SaleEventMessages {

    static final String PRODUCER = "sales";
    static final String AGGREGATE_TYPE = "Sale";
    static final String SALE_CONFIRMED = "SaleConfirmed";
    static final int SALE_CONFIRMED_VERSION = 1;
    /** {@code <agregado>.<hecho>.v<versión>}: un consumidor puede enlazarse a una versión concreta. */
    static final String SALE_CONFIRMED_ROUTING_KEY = "sale.confirmed.v1";

    /**
     * Mapper propio (como el del adaptador de catalog): el contrato no cambia si cambia la configuración
     * de Jackson de la API REST. Orden de declaración (Jackson 3 ordena alfabéticamente por defecto) y
     * decimales sin notación científica.
     */
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    /** Un evento de la venta como mensaje listo para el outbox, con un {@code eventId} nuevo. */
    OutboxMessage toMessage(SaleEvent event, Sale sale) {
        if (!event.saleId().equals(sale.id())) {
            throw new IllegalArgumentException("El evento " + event + " no es de la venta " + sale.id().value());
        }
        UUID eventId = UUID.randomUUID();
        return switch (event) {
            case SaleEvent.SaleConfirmed confirmed -> new OutboxMessage(eventId, SALE_CONFIRMED,
                    SALE_CONFIRMED_VERSION, SALE_CONFIRMED_ROUTING_KEY,
                    JSON.writeValueAsString(new Envelope(eventId, SALE_CONFIRMED, SALE_CONFIRMED_VERSION,
                            confirmed.occurredAt(), PRODUCER, saleConfirmedV1(sale))),
                    confirmed.occurredAt());
        };
    }

    private static SaleConfirmedV1 saleConfirmedV1(Sale sale) {
        CustomerSnapshot customer = sale.customer();
        Customer customerData = new Customer(customer.customerId(), customer.documentType().name(),
                customer.documentNumber(), customer.checkDigit(), customer.personType().name(),
                customer.companyName(), customer.names(), customer.taxScheme().name(),
                customer.fiscalResponsibilities().stream().map(Enum::name).toList(), customer.email(),
                customer.municipalityCode(), customer.address(), customer.phone());
        Terms terms = switch (sale.paymentTerms()) {
            case PaymentTerms.Cash ignored -> new Terms("CASH", null);
            case PaymentTerms.Credit credit -> new Terms("CREDIT", credit.dueDate());
        };
        return new SaleConfirmedV1(sale.id().value(), sale.referenceCode().value(), sale.saleDate(), customerData,
                sale.lines().stream().map(SaleEventMessages::line).toList(), sale.paymentMethod().name(), terms,
                sale.observation().orElse(null), totals(sale));
    }

    private static Line line(SaleLine line) {
        return new Line(line.productId(), line.code(), line.name(), line.quantity().value(),
                line.unitPrice().amount(), line.discountRate().percent(), line.unit().name(), line.ivaExcluded(),
                line.taxes().stream().map(tax -> new Tax(tax.type().name(), tax.rate().percent())).toList());
    }

    /** Mismo desglose que la respuesta de invoicing: solo los tipos presentes en alguna línea, en orden del enum. */
    private static Totals totals(Sale sale) {
        List<TaxTotal> taxes = Arrays.stream(TaxType.values())
                .filter(type -> sale.lines().stream()
                        .anyMatch(line -> line.taxes().stream().anyMatch(tax -> tax.type() == type)))
                .map(type -> new TaxTotal(type.name(), amount(sale.totalTax(type))))
                .toList();
        return new Totals(amount(sale.subtotal()), amount(sale.totalDiscount()), taxes, amount(sale.totalTax()),
                amount(sale.total()));
    }

    private static BigDecimal amount(Money money) {
        return money.amount();
    }

    // ----- Contrato (sale-confirmed.v1.schema.json). Un cambio aquí exige revisar el esquema. -----

    record Envelope(UUID eventId, String eventType, int eventVersion, Instant occurredAt, String producer,
                    Object payload) {
    }

    record SaleConfirmedV1(UUID saleId, String referenceCode, LocalDate saleDate, Customer customer,
                           List<Line> lines, String paymentMethod, Terms paymentTerms, String observation,
                           Totals totals) {
    }

    record Customer(UUID customerId, String documentType, String documentNumber, Integer checkDigit,
                    String personType, String companyName, String names, String taxScheme,
                    List<String> fiscalResponsibilities, String email, String municipalityCode, String address,
                    String phone) {
    }

    record Line(UUID productId, String code, String name, BigDecimal quantity, BigDecimal unitPrice,
                BigDecimal discountRate, String unit, boolean ivaExcluded, List<Tax> taxes) {
    }

    record Tax(String type, BigDecimal rate) {
    }

    record Terms(String type, LocalDate dueDate) {
    }

    /**
     * INFORMATIVO: los totales según la copia del cálculo que tiene sales (ADR 0012, D7). Invoicing NO los
     * usa para calcular (es la autoridad); en la Fase 2 los comparará con los suyos y registrará un WARN si
     * difieren, para detectar que las dos copias del cálculo divergieron.
     */
    record Totals(BigDecimal subtotal, BigDecimal totalDiscount, List<TaxTotal> taxes, BigDecimal totalTax,
                  BigDecimal total) {
    }

    record TaxTotal(String type, BigDecimal amount) {
    }
}
