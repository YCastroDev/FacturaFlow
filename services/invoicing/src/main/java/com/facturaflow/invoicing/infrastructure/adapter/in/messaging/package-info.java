/**
 * Adaptador de entrada de eventos (ADR 0014): consume {@code SaleConfirmed} de sales desde RabbitMQ, crea la
 * factura y la emite con los casos de uso existentes ({@code CreateInvoiceUseCase}, {@code IssueInvoiceUseCase}),
 * sin modificarlos. Es un Anti-Corruption Layer, todo package-private: el DTO del evento, el cliente de RabbitMQ y
 * la topología de colas no salen de este paquete (reglas 10 y 11 de ArchUnit).
 */
package com.facturaflow.invoicing.infrastructure.adapter.in.messaging;
