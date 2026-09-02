"""Read-only source inventory for the full ARISUINFO common-area migration.

This records source locations, not operational data or connection settings.
Matches are review candidates: a regex match never establishes functional parity.
"""
import argparse
import csv
import hashlib
import json
import re
from collections import Counter
from pathlib import Path


def read_source(path):
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "cp949"):
        try:
            return data.decode(encoding), encoding
        except UnicodeDecodeError:
            pass
    raise ValueError(f"Cannot decode source: {path}")


def uncomment(text, xml=False):
    pattern = r"<!--[\s\S]*?-->" if xml else r'/\*[\s\S]*?\*/|//[^\n]*'
    # Preserve source offsets and line numbers; do not remove // inside strings.
    if not xml:
        pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(pattern, lambda m: m[0] if not xml and m[0][0] in "\"'"
                  else re.sub(r"[^\n]", " ", m[0]), text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("C:/arisuinfo"))
    parser.add_argument("--output", type=Path, default=Path("migration/inventory"))
    args = parser.parse_args()
    root = args.source.resolve()
    scopes = {
        "java-cm": "src/main/java/arisuinfo/cm",
        "java-com": "src/main/java/arisuinfo/com",
        "java-common": "src/main/java/arisuinfo/common",
        "mapper-cm": "src/main/resources/mappers/arisuinfo/cm",
        "mapper-com": "src/main/resources/mappers/arisuinfo/com",
        "screen-cm": "src/main/nexacro/form/cm",
        "screen-common": "src/main/nexacro/common",
        "screen-frame": "src/main/nexacro/frame",
        "script-common": "src/main/nexacro/_extlib_/arisuInfo",
    }
    files, endpoints, statements, dependencies, screens = [], [], [], [], []
    known = {}
    for scope, folder in scopes.items():
        for path in sorted((root / folder).rglob("*")):
            if not path.is_file() or path.suffix.lower() not in {".java", ".xml", ".xfdl", ".js", ".xjs"}:
                continue
            text, encoding = read_source(path)
            relative = path.relative_to(root).as_posix()
            clean = uncomment(text, path.suffix.lower() in {".xml", ".xfdl"})
            entry = {"scope": scope, "source": relative, "encoding": encoding,
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                     "status": "NOT_VERIFIED"}
            files.append(entry)
            if path.suffix == ".java":
                package = re.search(r"\bpackage\s+([\w.]+)\s*;", clean)
                if package:
                    known[package[1] + "." + path.stem] = relative
                for match in re.finditer(r'@(?:Request|Get|Post|Put|Delete|Patch)Mapping\s*\(([\s\S]*?)\)', clean):
                    for route in re.findall(r'"([^"\n]+)"', match[1]):
                        if route.startswith("/"):
                            endpoints.append({"source": relative, "line": text.count("\n", 0, match.start()) + 1,
                                              "route": route, "status": "NOT_VERIFIED"})
                for match in re.finditer(r"\bimport\s+(?:static\s+)?(arisuinfo\.[\w.*]+)\s*;", clean):
                    dependencies.append({"source": relative, "import": match[1]})
            elif path.suffix == ".xml":
                namespace = re.search(r'<mapper\s+namespace="([^"]+)"', clean)
                if namespace:
                    for match in re.finditer(r'<(select|insert|update|delete|sql)\b[^>]*\bid="([^"]+)"', clean):
                        statements.append({"source": relative, "line": text.count("\n", 0, match.start()) + 1,
                                           "namespace": namespace[1], "kind": match[1], "id": match[2],
                                           "status": "NOT_VERIFIED"})
            elif path.suffix == ".xfdl":
                form = re.search(r'<Form\b([^>]*)>', clean)
                title = re.search(r'titletext="([^"]*)"', form[1]) if form else None
                routes = sorted(set(re.findall(r'(?:svc::)?/?(?:form/)?cm/[\w/]+\.do', clean)))
                screens.append({"source": relative, "title": title[1] if title else "",
                                "route_references": " | ".join(routes), "status": "NOT_VERIFIED"})

    for entry in dependencies:
        entry["target"] = known.get(entry["import"], "")
        entry["scope"] = "IN_SCOPE" if entry["target"] else "EXTERNAL_REVIEW"
    args.output.mkdir(parents=True, exist_ok=True)
    for name, rows in {"files": files, "endpoints": endpoints, "statements": statements,
                       "dependencies": dependencies, "screens": screens}.items():
        with (args.output / f"{name}.csv").open("w", encoding="utf-8-sig", newline="") as stream:
            if rows:
                writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
    summary = {"source_root": root.as_posix(), "scopes": scopes,
               "missing_scope_directories": [folder for folder in scopes.values() if not (root / folder).is_dir()],
               "files_by_scope": dict(Counter(item["scope"] for item in files)),
               "files": len(files), "endpoint_candidates": len(endpoints), "sql_statements": len(statements),
               "screens": len(screens),
               "external_dependency_candidates": len({item["import"] for item in dependencies
                                                       if item["scope"] == "EXTERNAL_REVIEW"}),
               "notes": ["Source discovery only; all parity statuses require manual verification.",
                         "Mapping annotations may be class prefixes; compose with method mappings during review.",
                         "SQL includes reusable fragments; counts are not business feature counts.",
                         "Missing or external scopes are not automatically excluded from migration."]}
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
