package com.facturaflow.invoicing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.invoicing.InvoicingApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hace cumplir la arquitectura hexagonal del ADR 0002.
 *
 * <p>Mientras los paquetes estén vacíos, cada regla usa {@code allowEmptyShould(true)}
 * (o {@code withOptionalLayers(true)}) para no fallar por no tener clases que revisar.
 * Para que eso no oculte una configuración rota (p. ej. un paquete mal escrito que deje
 * todas las reglas pasando "en vacío"), {@link #importsProductionCode} exige que el
 * import contenga el código de producción.
 */
@AnalyzeClasses(packagesOf = InvoicingApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    private static final String BASE = "com.facturaflow.invoicing";
    private static final String DOMAIN = BASE + ".domain..";
    private static final String APPLICATION = BASE + ".application..";
    private static final String INFRASTRUCTURE = BASE + ".infrastructure..";

    @ArchTest
    void importsProductionCode(JavaClasses classes) {
        assertThat(classes.contain(InvoicingApplication.class))
                .as("ArchUnit no importó el código de producción: revisa @AnalyzeClasses")
                .isTrue();
    }

    // Reglas 1 y 3: dirección de las dependencias entre capas.
    @ArchTest
    static final ArchRule layers_point_inwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Domain").definedBy(DOMAIN)
            .layer("Application").definedBy(APPLICATION)
            .layer("Infrastructure").definedBy(INFRASTRUCTURE)
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
            .because("las dependencias deben apuntar hacia adentro (infrastructure -> application -> domain): "
                    + "domain no puede conocer casos de uso ni adaptadores, y application no puede conocer "
                    + "adaptadores concretos (ADR 0002)");

    // Regla 2: domain es Java puro (lista blanca: solo java.* y el propio dominio).
    @ArchTest
    static final ArchRule domain_is_pure_java = classes()
            .that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN)
            .allowEmptyShould(true)
            .because("el dominio solo puede usar Java estándar: nada de Spring, JPA (jakarta.persistence), "
                    + "Bean Validation (jakarta.validation), Jackson, Hibernate ni librerías externas; "
                    + "las anotaciones y los mapeos van en infrastructure (ADR 0002)");

    // Regla 4: application es Java puro (puede usar el dominio).
    @ArchTest
    static final ArchRule application_is_pure_java = classes()
            .that().resideInAPackage(APPLICATION)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN, APPLICATION)
            .allowEmptyShould(true)
            .because("los casos de uso son clases Java puras: sin @Service, @Transactional ni otras "
                    + "dependencias de Spring o de otros frameworks; se registran como beans y se les "
                    + "agrega transaccionalidad desde infrastructure.config (ADR 0002)");

    // Regla 5: los puertos son contratos (interfaces) y sus datos de entrada/salida (records).
    @ArchTest
    static final ArchRule ports_are_interfaces_or_records = classes()
            .that().resideInAnyPackage(BASE + ".application.port.in..", BASE + ".application.port.out..")
            .should().beInterfaces()
            .orShould().beRecords()
            .allowEmptyShould(true)
            .because("en los puertos solo van contratos (interfaces) y sus comandos/resultados inmutables "
                    + "(records); las implementaciones van en application.service (puertos de entrada) "
                    + "o en infrastructure.adapter.out (puertos de salida)");

    // Regla 6: los adaptadores de entrada pasan siempre por los casos de uso.
    @ArchTest
    static final ArchRule inbound_adapters_do_not_use_outbound_adapters = noClasses()
            .that().resideInAPackage(BASE + ".infrastructure.adapter.in..")
            .should().dependOnClassesThat().resideInAPackage(BASE + ".infrastructure.adapter.out..")
            .allowEmptyShould(true)
            .because("un adaptador de entrada (p. ej. un controlador REST) debe invocar un puerto de entrada; "
                    + "si usa un repositorio o un cliente externo directamente, se salta los casos de uso "
                    + "y las reglas de negocio");

    // Regla 8: las transacciones solo existen en el adaptador de persistencia (ADR 0006).
    @ArchTest
    static final ArchRule transactions_only_in_persistence_adapter = noClasses()
            .that().resideOutsideOfPackage(BASE + ".infrastructure.adapter.out.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.transaction..", "jakarta.transaction..")
            .allowEmptyShould(true)
            .because("cada operación del repositorio es su propia transacción corta; si un caso de uso, "
                    + "un controlador o la configuración abrieran una (@Transactional, TransactionTemplate), "
                    + "la llamada al proveedor de facturación quedaría dentro y retendría conexión y bloqueos "
                    + "mientras espera a un sistema externo");

    // Regla 7: sin ciclos entre ningún par de paquetes.
    @ArchTest
    static final ArchRule no_package_cycles = slices()
            .matching(BASE + ".(**)")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .because("un ciclo entre paquetes los convierte en un solo bloque: no se pueden entender, "
                    + "probar ni mover por separado");
}
