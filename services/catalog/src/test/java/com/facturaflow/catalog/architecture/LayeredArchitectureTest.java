package com.facturaflow.catalog.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.catalog.CatalogApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hace cumplir la arquitectura en capas de catalog (ADR 0011): controller → service → repository, con
 * el modelo como base común. (Invoicing sigue siendo hexagonal, ADR 0002, con sus propias reglas.)
 */
@AnalyzeClasses(packagesOf = CatalogApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

    private static final String BASE = "com.facturaflow.catalog";
    private static final String WEB = BASE + ".web..";
    private static final String SERVICE = BASE + ".service..";
    private static final String REPOSITORY = BASE + ".repository..";
    private static final String MODEL = BASE + ".model..";
    private static final String CONFIG = BASE + ".config..";

    @ArchTest
    void importsProductionCode(JavaClasses classes) {
        assertThat(classes.contain(CatalogApplication.class))
                .as("ArchUnit no importó el código de producción: revisa @AnalyzeClasses")
                .isTrue();
    }

    // Regla 1: sentido de las dependencias entre capas.
    @ArchTest
    static final ArchRule layers_point_down = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy(WEB)
            .layer("Service").definedBy(SERVICE)
            .layer("Repository").definedBy(REPOSITORY)
            .layer("Model").definedBy(MODEL)
            .layer("Config").definedBy(CONFIG)
            .whereLayer("Web").mayNotBeAccessedByAnyLayer()
            .whereLayer("Config").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Web")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Model").mayOnlyBeAccessedByLayers("Web", "Service", "Repository")
            .because("las dependencias van hacia abajo (web → service → repository, todas sobre model): una "
                    + "capa inferior nunca conoce a la superior (ADR 0011)");

    // Regla 2: los controladores no usan repositorios.
    @ArchTest
    static final ArchRule web_does_not_use_repositories = noClasses()
            .that().resideInAPackage(WEB)
            .should().dependOnClassesThat().resideInAPackage(REPOSITORY)
            .because("un controlador que va directo al repositorio se salta las reglas del servicio "
                    + "(unicidad, versión, transacción); siempre pasa por service (ADR 0011)");

    // Regla 3: transacciones solo en la capa de servicio.
    @ArchTest
    static final ArchRule transactions_only_in_services = noClasses()
            .that().resideOutsideOfPackage(SERVICE)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.transaction..", "jakarta.transaction..")
            .because("la transacción es el caso de uso completo (leer, validar la versión, guardar): se abre en "
                    + "service, nunca en el controlador ni en el modelo (ADR 0011)");

    // Regla 4: el modelo no conoce Spring, Jackson ni Hibernate (solo JPA estándar).
    @ArchTest
    static final ArchRule model_only_uses_java_and_jpa = classes()
            .that().resideInAPackage(MODEL)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "jakarta.persistence..", MODEL)
            .because("las reglas de los datos maestros no dependen del framework web ni de la serialización; "
                    + "en capas se admiten las anotaciones de JPA en las entidades (ADR 0011)");

    // Regla 5: los DTOs son el contrato de la API y no exponen el modelo.
    @ArchTest
    static final ArchRule dtos_do_not_expose_model = noClasses()
            .that().resideInAPackage(BASE + ".web.dto..")
            .should().dependOnClassesThat().resideInAPackage(MODEL)
            .because("si un DTO usara una entidad o un enum del modelo, un cambio interno cambiaría la API en "
                    + "silencio; la traducción va en ApiMapper");

    @ArchTest
    static final ArchRule controllers_never_return_entities = noMethods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .should().haveRawReturnType(resideInAPackage(MODEL))
            .because("el controlador lee las entidades, nunca las serializa");

    // Regla 6: cada estereotipo en su capa.
    @ArchTest
    static final ArchRule controllers_live_in_web = classes()
            .that().areAnnotatedWith(RestController.class).should().resideInAPackage(WEB);

    @ArchTest
    static final ArchRule services_live_in_service = classes()
            .that().areAnnotatedWith(Service.class).should().resideInAPackage(SERVICE);

    @ArchTest
    static final ArchRule repositories_live_in_repository = classes()
            .that().areAssignableTo(Repository.class).should().resideInAPackage(REPOSITORY);

    // Regla 7: ningún código de otro servicio (ADR 0001).
    @ArchTest
    static final ArchRule no_code_from_other_services = noClasses()
            .should().dependOnClassesThat(resideInAPackage("com.facturaflow..").and(resideOutsideOfPackage(BASE + "..")))
            .because("los servicios no comparten código: los conceptos repetidos (p. ej. el DV del NIT) se "
                    + "duplican a propósito y la comunicación es solo por HTTP o eventos (ADR 0001)");

    // Regla 8: sin ciclos entre paquetes.
    @ArchTest
    static final ArchRule no_package_cycles = slices()
            .matching(BASE + ".(**)")
            .should().beFreeOfCycles()
            .because("un ciclo entre paquetes los convierte en un solo bloque: no se pueden entender, probar ni "
                    + "mover por separado");
}
