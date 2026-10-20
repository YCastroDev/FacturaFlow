# 0004. Modelo de dominio de la factura

## Estado

Aceptado — 2026-10-20

## Contexto

El servicio invoicing emite facturas electrónicas ante la DIAN a través de Factus. Factus identifica casi todo con IDs numéricos de sus catálogos (municipios, unidades, tributos, tipos de documento) y devuelve sus propios totales. Necesitamos un modelo que exprese las reglas de negocio sin quedar atado a ese proveedor, que calcule montos de forma predecible y que pueda crecer (p. ej. con retenciones) sin reescribirse.

## Decisión

**A. Conceptos propios, nunca IDs de Factus.** El dominio usa enums y value objects propios (`DocumentType`, `UnitOfMeasure`, `PaymentMethod`, `TaxRate`…). La traducción a los IDs de los catálogos de Factus la hará el adaptador de salida, que actúa como *Anti-Corruption Layer*.

**B. Totales propios + total oficial.** `Invoice` calcula subtotal, descuentos, impuestos y total para mostrarlos y validarlos. Al emitirse, guarda en `ElectronicDocument` el total oficial que devuelve Factus, que es el que tiene validez legal. Si ambos difieren, la factura se emite igual y `hasOfficialTotalDiscrepancy()` permite detectarlo e investigarlo.

**C. Retenciones fuera de alcance.** No se modelan todavía. El diseño las admite sin reescribir: `total()` es el total de la factura (subtotal − descuentos + impuestos) y no se modificará; las retenciones llegarán como una colección nueva y un `payableAmount() = total() − retenciones`. Se postergan porque dependen de la calidad tributaria del comprador y del vendedor (agente retenedor, autorretenedor, régimen), de bases mínimas que cambian cada año con la UVT y de catálogos adicionales: modelarlas mal hoy es peor que no modelarlas.

**Política de montos**

- `Money` representa COP con 2 decimales, nunca negativo. Los valores de entrada (precios) se rechazan si traen más de 2 decimales; los resultados de cálculos se redondean de forma explícita.
- Redondeo **`HALF_UP` a 2 decimales** (redondeo comercial), el que usan la DIAN, los contadores y los proveedores tecnológicos; así nuestros totales coinciden con el oficial con mayor probabilidad. `HALF_EVEN` (bancario) reduce el sesgo estadístico pero produciría diferencias de un centavo frente a Factus.
- Redondeo **por línea**: bruto, descuento e impuesto se redondean en cada línea, y la factura suma valores ya redondeados. La factura electrónica (UBL) reporta los valores de cada línea, así que la suma de lo que se ve en el documento debe dar el total.
- Por línea: `bruto = cantidad × precio`; `descuento = bruto × %descuento`; `base = bruto − descuento`; `impuesto = base × %impuesto` (0 si la línea está excluida); `total = base + impuesto`. El descuento se aplica antes del impuesto.
- `unitPrice` **no incluye impuestos ni descuentos**. ~~Pendiente: confirmar cómo interpreta Factus su campo `price`~~ **Resuelto (2026-10-20):** según la [documentación oficial de Factus](https://developers.factus.com.co/facturas/descripcion-de-campos/), `items.price` es el precio unitario neto, sin impuestos ni descuentos, con máximo 2 decimales. Coincide con `unitPrice`: el adaptador no necesita conversión.
- Cantidades y porcentajes con máximo 2 decimales (la precisión de la cantidad está centralizada en `Quantity.MAX_DECIMALS`).

**Otras reglas del modelo**

- El dominio no consulta el reloj del sistema: las fechas entran como parámetro.
- Las excepciones de `domain.exception` reciben solo valores simples, nunca objetos de `domain.model`, para evitar ciclos entre paquetes.

## Alternativas consideradas

- **Usar directamente los IDs y estructuras de Factus en el dominio:** menos código de mapeo, pero acopla las reglas de negocio a un proveedor y a sus catálogos; cambiar de proveedor obligaría a reescribir el dominio.
- **Confiar solo en los totales de Factus:** no podríamos mostrar totales antes de emitir ni detectar errores de cálculo propios o del proveedor.
- **Redondear solo sobre el total:** reduce errores acumulados, pero las líneas dejan de cuadrar con el total que ve el cliente.

## Consecuencias

**Positivas**
- El dominio se entiende y se prueba sin Factus; cambiar de proveedor solo afecta al adaptador.
- Cálculos deterministas y documentados, cubiertos por tests unitarios que corren en milisegundos.
- Las discrepancias con el proveedor quedan visibles en vez de ocultarse.
- Agregar retenciones no exige cambiar `total()` ni las líneas.

**Negativas**
- El adaptador de Factus deberá mantener tablas de traducción (documento, unidad, medio de pago, municipio…).
- Puede haber diferencias de centavos con el total oficial si Factus calcula distinto; habrá que investigarlas cuando aparezcan.
- Mientras no existan retenciones, el sistema no sirve para clientes que las requieran.

## Mapeo con Factus

Fuente: [Factus — descripción de campos de facturas](https://developers.factus.com.co/facturas/descripcion-de-campos/) (consultada el 2026-10-20). Lo aplicará el adaptador de salida; el dominio no cambia.

| Dominio | Factus | Notas |
|---|---|---|
| `InvoiceLine.unitPrice` | `items.price` | Precio unitario neto, sin impuestos ni descuentos, máx. 2 decimales. Sin conversión. |
| `InvoiceLine.quantity` | `items.quantity` | Máx. 2 decimales en ambos (`Quantity.MAX_DECIMALS`). |
| `InvoiceLine.taxRate` + `excluded` | `items.taxes[]` (`rate`, `is_excluded`) | Factus usa una **lista** de impuestos por ítem (IVA, INC…). Hoy el dominio tiene un solo impuesto por línea, implícitamente IVA: el adaptador enviará una lista de un elemento. Ver "Evoluciones futuras". |
| `InvoiceLine.discountRate` | `items.discount_rate` | Factus acepta `discount_rate` **o** `discount_amount`, no ambos. Usaremos solo el porcentaje. |
| `DocumentType`, `UnitOfMeasure`, `PaymentMethod` | IDs de catálogos de Factus | Tablas de traducción en el adaptador (decisión A). |

## Evoluciones futuras

- **Varios impuestos por línea (IVA, INC, etc.).** Factus ya los soporta como lista. El dominio modela uno solo y no registra de qué tributo se trata. Cuando se necesite, cada línea pasará a tener una lista de impuestos tipados; `Invoice.totalTax()` no cambia, porque suma `InvoiceLine.taxAmount()`.
- **Retenciones** (decisión C): colección nueva en la factura y `payableAmount() = total() − retenciones`, sin modificar `total()`.

