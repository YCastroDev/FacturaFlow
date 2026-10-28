package com.facturaflow.sales;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Un Keycloak REAL (imagen del compose, {@link InfraImages}) con el realm del repositorio y contraseñas y secreto
 * aleatorios por ejecución, para los tests de punta a punta de la Fase 2 (ADR 0018). Sin {@code KC_HOSTNAME}, el
 * {@code iss} de los tokens es la URL con la que se pidieron ({@link #issuer()}), y así se configura el servicio.
 *
 * <p><b>Copia deliberada</b> en gateway, catalog y sales (sin código compartido entre servicios, ADR 0001).
 */
public final class RealKeycloak {

    static final String REALM_FILE = "../../infra/keycloak/import/facturaflow-realm.json";
    public static final String SALES_SERVICE_SECRET = UUID.randomUUID().toString();
    private static final Map<String, String> PASSWORDS = Map.of(
            "admin.demo", UUID.randomUUID().toString(),
            "vendedor.demo", UUID.randomUUID().toString(),
            "contador.demo", UUID.randomUUID().toString());

    @SuppressWarnings("resource")
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(InfraImages.KEYCLOAK)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin-" + UUID.randomUUID())
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", UUID.randomUUID().toString())
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KC_DEMO_ADMIN_PASSWORD", PASSWORDS.get("admin.demo"))
            .withEnv("KC_DEMO_VENDEDOR_PASSWORD", PASSWORDS.get("vendedor.demo"))
            .withEnv("KC_DEMO_CONTADOR_PASSWORD", PASSWORDS.get("contador.demo"))
            .withEnv("SALES_SERVICE_CLIENT_SECRET", SALES_SERVICE_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE),
                    "/opt/keycloak/data/import/facturaflow-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    private RealKeycloak() {
    }

    /** Arranca el contenedor una sola vez por ejecución (lo detiene Testcontainers al terminar). */
    public static synchronized void start() {
        if (!KEYCLOAK.isRunning()) {
            KEYCLOAK.start();
        }
    }

    public static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/facturaflow";
    }

    public static String jwkSetUri() {
        return issuer() + "/protocol/openid-connect/certs";
    }

    public static String tokenUri() {
        return issuer() + "/protocol/openid-connect/token";
    }

    /** Token de un usuario de demostración con el cliente de terminal (facturaflow-cli, password grant). */
    public static String userToken(String username) {
        return userToken("facturaflow-cli", username);
    }

    /** Token de un usuario de demostración pedido con otro cliente público del realm (p. ej. admin-cli). */
    public static String userToken(String clientId, String username) {
        return token(form("grant_type", "password", "client_id", clientId, "username", username,
                "password", PASSWORDS.get(username)));
    }

    /** Token de sales-service (client credentials). */
    public static String salesServiceToken() {
        return token(form("grant_type", "client_credentials", "client_id", "sales-service",
                "client_secret", SALES_SERVICE_SECRET));
    }

    private static String token(MultiValueMap<String, String> form) {
        String response = RestClient.create().post().uri(tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(String.class);
        return JsonPath.read(response, "$.access_token");
    }

    private static MultiValueMap<String, String> form(String... keyValues) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            form.add(keyValues[i], keyValues[i + 1]);
        }
        return form;
    }
}
