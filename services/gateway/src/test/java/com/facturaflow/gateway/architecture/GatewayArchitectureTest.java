package com.facturaflow.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.gateway.GatewayApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * El gateway es un servicio independiente más (ADR 0001, ADR 0017): solo enruta por HTTP, nunca conoce el código de
 * los servicios a los que enruta.
 */
@AnalyzeClasses(packagesOf = GatewayApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class GatewayArchitectureTest {

    @ArchTest
    void importsProductionCode(JavaClasses classes) {
        assertThat(classes.contain(GatewayApplication.class))
                .as("ArchUnit no importó el código de producción: revisa @AnalyzeClasses")
                .isTrue();
    }

    // Regla 1: ningún código de otro servicio.
    @ArchTest
    static final ArchRule no_other_service_code = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.facturaflow.invoicing..", "com.facturaflow.catalog..", "com.facturaflow.sales..")
            .because("el gateway se comunica con los servicios solo por HTTP (ADR 0001)");

    // Regla 2: sin ciclos entre paquetes.
    @ArchTest
    static final ArchRule no_cycles = slices().matching("com.facturaflow.gateway.(*)..")
            .should().beFreeOfCycles();
}
