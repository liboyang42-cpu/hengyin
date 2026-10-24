#!/usr/bin/env python3
"""Fail closed when current Mapper XML, compiled resources and packaged jar do not have identical bytes."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def audit_resources(root, artifact, all_main_resources=False):
    root, artifact = Path(root).resolve(), Path(artifact).resolve()
    rows, problems = [], []
    with zipfile.ZipFile(artifact) as outer:
        names = outer.namelist()
        if all_main_resources:
            for name in names:
                if name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    with zipfile.ZipFile(io.BytesIO(outer.read(name))) as nested_archive:
                        nested_names = nested_archive.namelist()
                        if len(nested_names) != len(set(nested_names)) or nested_archive.testzip() is not None:
                            problems.append({"archive": name, "problem": "duplicate-or-corrupt-zip-entry"})
            if len(names) != len(set(names)) or outer.testzip() is not None:
                problems.append({"archive": "application", "problem": "duplicate-or-corrupt-zip-entry"})
            for name in names:
                if name.rsplit("/", 1)[-1].lower().startswith(".env") or name.lower().endswith((".env", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")):
                    problems.append({"archive": "application", "path": name, "problem": "credential-file-in-application-resources"})
        for module in sorted(root.glob("chengyinhub-*")):
            resources = module / "src/main/resources"
            if all_main_resources and any(p.is_symlink() for p in resources.rglob("*")):
                raise ValueError("Symlink main resource refused")
            sources = sorted(p for p in resources.rglob("*") if p.is_file()) if all_main_resources else sorted(resources.glob("mapper/**/*.xml"))
            if not sources and not (all_main_resources and (module / "pom.xml").is_file()):
                continue
            nested = [n for n in names if n.startswith("BOOT-INF/lib/" + module.name + "-") and n.endswith(".jar")]
            application = all_main_resources and module.name == "chengyinhub-admin"
            if not application and len(nested) != 1:
                problems.append({"module": module.name, "problem": "missing-or-ambiguous-module-jar"})
                continue
            packed_bytes = artifact.read_bytes() if application else outer.read(nested[0])
            with zipfile.ZipFile(io.BytesIO(packed_bytes)) as packed:
                prefix = "BOOT-INF/classes/" if application else ""
                packed_names = [n[len(prefix):] for n in packed.namelist() if n.startswith(prefix) and not n.endswith("/")]
                # Boot's repackager moves this exact reviewed resource to archive root.
                relocated = "META-INF/spring-devtools.properties"
                if application and relocated in packed.namelist():
                    packed_names.append(relocated)
                expected = {p.relative_to(resources).as_posix() for p in sources}
                extra = {n for n in packed_names if n.startswith("mapper/") and n.endswith(".xml")} - expected
                for name in sorted(extra):
                    problems.append({"module": module.name, "path": name, "problem": "unexpected-packaged-mapper"})
                if all_main_resources:
                    for name in packed_names:
                        basename = name.rsplit("/", 1)[-1].lower()
                        if basename.startswith(".env") or name.lower().endswith((".env", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")) or "/.git/" in "/" + name:
                            problems.append({"module": module.name, "path": name, "problem": "credential-file-in-application-resources"})
                        generated = name == "git.properties" if application else name in (
                            "META-INF/MANIFEST.MF", "META-INF/maven/com.chengyinhub/" + module.name + "/pom.xml",
                            "META-INF/maven/com.chengyinhub/" + module.name + "/pom.properties")
                        if not name.endswith(("/", ".class")) and name not in expected and not generated:
                            problems.append({"module": module.name, "path": name, "problem": "unexpected-packaged-resource"})
                compiled_root = module / "target/classes"
                extra_compiled = {p.relative_to(compiled_root).as_posix()
                                  for p in compiled_root.glob("mapper/**/*.xml")} - expected
                for name in sorted(extra_compiled):
                    problems.append({"module": module.name, "path": name, "problem": "unexpected-compiled-mapper"})
                for source in sources:
                    relative = source.relative_to(resources).as_posix()
                    compiled = compiled_root / relative
                    packaged_path = relative if application and relative == relocated else prefix + relative
                    row = {"source": source.relative_to(root).as_posix(), "module": module.name,
                           "resource": relative, "sourceSha256": digest(source.read_bytes()),
                           "compiledSha256": digest(compiled.read_bytes()) if compiled.is_file() else None,
                           "packagedSha256": digest(packed.read(packaged_path)) if packed_names.count(relative) == 1 and packaged_path in packed.namelist() else None}
                    rows.append(row)
                    if row["compiledSha256"] != row["sourceSha256"]:
                        problems.append({"source": row["source"], "problem": "compiled-resource-missing-or-stale"})
                    if row["packagedSha256"] != row["sourceSha256"]:
                        problems.append({"source": row["source"], "problem": "packaged-resource-missing-duplicate-or-stale"})
    if not rows:
        problems.append({"problem": "no-mapper-resources-verified"})
    hasher = hashlib.sha256()
    with artifact.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return {"ready": not problems, "artifactSha256": hasher.hexdigest(), "resourceCount": len(rows),
            "resources": rows, "problems": problems}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--artifact", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--expected-commit")
    mode.add_argument("--development-snapshot", action="store_true")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--all-main-resources", action="store_true",
                        help="Also verify application configuration/all own main resources and ZIP safety")
    args = parser.parse_args()
    try:
        if args.expected_commit:
            if not re.fullmatch(r"[0-9a-f]{40}", args.expected_commit):
                raise ValueError("full exact commit required")
            head = subprocess.check_output(["git", "-C", str(args.root), "rev-parse", "HEAD"], text=True).strip()
            dirty = subprocess.check_output(["git", "-C", str(args.root), "status", "--porcelain"], text=True)
            if head != args.expected_commit or dirty:
                raise ValueError("formal resource audit requires the exact clean candidate checkout")
        report = audit_resources(args.root, args.artifact, args.all_main_resources)
        report["evidenceLayer"] = "EXACT_CLEAN_CANDIDATE" if args.expected_commit else "DEVELOPMENT_SNAPSHOT_NOT_RELEASE"
        report["candidateCommit"] = args.expected_commit
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError):
        parser.exit(1, "Candidate resource verification rejected: check exact checkout and complete artifact\n")
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        print(rendered, end="")
    return 0 if report["ready"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
