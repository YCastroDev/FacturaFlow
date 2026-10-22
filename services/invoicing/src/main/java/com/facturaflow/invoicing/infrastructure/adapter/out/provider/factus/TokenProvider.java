package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

/** Entrega el access token de Factus vigente; quien lo usa no sabe cómo se obtiene ni se renueva. */
interface TokenProvider {

    /**
     * Token vigente. Si no hay uno, o está por vencer, lo obtiene antes de devolverlo.
     *
     * @throws FactusTechnicalException si no se pudo autenticar
     */
    String accessToken();

    /**
     * Factus rechazó {@code rejectedToken} (401). Si sigue siendo el actual, se descarta para que el
     * próximo {@link #accessToken()} lo renueve; si otro hilo ya lo cambió, no hace nada.
     */
    void invalidate(String rejectedToken);
}
