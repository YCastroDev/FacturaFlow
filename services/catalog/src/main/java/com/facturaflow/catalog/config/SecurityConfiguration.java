package com.facturaflow.catalog.config;

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
 * Catalog como resource server (ADR 0018, zero trust): valida el JWT de Keycloak (firma con las claves del realm,
 * {@code iss}, {@code aud = facturaflow-api}, vigencia; propiedades {@code spring.security.oauth2.resourceserver.*})
 * y aplica su fila de la matriz de permisos. Las claves se piden a Keycloak con el primer token, no al arrancar.
 *
 * <table>
 *   <tr><th>Ruta</th><th>Quién</th></tr>
 *   <tr><td>consultar productos y clientes ({@code GET}) y la consulta por lote ({@code POST /products/lookup})</td>
 *       <td>admin, vendedor, {@code SCOPE_catalog.read} (sales-service)</td></tr>
 *   <tr><td>crear y editar clientes ({@code POST /customers}, {@code PUT /customers/{id}})</td>
 *       <td>admin, vendedor</td></tr>
 *   <tr><td>crear, editar, activar y desactivar productos; activar y desactivar clientes</td><td>admin</td></tr>
 *   <tr><td>{@code /actuator/health/**}, Swagger UI y {@code /v3/api-docs}</td><td>público</td></tr>
 *   <tr><td>resto de {@code /actuator}</td><td>admin</td></tr>
 *   <tr><td>cualquier otra</td><td>nadie</td></tr>
 * </table>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String ADMIN = "admin";
    static final String VENDEDOR = "vendedor";
    /** El permiso de sales-service (client credentials): un scope, no un rol de persona (ADR 0017). */
    static final String CATALOG_READ = "SCOPE_catalog.read";

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
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs",
                                "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/customers/**")
                        .hasAnyAuthority("ROLE_" + ADMIN, "ROLE_" + VENDEDOR, CATALOG_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/products/lookup")
                        .hasAnyAuthority("ROLE_" + ADMIN, "ROLE_" + VENDEDOR, CATALOG_READ)
                        // Registrar o corregir un cliente en caja es parte de vender; activar o desactivarlo, no.
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers").hasAnyRole(ADMIN, VENDEDOR)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/customers/*").hasAnyRole(ADMIN, VENDEDOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/customers/*/activate",
                                "/api/v1/customers/*/deactivate").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/products", "/api/v1/products/*/activate",
                                "/api/v1/products/*/deactivate").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/v1/products/*").hasRole(ADMIN)
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
