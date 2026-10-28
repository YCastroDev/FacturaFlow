# 0017. Seguridad, Fase 1: Keycloak como proveedor de identidad (realm como código) y API Gateway con Spring Cloud Gateway (WebMVC)

## Estado

Aceptado — 2026-10-28. Fase 1 de 2: Keycloak y el gateway quedan funcionando, pero **nadie exige token todavía**. La Fase 2 (validación del JWT y de los roles en el gateway y en cada servicio, y sales llamando a catalog con su identidad) tendrá su propio ADR.

## Contexto

Los tres servicios (invoicing, catalog y sales) no tienen autenticación: cualquiera que llegue a sus puertos puede emitir facturas o crear ventas. Para cerrarlo se agregan un proveedor de identidad y un punto de entrada único, en dos fases, para que cada una sea revisable por separado.

Decisiones tomadas para las dos fases:
1. **Gateway con Spring Cloud Gateway.**
2. **Zero trust:** en la Fase 2 validarán el JWT y los roles el gateway **y** cada servicio. Un servicio no confía en que la petición "ya pasó por el gateway".
3. **Roles de realm:**
   - `admin`: todo;
   - `vendedor`: crear y ver ventas, y consultar productos y clientes;
   - `contador`: ver y emitir facturas y notas crédito, y descargar sus PDF.

   Hay un usuario de demostración por rol.
4. **Sales llama a catalog con su propia identidad:** client credentials con el cliente confidencial `sales-service`, con permiso de solo lectura en catalog. Los eventos de RabbitMQ no cambian.
5. **Keycloak como código:** el realm `facturaflow` vive en un archivo del repositorio y se importa al arrancar. Keycloak tiene su propia base `keycloak_db` con el usuario `keycloak_user`, creada por el script idempotente de PostgreSQL (ADR 0003).

Restricción de esta fase: los servicios no cambian, salvo lo indispensable (ver la nota final).

## Decisión

### 1. Keycloak 26.8.1, perfil de producción sobre HTTP

- **Imagen:** `quay.io/keycloak/keycloak:26.8.1` (la última estable al 2026-10-28), fijada en `infra/docker-compose.yml`. Es la fuente única de las imágenes de los tests (ADR 0016): el test del realm usa la misma.
- **Modo `start` con `KC_HTTP_ENABLED=true`, no `start-dev`:**
  - es la configuración de producción salvo TLS;
  - `start-dev` relaja valores (caché de temas, hostname) y esconde errores que aparecerían al desplegar;
  - lo que cuesta es poco: `KC_CACHE=local`, porque hay un solo nodo y no hace falta el clúster de Infinispan.
- **Base propia:** `keycloak_db` / `keycloak_user` (`KEYCLOAK_DB_PASSWORD`), con una línea más en `infra/postgres/init/01-create-service-databases.sh`. Keycloak no es un servicio de FacturaFlow, pero sigue la misma regla del ADR 0003.
- **Issuer fijo:** `KC_HOSTNAME=http://localhost:8180` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`.
  - Todo token dice `iss: http://localhost:8180/realms/facturaflow`, tanto si se pide desde la terminal como desde un contenedor.
  - Las llamadas internas (JWKS, token endpoint) usan la URL con la que se llega: `http://keycloak:8080/...` dentro de la red de compose.
  - Sin esto, un token obtenido desde la terminal y validado dentro de Docker tendría otro issuer, y la Fase 2 lo rechazaría. Lo verifica el README (`.well-known/openid-configuration` desde el contenedor del gateway).
- **Puertos:**
  - solo `127.0.0.1:8180`, que da la consola y los endpoints del realm;
  - el de gestión (9000, health) no se publica: lo usa el healthcheck, que hace la petición con `/dev/tcp` de bash, porque la imagen no trae curl ni wget.
- **Administrador inicial:** `KC_BOOTSTRAP_ADMIN_USERNAME`/`PASSWORD` desde `.env` (`KEYCLOAK_ADMIN_USER`/`KEYCLOAK_ADMIN_PASSWORD`). Se crea solo en el primer arranque.

### 2. El realm `facturaflow` (`infra/keycloak/import/facturaflow-realm.json`)

| Elemento | Configuración |
|---|---|
| Roles de realm | `admin`, `vendedor`, `contador`, con su descripción |
| Usuarios | `admin.demo`, `vendedor.demo` y `contador.demo`, cada uno con solo su rol, correo verificado, nombre y apellido (sin ellos, el *user profile* de Keycloak exigiría completar el perfil y el password grant fallaría) y contraseña no temporal |
| `sales-service` | Confidencial (`client-secret`), **solo** service account (sin login de usuarios ni password grant), `fullScopeAllowed: false`, scopes `basic`, `service_account` y `catalog.read` |
| `facturaflow-swagger` | Público, **solo** authorization code con **PKCE S256 obligatorio**. Redirecciones a `/swagger-ui/oauth2-redirect.html` de 8080–8083. Lo usará Swagger UI en la Fase 2 |
| `facturaflow-cli` | Público, **solo** direct access grants (decisión 4). Solo desarrollo |
| Protección | `bruteForceProtected` (5 fallos, espera creciente hasta 15 min), sin registro ni recuperación de contraseña, `sslRequired: external`, access token de 5 min |

- **Permiso de solo lectura de sales en catalog: client scope `catalog.read`.**
  - Es un scope por defecto solo de `sales-service`. En el token aparece como `scope: "catalog.read"`, y Spring Security lo convertirá sin código propio en `SCOPE_catalog.read`.
  - Ningún otro cliente lo tiene: un usuario que lo pide recibe `400 invalid_scope` (probado).
  - En la Fase 2, catalog aceptará en sus lecturas y en el lookup un rol `admin`/`vendedor` **o** `SCOPE_catalog.read`.
  - Alternativas descartadas:
    - un rol de realm `catalog-reader`: mezcla identidades de máquina con roles de personas;
    - un rol del cliente `catalog`: va en `resource_access.catalog.roles` y exigiría un conversor de roles a medida.
- **Los client scopes estándar se incluyen en el archivo.**
  - Si un realm importado declara `clientScopes`, Keycloak **no crea** los predeterminados. Se comprobó: solo quedan los declarados más `offline_access`.
  - Sin `basic` los tokens no llevan `sub`; sin `roles` no llevan `realm_access.roles`.
  - Por eso el archivo trae, junto a `catalog.read`, los scopes OIDC estándar **tal como los exporta Keycloak 26.8.1** para un realm vacío (sin ids), con `defaultDefaultClientScopes` y `defaultOptionalClientScopes`. Al actualizar Keycloak, conviene comparar con un export nuevo.

### 3. Secretos: marcadores `${VAR}` que Keycloak sustituye al importar

- El archivo del realm **no contiene ninguna contraseña ni secreto**:
  - las contraseñas de los usuarios son `${KC_DEMO_ADMIN_PASSWORD}`, `${KC_DEMO_VENDEDOR_PASSWORD}` y `${KC_DEMO_CONTADOR_PASSWORD}`;
  - el secreto de `sales-service` es `${SALES_SERVICE_CLIENT_SECRET}`.
- Keycloak sustituye los marcadores por variables de entorno al importar. Compose se las pasa desde `.env` y las exige con `:?`: sin ellas Keycloak no arranca, en vez de importar el texto literal como contraseña.
- **Se verifica con tres tests:**
  - `RealmFileSecretsTest`: toda `credentials[].value` y todo `secret` es un marcador `${VAR}`, y cada VAR está en `.env.example` (con `CAMBIAME`) y en el `environment` de `keycloak` del compose;
  - `KeycloakRealmTest`: cada usuario obtiene su token con la contraseña aleatoria inyectada en esa ejecución, y el marcador literal no sirve como contraseña;
  - gitleaks, como en todo el repo.
- **Los `${...}` propios de Keycloak** (`${rolesScopeConsentText}`, `resource_access.${client_id}.roles`) no son variables de entorno: quedan literales tras la importación (se comprobó). Por eso los nombres de nuestras variables no deben coincidir con ellos.
- **Limitación: la importación solo crea el realm si no existe** (estrategia `IGNORE_EXISTING`). Es el mismo comportamiento que el init de PostgreSQL con las contraseñas.
  - Cambiar el archivo, o una contraseña en `.env`, no tiene efecto sobre un volumen que ya tiene el realm.
  - Para aplicarlo: borrar el realm `facturaflow` en la consola (Realm settings → Action → Delete) y reiniciar Keycloak, que lo vuelve a importar (README).
  - Es aceptable aquí: los usuarios y los datos del realm son de demostración.

### 4. Tokens desde la terminal: cliente `facturaflow-cli` con direct access grants

- `./scripts/keycloak-token.sh <usuario>` pide la contraseña sin eco (o la toma de `KC_PASSWORD`) e imprime el access token. Con `--claims` muestra el contenido del token. Con `sales-service` usa client credentials (`KC_CLIENT_SECRET`).
  - La contraseña nunca va en los argumentos de curl (no aparece en `ps`): se le pasa por la entrada estándar.
  - El script no lee `.env`.
- **A favor:** una sola petición y nada de navegador. Es ideal para demos, scripts y el test del realm.
- **En contra:** el password grant (ROPC) está desaconsejado y se elimina en OAuth 2.1, porque el cliente ve la contraseña del usuario. Por eso es un cliente aparte, público, solo para desarrollo, que **no se importaría en producción**. Los usuarios reales entrarán por authorization code + PKCE (`facturaflow-swagger` y, después, el frontend).
- **Descartado:** el device authorization grant. Es más realista, pero cada token exige abrir el navegador y aprobar.

### 5. API Gateway: Spring Cloud Gateway Server **WebMVC** (`services/gateway`)

- **Versiones:**
  - Spring Cloud **2025.1.4** (Oakwood), que soporta Boot 4.1 desde la 2025.1.2;
  - `spring-cloud-starter-gateway-server-webmvc` 5.0.4, gestionado por el BOM.
- **WebMVC y no WebFlux:**
  - es el mismo modelo servlet de los otros tres servicios, con los mismos tests (`RestTestClient`);
  - en la Fase 2, la misma configuración de Spring Security (resource server servlet) en el gateway y en cada servicio, que es lo que pide el zero trust;
  - con hilos virtuales (`spring.threads.virtual.enabled`), una petición reenviada que bloquea su hilo no agota un pool, lo que alcanza de sobra para una pyme.
  - WebFlux es más completa y escala mejor con miles de conexiones abiertas, pero impondría un modelo reactivo (Reactor, `WebTestClient`, seguridad reactiva) distinto del resto sin necesidad.
- **Servicio independiente** como los demás: pom, `mvnw`, Dockerfile multi-stage sin root y `.dockerignore`. Sin hexagonal ni capas: no tiene dominio, solo configuración y su manejador de errores (`com.facturaflow.gateway.web`). Su ArchUnit exige que no use código de otro servicio y que no haya ciclos.
- **Rutas** (`application.yml`; destinos por `GATEWAY_*_URI`, por defecto los nombres de compose):

  | Ruta | Servicio |
  |---|---|
  | `/api/v1/invoices/**`, `/api/v1/credit-notes/**` (incluye `/api/v1/invoices/{id}/credit-notes`) | invoicing |
  | `/api/v1/products/**`, `/api/v1/customers/**` | catalog |
  | `/api/v1/sales/**` | sales |

  - El método, la ruta, la query, el cuerpo y las cabeceras pasan sin cambios. `Authorization` también pasa, lo que necesitará la Fase 2.
  - Las respuestas de los servicios, incluidos sus Problem Details, vuelven intactas.
  - Swagger UI y Actuator de cada servicio siguen en sus puertos propios: el gateway solo expone la API.
- **Sin reintentos:** hay POST no idempotentes. La idempotencia la dan los servicios (`referenceCode`).
- **Timeouts** del cliente HTTP (`spring.http.clients.*` de Boot 4, que es lo que usa el gateway):
  - conexión: 2 s;
  - lectura: **60 s**, porque una emisión a Factus puede tardar unos 45 s en el peor caso (3 intentos × (3 s + 10 s) + esperas, ADR 0010).
- **`X-Forwarded-*`:** sin `trusted-proxies`, el gateway no las agrega y **borra** las que mande el cliente (también `Forwarded`). Así nadie se hace pasar por otra IP ante los servicios, y ninguno las usa todavía. Si un servicio las necesitara, se configuraría `trusted-proxies` con la red de compose.
- **Errores propios** (Problem Details con el formato de los servicios; `ProblemDetailsHandler` del gateway):

  | Situación | Estado | `type` |
  |---|---|---|
  | Ninguna ruta coincide | 404 | `urn:facturaflow:problem:not-found` |
  | El servicio no acepta la conexión (caído, reiniciándose) | 502 | `urn:facturaflow:problem:upstream-unavailable` |
  | El servicio no respondió dentro del timeout de lectura | 504 | `urn:facturaflow:problem:upstream-timeout` (el `detail` advierte que la operación pudo completarse) |
  | Cualquier otra excepción | 500 | `urn:facturaflow:problem:internal-error` |

  Nunca se exponen la URL interna, trazas ni mensajes de Spring. El log registra solo la clase de la causa, nunca URLs ni cabeceras, porque en la Fase 2 llevarán el token.
- **Health propio** (mismo criterio que el ADR 0010): `/actuator/health` no consulta los servicios ni Keycloak. Con un servicio caído, el gateway sigue UP y responde 502 solo en esa ruta (probado). En compose depende de los servicios con `service_started`.
- **Puertos:** el gateway en `127.0.0.1:8080`. Los servicios **conservan** 8081–8083 en esta fase; se decidirá en la Fase 2 si dejan de publicarse.

### 6. Sin identificador de correlación por ahora

- No se agrega un `X-Request-Id` propio. La fase de observabilidad traerá Micrometer Tracing, con `traceparent` (W3C) propagado por HTTP **y** por RabbitMQ (relay del outbox y consumidores).
- Un identificador propio hoy solo lo registraría el gateway, porque los servicios no cambian en esta fase, y después convivirían dos identificadores.
- Queda pendiente para esa fase.

### 7. Tests

- **Enrutamiento** (`GatewayRoutingTest`, `GatewayServiceDownTest`): el gateway real en un puerto aleatorio contra tres WireMock.
  - Cada ruta llega a su servicio y a ningún otro.
  - El cuerpo, la query y las cabeceras pasan.
  - `X-Forwarded-For`/`Forwarded` falsos se quitan.
  - Un 422 del servicio vuelve intacto.
  - Una ruta desconocida da 404 sin tocar ningún servicio.
  - Un servicio lento da 504 y uno caído da 502, con el health en UP.
- **Realm** (`KeycloakRealmTest`): Keycloak real (Testcontainers, imagen de `InfraImages`) que importa el archivo del repositorio con contraseñas y secreto aleatorios.
  - Verifica los roles y cada usuario con solo su rol.
  - Verifica el token de cada usuario (con su rol, `sub` y `preferred_username`).
  - Verifica que `sales-service` obtiene un token con client credentials (`catalog.read` y ningún rol de persona) y que un secreto incorrecto da 401.
  - Verifica que un usuario no puede pedir `catalog.read`, que `sales-service` no admite password grant y que el cliente de Swagger es público, con PKCE S256 y solo authorization code.
- **Librería:** `GenericContainer` de Testcontainers + `RestClient` contra la API de administración, sin `testcontainers-keycloak`.
  - Esa librería es más cómoda, pero arrastra `keycloak-admin-client`, con RESTEasy y Jackson 2, junto al Jackson 3 de Boot 4.
  - El precio es escribir la espera (`/health/ready` en el puerto 9000) y unas líneas de HTTP.
- Estos tests viven en el gateway, el borde de seguridad. La CI los dispara también por `infra/keycloak/**` y `.env.example` (decisión 8).

### 8. CI

- `ci.yml`: filtro `gateway` (`services/gateway/**`, `infra/keycloak/**`, `.env.example`, más los comunes de CI y del compose) y un job con `_service.yml`, que `ci-result` incluye en `needs`. Tabla actualizada en la nota del 2026-10-28 del ADR 0016.
- Dependabot: `/services/gateway` en los ecosistemas `maven` y `docker`. La imagen de Keycloak la actualiza el ecosistema `docker-compose`, y el PR dispara los tests del realm.
- SonarCloud: proyecto `ycastrodev_facturaflow-gateway` (organización `ycastrodev`), que se crea a mano. Hasta entonces, el análisis del gateway falla solo si existe `SONAR_TOKEN` y el proyecto no.

## Nota: `snakeyaml` con scope `test` dejaba a los servicios sin arrancar

- Al levantar compose con imágenes reconstruidas, **invoicing, catalog y sales no arrancaban**: `Attempted to load Config resource 'class path resource [application.yml]' ... but snakeyaml was not found on the classpath`.
- **Causa:** el PR #5 (ADR 0016, nota del 2026-10-27) declaró `org.yaml:snakeyaml` con `<scope>test</scope>` para `InfraImages`. Un scope declarado en el pom pisa el de la dependencia transitiva (`spring-boot-starter`, compile), así que SnakeYAML salía del jar ejecutable.
  - Los tests no lo notaban, porque en test sí está.
  - La CI tampoco, porque construye la imagen pero no la arranca.
- **Corrección** (lo único que cambia en los servicios en esta fase): la dependencia queda sin scope en los cuatro poms, con un comentario que explica por qué.
- **Pendiente sugerido:** un test de humo en la CI que arranque la imagen y espere el health. Ya aparece en "Cómo se haría en producción" del ADR 0016.

## Cómo se haría en producción

- **TLS** de punta a punta: Keycloak con `https` (`KC_HTTPS_*`), detrás de un proxy con `KC_PROXY_HEADERS`, y `sslRequired: all`.
- **Realm declarativo con reconciliación** (keycloak-config-cli o el proveedor de Terraform) en vez de una importación que solo crea, así un cambio del archivo se aplica sin borrar el realm. Los secretos, desde un gestor (Vault, AWS Secrets Manager), con rotación del secreto de `sales-service`.
- **Sin usuarios de demostración ni `facturaflow-cli`:** los usuarios vienen de un directorio o de un registro con aprobación, y los tokens humanos solo por authorization code + PKCE, con MFA para `admin`.
- **Keycloak en clúster** (varias réplicas con caché distribuida) y una imagen optimizada (`kc.sh build` + `start --optimized`).
- **Gateway:** limitador por cliente, cabeceras de seguridad, tamaño máximo de petición, `trusted-proxies` hacia el balanceador y varias réplicas detrás de él. Los servicios dejarían de publicar puertos.
- **Trazas distribuidas** (decisión 6) y auditoría de los eventos de Keycloak (`eventsEnabled`, `adminEventsEnabled`).

## Consecuencias

- **Positivas:**
  - hay un proveedor de identidad y un punto de entrada único listos para exigir tokens en la Fase 2;
  - la configuración de Keycloak está versionada y probada, sin secretos en el repositorio;
  - el issuer es el mismo desde dentro y desde fuera de Docker;
  - el gateway no cae cuando cae un servicio.
- **Negativas:**
  - **Todavía no hay seguridad real:** los servicios aceptan peticiones sin token, por el gateway o por sus puertos. Eso es la Fase 2.
  - **Importación que solo crea:** un cambio del realm (o de una contraseña del `.env`) exige borrar el realm y reiniciar Keycloak.
  - El realm repite unas 600 líneas de client scopes estándar que hay que revisar al subir de versión de Keycloak.
  - Un salto más en cada petición, con timeouts que deben superar a los de los servicios (60 s frente a los ~45 s de Factus).
  - Dos componentes más en compose (Keycloak tarda unos 20–30 s en arrancar).
