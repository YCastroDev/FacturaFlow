/**
 * Modelo del catálogo: entidades JPA ({@link com.facturaflow.catalog.model.Product},
 * {@link com.facturaflow.catalog.model.Customer}) y los value objects que validan sus datos.
 *
 * <p>Arquitectura en capas (ADR 0011): aquí sí hay anotaciones de JPA, pero no de Spring ni de Jackson.
 * Las reglas de los datos maestros (formato del documento, DV del NIT, tipo de persona, situación
 * tributaria, impuestos...) son una COPIA deliberada de las de invoicing (ADR 0001): los servicios no
 * comparten código. Si una regla cambia en un servicio, hay que revisar su copia en el otro.
 */
package com.facturaflow.catalog.model;
