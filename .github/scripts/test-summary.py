"""Resumen de los tests de un servicio para el "job summary" de GitHub Actions (ADR 0016).

Lee los TEST-*.xml de Surefire y escribe en la salida estándar una tabla en Markdown con los totales y, si hubo
fallas, la lista de los tests que fallaron. Solo la biblioteca estándar: nada que instalar en el runner.

Uso: python3 test-summary.py <servicio> <carpeta de surefire-reports>
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_FAILURES_LISTED = 30


def main() -> None:
    service, reports = sys.argv[1], Path(sys.argv[2])
    files = sorted(reports.glob("TEST-*.xml")) if reports.is_dir() else []
    print(f"### Tests de {service}\n")
    if not files:
        print("No hay reportes de tests (la compilación falló antes de correrlos).\n")
        return

    tests = failures = errors = skipped = 0
    time = 0.0
    failed = []
    for file in files:
        suite = ET.parse(file).getroot()
        tests += int(suite.get("tests", 0))
        failures += int(suite.get("failures", 0))
        errors += int(suite.get("errors", 0))
        skipped += int(suite.get("skipped", 0))
        time += float(suite.get("time", 0) or 0)
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                failed.append(f"{case.get('classname', '?')}.{case.get('name', '?')}")

    status = "✅" if failures + errors == 0 else "❌"
    print("| Estado | Tests | Fallidos | Errores | Omitidos | Tiempo |")
    print("|---|---|---|---|---|---|")
    print(f"| {status} | {tests} | {failures} | {errors} | {skipped} | {time:.0f} s |\n")
    if failed:
        print("**Fallaron:**\n")
        for name in failed[:MAX_FAILURES_LISTED]:
            print(f"- `{name}`")
        if len(failed) > MAX_FAILURES_LISTED:
            print(f"- … y {len(failed) - MAX_FAILURES_LISTED} más (ver el artefacto surefire-reports)")
        print()


if __name__ == "__main__":
    main()
