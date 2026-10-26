package com.facturaflow.sales;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Los contratos de eventos de {@code docs/events/} (ADR 0013), leídos del MISMO archivo que usará el
 * consumidor: sin copias ni librería compartida. La ruta es relativa a {@code services/sales}: funciona con
 * {@code ./scripts/mvn.sh} (que monta el repo completo) y desde un IDE abierto en el servicio.
 */
public final class EventContracts {

    private static final Path EVENTS = Path.of("../../docs/events");
    public static final Path SALE_CONFIRMED_V1 = EVENTS.resolve("sale-confirmed.v1.schema.json");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Schema SALE_CONFIRMED_V1_SCHEMA = load(SALE_CONFIRMED_V1);
    /** Los resultados de facturación que consume sales (ADR 0015), por {@code eventType}. */
    private static final Map<String, Schema> INVOICE_RESULTS = Map.of(
            "InvoiceIssued", load(EVENTS.resolve("invoice-issued.v1.schema.json")),
            "InvoiceRejected", load(EVENTS.resolve("invoice-rejected.v1.schema.json")),
            "InvoiceRequestRejected", load(EVENTS.resolve("invoice-request-rejected.v1.schema.json")));

    private EventContracts() {
    }

    /** Violaciones de {@code SaleConfirmed} v1; vacía si el mensaje cumple el contrato. */
    public static List<String> saleConfirmedV1Violations(String message) {
        return saleConfirmedV1Violations(JSON.readTree(message));
    }

    public static List<String> saleConfirmedV1Violations(JsonNode message) {
        return SALE_CONFIRMED_V1_SCHEMA.validate(message).stream().map(Error::toString).toList();
    }

    /** Violaciones de un resultado de facturación v1 contra el esquema de su {@code eventType}. */
    public static List<String> invoiceResultViolations(String message) {
        JsonNode json = JSON.readTree(message);
        Schema schema = INVOICE_RESULTS.get(json.path("eventType").asString(""));
        if (schema == null) {
            return List.of("eventType desconocido: " + json.path("eventType"));
        }
        return schema.validate(json).stream().map(Error::toString).toList();
    }

    private static Schema load(Path path) {
        // En draft 2020-12, "format" solo anota por defecto; aquí también se exige (uuid, date, date-time).
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .formatAssertionsEnabled(true).build()));
        try (InputStream in = Files.newInputStream(path)) {
            return registry.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el contrato " + path.toAbsolutePath(), e);
        }
    }
}
