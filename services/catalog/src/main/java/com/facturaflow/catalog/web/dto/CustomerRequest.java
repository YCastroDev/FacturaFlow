package com.facturaflow.catalog.web.dto;

import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_FISCAL_RESPONSIBILITIES;
import static com.facturaflow.catalog.web.dto.RequestLimits.MAX_TEXT;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Cuerpo de {@code POST /api/v1/customers} y de {@code PUT /api/v1/customers/{id}} (reemplazo completo).
 * Los mismos campos y nombres que el cliente de una factura en invoicing. Bean Validation solo revisa la
 * FORMA; las reglas las valida el modelo (422).
 */
@Schema(description = "Datos fiscales de un cliente. En PUT se envían TODOS (reemplazo completo) y el documento "
        + "(tipo, número y DV) debe ser el mismo: no se puede cambiar.")
public record CustomerRequest(
        @Schema(description = "No cambia nunca. Único junto con documentNumber.")
        @NotNull(message = "es obligatorio")
        ApiDocumentType documentType,

        @Schema(description = "Numérico de 3 a 15 dígitos; alfanumérico de 3 a 20 en documentos extranjeros "
                + "(se guarda en mayúsculas)", example = "890903938")
        @NotBlank(message = "es obligatorio") @Size(max = MAX_TEXT, message = "es demasiado largo")
        String documentNumber,

        @Schema(description = "Dígito de verificación: obligatorio para NIT (debe ser el correcto) y prohibido en "
                + "los demás tipos", example = "8")
        Integer checkDigit,

        @Schema(description = "LEGAL_ENTITY exige companyName (sin names); NATURAL_PERSON exige names "
                + "(sin companyName). No depende del tipo de documento.")
        @NotNull(message = "es obligatorio")
        ApiPersonType personType,

        @Schema(description = "Razón social: solo para LEGAL_ENTITY")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String companyName,

        @Schema(description = "Nombres: solo para NATURAL_PERSON")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String names,

        @Schema(description = "Responsabilidad tributaria (tributo del cliente ante la DIAN)")
        @NotNull(message = "es obligatorio")
        ApiTaxScheme taxScheme,

        @Schema(description = "Responsabilidades fiscales del RUT, al menos una. NOT_RESPONSIBLE va sola.")
        @NotNull(message = "es obligatorio")
        @Size(max = MAX_FISCAL_RESPONSIBILITIES, message = "admite máximo " + MAX_FISCAL_RESPONSIBILITIES
                + " responsabilidades")
        List<@NotNull(message = "no puede ser nulo") ApiFiscalResponsibility> fiscalResponsibilities,

        @Schema(description = "Opcional, código DIVIPOLA del DANE (5 dígitos)", example = "11001")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String municipalityCode,

        @Schema(description = "Opcional, máximo 250 caracteres", example = "Calle 10 # 20-30")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String address,

        @Schema(description = "Opcional: de 7 a 15 dígitos, con '+' inicial opcional; se ignoran espacios, guiones "
                + "y paréntesis", example = "+57 601 555 1234")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String phone,

        @Schema(description = "Opcional", example = "facturacion@cliente.example")
        @Size(max = MAX_TEXT, message = "es demasiado largo")
        String email) {
}
