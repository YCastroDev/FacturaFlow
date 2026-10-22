# 0007. Datos fiscales del cliente y unidades de medida emitibles

## Estado

Aceptado — 2026-10-21. Reemplaza parcialmente al ADR 0004 en dos puntos: la regla "razón social o nombres" del cliente y la lista de unidades de medida. Actualiza la huella del ADR 0006 a v2.

## Contexto

El spike contra el sandbox de Factus ([spike 0001](../spikes/0001-factus-sandbox.md)) mostró que el modelo del cliente del ADR 0004 no alcanza para emitir ante la DIAN:

- **Tipo de persona:** Factus exige `legal_organization_code`: `1` para una persona jurídica, que se identifica con `company`; `2` para una persona natural, que exige `names`. Nuestro dominio solo pedía "razón social o nombres" y permitía las dos a la vez, sin decir qué tipo de persona era.
- **Situación tributaria:** exige `responsibilities`, las responsabilidades fiscales del RUT, y acepta `tribute_code`, el tributo del cliente.
- **Municipio:** acepta el código DIVIPOLA del DANE.
- **Unidades de medida:** solo acepta 8 códigos. Probamos 48 y 8 de nuestras 16 unidades no tienen ninguno aceptado.

Además, el proyecto es de portafolio, sin datos ni usuarios en producción. La única factura existente fuera de los tests es una de prueba en la base local.

## Decisión

### 1. Tipo de persona (`PersonType`)

Hay dos valores: `NATURAL_PERSON` y `LEGAL_ENTITY`. Reemplaza la regla "razón social o nombres" con una **exclusión estricta**:

- `LEGAL_ENTITY` exige `companyName` y **no** admite `names`;
- `NATURAL_PERSON` exige `names` y **no** admite `companyName`.

El tipo de persona **no** se deduce del tipo de documento. Una persona natural comerciante puede tener NIT, y Factus acepta NIT con `legal_organization_code = 2`.

### 2. Situación tributaria (`FiscalProfile`)

Es un value object con dos partes, y ambas son obligatorias, sin valor por defecto, para que nadie las olvide.

- **`TaxScheme`**, la responsabilidad tributaria (el tributo del cliente):

  | Valor | Código DIAN |
  |---|---|
  | `IVA` | `01` |
  | `NOT_APPLICABLE` | `ZZ` |

  Son los únicos dos valores que Factus aceptó.

- **`Set<FiscalResponsibility>`**, las responsabilidades fiscales del RUT. Es un conjunto no vacío: repetir una no cambia nada, y `NOT_RESPONSIBLE` significa "ninguna de las anteriores", así que **no se combina** con otras.

  | Valor | Código DIAN |
  |---|---|
  | `LARGE_TAXPAYER` | O-13 |
  | `SELF_WITHHOLDER` | O-15 |
  | `VAT_WITHHOLDING_AGENT` | O-23 |
  | `SIMPLE_TAX_REGIME` | O-47 |
  | `NOT_RESPONSIBLE` | R-99-PN |

Los códigos DIAN **no** viven en el dominio (ADR 0004, decisión A): los traduce el adaptador.

### 3. Municipio (`Municipality`) y datos de contacto

- **`Municipality(code)`:** es el código **DIVIPOLA del DANE**, exactamente 5 dígitos. Los 2 primeros deben ser uno de los 33 códigos de departamento. Es un código oficial del Estado, no de un proveedor, así que puede vivir en el dominio. El catálogo de 1.100 municipios no se incluye: el detalle lo valida el proveedor.
- **El municipio es opcional.** Se verificó con una factura real en el sandbox, `SETP990028966`, sin municipio, dirección ni teléfono: se **validó**, y la DIAN solo agregó la notificación `FAK08` (dirección incompleta), que no es un rechazo. En una venta de mostrador a consumidor final no suele haber dirección. Swagger recomienda informarlo.
- **Dirección:** opcional, de máximo 250 caracteres. El límite lo fija el dominio, porque Factus no publica uno.
- **Teléfono (`PhoneNumber`):** opcional. Se normaliza quitando espacios, guiones, puntos y paréntesis. Después debe tener entre 7 y 15 dígitos, con un `+` inicial opcional (estilo E.164).

### 4. Unidades de medida: solo las emitibles

`UnitOfMeasure` se reduce a las 8 unidades que el proveedor acepta: `UNIT`, `KILOGRAM`, `GRAM`, `POUND`, `LITER`, `MILLILITER`, `GALLON` y `METER`.

- Se eliminan `DOZEN`, `BOX`, `PACKAGE`, `CENTIMETER`, `SQUARE_METER`, `HOUR`, `DAY` y `SERVICE`.
- Los servicios y las horas se facturan en `UNIT`, que la DIAN admite para servicios.
- **Principio:** el dominio no ofrece nada que después no se pueda emitir.
- Agregar una unidad exige confirmar que el proveedor la acepta y escribir una migración (`CHECK`).
- `ApiUnitOfMeasure` se reduce igual. Es un cambio incompatible de la API, aceptable porque la v1 aún no tiene clientes.

### 5. Persistencia: migración V4

**Columnas nuevas** en `invoices`:

| Columna | Tipo |
|---|---|
| `customer_person_type` | `VARCHAR(20)` |
| `customer_tax_scheme` | `VARCHAR(20)` |
| `customer_fiscal_responsibilities` | `TEXT[]` |
| `customer_municipality_code` | `VARCHAR(5)` |
| `customer_address` | `VARCHAR(250)` |
| `customer_phone` | `VARCHAR(16)` |

Los largos de las tres últimas los impone el dominio.

- **Responsabilidades como `TEXT[]`** (arreglo nativo de PostgreSQL, mapeado con `@JdbcTypeCode(SqlTypes.ARRAY)`). Es un valor pequeño del snapshot que nunca se consulta por separado: sin tabla hija ni join, la carga sigue siendo **una** sentencia, y la base todavía puede validarlo con un `CHECK`.
- **Filas existentes:** se rellena solo lo que se deduce sin ambigüedad, y se **aborta con un mensaje explícito** en lo demás, en vez de inventar datos.

  | Columna | Relleno |
  |---|---|
  | `customer_person_type` | razón social sin nombres → `LEGAL_ENTITY`; nombres sin razón social → `NATURAL_PERSON`; **con las dos** (antes permitido) → aborta |
  | `customer_tax_scheme` | `NOT_APPLICABLE`, el mismo valor por defecto que aplica Factus |
  | `customer_fiscal_responsibilities` | `{NOT_RESPONSIBLE}`, el valor más neutro |
  | municipio, dirección, teléfono | `NULL` |
  | `invoice_lines.unit_of_measure` | sin cambio; si alguna línea usa una unidad eliminada → aborta |

  Como el DDL de PostgreSQL es transaccional, un aborto deja la base intacta en V3.

- **Defensa en profundidad:** los `CHECK` repiten las reglas del dominio.
  - El `CHECK` de V2 sobre los nombres se reemplaza por uno que depende del tipo de persona.
  - Responsabilidades: no vacías, solo valores conocidos y `NOT_RESPONSIBLE` siempre sola. **No** se comprueban duplicados, porque un `CHECK` no admite subconsultas; los evita el dominio, que usa un conjunto.
  - Formato del municipio y del teléfono.
  - El `CHECK` de unidades queda con los 8 valores.
- **Test de migración (`V4MigrationTest`):** aplica V1–V3, inserta filas "estilo V3" y migra. Cubre el relleno, una base vacía y los dos casos que abortan, comprobando además que la base queda en V3.

### 6. Huella de la petición v2, sin versionar

La huella del ADR 0006 pasa a **v2** (prefijo `invoice-request-v2`). Agrega el tipo de persona, la responsabilidad tributaria, las responsabilidades fiscales (**ordenadas**, porque son un conjunto), el municipio, la dirección y el teléfono, todo ya normalizado por el dominio.

**No se versiona** la huella guardada: el cálculo v1 se elimina.

**Limitación aceptada.** La única factura guardada con una huella v1 es la de prueba de la base local. Un reintento de su creación, aunque fuera idéntico, se compararía con la v2 y daría un **409 falso**. En este proyecto esa protección solo cuidaría datos de prueba, así que se prefiere la solución simple (ver el principio de trabajo en `CLAUDE.md`).

**Cómo se haría en un sistema real (huella versionada con v1 congelada):**
1. Agregar la columna `request_hash_version SMALLINT NOT NULL`: las filas existentes quedan en `1`, se quita el valor por defecto y el `CHECK` admite `1` o `2`. Las inserciones nuevas escriben `2`.
2. `RequestHash` pasa a ser `(int version, String value)`.
3. `InvoiceRequestHasher` conserva el cálculo **v1 congelado** junto al v2, protegido por un test con un hash fijo (*golden*) para que nadie lo modifique sin querer.
4. Al comparar, la huella de la petición se calcula **con la versión de la fila guardada**.
5. Consecuencia que habría que aceptar: con v1, dos peticiones que solo difieran en los campos nuevos se consideran iguales, porque la factura vieja nunca tuvo esos datos.

Se descartaría recalcular las huellas en SQL, porque replicaría la codificación canónica en PL/pgSQL.

### 7. API

`CreateInvoiceRequest.customer` gana:
- `personType`, `taxScheme` y `fiscalResponsibilities`, obligatorios por forma;
- `municipalityCode`, `address` y `phone`, opcionales;
- un tope anti-abuso de 10 responsabilidades.

La respuesta devuelve los mismos campos: las responsabilidades en el orden del enum y el teléfono ya normalizado. Las reglas de negocio (exclusión de nombres, `NOT_RESPONSIBLE` sola, formato del municipio y del teléfono) **no** se duplican en Bean Validation: responden 422 desde el dominio (ADR 0006, D6).

Los ejemplos de Swagger se actualizaron:
- **contado con IVA:** persona jurídica, responsable de IVA, gran contribuyente y autorretenedor, con ubicación completa;
- **crédito con IVA + INC:** persona natural no responsable, solo con municipio.

## Alternativas consideradas

- **Mantener las 16 unidades y traducir las no soportadas a `94`:** el documento legal diría "2 unidades" cuando fueron "2 horas". Es un dato tributario incorrecto, oculto en el adaptador.
- **Mantener las 16 unidades y rechazarlas al emitir:** la factura se crearía bien y nunca podría emitirse. Validarla al crear obligaría al dominio a conocer al proveedor.
- **Deducir el tipo de persona del documento (NIT → jurídica):** es falso para las personas naturales comerciantes.
- **Permitir razón social y nombres a la vez:** no hay forma clara de enviarlo a Factus, y la factura quedaría ambigua.
- **Responsabilidades en una tabla hija:** SQL estándar, pero suma una colección al entity graph y más filas, para un valor que nunca se consulta por separado.
- **Municipio obligatorio, o solo para personas jurídicas:** el spike mostró que no hace falta, y obligaría a inventar municipios en las ventas de mostrador y en las filas existentes.
- **Huella versionada:** ver la decisión 6. Es la solución correcta para producción y no se justifica aquí.
- **Empezar de cero (`down -v`) en vez de migrar:** dejaría una V4 sin probar contra datos reales.

## Consecuencias

**Positivas**
- El cliente tiene todo lo que Factus y la DIAN necesitan, con conceptos propios y sin códigos del proveedor en el dominio.
- Ninguna factura que el dominio acepte queda sin poder emitirse por su unidad de medida.
- La migración V4 es segura en cualquier base: completa lo deducible y aborta, sin aplicar nada, ante lo ambiguo.

**Negativas**
- Las pymes de servicios facturan horas y servicios como `UNIT`. Ampliar la lista requiere que el proveedor acepte la unidad y una migración.
- Los clientes de la API deben enviar tres campos obligatorios nuevos, y la lista de unidades se redujo.
- La factura de prueba con huella v1 daría un 409 falso si se reintentara su creación (decisión 6).
- Las filas migradas quedan con una situación tributaria neutra (`NOT_APPLICABLE`, `{NOT_RESPONSIBLE}`) que pudo no ser la real.
- La base no comprueba duplicados en el arreglo de responsabilidades; depende del dominio.
