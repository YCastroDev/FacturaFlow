# 0001. Monorepo para todos los servicios

## Estado

Aceptado — 2026-10-20

## Contexto

FacturaFlow se compone de varios microservicios (identity, catalog, sales, invoicing, notifications), un gateway y un frontend. Es un proyecto de portafolio mantenido por una sola persona: debe ser fácil de clonar, entender y levantar por quien lo revise.

## Decisión

Usar un único repositorio Git. Cada servicio vive en `services/<nombre>/` como proyecto independiente (su propio `pom.xml`, `Dockerfile` y base de datos). La infraestructura compartida de desarrollo vive en `infra/` y la documentación en `docs/`.

## Alternativas consideradas

- **Polyrepo (un repositorio por servicio):** mejor aislamiento y permisos/versionado independientes, pero coordinar un cambio que toca varios servicios exige varios PRs y versiones sincronizadas. Para una persona, ese costo no se justifica.

## Consecuencias

**Positivas**
- Un solo `git clone` y un solo `docker compose up` para ver todo el sistema.
- Un cambio que afecta a varios servicios cabe en un solo commit.
- Documentación, ADRs y convenciones en un único lugar.

**Negativas**
- Es fácil acoplar servicios por accidente (importar código de otro servicio). Requiere disciplina: los servicios solo se comunican por HTTP o eventos.
- La CI deberá construir y probar solo lo que cambió, usando filtros por ruta (`services/<nombre>/**`).
