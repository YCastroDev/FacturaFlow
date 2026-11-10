package com.facturaflow.invoicing.infrastructure.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Logs a Loki por OTLP (ADR 0020, D1). Agrega al logger raíz de Logback el appender de OpenTelemetry y lo conecta al
 * SDK de Spring Boot: cada registro sale con el {@code trace_id} y el {@code span_id} de la traza en curso, así Grafana
 * salta de un log a su traza y de una traza a sus logs.
 *
 * <p>Por código y no con un {@code logback-spring.xml}: la configuración de Boot queda intacta (incluido el JSON ECS de
 * la consola, {@code logging.structured.format.console}). El appender solo EXPORTA si hay un exportador de logs, es
 * decir, si está definido {@code MANAGEMENT_OPENTELEMETRY_LOGGING_EXPORT_OTLP_ENDPOINT} (el compose); si no, los
 * registros se descartan sin costo apreciable.
 *
 * <p>Los logs del propio SDK de OpenTelemetry no se exportan: una falla de exportación (colector caído) genera un
 * WARN, que volvería a exportarse y a fallar.
 *
 * <p>Copia deliberada en los cuatro servicios: si cambia una, cambian las cuatro (con su test).
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLogsConfiguration {

    static final String APPENDER_NAME = "OTEL";
    static final String OPENTELEMETRY_LOGGERS = "io.opentelemetry";

    @Bean
    InitializingBean openTelemetryLogbackAppender(ObjectProvider<OpenTelemetry> openTelemetry) {
        return () -> openTelemetry.ifAvailable(sdk -> {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
                Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                // Idempotente: en los tests varios contextos de Spring comparten el mismo Logback.
                if (root.getAppender(APPENDER_NAME) == null) {
                    OpenTelemetryAppender appender = new OpenTelemetryAppender();
                    appender.setName(APPENDER_NAME);
                    appender.setContext(context);
                    appender.addFilter(new ExcludeOpenTelemetryLoggers());
                    appender.start();
                    root.addAppender(appender);
                }
            }
            // Conecta todos los appenders de OpenTelemetry al SDK de este contexto (el último instalado).
            OpenTelemetryAppender.install(sdk);
        });
    }

    /** Descarta los logs del SDK de OpenTelemetry (evita el bucle exportar → fallar → registrar → exportar). */
    static final class ExcludeOpenTelemetryLoggers extends Filter<ILoggingEvent> {

        @Override
        public FilterReply decide(ILoggingEvent event) {
            return event.getLoggerName().startsWith(OPENTELEMETRY_LOGGERS) ? FilterReply.DENY : FilterReply.NEUTRAL;
        }
    }
}
