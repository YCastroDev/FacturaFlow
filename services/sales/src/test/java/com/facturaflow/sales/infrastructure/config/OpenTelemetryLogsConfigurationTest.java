package com.facturaflow.sales.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * El appender de logs de OpenTelemetry (ADR 0020, D1), sin Spring: un SDK con un exportador en memoria. Copia deliberada
 * en los cuatro servicios, como {@link OpenTelemetryLogsConfiguration}.
 */
class OpenTelemetryLogsConfigurationTest {

    private final InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    private OpenTelemetrySdk sdk;

    @BeforeEach
    void installAppender() throws Exception {
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setLoggerProvider(SdkLoggerProvider.builder()
                        .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter)).build())
                .build();
        install(sdk);
    }

    @AfterEach
    void closeSdk() {
        sdk.close();
    }

    private static void install(OpenTelemetry openTelemetry) throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("openTelemetry", openTelemetry);
        new OpenTelemetryLogsConfiguration().openTelemetryLogbackAppender(beans.getBeanProvider(OpenTelemetry.class))
                .afterPropertiesSet();
    }

    private List<LogRecordData> exported(String message) {
        return exporter.getFinishedLogRecordItems().stream()
                .filter(record -> message.equals(record.getBodyValue().asString())).toList();
    }

    @Test
    @DisplayName("un log escrito dentro de una traza se exporta con su trace_id y su span_id")
    void logCarriesTheCurrentTrace() {
        Span span = sdk.getTracer("test").spanBuilder("operación").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            LoggerFactory.getLogger("com.facturaflow.test").info("dentro de la traza");
        } finally {
            span.end();
        }

        List<LogRecordData> records = exported("dentro de la traza");
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getSpanContext().getTraceId()).isEqualTo(span.getSpanContext().getTraceId());
        assertThat(records.getFirst().getSpanContext().getSpanId()).isEqualTo(span.getSpanContext().getSpanId());
    }

    @Test
    @DisplayName("los logs del propio SDK de OpenTelemetry no se exportan (sin bucle si el colector está caído)")
    void openTelemetryLogsAreNotExported() {
        LoggerFactory.getLogger("io.opentelemetry.exporter.internal.http.HttpExporter").warn("falló la exportación");

        assertThat(exported("falló la exportación")).isEmpty();
    }

    @Test
    @DisplayName("instalarlo varias veces (varios contextos de Spring) deja un solo appender: ningún log se duplica")
    void installIsIdempotent() throws Exception {
        install(sdk);

        LoggerFactory.getLogger("com.facturaflow.test").info("una sola vez");

        assertThat(exported("una sola vez")).hasSize(1);
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        assertThat(root.getAppender(OpenTelemetryLogsConfiguration.APPENDER_NAME)).isNotNull();
    }
}
