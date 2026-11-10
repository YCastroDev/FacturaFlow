package com.facturaflow.invoicing;

import static org.awaitility.Awaitility.await;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Trazas en los tests sin el colector (ADR 0019): un {@link InMemorySpanExporter} que Spring Boot conecta al SDK de
 * OpenTelemetry como cualquier otro exportador. Exige {@code @AutoConfigureTracing} (los tests apagan la exportación
 * por defecto) y conviene un {@code management.opentelemetry.tracing.export.schedule-delay} corto.
 *
 * <p>Copia deliberada en sales e invoicing (sin código compartido entre servicios).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TracingTestConfiguration {

    public static final Duration WAIT = Duration.ofSeconds(15);

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return InMemorySpanExporter.create();
    }

    /** Un {@code traceparent} W3C nuevo (versión 00, muestreado), como el que enviaría un cliente o un productor. */
    public static String newTraceParent() {
        return "00-" + randomHex(16) + "-" + randomHex(8) + "-01";
    }

    public static String traceId(String traceParent) {
        return traceParent.split("-")[1];
    }

    public static String spanId(String traceParent) {
        return traceParent.split("-")[2];
    }

    /** Espera a que se exporte un span que cumpla {@code condition} y lo devuelve. */
    public static SpanData awaitSpan(InMemorySpanExporter exporter, Predicate<SpanData> condition) {
        return await().atMost(WAIT).until(() -> exporter.getFinishedSpanItems().stream().filter(condition).findFirst(),
                found -> found.isPresent()).orElseThrow();
    }

    /** Los spans exportados de una traza. */
    public static List<SpanData> spansOf(InMemorySpanExporter exporter, String traceId) {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getTraceId().equals(traceId)).toList();
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        random[0] |= 1;   // nunca todo ceros (inválido en W3C)
        return HexFormat.of().formatHex(random);
    }
}
