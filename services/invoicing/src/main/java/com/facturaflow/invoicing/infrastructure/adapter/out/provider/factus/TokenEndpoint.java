package com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus;

import com.facturaflow.invoicing.infrastructure.adapter.out.provider.factus.FactusDtos.TokenResponse;

/** Las dos concesiones de {@code POST /oauth/token}. Separado para probar el {@link TokenProvider} sin HTTP. */
interface TokenEndpoint {

    /** {@code grant_type=password} con las credenciales configuradas. */
    TokenResponse passwordGrant();

    /** {@code grant_type=refresh_token}. Factus revoca al instante el access y el refresh token anteriores. */
    TokenResponse refreshGrant(String refreshToken);
}
