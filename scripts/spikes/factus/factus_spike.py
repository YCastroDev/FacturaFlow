"""Spike contra el sandbox de Factus (ver docs/spikes/0001-factus-sandbox.md).

Solo biblioteca estándar. Reglas de seguridad incorporadas:
  * Credenciales solo desde variables de entorno FACTUS_*; nunca se imprimen.
  * Todo texto que se muestra o se guarda pasa por sanitize(): los valores secretos y los
    tokens obtenidos se reemplazan por una máscara.
  * Solo GET y POST: este script NO puede borrar nada en Factus.
  * reference_code debe empezar por SPIKE-, send_email se fuerza a false y hay un tope de
    facturas creadas (MAX_BILLS), contado en /out/bills.log.
  * Pausa mínima entre peticiones y parada inmediata ante un 429 (límite: 80/min).
"""

import base64
import datetime
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

OUT = "/out"
MAX_BILLS = 10
MIN_INTERVAL_SECONDS = 1.0
TIMEOUT_SECONDS = 60
SECRET_ENV = ("FACTUS_CLIENT_ID", "FACTUS_CLIENT_SECRET", "FACTUS_USERNAME", "FACTUS_PASSWORD")
TOKEN_KEYS = ("access_token", "refresh_token")
# Cloudflare (delante de Factus) bloquea con 403/1010 el User-Agent por defecto de las librerías
# HTTP ("Python-urllib/x.y"). El cliente se identifica con un User-Agent propio y honesto.
USER_AGENT = "FacturaFlow-Spike/1.0"

_secrets = set()
_last_request = 0.0


# ---------------------------------------------------------------- utilidades

def env(name):
    value = os.environ.get(name, "")
    if not value or value == "CAMBIAME":
        sys.exit(f"Falta la variable {name} en el .env (no se muestra su valor).")
    return value


def mask(value):
    value = str(value)
    return f"{value[:6]}…[{len(value)} caracteres]" if len(value) > 6 else "***"


def remember_secret(value):
    if value and len(str(value)) >= 4:
        _secrets.add(str(value))


def sanitize(obj):
    """Copia del objeto sin secretos: claves de token enmascaradas y valores secretos reemplazados."""
    if isinstance(obj, dict):
        return {k: (mask(v) if k in TOKEN_KEYS and v else sanitize(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [sanitize(v) for v in obj]
    if isinstance(obj, str):
        text = obj
        for secret in sorted(_secrets, key=len, reverse=True):
            text = text.replace(secret, "***")
        return text
    return obj


def truncate_large(obj, limit=300):
    """Recorta cadenas enormes (p. ej. el PDF en Base64) para que el JSON guardado sea legible."""
    if isinstance(obj, dict):
        return {k: truncate_large(v, limit) for k, v in obj.items()}
    if isinstance(obj, list):
        return [truncate_large(v, limit) for v in obj]
    if isinstance(obj, str) and len(obj) > limit:
        return obj[:80] + f"…[recortado: {len(obj)} caracteres]"
    return obj


def save(name, data):
    path = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(truncate_large(sanitize(data)), f, ensure_ascii=False, indent=2)
    return path


def show(title, data):
    print(f"\n=== {title}")
    print(json.dumps(truncate_large(sanitize(data)), ensure_ascii=False, indent=2))


# ---------------------------------------------------------------- HTTP

def base_url():
    return env("FACTUS_BASE_URL").rstrip("/")


def request(method, path, token=None, json_body=None, form=None, record=None):
    """Hace una petición y devuelve (status, headers, body, elapsed_ms). Nunca lanza por códigos HTTP."""
    global _last_request
    if method not in ("GET", "POST"):
        raise ValueError("El spike solo usa GET y POST")
    wait = MIN_INTERVAL_SECONDS - (time.monotonic() - _last_request)
    if wait > 0:
        time.sleep(wait)

    headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if json_body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(json_body).encode()
    elif form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = urllib.parse.urlencode(form).encode()

    url = path if path.startswith("http") else base_url() + path
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_SECONDS) as resp:
            status, resp_headers, raw = resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        status, resp_headers, raw = e.code, dict(e.headers), e.read()
    _last_request = time.monotonic()
    elapsed = round((_last_request - started) * 1000)

    text = raw.decode("utf-8", errors="replace")
    try:
        body = json.loads(text) if text else None
    except json.JSONDecodeError:
        body = {"_no_json": text[:2000]}

    interesting = {k: v for k, v in resp_headers.items()
                   if k.lower() in ("content-type", "retry-after", "x-ratelimit-limit",
                                    "x-ratelimit-remaining", "date")}
    if record:
        save(record, {
            "request": {"method": method, "path": path, "json": json_body,
                        "form_keys": sorted(form) if form else None},
            "response": {"status": status, "elapsed_ms": elapsed, "headers": interesting, "body": body},
        })
    if status == 429:
        show("429 Too Many Requests: el spike se detiene", {"headers": interesting, "body": body})
        sys.exit(2)
    return status, interesting, body, elapsed


def credentials():
    values = {name: env(name) for name in SECRET_ENV}
    for value in values.values():
        remember_secret(value)
    return values


def password_grant(record):
    c = credentials()
    status, headers, body, _ = request("POST", "/oauth/token", form={
        "grant_type": "password",
        "client_id": c["FACTUS_CLIENT_ID"],
        "client_secret": c["FACTUS_CLIENT_SECRET"],
        "username": c["FACTUS_USERNAME"],
        "password": c["FACTUS_PASSWORD"],
    }, record=record)
    if status != 200 or not isinstance(body, dict) or "access_token" not in body:
        show(f"Autenticación fallida (HTTP {status})", body)
        sys.exit(1)
    remember_secret(body.get("access_token"))
    remember_secret(body.get("refresh_token"))
    return body


def refresh_grant(refresh_token, record):
    c = credentials()
    status, headers, body, _ = request("POST", "/oauth/token", form={
        "grant_type": "refresh_token",
        "client_id": c["FACTUS_CLIENT_ID"],
        "client_secret": c["FACTUS_CLIENT_SECRET"],
        "refresh_token": refresh_token,
    }, record=record)
    if isinstance(body, dict):
        remember_secret(body.get("access_token"))
        remember_secret(body.get("refresh_token"))
    return status, body


def token():
    return password_grant("auth/last-password-grant.json")["access_token"]


# ---------------------------------------------------------------- pasos

def step_auth(_args):
    """Duración del token, refresh y si el token viejo sigue sirviendo."""
    first = password_grant("auth/01-password-grant.json")
    shape = {k: (type(v).__name__ if k in TOKEN_KEYS else v) for k, v in first.items()}
    show("Respuesta de /oauth/token (grant password), sin tokens", shape)
    print(f"access_token: {mask(first['access_token'])}  refresh_token: {mask(first.get('refresh_token', ''))}")

    probe = "/v1/numbering-ranges"
    status_before, *_ = request("GET", probe, token=first["access_token"], record="auth/02-probe-first-token.json")

    status, refreshed = refresh_grant(first.get("refresh_token"), "auth/03-refresh-grant.json")
    findings = {"refresh_status": status}
    if status == 200 and isinstance(refreshed, dict):
        findings.update({
            "refresh_expires_in": refreshed.get("expires_in"),
            "new_access_token_differs": refreshed.get("access_token") != first["access_token"],
            "refresh_token_rotated": refreshed.get("refresh_token") != first.get("refresh_token"),
            "keys": sorted(refreshed),
        })
        s_old, *_ = request("GET", probe, token=first["access_token"], record="auth/04-probe-old-token.json")
        s_new, *_ = request("GET", probe, token=refreshed["access_token"], record="auth/05-probe-new-token.json")
        s_reuse, reuse_body = refresh_grant(first.get("refresh_token"), "auth/06-reuse-old-refresh-token.json")
        findings.update({"probe_with_first_token_before_refresh": status_before,
                         "probe_with_old_token_after_refresh": s_old,
                         "probe_with_new_token": s_new,
                         "reuse_old_refresh_token_status": s_reuse})
    else:
        findings["refresh_body"] = refreshed
    s_bad, *_ = request("GET", probe, token="token-invalido", record="auth/07-invalid-token.json")
    findings["probe_with_invalid_token"] = s_bad
    show("Hallazgos de autenticación", findings)


def step_get(args):
    """GET genérico (solo lectura) para explorar: get <ruta> [nombre-archivo]."""
    if not args:
        sys.exit("Uso: get <ruta> [nombre]")
    path = args[0]
    name = args[1] if len(args) > 1 else path.strip("/").replace("/", "_").replace("?", "_")[:80]
    status, headers, body, elapsed = request("GET", path, token=token(), record=f"get/{name}.json")
    show(f"GET {path} → HTTP {status} ({elapsed} ms)", {"headers": headers, "body": body})


def load_payload(file, reference_code):
    with open(os.path.join("/spike", file), encoding="utf-8") as f:
        text = f.read().replace("{{REFERENCE_CODE}}", reference_code)
    payload = json.loads(text)
    payload["send_email"] = False   # regla del spike: nunca enviar correos
    return payload


def count_bills():
    path = os.path.join(OUT, "bills.log")
    if not os.path.exists(path):
        return 0
    with open(path, encoding="utf-8") as f:
        return sum(1 for line in f if line.strip())


def step_bill(args):
    """bill <payload.json> [reference_code] [ruta]: envía una factura al endpoint de validación."""
    if not args:
        sys.exit("Uso: bill <payload.json> [reference_code] [ruta]")
    file = args[0]
    reference_code = args[1] if len(args) > 1 else "SPIKE-" + datetime.datetime.now().strftime("%m%d%H%M%S")
    path = args[2] if len(args) > 2 else "/v2/bills/validate"
    if not reference_code.startswith("SPIKE-"):
        sys.exit("Regla del spike: el reference_code debe empezar por SPIKE-")
    if count_bills() >= MAX_BILLS:
        sys.exit(f"Tope del spike alcanzado ({MAX_BILLS} envíos). Revisa /out/bills.log.")
    payload = load_payload(file, reference_code)

    access_token = token()
    with open(os.path.join(OUT, "bills.log"), "a", encoding="utf-8") as f:
        f.write(f"{datetime.datetime.now().isoformat()} {reference_code} {file} {path}\n")
    sent = count_bills()   # ya incluye este envío: cada archivo es único aunque se repita el código
    name = f"bills/{sent:02d}-{reference_code}-{os.path.basename(file).removesuffix('.json')}"
    status, headers, body, elapsed = request("POST", path, token=access_token, json_body=payload,
                                             record=f"{name}.json")
    show(f"POST {path} [{reference_code}] → HTTP {status} ({elapsed} ms)", {"headers": headers, "body": body})


def step_probe(args):
    """probe <ruta> [esqueleto.json]: POST de un cuerpo incompleto (sin reference_code) para leer los errores de validación."""
    path = args[0] if args else "/v2/bills/validate"
    body_in = {}
    name = "empty"
    if len(args) > 1:
        with open(os.path.join("/spike", args[1]), encoding="utf-8") as f:
            body_in = json.load(f)
        name = os.path.basename(args[1]).removesuffix(".json")
    # Sin reference_code la validación falla siempre: un probe nunca puede crear una factura.
    body_in.pop("reference_code", None)
    status, headers, body, elapsed = request("POST", path, token=token(), json_body=body_in,
                                             record=f"probe/{path.strip('/').replace('/', '_')}-{name}.json")
    show(f"POST {path} ({name}) → HTTP {status} ({elapsed} ms)", {"headers": headers, "body": body})


def step_pdf(args):
    """pdf <número>: descarga el PDF (Base64) y lo guarda decodificado en /out/pdf/."""
    if not args:
        sys.exit("Uso: pdf <número de factura>")
    number = args[0]
    path = args[1] if len(args) > 1 else f"/v2/bills/{number}/download-pdf"
    status, headers, body, elapsed = request("GET", path, token=token(), record=f"pdf/{number}.json")
    summary = {"status": status, "elapsed_ms": elapsed, "headers": headers}
    data = body.get("data") if isinstance(body, dict) else None
    encoded = data.get("pdf_base_64_encoded") if isinstance(data, dict) else None
    if encoded:
        pdf = base64.b64decode(encoded)
        os.makedirs(os.path.join(OUT, "pdf"), exist_ok=True)
        with open(os.path.join(OUT, "pdf", f"{number}.pdf"), "wb") as f:
            f.write(pdf)
        summary.update({"data_keys": sorted(data), "pdf_bytes": len(pdf), "starts_with": pdf[:8].decode("latin-1"),
                        "file_name": data.get("file_name")})
    else:
        summary["body"] = body
    show(f"PDF de {number}", summary)


def step_unauthorized(args):
    """Cuerpo de un 401: llamada a la validación con un token inválido (no crea nada)."""
    path = args[0] if args else "/v2/bills/validate"
    status, headers, body, _ = request("POST", path, token="token-invalido", json_body={},
                                       record="errors/401-invalid-token.json")
    show(f"401 en {path} → HTTP {status}", {"headers": headers, "body": body})


# Códigos candidatos (tablas oficiales de la DIAN, Anexo técnico 1.9) que se verifican uno a uno.
CODE_CANDIDATES = {
    "customer.identification_document_code": ["11", "12", "13", "21", "22", "31", "41", "42", "47", "48", "50", "91"],
    "customer.legal_organization_code": ["1", "2"],
    "customer.tribute_code": ["01", "04", "ZA", "ZZ", "XX-INVALIDO"],
    "customer.responsibilities": ["O-13", "O-15", "O-23", "O-47", "R-99-PN", "XX-INVALIDO"],
    "items.0.unit_measure_code": ["94", "KGM", "GRM", "LBR", "LTR", "MLT", "GLL", "MTR", "CMT", "MTK", "HUR",
                                  "DAY", "DZN", "BX", "PK", "E48", "XX-INVALIDO"],
    "items.0.standard_code": ["999", "001", "010", "020"],
    "items.0.taxes.0.code": ["01", "04", "03", "ZZ"],
    "payment_details.0.payment_form": ["1", "2"],
    "payment_details.0.payment_method_code": ["10", "20", "42", "47", "48", "49", "ZZZ"],
}


def set_path(obj, dotted, value):
    keys = dotted.split(".")
    for key in keys[:-1]:
        obj = obj[int(key)] if key.isdigit() else obj[key]
    last = keys[-1]
    obj[last] = [value] if last == "responsibilities" else value


def step_codes(args):
    """codes [esqueleto.json]: verifica qué códigos de catálogo acepta Factus (sin reference_code: no crea facturas)."""
    candidates = CODE_CANDIDATES
    if len(args) >= 2:   # codes <campo> <c1,c2,...>: verifica solo esa lista
        candidates = {args[0]: args[1].split(",")}
        args = args[2:]
    skeleton_file = args[0] if args else "probes/plausible.json"
    with open(os.path.join("/spike", skeleton_file), encoding="utf-8") as f:
        skeleton = json.load(f)
    skeleton.pop("reference_code", None)
    skeleton["customer"]["email"] = "facturacion@cliente.example"
    access_token = token()
    results = {}
    for field, codes in candidates.items():
        results[field] = {}
        for code in codes:
            body_in = json.loads(json.dumps(skeleton))
            set_path(body_in, field, code)
            if field == "payment_details.0.payment_form" and code == "2":
                body_in["payment_details"][0]["due_date"] = "2030-01-31"
            status, _, body, _ = request("POST", "/v2/bills/validate", token=access_token, json_body=body_in)
            errors = (body or {}).get("data", {}).get("errors", {}) if isinstance(body, dict) else {}
            related = {k: v for k, v in errors.items() if k.startswith(field.rsplit(".", 1)[0]) and k != "reference_code"}
            field_errors = errors.get(field) or [m for k, v in related.items() for m in v if field.split(".")[-1] in k]
            results[field][code] = "acepta" if not field_errors else "; ".join(field_errors)
            if set(errors) - {"reference_code", field}:
                results[field][code] += f"  (otros errores: {sorted(set(errors) - {'reference_code', field})})"
    save(f"codes/results-{'-'.join(f.split('.')[-1] for f in candidates)}.json", results)
    show("Códigos aceptados por la validación de Factus", results)


STEPS = {
    "auth": step_auth,
    "get": step_get,
    "probe": step_probe,
    "bill": step_bill,
    "pdf": step_pdf,
    "unauthorized": step_unauthorized,
    "codes": step_codes,
}


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in STEPS:
        print(__doc__)
        for name, fn in STEPS.items():
            print(f"  {name:13} {fn.__doc__.strip()}")
        sys.exit(0 if len(sys.argv) > 1 and sys.argv[1] == "help" else 1)
    STEPS[sys.argv[1]](sys.argv[2:])


if __name__ == "__main__":
    main()
