package com.facturaflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

class InfraImagesTest {

    @Test
    @DisplayName("Toda imagen del compose fija su versión: al menos mayor.menor y nunca latest")
    void everyComposeImageIsPinned() {
        assertThat(InfraImages.BY_SERVICE).allSatisfy((service, image) ->
                assertThat(DockerImageName.parse(image).getVersionPart()).as(service).matches("\\d+\\.\\d+.*"));
    }

    @Test
    @DisplayName("Los servicios del compose que usan la misma imagen usan la misma versión")
    void sameRepositorySameVersion() {
        Map<String, Long> versionsByRepository = InfraImages.BY_SERVICE.values().stream()
                .map(DockerImageName::parse).distinct()
                .collect(Collectors.groupingBy(DockerImageName::getRepository, Collectors.counting()));
        assertThat(versionsByRepository).allSatisfy((repository, versions) ->
                assertThat(versions).as(repository).isEqualTo(1L));
    }

    @Test
    @DisplayName("Los tests usan las imágenes de infraestructura que declara el compose")
    void testsUseComposeImages() {
        assertThat(InfraImages.POSTGRES.getRepository()).isEqualTo("postgres");
        assertThat(InfraImages.KEYCLOAK.getRepository()).isEqualTo("keycloak/keycloak");
    }
}
