package com.facturaflow.gateway.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * El archivo del realm nunca lleva secretos (ADR 0017): toda contraseña y todo secreto de cliente es un marcador
 * {@code ${VAR}} que Keycloak sustituye al importar, y cada VAR existe en {@code .env.example} y en el servicio
 * {@code keycloak} del compose (si faltara ahí, Keycloak importaría el texto literal como contraseña).
 */
class RealmFileSecretsTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)}$");
    private static final Path ENV_EXAMPLE = Path.of("../../.env.example");
    private static final Path COMPOSE = Path.of("../../infra/docker-compose.yml");

    @Test
    @DisplayName("Las contraseñas y los secretos de cliente del realm son marcadores ${VAR}, nunca valores")
    void secretsArePlaceholders() throws IOException {
        assertThat(secretValues()).isNotEmpty().allSatisfy(value -> assertThat(value).matches(PLACEHOLDER));
    }

    @Test
    @DisplayName("Cada variable del realm está en .env.example (con CAMBIAME) y en el entorno de keycloak del compose")
    @SuppressWarnings("unchecked")
    void placeholdersAreDeclared() throws IOException {
        String envExample = Files.readString(ENV_EXAMPLE);
        Map<String, Object> compose = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(COMPOSE));
        Map<String, Object> keycloakEnv = (Map<String, Object>)
                ((Map<String, Map<String, Object>>) compose.get("services")).get("keycloak").get("environment");

        assertThat(secretValues()).allSatisfy(value -> {
            Matcher matcher = PLACEHOLDER.matcher(value);
            assertThat(matcher.matches()).isTrue();
            String variable = matcher.group(1);
            assertThat(envExample).as(".env.example").containsPattern("(?m)^" + variable + "=CAMBIAME$");
            assertThat(keycloakEnv).as("environment de keycloak en el compose").containsKey(variable);
        });
    }

    private static List<String> secretValues() throws IOException {
        String realm = Files.readString(Path.of(KeycloakRealmTest.REALM_FILE));
        List<String> passwords = JsonPath.read(realm, "$.users[*].credentials[*].value");
        List<String> clientSecrets = JsonPath.read(realm, "$.clients[*].secret");
        return Stream.concat(passwords.stream(), clientSecrets.stream()).toList();
    }
}
