package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.application.port.out.ElectronicCreditNoteProvider;
import com.facturaflow.invoicing.application.port.out.ElectronicInvoiceProvider;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Cómo se activa el adaptador y qué pasa si falta configuración (sin levantar la aplicación entera). */
class FactusConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FactusConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC);

    private static final String[] VALID = {
            "invoicing.provider=factus",
            "invoicing.factus.base-url=https://api-sandbox.factus.com.co",
            "invoicing.factus.client-id=id",
            "invoicing.factus.client-secret=secreto-que-no-debe-aparecer",
            "invoicing.factus.username=usuario@empresa.example",
            "invoicing.factus.password=clave-que-no-debe-aparecer",
            "invoicing.factus.send-email=false",
            "invoicing.factus.numbering-range-id=",
            "invoicing.factus.credit-note-numbering-range-id=390",
            "invoicing.factus.connect-timeout=5s",
            "invoicing.factus.read-timeout=30s",
            "invoicing.factus.token-refresh-margin=60s",
            "invoicing.factus.user-agent=FacturaFlow-Test/1.0",
            "invoicing.factus.resilience.retry.max-attempts=3",
            "invoicing.factus.resilience.retry.initial-wait=500ms",
            "invoicing.factus.resilience.retry.multiplier=2.0",
            "invoicing.factus.resilience.retry.jitter=0.5",
            "invoicing.factus.resilience.retry.max-retry-after=5s",
            "invoicing.factus.resilience.circuit-breaker.sliding-window-size=10",
            "invoicing.factus.resilience.circuit-breaker.minimum-number-of-calls=5",
            "invoicing.factus.resilience.circuit-breaker.failure-rate-threshold=50",
            "invoicing.factus.resilience.circuit-breaker.wait-duration-in-open-state=30s",
            "invoicing.factus.resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state=2",
            "invoicing.factus.resilience.rate-limiter.limit-for-period=10",
            "invoicing.factus.resilience.rate-limiter.limit-refresh-period=6s",
            "invoicing.factus.resilience.rate-limiter.timeout=2s"};

    @Test
    @DisplayName("con invoicing.provider=factus los dos puertos (facturas y notas) los implementa el MISMO adaptador")
    void factusProviderIsActive() {
        runner.withPropertyValues(VALID).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ElectronicInvoiceProvider.class))
                    .isInstanceOf(FactusElectronicInvoiceProvider.class)
                    .isSameAs(context.getBean(ElectronicCreditNoteProvider.class));
            assertThat(context.getBean(FactusProperties.class).creditNoteNumberingRangeId()).isEqualTo(390);
            assertThat(context.getBean(FactusProperties.class).numberingRangeId()).isNull();
            assertThat(context.getBean(FactusProperties.class).resilience().rateLimiter().limitForPeriod())
                    .isEqualTo(10);
            assertThat(context).hasSingleBean(FactusHealthIndicator.class);
        });
    }

    @Test
    @DisplayName("con otro proveedor no se crea el adaptador ni se exigen credenciales de Factus")
    void inactiveWithOtherProvider() {
        runner.withPropertyValues("invoicing.provider=fake").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ElectronicInvoiceProvider.class);
        });
    }

    @Test
    @DisplayName("si falta una credencial, la aplicación no arranca, y el error no muestra las demás")
    void failsFastWithoutCredentials() {
        runner.withPropertyValues(VALID).withPropertyValues("invoicing.factus.client-secret=").run(context -> {
            assertThat(context).hasFailed();
            String failure = stackTrace(context.getStartupFailure());
            assertThat(failure).contains("clientSecret");
            assertThat(failure).doesNotContain("clave-que-no-debe-aparecer");
        });
    }

    @Test
    @DisplayName("sin el rango de numeración de notas crédito la aplicación no arranca: Factus lo exige")
    void failsFastWithoutCreditNoteRange() {
        runner.withPropertyValues(VALID).withPropertyValues("invoicing.factus.credit-note-numbering-range-id=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTrace(context.getStartupFailure())).contains("creditNoteNumberingRangeId");
                });
    }

    @Test
    @DisplayName("una configuración de resiliencia inválida (p. ej. 0 intentos) impide el arranque")
    void failsFastWithInvalidResilience() {
        runner.withPropertyValues(VALID).withPropertyValues("invoicing.factus.resilience.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("el toString de la configuración nunca muestra las credenciales")
    void propertiesToStringIsMasked() {
        runner.withPropertyValues(VALID).run(context -> assertThat(context.getBean(FactusProperties.class).toString())
                .doesNotContain("secreto-que-no-debe-aparecer", "clave-que-no-debe-aparecer", "usuario@empresa")
                .contains("clientSecret=***"));
    }

    private static String stackTrace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
