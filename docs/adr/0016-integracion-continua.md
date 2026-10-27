# 0016. Integración continua con GitHub Actions: orquestador con filtros de ruta, workflow reutilizable por servicio, GHCR, gitleaks, Dependabot y SonarCloud

## Estado

Aceptado — 2026-10-26. Cumple lo anticipado en el [ADR 0001](0001-monorepo.md): "la CI deberá construir y probar solo lo que cambió, usando filtros por ruta".

## Contexto

FacturaFlow es un monorepo público en GitHub con tres servicios Spring Boot (invoicing, catalog y sales) y hasta ahora no tenía CI. Todo se probaba en local con `./scripts/mvn.sh`.

**Requisitos:**
- solo herramientas gratuitas;
- ningún secreto en los workflows;
- en cada servicio, compilar, correr **todos** los tests (Testcontainers con PostgreSQL y RabbitMQ) y construir su imagen, solo cuando ese servicio (o algo que lo afecte) cambió;
- escaneo de secretos de todo el historial;
- dependencias al día sin decenas de PRs;
- análisis de calidad;
- poder proteger `main` exigiendo que la CI pase.

## Decisión

### 1. Un orquestador que siempre arranca + un workflow reutilizable por servicio (opción B)

```
.github/workflows/ci.yml         changes → invoicing | catalog | sales (si cambiaron) → ci-result
.github/workflows/_service.yml   reutilizable (workflow_call, input "service"): test → imagen
.github/workflows/gitleaks.yml   historial completo, en cada push y PR
.github/scripts/test-summary.py  resumen de los tests en el job summary
.github/dependabot.yml
```

**El problema de la alternativa A** (un workflow por servicio con `on.push.paths`): si un workflow no se ejecuta por sus filtros de ruta, su check queda **"Pending" para siempre**, y un PR que lo exige no se puede fusionar. Con tres workflows filtrados no se podría exigir ningún servicio en la protección de `main`.

**La solución (B):**
- `ci.yml` corre en cada push a `main`, en cada PR y a mano.
- El job `changes` (`dorny/paths-filter`) decide qué servicios cambiaron, y solo esos llaman a `_service.yml`. Los demás quedan **skipped**, que no bloquea.
- El job final **`ci-result`** (`if: always()`) falla si algún job falló o se canceló, y **es el único check obligatorio**.
- La configuración de un servicio existe una sola vez; cada servicio aparece como su propio grupo de jobs (`invoicing / test`, `invoicing / imagen`).
- **Lo que se pierde:** una insignia por servicio. Hay una insignia de CI, y la calidad por servicio la muestran las insignias de SonarCloud.

**Qué dispara a cada servicio:**

| Servicio | Rutas |
|---|---|
| invoicing | `services/invoicing/**`, `docs/events/**`, `infra/rabbitmq/**` |
| sales | `services/sales/**`, `docs/events/**`, `infra/rabbitmq/**` |
| catalog | `services/catalog/**` |
| los tres | `.github/workflows/**`, `.github/scripts/**`; `workflow_dispatch` (manual) prueba los tres siempre |

- `docs/events/` dispara a invoicing y sales porque sus tests validan los eventos contra esos esquemas (ADR 0013–0015).
- `infra/rabbitmq/` los dispara porque sus tests ejecutan el script de init real en Testcontainers.
- **`scripts/mvn.sh` no dispara nada:** la CI no lo usa. Corre `./mvnw` directamente en el runner, con Java 25 de `actions/setup-java`. El mismo Maven (3.9.16, por el wrapper) y el mismo JDK que en local.

**Concurrencia:** en una rama de PR, un push nuevo cancela la ejecución anterior. En `main` no se cancela, porque cada commit publica su imagen.

### 2. Job `test`: compilar, probar, cubrir y analizar

1. `actions/setup-java`: Temurin 25 con **caché de Maven** (`~/.m2/repository`), cuya clave es el `pom.xml` del servicio.
2. `./mvnw -B -ntp verify` desde `services/<servicio>`:
   - Testcontainers usa el Docker que traen los runners `ubuntu-24.04`;
   - **los `@Tag("sandbox")` (Factus real) no corren:** el pom de invoicing ya los excluye por defecto (`excludedGroups`), así que la CI no necesita credenciales de Factus;
   - **JaCoCo 0.8.15** (`prepare-agent` + `report` en `verify`) deja `target/site/jacoco/jacoco.xml`. La 0.8.16 se descartó por recién publicada; la 0.8.14 o superior soporta Java 25.
3. **Resumen de los tests** en el *job summary*: totales y tests fallidos, con `.github/scripts/test-summary.py` (solo la biblioteca estándar de Python, sin acciones de terceros).
4. **Si falla:** sube `target/surefire-reports/` como artefacto (14 días).
5. **SonarCloud, solo si existe `SONAR_TOKEN`** (decisión 6). Si no existe, el paso se omite con una línea en el resumen y la CI sigue en verde.

### 3. Job `imagen`: el Dockerfile se comprueba siempre; se publica en GHCR desde `main`

- Corre después de `test`: `docker/build-push-action` con el Dockerfile del servicio y caché de BuildKit `type=gha` separada por servicio.
- **En un PR:** solo construye. Comprueba que el Dockerfile funciona.
- **En un push a `main`:**
  - publica `ghcr.io/ycastrodev/facturaflow-<servicio>` con los tags `sha-<7>` (trazable al commit) y `latest`;
  - usa el `GITHUB_TOKEN` del workflow con `packages: write`, sin secretos propios;
  - `docker/metadata-action` agrega los labels OCI, incluido `org.opencontainers.image.source`, que enlaza el paquete con el repositorio.
- **Por qué publicar aunque nadie despliegue todavía:**
  - es gratis para repos públicos;
  - deja un artefacto versionado por commit;
  - es la base de un CD futuro.
- **Primera publicación:** puede que haya que marcar los paquetes como públicos a mano (Package settings).

### 4. gitleaks sobre todo el historial

`gitleaks.yml` corre en **cada push (de cualquier rama) y cada PR**:
- `fetch-depth: 0`;
- la imagen `zricethezav/gitleaks:v8.30.1` con el `.gitleaks.toml` del repo y `--redact`;
- **la misma imagen, versión y configuración** que el hook pre-commit y `scripts/check-secrets.sh`, así que local y CI dan el mismo resultado;
- no usa `gitleaks-action`, que pide licencia en organizaciones.

En un PR corre dos veces (push y pull_request). Cuesta segundos y deja el check `gitleaks` en el PR.

### 5. Dependabot, agrupado

| Ecosistema | Dónde | Grupos |
|---|---|---|
| `maven` | los tres servicios (`directories:`) | `maven-minor-patch` y `maven-major` |
| `docker` | los tres Dockerfiles | uno; **ignora** los cambios mayores de `eclipse-temurin` y `maven`, porque Java 25 es la LTS elegida |
| `docker-compose` | `infra/` | uno |
| `github-actions` | `/` | uno (actualiza los SHA fijados) |

- **Cadencia:** semanal (lunes 06:00, Bogotá), como máximo 5 PRs abiertos de Maven.
- **Un PR para los tres servicios:** gracias a `directories` + grupos, un mismo cambio (p. ej. Spring Boot) llega en UN PR, que la CI prueba en los tres.

**No cubre:**
- las imágenes fijadas en el código de los tests (`postgres:18-alpine` y `rabbitmq:4.3.6-management-alpine` en `TestcontainersConfiguration`);
- la de gitleaks (hook, script y workflow);
- la de Maven en `scripts/mvn.sh`.

Al actualizar una de esas, se buscan todas sus apariciones (`grep -rn "rabbitmq:4.3.6"`).

### 6. Calidad: SonarCloud con cobertura de JaCoCo, un proyecto por servicio

- **SonarCloud** es gratis para repos públicos y da *code smells*, bugs, vulnerabilidades, duplicación y cobertura, con *Quality Gate* por PR.
- **Un proyecto por servicio** (soporta monorepos), porque son proyectos Maven independientes: `ycastrodev_facturaflow-<servicio>`, en la organización `ycastrodev`.
- **Configuración:** está en las propiedades `sonar.*` de cada pom. `sonar-maven-plugin` va en `pluginManagement` (con su versión en una propiedad), para que `./mvnw sonar:sonar` lo resuelva por su prefijo y Dependabot lo actualice.
- **Análisis:** reutiliza lo compilado y el `jacoco.xml` de `verify`, sin volver a correr los tests. En un PR, el plugin detecta el contexto de GitHub Actions y decora el PR.
- **Se salta limpiamente sin `SONAR_TOKEN`:** el secreto pasa a una variable de entorno del job (los secretos no se pueden usar en `if:`) y el paso corre solo si no está vacía.
  - Mientras no lo configures, la CI sigue en verde.
  - Los PRs de Dependabot y de forks tampoco reciben secretos, así que se saltan igual.
- **Insignias de Sonar:** van comentadas en el README hasta que el proyecto exista, para no mostrar insignias rotas.

**Alternativas descartadas:**
- **CodeQL:** gratis y sin cuenta, pero solo de seguridad (sin cobertura ni mantenibilidad). Se puede sumar después.
- **Codecov:** solo cobertura, y también pide cuenta y token.
- **JaCoCo solo como artefacto:** nadie lo mira.

### 7. Seguridad de los workflows

- **Acciones fijadas por SHA completo**, con la versión en un comentario (`actions/checkout@3d3c42e… # v7.0.1`): un tag movido o comprometido no cambia lo que corre. Dependabot actualiza los SHA.
- **Permisos mínimos:**
  - `permissions: contents: read` por defecto;
  - `pull-requests: read` solo en `changes`;
  - `packages: write` solo en `imagen`, y solo se usa en `main`.
- **`persist-credentials: false`** en todos los checkout: el token no queda en `.git/config` para los pasos siguientes.
- **Sin interpolar** datos del evento dentro de los scripts: van por variables de entorno.

### 8. Protección de `main` y trabajo con PRs, aunque se trabaje solo

**Ruleset** de `main`, que se activa cuando la primera ejecución esté en verde:
- exigir PR, **con 0 aprobaciones** (no se puede aprobar el propio PR);
- exigir los checks **`ci-result`** y **`gitleaks`**;
- bloquear el *force push* y el borrado;
- historial lineal (*squash merge*);
- el administrador puede saltarlo en una emergencia, y queda registrado.

**Por qué, aunque se trabaje solo:**
- ningún cambio entra a `main` sin la CI en verde;
- los PRs de Dependabot se prueban solos;
- cada cambio queda documentado en su PR;
- es la forma de trabajo que se espera ver en un portafolio.

**El flujo:** rama → commits → `gh pr create` → CI → `gh pr merge --squash`.

### 9. Licencia

El repo es público y no tenía licencia: el código era visible pero, legalmente, nadie podía reutilizarlo. Se agrega **MIT** (`LICENSE`, © 2026 YCastroDev).

### 10. Verificación local

- **`actionlint`** (vía Docker, `rhysd/actionlint:1.7.12`, con shellcheck) sobre los workflows. Detectó un `: ` dentro de un escalar YAML sin comillas; se corrigió.
- **Esquemas oficiales** de SchemaStore para `dependabot.yml` y los workflows, con `check-jsonschema`.
- **Los comandos exactos de la CI:**
  - `./mvnw -B -ntp verify` en los tres servicios, en la imagen de Maven (con JaCoCo);
  - el escaneo de gitleaks;
  - la construcción de las imágenes.
- **Lo que no se puede simular en local** (permisos del token, caché `gha`, GHCR, paths-filter contra la API) se revisa en la primera ejecución (README).

## Cómo se haría en producción

- **CD:**
  - desplegar la imagen `sha-…` en un entorno de staging tras `main` y promoverla a producción con aprobación (*environments* de GitHub);
  - firmar las imágenes (cosign) y publicar su SBOM y procedencia (build-push-action ya genera una procedencia mínima).
- **Tests de contrato entre servicios** en un job que se dispare con `docs/events/**`, y un test de humo de punta a punta con `docker compose` en la CI.
- **Seguridad:**
  - escaneo de vulnerabilidades de las imágenes (Trivy, Grype) y de dependencias (OWASP dependency-check o el *dependency review* de GitHub);
  - CodeQL;
  - `actionlint` y `zizmor` como jobs de la propia CI.
- **Tiempos:** runners más grandes o paralelizar las suites de tests si crecen; hoy un servicio tarda pocos minutos.
- **Monorepo grande:** una herramienta de builds incrementales (p. ej. Bazel, o Maven multimódulo con `-pl/-am`) en vez de filtros de ruta escritos a mano.

## Consecuencias

**Positivas**
- **Cada cambio se prueba como en local**, con los mismos tests, Testcontainers y Java, y solo en los servicios afectados.
- **`main` se puede proteger** con un único check que siempre llega (`ci-result`).
- **Imágenes versionadas por commit** en GHCR, sin secretos.
- **Secretos vigilados en todo el historial** con la misma configuración que el hook local.
- **Dependencias al día** con pocos PRs agrupados.
- **Calidad y cobertura por servicio** en SonarCloud cuando se configure, sin romper la CI mientras tanto.

**Negativas**
- **Rutas escritas a mano:** si un servicio empieza a depender de otra carpeta, hay que agregarla en `ci.yml` (y en esta tabla).
- **Una dependencia de terceros** (`dorny/paths-filter`), fijada por SHA.
- **Una sola insignia de CI** en vez de una por servicio.
- **Versiones fijadas que Dependabot no ve** (las imágenes de los tests y de gitleaks): hay que revisarlas a mano.
- **Pasos manuales:** SonarCloud, la visibilidad de los paquetes de GHCR y el ruleset de `main` (README).
