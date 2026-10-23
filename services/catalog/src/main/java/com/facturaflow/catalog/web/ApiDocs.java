package com.facturaflow.catalog.web;

/** Textos de la documentación OpenAPI que comparten los dos controladores. */
final class ApiDocs {

    static final String IF_MATCH_PUT = """
            ETag de la versión que usted consultó, **con comillas**: por ejemplo `"0"`.

            **De dónde sale:** del header `ETag` de la respuesta de `GET /{id}`, de la creación (`POST`) o del \
            último `PUT`. En Swagger UI aparece en *Responses → Response headers* como `etag: "0"`. Es el mismo \
            número que el campo `version` del cuerpo (también se acepta sin comillas: `0`).

            Si otra persona actualizó el registro después de su consulta, la versión ya no coincide: responde \
            **412** y nada se modifica. Sin este header responde **428**.""";

    /** Con comillas, como llega en el header ETag (declarado como string para que springdoc no lo lea como el número 0). */
    static final String IF_MATCH_EXAMPLE = "\"0\"";

    static final String IF_MATCH_OPTIONAL = """
            Opcional. Si lo envía (el ETag con comillas, p. ej. `"0"`), la operación solo se aplica si el \
            registro sigue en esa versión (si no, 412). Sin él, se aplica sobre la versión actual.""";

    static final String ETAG_HEADER = "Versión actual del registro, entre comillas (p. ej. \"0\"). Envíela en "
            + "If-Match para actualizarlo.";

    static final String PAGE = "Número de página, desde 0 (máximo 10000)";
    static final String SIZE = "Registros por página, de 1 a 100 (fuera de rango: 400)";
    static final String ACTIVE = "true = solo activos; false = solo inactivos; sin el parámetro = todos";

    private ApiDocs() {
    }
}
