# 0018. Seguridad, Fase 2: el gateway y cada servicio exigen token y roles (resource servers), y sales llama a catalog con client credentials

## Estado

Aceptado — 2026-10-28. Fase 2 de 2 del ADR 0017, que anunció un ADR propio para esta fase. Completa los ADR 0006, 0011 y 0012 (tablas de errores) y el 0016 (filtros de la CI y prueba de humo).

> **Nota posterior (2026-10-28):** se resolvió el punto "Pendiente" (D6, quién hizo la venta) en un PR aparte: decisión 13, con la regla 13 de ArchUnit ampliada al adaptador REST de sales (decisión 9).

## Contexto

Tras la Fase 1 (ADR 0017) hay un Keycloak con el realm `facturaflow` como código y un gateway, pero nadie exige token. Las decisiones de las dos fases ya estaban tomadas:
- **Zero trust (decisión 2):** validan el JWT el gateway **y** cada servicio.
- **Roles (decisión 3):** `admin`, `vendedor` y `contador`.
- **Sales llama a catalog con su propia identidad (decisión 4):** client credentials de `sales-service`, con el scope `catalog.read`.

Esta fase las implementa sin tocar el dominio ni los casos de uso: la seguridad es infraestructura. Las reglas de ArchUnit siguen en verde, y hay una nueva (la 13, decisión 9).

## Decisión

### 1. Cuatro resource servers con la configuración de Spring Boot (sin descubrimiento)

Cada servicio (y el gateway) usa `spring-boot-starter-security-oauth2-resource-server` con:

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${SECURITY_JWT_ISSUER:http://localhost:8180/realms/facturaflow}
  jwk-set-uri: ${SECURITY_JWT_JWK_SET_URI:http://keycloak:8080/realms/facturaflow/protocol/openid-connect/certs}
  audiences: facturaflow-api
```

- **Qué valida Spring Boot:** la firma (RS256, con las claves del JWKS del realm), `iss`, `aud`, `exp` y `nbf` (60 s de tolerancia).
- **`jwk-set-uri` además de `issuer-uri`:** con los dos, Boot NO hace descubrimiento. Toma las claves de la URL **interna** y valida el `iss` contra el valor **fijo** del realm (`KC_HOSTNAME`, ADR 0017). Solo con `issuer-uri`, buscaría `/.well-known` en `localhost:8180`, que dentro de Docker no es Keycloak.
- **Arranque sin Keycloak (requisito de la prueba de humo, ADR 0016):**
  - el decodificador no pide nada al construirse: las claves se bajan con el primer token y quedan en caché (5 min, la de Nimbus);
  - el health es público;
  - por eso la prueba de humo no cambia: cada imagen arranca solo con PostgreSQL y responde `UP` (verificado con las cuatro).
- **Sesiones y CSRF:** API sin sesión ni cookies (`STATELESS`, sin CSRF ni caché de peticiones). El token viaja en cada petición.

### 2. Audiencia `facturaflow-api` (cambio del realm)

- **Sin audiencia, cualquier token del realm valdría.** Por ejemplo, uno que un usuario obtiene con un cliente interno de Keycloak (`admin-cli`, `account-console`) sería aceptado.
- **El scope nuevo:** client scope `facturaflow-api` con un `oidc-audience-mapper` (`aud: facturaflow-api`, sin aparecer en `scope`). Es scope por defecto **solo** de `facturaflow-cli`, `facturaflow-swagger` y `sales-service`; no de `defaultDefaultClientScopes`, que lo daría a los clientes internos.
- **Se prueba con Keycloak real:**
  - `KeycloakRealmTest`: los tres clientes la emiten, y un token de `admin-cli` del mismo realm no la trae;
  - `GatewayKeycloakSecurityTest` y `CatalogKeycloakIntegrationTest`: ese token da 401.
- **Consecuencia operativa:** la importación solo crea el realm (ADR 0017), así que un entorno ya levantado necesita borrar el realm y reiniciar Keycloak (README).

### 3. Roles: conversor propio, copiado en los cuatro servicios

- **Por qué un conversor propio:** Spring Security solo lee autoridades de un claim de primer nivel, y Keycloak pone los roles de realm en `realm_access.roles`. Catalog necesita además el scope (`SCOPE_catalog.read`).
- **`KeycloakJwtAuthoritiesConverter`** (unas 40 líneas, con su test):
  - `realm_access.roles` → `ROLE_<rol>`;
  - `scope` → `SCOPE_<scope>`, con el conversor estándar;
  - ignora formas inesperadas y los roles de cliente (`resource_access`).
- **Copia deliberada** en gateway, invoicing, catalog y sales, igual que los cálculos de totales (ADR 0001: sin librería compartida). Si cambia en uno, cambia en los cuatro.
- **Alternativas descartadas:**
  - un mapper de Keycloak que copie los roles a un claim `roles` de primer nivel, más `authorities-claim-name` de Boot: no lleva Java en tres servicios, pero pierde los `SCOPE_` (Boot aplica un solo claim), así que catalog necesitaría código igual. Además ata los servicios a un claim inventado;
  - `authorities-claim-expressions` (SpEL, Boot 4.1): un solo prefijo para todas las expresiones; habría que construir `ROLE_`/`SCOPE_` dentro del SpEL, que es menos legible que 40 líneas de Java con test.

### 4. Matriz de permisos

Se escribe **por URL**, en un único `SecurityConfiguration` por servicio (el mismo orden que esta tabla), no con `@PreAuthorize` repartido por los controladores: se compara de un vistazo con este ADR y se prueba fila por fila. **Lo que no está en la matriz se niega** (`denyAll`), también a `admin`: un endpoint nuevo que nadie agregó aquí queda cerrado, no abierto.

| Servicio | Endpoint | admin | vendedor | contador | sales-service (`SCOPE_catalog.read`) |
|---|---|:-:|:-:|:-:|:-:|
| invoicing | `POST /api/v1/invoices`, `GET /api/v1/invoices?referenceCode=`, `GET /{id}`, `POST /{id}/issue`, `GET /{id}/pdf` | ✓ | | ✓ | |
| invoicing | notas crédito: `POST`/`GET /api/v1/invoices/{id}/credit-notes`, `GET /api/v1/credit-notes/{id}`, `POST /{id}/issue`, `GET /{id}/pdf` | ✓ | | ✓ | |
| catalog | `GET` de productos y clientes (lista, id, `by-code`, `by-document`) | ✓ | ✓ | | ✓ |
| catalog | `POST /api/v1/products/lookup` | ✓ | ✓ | | ✓ |
| catalog | crear y editar clientes (`POST /customers`, `PUT /customers/{id}`) | ✓ | ✓ | | |
| catalog | activar y desactivar clientes | ✓ | | | |
| catalog | crear, editar, activar y desactivar productos | ✓ | | | |
| sales | `POST /api/v1/sales`, `GET /api/v1/sales`, `GET /{id}`, `GET /by-reference/{code}` | ✓ | ✓ | | |
| gateway | `/api/**` (filtro grueso, decisión 5) | ✓ | ✓ | ✓ | |
| todos | `GET /actuator/health`, `/actuator/health/**` | público | público | público | público |
| todos | resto de Actuator (`metrics`, `circuitbreakers`, `retries`, `ratelimiters`, `info`) | ✓ | | | |
| invoicing, catalog, sales | Swagger UI y `/v3/api-docs` | público | público | público | público |
| todos | cualquier otra ruta | | | | |

- **Vendedor:** crea y edita clientes porque registrar o corregir un cliente en caja es parte de vender. Activar o desactivar un registro, y todo lo de productos (precios, impuestos), es del administrador.
- **Contador:** no lee catalog ni ventas (mínimo privilegio): la venta ya expone el número y el CUFE de su factura. Si lo necesita, es una fila nueva.
- **Facturas que nacen de una venta:** llegan por RabbitMQ (ADR 0014), sin token. Los eventos no cambian.
- **Rutas desconocidas:** dan 403 con token y 401 sin él, y ya no 404. Se ajustó `InvoiceApiEndToEndTest.unknownRoute`, porque esto es lo que se pretende.

### 5. Gateway: filtro grueso, matriz fina en los servicios

- **El filtro:** `/api/**` exige un token válido **y** al menos un rol de la aplicación. Rechaza un token de máquina (`sales-service` llama a catalog directamente, no por el gateway) o el de un usuario sin rol.
- **El token pasa intacto** al servicio, que lo vuelve a validar y aplica su matriz.
- **No se duplica la matriz en el gateway:** dos fuentes de verdad divergirían, y la de los servicios es la que cuenta igual (zero trust).
- **Fuera de `/api`:** se niega. Una ruta desconocida bajo `/api` sigue siendo el 404 del gateway (ADR 0017).

### 6. 401, 403 y 503 en Problem Details, sin detalles del token

- **Dónde se generan:** los filtros de Spring Security actúan antes que los controladores, así que el `ProblemDetailsHandler` no ve estos errores. Un `SecurityProblemResponses` (punto de entrada y manejador de acceso denegado, copia deliberada en los cuatro) escribe el mismo formato:

  | Situación | Estado | `type` | `WWW-Authenticate` |
  |---|---|---|---|
  | Sin token | 401 | `urn:facturaflow:problem:unauthorized` | `Bearer` |
  | Token inválido (firma, emisor, audiencia, vencido, mal formado) | 401 | `urn:facturaflow:problem:unauthorized` | `Bearer error="invalid_token"` |
  | Token válido sin el rol de la ruta | 403 | `urn:facturaflow:problem:forbidden` | `Bearer error="insufficient_scope"` |
  | Keycloak no entrega las claves (caído y sin claves en caché) | 503 + `Retry-After: 10` | `urn:facturaflow:problem:authentication-unavailable` | — |

- **Nunca se devuelve el motivo exacto:** ni el `error_description` de Spring ("Jwt expired at …"), ni el token, ni la URL de Keycloak. El log de un 503 registra solo la clase de la causa.
- **El 503 en vez del 500:** por defecto Spring relanza la falla del JWKS (`AuthenticationServiceException`) y terminaría en 500. Se cambia el manejador de fallas del filtro (`ObjectPostProcessor`).

### 7. Sales → catalog: client credentials dentro del circuito de catalog

- **Implementación:** con Spring Security OAuth2 Client (`spring-boot-starter-security-oauth2-client`), construido a mano dentro del ACL `adapter/out/catalog` (`CatalogTokenProvider`), sin la autoconfiguración ni `spring.security.oauth2.client.*`.
- **El cliente:**
  - `ClientRegistration` `sales-service` con `client_secret_basic`;
  - `AuthorizedClientServiceOAuth2AuthorizedClientManager`, porque es la identidad del servicio, no la del usuario de la petición;
  - un `RestClient` propio con timeouts (`sales.catalog.auth.*`: conexión 1 s, lectura 2 s).
- **Caché:** el token (5 min) se guarda en memoria y se renueva cuando le faltan menos de `renew-before-expiry` (60 s) para vencer. Nada se pide al arrancar.
- **Dentro de Retry → CircuitBreaker:** el token se pide en `CatalogHttp.send`, es decir, dentro del reintento y del circuito `catalog` (ADR 0012). Para sales, la dependencia es "catalog con mi identidad", y así una caída de Keycloak no golpea a Keycloak en cada venta.

  | Falla | Reintento | Cuenta en el circuito | Resultado |
  |---|---|---|---|
  | Keycloak sin conexión, o 502/503/504 del token endpoint | sí, 1 vez | sí | 503 `catalog-unavailable` |
  | Keycloak rechaza la solicitud (401 `invalid_client`, 400) | no; log ERROR (es configuración) | sí | 503 `catalog-unavailable` |
  | Catalog responde 401 (token rechazado: claves rotadas) | sí: se descarta el token y el reintento pide uno nuevo | sí | lo que diga el segundo intento |
  | Catalog responde 403 (`sales-service` perdió `catalog.read`) | no; log ERROR | sí | 503 `catalog-unavailable` |

  Ninguna termina en 500 (antes, un 401 o 403 inesperado de catalog habría subido como `IllegalStateException`).
- **Configuración:**
  - el secreto, `SALES_SERVICE_CLIENT_SECRET`: la misma variable que ya recibía el realm (ADR 0017), ahora también en el `environment` de sales en compose, con `:?`;
  - `SALES_KEYCLOAK_TOKEN_URI`, opcional, apunta a `keycloak:8080`;
  - el `toString` de las propiedades enmascara el secreto.
- **Alternativa descartada: un cliente propio** (petición del token, caché y renovación a mano). Es más corto de leer, pero reimplementa lo que Spring ya resuelve bien (expiración con desfase de reloj, invalidación).

### 8. Swagger UI con "Authorize" (authorization code + PKCE)

- **El esquema:** `OpenApiConfiguration` declara un esquema `oauth2` authorizationCode, con `authorizationUrl` y `tokenUrl` que salen del `issuer-uri` (la dirección de Keycloak que ve el navegador).
- **Springdoc:** usa `springdoc.swagger-ui.oauth.client-id: facturaflow-swagger` y `use-pkce-with-authorization-code-grant: true`.
- **El realm ya estaba listo:** tiene las redirecciones y los `webOrigins` de 8080–8083 desde la Fase 1.
- **El acceso a la documentación:** Swagger UI y `/v3/api-docs` son públicos. La documentación no es un secreto; las operaciones sí exigen token.

### 9. Regla 13 de ArchUnit (invoicing y sales)

- **invoicing:** `org.springframework.security` solo en `infrastructure.config`.
- **sales:** también en el ACL `infrastructure.adapter.out.catalog`, y el cliente OAuth2 (`org.springframework.security.oauth2.client`) solo en ese ACL.
- **Ampliada al registrar quién hizo la venta (decisión 13):** en sales también se permite en `infrastructure.adapter.in.rest`, porque el controlador lee el `Jwt` (`@AuthenticationPrincipal`) y pasa `sub` y `preferred_username` como texto en el comando. Dominio y aplicación siguen sin Spring Security.
  - Se descartó un resolvedor de argumentos en `config` que entregara un tipo propio al controlador: la regla quedaba igual, pero era una indirección sin beneficio.
- **Catalog (en capas) y el gateway:** no tienen esta regla. Su configuración de seguridad vive en `config` y en `security`, respectivamente.

### 10. Puertos: los servicios siguen publicados en `127.0.0.1`

- **Se mantiene `127.0.0.1:8081–8083`.** Con zero trust, publicarlos en loopback no abre un atajo: cada servicio exige token.
- **A cambio se conserva:** el Swagger y el Actuator de cada servicio, `seed-catalog.sh` contra 8082 y la depuración directa.
- **Publicar solo el gateway** obligaría a enrutar (o agregar) los Swagger en el gateway y a cambiar los scripts, y no da más seguridad en local.
- **En producción** solo se publicaría el gateway (ver abajo).

### 11. Tests

- **Capa web, por servicio** (`SaleSecurityTest`, `InvoicingSecurityTest`, `CatalogSecurityTest`; `@WebMvcTest` con la configuración real):
  - cada fila de la matriz con cada identidad (los tres roles y `sales-service`): sin token 401; identidad fuera de la fila 403; dentro, 2xx;
  - tokens inválidos (vencido, otro emisor, sin audiencia, otra clave, mal formado): 401 sin detalles;
  - Keycloak caído: 503 (`KeycloakDownSecurityTest`).
- **Los tokens de esos tests:** tienen la forma de los del realm y los firma `FakeKeycloak`, un WireMock que publica el JWKS de una clave generada en cada ejecución. Pasan por el **decodificador real** de Boot (firma, `iss`, `aud`, vigencia) y por el conversor, no por un `JwtDecoder` simulado.
- **Tests de controlador existentes:** usan `@WithMockUser` (soporte de `spring-security-test`) con su rol.
- **Integración** (`RestTestClient` + Testcontainers): `TestSecurityConfiguration` apunta el JWKS al Keycloak falso y le da al `RestTestClient` un token por defecto (vendedor en sales, contador en invoicing, admin en catalog y el gateway). En sales, el token endpoint falso entrega el token de `sales-service`, y el test de punta a punta verifica que llega a catalog.
- **`SecurityEndToEndTest`, por servicio:** health público, resto de Actuator solo admin, Swagger y `/v3/api-docs` públicos con el esquema OAuth2.
- **Keycloak real** (`RealKeycloak`, copiado en gateway, catalog y sales; imagen de `InfraImages`; contraseñas y secreto aleatorios):
  - `GatewayKeycloakSecurityTest`: cada usuario de demostración entra por el gateway con su token real; el token de `sales-service` da 403; el de `admin-cli` (sin audiencia), 401;
  - `CatalogKeycloakIntegrationTest`: admin crea, vendedor lee pero no crea, contador no lee; `sales-service`, con su token de client credentials, lee y hace el lookup pero no escribe;
  - `SalesKeycloakIntegrationTest`: el vendedor vende con su token real y sales pide de verdad su token a Keycloak. Catalog (WireMock) recibe un token de `sales-service` con `catalog.read` y la audiencia, el mismo en las llamadas de dos ventas (caché).

  Las dos últimas, juntas, cubren el flujo sales → catalog sin compartir código entre servicios.
- **Adaptador de catalog** (`CatalogRestClientTest`): token en caché, renovación antes de vencer, 401 de catalog con renovación, 403, `invalid_client`, Keycloak reiniciando (503) y caído.

### 12. CI y scripts

- **CI:** catalog y sales pasan a depender de `infra/keycloak/**` (alias `realm` en `ci.yml`, que también usa el gateway). Tabla en la nota del ADR 0016.
- **`seed-catalog.sh`:** usa `FACTURAFLOW_TOKEN`, o pide el token de `admin.demo` con `keycloak-token.sh`. La cabecera `Authorization` va a curl por un descriptor de archivo (`-H @<(...)`), no en los argumentos.
- **`keycloak-token.sh --claims`:** muestra también `aud`.

### 13. Quién hizo la venta (`SoldBy`, decisión D6 de la planificación)

Resuelve el punto que esta fase dejó pendiente. Toca dominio y caso de uso, por eso fue un PR aparte.

- **Qué se guarda:** un value object `SoldBy(userId, username)` en el agregado `Sale` (Java puro). Es una **copia** del usuario al momento de vender, como el snapshot del cliente.
  - `userId` = `sub`: el identificador estable, que no cambia si el usuario se renombra o cambia su email.
  - `username` = `preferred_username`: el nombre para mostrar, tal como era al vender. Si el token no lo trae (sin el scope `profile`), se usa el `sub`.
  - Ninguno vacío, sin espacios a los lados, máximo 255 caracteres (el tope de Keycloak). `userId` es opaco: sales no asume que sea un UUID.
  - **Descartados:** `email`, `name`, `given_name` y `family_name`. Son datos personales que pueden cambiar y no hacen falta para saber quién vendió (mínimo de datos).
- **De dónde sale:** el controlador lo lee del `Jwt` ya validado y lo pasa en `CreateSaleCommand` (`soldByUserId`, `soldByUsername`) como texto. Nunca viene del cuerpo: un `soldBy` en la petición es un campo desconocido (400). Un token sin `sub` es un error de configuración del realm (el scope `basic` lo emite siempre): responde 500 con log ERROR, no 422.
- **Ventas que ya existían:** la migración V4 agrega `sold_by_user_id` y `sold_by_username` **opcionales**, con el CHECK `ck_sales_sold_by` (las dos o ninguna).
  - `Sale.confirm` exige `SoldBy`; solo `reconstitute` lo acepta vacío. La API responde `"soldBy": null` en esas ventas.
  - **Descartado:** rellenarlas con un valor ficticio (`unknown`), porque guardaría una identidad falsa. Borrar los datos (`down -v`) tampoco sirve, porque una migración debe aplicarse sobre un volumen con datos.
  - En una base nueva (CI, tests) todas las ventas lo tienen; el `NULL` queda solo para la historia.
- **Idempotencia: `SoldBy` NO entra en la huella.** La huella identifica el contenido de la venta, no a quien la pide.
  - Si otro usuario reintenta la misma petición (cambio de turno, o un cliente que reintenta con otro token), recibe 200 con la venta existente, que conserva al vendedor original.
  - Si `SoldBy` entrara en la huella, ese reintento daría un 409, como si fuera otra venta. `SaleRequestHasher` no cambia: no hace falta un formato `v2`.
- **Evento `SaleConfirmed`: no lo lleva.**
  - Invoicing no lo usa, y así el nombre de usuario no sale de sales (mínimo de datos).
  - `docs/events/` no cambia. `SaleConfirmedSchemaTest` comprueba que el mensaje no lo incluye.
  - Si algún consumidor lo necesitara, sería un campo opcional nuevo: un cambio compatible de la v1 (`docs/events/README.md`).
- **Autorización a nivel de datos: vendedor y admin ven TODAS las ventas.**
  - En una pyme con una caja compartida, cualquier vendedor necesita consultar ventas de otros: devoluciones, o revisar si una venta ya se facturó.
  - La matriz de la decisión 4 no cambia.
  - `SaleApiEndToEndTest` lo fija: la vendedora A vende, y el vendedor B y el admin leen la venta (200, `soldBy` = A); el reintento de B devuelve la venta de A.
  - La alternativa ("cada vendedor solo ve las suyas") queda en "Cómo se haría en producción".
- **Tests:**
  - dominio: `SoldByTest`, `SaleTest` y `SaleInvoicingTest` (las transiciones lo conservan);
  - caso de uso: `CreateSaleServiceTest` (un `SoldBy` inválido es 422 antes de llamar a catalog, y un reintento de otro vendedor da 200 con el original), y `SaleRequestHasherTest` (otro vendedor, misma huella);
  - persistencia: `JpaSaleRepositoryAdapterTest` (ida y vuelta, venta antigua en `NULL`, cambio de estado sin tocarlo, CHECK);
  - web: `SaleControllerTest` (`jwt()` de spring-security-test: 400 con `soldBy` en el cuerpo, `sub` como nombre de respaldo, 500 sin `sub`) y `SaleSecurityTest` (token de `FakeKeycloak` por el decodificador real);
  - Keycloak real: `SalesKeycloakIntegrationTest` (`soldBy` = `sub` y `vendedor.demo` del token).

## Cómo se haría en producción

- **Solo el gateway publica puertos**, detrás de un balanceador con TLS; los servicios, en una red privada (o mTLS entre ellos con una malla de servicios).
- **TLS en todo**, incluido el JWKS (`https://`), y Keycloak con `sslRequired: all` (ADR 0017).
- **Una audiencia por servicio** (`aud: catalog` para el token de `sales-service`, `aud: gateway` para el de los usuarios), o token exchange (RFC 8693) en el gateway, para que un token robado a un servicio no sirva contra otro.
- **Secretos de cliente rotados desde un gestor** (Vault, AWS Secrets Manager), o mejor `private_key_jwt` (RFC 7523) en vez de `client_secret` para `sales-service`.
- **Permisos más finos:** roles de cliente o scopes por operación (`invoices:issue`), y quizás autorización por atributos con una política externa (OPA) si crece la matriz.
- **Cada vendedor solo ve sus ventas** (alternativa a la decisión 13), si el negocio lo pidiera. Habría que:
  - pasar quién consulta (`userId` y si es admin) a `GetSaleUseCase` y `ListSalesUseCase`;
  - filtrar el listado por `sold_by_user_id`, con un índice nuevo;
  - responder 404, no 403, a una venta ajena, para no revelar que existe;
  - definir el reintento de una venta ajena: el 200 idempotente la mostraría, así que tendría que ser un 409;
  - decidir qué pasa con las ventas sin `soldBy`: solo el admin las vería.
- **Revocación:** tokens cortos (5 min, ya) y, para cortar una sesión al instante, introspección (RFC 7662) en las operaciones sensibles o un evento de logout de Keycloak.
- **Auditoría:** eventos de Keycloak y quién hizo qué en cada servicio (en sales, la venta ya guarda quién la hizo: decisión 13), con el `traceparent` de la fase de observabilidad.
- **Limitador por cliente en el gateway** y cabeceras de seguridad (ADR 0017).

## Consecuencias

- **Positivas:**
  - nadie llega a la API sin un token del realm emitido para ella, con el rol correcto, ni por el gateway ni por el puerto de un servicio;
  - la matriz vive en un solo archivo por servicio y está probada fila por fila;
  - sales tiene su propia identidad ante catalog, en caché y dentro de la resiliencia existente;
  - los servicios siguen arrancando sin Keycloak; el dominio y los casos de uso no cambiaron.
- **Negativas:**
  - **Cuatro copias** del conversor, de las respuestas de seguridad y de los soportes de test (Keycloak falso y real). Se aceptan por el ADR 0001 y están marcadas como copias deliberadas.
  - **Las demos necesitan tokens** que vencen a los 5 minutos (`keycloak-token.sh`, o el botón "Authorize").
  - **Un entorno existente debe reimportar el realm** (audiencia nueva).
  - **Más tiempo de CI:** tres suites levantan un Keycloak real (unos 20–30 s cada una).
  - **Rutas desconocidas:** en los servicios dan 403 en vez de 404 (denegar por defecto).
