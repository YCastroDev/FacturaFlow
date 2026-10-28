package com.facturaflow.gateway.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.facturaflow.gateway.InfraImages;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * El realm del repositorio ({@code infra/keycloak/import/facturaflow-realm.json}) importado en un Keycloak real, con la
 * imagen del compose y contraseñas y secreto aleatorios en cada ejecución, inyectados como variables de entorno igual que
 * en compose (ADR 0017). Que cada usuario obtenga su token con SU contraseña prueba que la sustitución {@code ${VAR}}
 * funciona: con el marcador sin sustituir, la contraseña sería el texto literal.
 */
@Testcontainers
class KeycloakRealmTest {

    static final String REALM_FILE = "../../infra/keycloak/import/facturaflow-realm.json";
    private static final String ADMIN_USER = "admin-" + UUID.randomUUID();
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String SALES_SECRET = UUID.randomUUID().toString();
    private static final Map<String, String> PASSWORDS = Map.of(
            "admin.demo", UUID.randomUUID().toString(),
            "vendedor.demo", UUID.randomUUID().toString(),
            "contador.demo", UUID.randomUUID().toString());
    private static final List<String> HUMAN_ROLES = List.of("admin", "vendedor", "contador");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(InfraImages.KEYCLOAK)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withEnv("KC_DEMO_ADMIN_PASSWORD", PASSWORDS.get("admin.demo"))
            .withEnv("KC_DEMO_VENDEDOR_PASSWORD", PASSWORDS.get("vendedor.demo"))
            .withEnv("KC_DEMO_CONTADOR_PASSWORD", PASSWORDS.get("contador.demo"))
            .withEnv("SALES_SERVICE_CLIENT_SECRET", SALES_SECRET)
            .withCopyFileToContainer(MountableFile.forHostPath(REALM_FILE),
                    "/opt/keycloak/data/import/facturaflow-realm.json")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));

    private static RestClient http;
    private static String adminToken;

    @BeforeAll
    static void connect() {
        http = RestClient.builder()
                .baseUrl("http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080))
                // Los 4xx se inspeccionan en los tests, no son excepciones.
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> { })
                .build();
        adminToken = JsonPath.read(token("master", form("grant_type", "password", "client_id", "admin-cli",
                "username", ADMIN_USER, "password", ADMIN_PASSWORD)).body(), "$.access_token");
    }

    @Test
    @DisplayName("El realm define los tres roles de negocio")
    void realmRoles() {
        List<String> roles = JsonPath.read(admin("/roles"), "$[*].name");

        assertThat(roles).containsAll(HUMAN_ROLES);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"admin.demo, admin", "vendedor.demo, vendedor", "contador.demo, contador"})
    @DisplayName("Cada usuario de demostración existe, está activo y tiene solo su rol de negocio")
    void demoUsers(String username, String role) {
        String users = admin("/users?exact=true&username=" + username);
        assertThat((List<Boolean>) JsonPath.read(users, "$[*].enabled")).containsExactly(true);
        String id = JsonPath.read(users, "$[0].id");

        List<String> roles = JsonPath.read(admin("/users/" + id + "/role-mappings/realm"), "$[*].name");

        assertThat(roles).contains(role).doesNotContainAnyElementsOf(others(role));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"admin.demo, admin", "vendedor.demo, vendedor", "contador.demo, contador"})
    @DisplayName("Cada usuario obtiene desde la terminal un token con su rol, usando la contraseña inyectada por entorno")
    void demoUserToken(String username, String role) {
        Token response = token("facturaflow", form("grant_type", "password", "client_id", "facturaflow-cli",
                "username", username, "password", PASSWORDS.get(username)));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String claims = claims(response.body());
        assertThat((String) JsonPath.read(claims, "$.preferred_username")).isEqualTo(username);
        assertThat((List<String>) JsonPath.read(claims, "$.realm_access.roles"))
                .contains(role).doesNotContainAnyElementsOf(others(role));
        assertThat((String) JsonPath.read(claims, "$.sub")).isNotBlank();
        assertThat(audiences(claims)).as("audiencia de la API (ADR 0018)").contains("facturaflow-api");
    }

    @Test
    @DisplayName("El marcador sin sustituir no sirve como contraseña")
    void placeholderIsNotThePassword() {
        Token response = token("facturaflow", form("grant_type", "password", "client_id", "facturaflow-cli",
                "username", "vendedor.demo", "password", "${KC_DEMO_VENDEDOR_PASSWORD}"));

        // RFC 6749: credenciales de usuario inválidas = 400 invalid_grant (no 401, que es para el cliente).
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        assertThat((String) JsonPath.read(response.body(), "$.error")).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("sales-service obtiene un token con client credentials: scope catalog.read y ningún rol de persona")
    void salesServiceClientCredentials() {
        Token response = token("facturaflow", form("grant_type", "client_credentials",
                "client_id", "sales-service", "client_secret", SALES_SECRET));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String claims = claims(response.body());
        assertThat((String) JsonPath.read(claims, "$.azp")).isEqualTo("sales-service");
        assertThat(((String) JsonPath.read(claims, "$.scope")).split(" ")).contains("catalog.read");
        assertThat(claims).doesNotContain("\"admin\"", "\"vendedor\"", "\"contador\"");
        assertThat(audiences(claims)).contains("facturaflow-api");
        assertThat(((String) JsonPath.read(claims, "$.scope")).split(" ")).doesNotContain("facturaflow-api");
    }

    @Test
    @DisplayName("Otro cliente del realm (admin-cli) da un token válido del mismo emisor para el usuario, pero SIN la "
            + "audiencia de la API: por eso el gateway y los servicios exigen aud = facturaflow-api")
    void otherClientsDoNotGetTheApiAudience() {
        Token response = token("facturaflow", form("grant_type", "password", "client_id", "admin-cli",
                "username", "admin.demo", "password", PASSWORDS.get("admin.demo")));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String claims = claims(response.body());
        assertThat((String) JsonPath.read(claims, "$.iss")).endsWith("/realms/facturaflow");
        assertThat((String) JsonPath.read(claims, "$.azp")).isEqualTo("admin-cli");
        assertThat(audiences(claims)).doesNotContain("facturaflow-api");
    }

    @Test
    @DisplayName("sales-service con un secreto incorrecto no obtiene token")
    void salesServiceWrongSecret() {
        Token response = token("facturaflow", form("grant_type", "client_credentials",
                "client_id", "sales-service", "client_secret", "no-es-el-secreto"));

        assertThat(response.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("Un usuario no puede pedir catalog.read ni sales-service admite contraseñas de usuario")
    void catalogReadIsOnlyForSalesService() {
        Token user = token("facturaflow", form("grant_type", "password", "client_id", "facturaflow-cli",
                "username", "admin.demo", "password", PASSWORDS.get("admin.demo"), "scope", "openid catalog.read"));
        assertThat(user.status()).as(user.body()).isEqualTo(400);
        assertThat((String) JsonPath.read(user.body(), "$.error")).isEqualTo("invalid_scope");

        Token passwordGrant = token("facturaflow", form("grant_type", "password", "client_id", "sales-service",
                "client_secret", SALES_SECRET, "username", "admin.demo", "password", PASSWORDS.get("admin.demo")));
        assertThat(passwordGrant.status()).isIn(400, 401);
    }

    @Test
    @DisplayName("El cliente de Swagger es público, solo authorization code y con PKCE S256 obligatorio")
    void swaggerClient() {
        String client = admin("/clients?clientId=facturaflow-swagger");

        assertThat((List<Boolean>) JsonPath.read(client, "$[*].publicClient")).containsExactly(true);
        assertThat((List<Boolean>) JsonPath.read(client, "$[*].standardFlowEnabled")).containsExactly(true);
        assertThat((List<Boolean>) JsonPath.read(client, "$[*].directAccessGrantsEnabled")).containsExactly(false);
        assertThat((List<Boolean>) JsonPath.read(client, "$[*].implicitFlowEnabled")).containsExactly(false);
        assertThat((List<String>) JsonPath.read(client, "$[*].attributes['pkce.code.challenge.method']"))
                .containsExactly("S256");
    }

    // ----- Internos -----

    private record Token(int status, String body) {
    }

    private static List<String> others(String role) {
        return HUMAN_ROLES.stream().filter(r -> !r.equals(role)).toList();
    }

    private static Token token(String realm, MultiValueMap<String, String> form) {
        var response = http.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().toEntity(String.class);
        return new Token(response.getStatusCode().value(), response.getBody());
    }

    private static String admin(String path) {
        return http.get().uri("/admin/realms/facturaflow" + path)
                .header("Authorization", "Bearer " + adminToken)
                .retrieve().body(String.class);
    }

    /** Las claims del access token (el payload del JWT). La firma la validarán el gateway y los servicios (Fase 2). */
    private static String claims(String tokenResponse) {
        String accessToken = JsonPath.read(tokenResponse, "$.access_token");
        return new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** {@code aud} puede ser un texto o una lista (RFC 7519); falta si ningún mapper la agrega. */
    private static List<String> audiences(String claims) {
        Object aud = JsonPath.parse(claims).read("$", Map.class).get("aud");
        if (aud instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return aud == null ? List.of() : List.of(String.valueOf(aud));
    }

    private static MultiValueMap<String, String> form(String... keyValues) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            form.add(keyValues[i], keyValues[i + 1]);
        }
        return form;
    }
}
