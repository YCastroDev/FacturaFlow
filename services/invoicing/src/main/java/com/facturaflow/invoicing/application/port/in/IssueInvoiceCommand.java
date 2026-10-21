package com.facturaflow.invoicing.application.port.in;

import java.util.UUID;

public record IssueInvoiceCommand(UUID invoiceId) {
}
