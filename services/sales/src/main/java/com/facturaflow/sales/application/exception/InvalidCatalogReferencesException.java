package com.facturaflow.sales.application.exception;

import java.util.List;
import java.util.Optional;

/**
 * 422: la venta pide un cliente o productos que catalog no tiene o tiene inactivos. Junta TODOS los
 * problemas en una sola respuesta (no el primero), para que el vendedor los corrija de una vez.
 */
public class InvalidCatalogReferencesException extends RuntimeException {

    private final String customerProblem;
    private final List<String> missingProducts;
    private final List<String> inactiveProducts;

    public InvalidCatalogReferencesException(String customerProblem, List<String> missingProducts,
                                             List<String> inactiveProducts) {
        super(message(customerProblem, missingProducts, inactiveProducts));
        this.customerProblem = customerProblem;
        this.missingProducts = List.copyOf(missingProducts);
        this.inactiveProducts = List.copyOf(inactiveProducts);
    }

    public Optional<String> customerProblem() {
        return Optional.ofNullable(customerProblem);
    }

    /** Códigos tal como venían en la petición. */
    public List<String> missingProducts() {
        return missingProducts;
    }

    /** Códigos tal como están en catalog. */
    public List<String> inactiveProducts() {
        return inactiveProducts;
    }

    private static String message(String customerProblem, List<String> missing, List<String> inactive) {
        StringBuilder text = new StringBuilder("La venta no se puede confirmar con los datos de catalog.");
        if (customerProblem != null) {
            text.append(' ').append(customerProblem);
        }
        if (!missing.isEmpty()) {
            text.append(" Productos inexistentes: ").append(String.join(", ", missing)).append('.');
        }
        if (!inactive.isEmpty()) {
            text.append(" Productos inactivos: ").append(String.join(", ", inactive)).append('.');
        }
        return text.toString();
    }
}
