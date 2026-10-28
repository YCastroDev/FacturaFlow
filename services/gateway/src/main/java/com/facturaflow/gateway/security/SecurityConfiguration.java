package com.facturaflow.gateway.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * El gateway como resource server (ADR 0018). Zero trust: valida el JWT de Keycloak (firma, {@code iss},
 * {@code aud = facturaflow-api}, vigencia) y además cada servicio lo vuelve a validar. Aquí solo hay un filtro GRUESO:
 * la matriz fina de permisos vive en cada servicio (una sola fuente de verdad). El token pasa intacto al servicio.
 *
 * <table>
 *   <tr><th>Ruta</th><th>Quién</th></tr>
 *   <tr><td>{@code /api/**}</td><td>un usuario con algún rol de la aplicación (admin, vendedor, contador); un cliente
 *       de máquina como {@code sales-service} llama a los servicios directamente, no por el gateway</td></tr>
 *   <tr><td>{@code /actuator/health/**}</td><td>público</td></tr>
 *   <tr><td>resto de {@code /actuator}</td><td>admin</td></tr>
 *   <tr><td>cualquier otra</td><td>nadie</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String ADMIN = "admin";
    static final String VENDEDOR = "vendedor";
    static final String CONTADOR = "contador";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper jsonMapper) {
        SecurityProblemResponses problems = new SecurityProblemResponses(jsonMapper);
        JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(new KeycloakJwtAuthoritiesConverter());
        http
                // API sin sesión ni cookies: el token viaja en cada petición, así que no hay CSRF que proteger.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        // El reenvío interno a /error (excepciones no traducidas) no vuelve a pasar la autorización.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        .requestMatchers("/api/**").hasAnyRole(ADMIN, VENDEDOR, CONTADOR)
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        // Keycloak caído (sin claves en caché): 503 en Problem Details, no la excepción que Spring
                        // relanza por defecto (terminaría en un 500).
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                AuthenticationEntryPointFailureHandler failures =
                                        new AuthenticationEntryPointFailureHandler(problems);
                                failures.setRethrowAuthenticationServiceException(false);
                                filter.setAuthenticationFailureHandler(failures);
                                return filter;
                            }
                        }));
        return http.build();
    }
}
