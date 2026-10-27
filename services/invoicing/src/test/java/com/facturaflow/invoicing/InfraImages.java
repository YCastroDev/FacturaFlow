package com.facturaflow.invoicing;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testcontainers.utility.DockerImageName;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Las imágenes de infraestructura de los tests, leídas de {@code infra/docker-compose.yml}: la ÚNICA fuente de sus
 * versiones, la que actualiza Dependabot (ecosistema {@code docker-compose}), así los tests prueban la misma imagen
 * que se despliega (ADR 0016). Copia deliberada de la de sales (ADR 0001). La ruta es relativa a
 * {@code services/invoicing}, como en {@link EventContracts}.
 */
public final class InfraImages {

    static final Path COMPOSE = Path.of("../../infra/docker-compose.yml");

    /** Imagen de cada servicio del compose que declara {@code image:} (los que se construyen no aparecen). */
    static final Map<String, String> BY_SERVICE = load();

    public static final DockerImageName POSTGRES = image("postgres");
    public static final DockerImageName RABBITMQ = image("rabbitmq");

    private InfraImages() {
    }

    private static DockerImageName image(String service) {
        String image = BY_SERVICE.get(service);
        if (image == null) {
            throw new IllegalStateException("El servicio '" + service + "' no declara image: en " + COMPOSE);
        }
        return DockerImageName.parse(image);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load() {
        try (Reader reader = Files.newBufferedReader(COMPOSE)) {
            Map<String, Object> compose = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            Map<String, String> images = new LinkedHashMap<>();
            ((Map<String, Map<String, Object>>) compose.get("services")).forEach((name, service) -> {
                if (service.get("image") instanceof String image) {
                    images.put(name, image);
                }
            });
            return Map.copyOf(images);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + COMPOSE.toAbsolutePath(), e);
        }
    }
}
