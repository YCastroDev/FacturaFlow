package com.facturaflow.sales.infrastructure.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Respuestas de seguridad en Problem Details (ADR 0018), con el mismo formato que el resto de la API. Los filtros
 * de Spring Security actúan antes que los controladores, así que el {@code ProblemDetailsHandler} no las ve.
 *
 * <table>
 *   <tr><th>Situación</th><th>Estado</th><th>{@code type}</th></tr>
 *   <tr><td>sin token, o token inválido (firma, emisor, audiencia, vencido)</td><td>401</td><td>unauthorized</td></tr>
 *   <tr><td>token válido sin el rol que pide la ruta</td><td>403</td><td>forbidden</td></tr>
 *   <tr><td>no se pudieron obtener las claves de Keycloak para validar el token</td><td>503</td>
 *       <td>authentication-unavailable</td></tr>
 * </table>
 * Nunca se devuelve ni se registra el motivo exacto del rechazo ni el token: la cabecera {@code WWW-Authenticate}
 * lleva solo el código de error de RFC 6750, sin {@code error_description}.
 *
 * <p><b>Copia deliberada</b> en gateway, invoicing, catalog y sales (ADR 0001).
 */
final class SecurityProblemResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String TYPE_PREFIX = "urn:facturaflow:problem:";
    /** Segundos sugeridos para reintentar cuando Keycloak no responde (las claves se piden de nuevo en ese momento). */
    static final int AUTHENTICATION_RETRY_AFTER_SECONDS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(SecurityProblemResponses.class);
    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    private final JsonMapper jsonMapper;

    SecurityProblemResponses(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
            throws IOException {
        if (failure instanceof AuthenticationServiceException) {
            // Solo la clase: el mensaje de Spring puede incluir la URL de Keycloak.
            LOG.warn("No se pudo validar un token: claves de Keycloak no disponibles ({})",
                    rootCause(failure).getClass().getSimpleName());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(AUTHENTICATION_RETRY_AFTER_SECONDS));
            write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "authentication-unavailable",
                    "Autenticación no disponible",
                    "No fue posible validar el token en este momento. Intente de nuevo más tarde.");
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                failure instanceof InvalidBearerTokenException ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "No autenticado",
                "Esta operación exige un token de acceso válido (Authorization: Bearer).");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Acceso denegado",
                "Su usuario no tiene permiso para esta operación.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug,
                       String title, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", TYPE_PREFIX + slug);
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(PROBLEM_JSON.toString());   // JSON siempre es UTF-8: sin charset, como el resto
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
