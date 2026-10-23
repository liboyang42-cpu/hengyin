#!/usr/bin/env python3
"""Observe standard JDK21 jdeps output; never create/approve a baseline or gate debt.

Only ZIP entries are indexed. This is not a class/bytecode parser. Collection
errors remain UNKNOWN observations and do not change Backend acceptance rules.
"""
from __future__ import annotations

import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

SPEC = importlib.util.spec_from_file_location("jdeps_shared_gate", Path(__file__).with_name("gate.py"))
shared = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(shared)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ADMIN = "chengyinhub-admin"
CLASS_ROW = re.compile(r"^\s+(\S+)\s+->\s+(\S+)\s+(.+?)\s*$")
SUMMARY_ROW = re.compile(r"^(\S+)\s+->\s+(.+?)\s*$")


def write_json(path, value):
    path.write_bytes(shared.encode(value))


def class_entries(data, prefix=""):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise shared.GateError("duplicate ZIP entry")
        classes = {}
        for name in names:
            parts = Path(name).parts
            if name.startswith("/") or ".." in parts or "\\" in name:
                raise shared.GateError("unsafe ZIP path")
            if not name.startswith(prefix) or not name.endswith(".class"):
                continue
            relative = name[len(prefix):]
            if relative.startswith("META-INF/") or relative == "module-info.class":
                raise shared.GateError("project multi-release/module descriptor is not modeled")
            classname = relative[:-6].replace("/", ".")
            if classname in classes:
                raise shared.GateError("ambiguous class entry")
            classes[classname] = shared.digest(archive.read(name))
        return classes


def bind_identity(root, artifact, identity, env):
    head = shared.git(root, "rev-parse", "HEAD").decode().strip()
    if (env.get("GITHUB_ACTIONS") != "true"
            or env.get("GITHUB_REPOSITORY") != "liboyang42-cpu/hengyin"
            or not shared.SHA.fullmatch(head) or head != env.get("GITHUB_SHA")):
        raise shared.GateError("actual CI checkout identity required")
    if shared.git(root, "status", "--porcelain", "--untracked-files=all").strip():
        raise shared.GateError("dirty source checkout")
    expected = {"checkoutCommit": head, "checkoutTree": shared.git(root, "rev-parse", "HEAD^{tree}").decode().strip(),
                "runId": env.get("GITHUB_RUN_ID"), "runAttempt": env.get("GITHUB_RUN_ATTEMPT"),
                "jarSha256": shared.digest(artifact.read_bytes())}
    if any(not value or identity.get(key) != value for key, value in expected.items()):
        raise shared.GateError("tested Boot artifact/source/run identity mismatch")
    return {"source_sha": head, "source_tree": expected["checkoutTree"],
            "candidate_sha": identity.get("candidateHead"), "run_id": expected["runId"],
            "run_attempt": expected["runAttempt"], "boot_artifact_sha256": expected["jarSha256"]}


def stage_inputs(root, artifact, output, library_dir, report):
    pom = shared.xml((root / "pom.xml").read_bytes())
    modules = sorted(node.text.strip() for node in pom.findall("m:modules/m:module", NS))
    if (len(modules) != 6 or len(set(modules)) != 6 or ADMIN not in modules
            or any(not re.fullmatch(r"chengyinhub-[a-z0-9-]+", m) for m in modules)):
        raise shared.GateError("unreviewed project module inventory")
    inputs = output / "inputs"
    inputs.mkdir()
    index, paths = {}, []
    boot = artifact.read_bytes()
    with zipfile.ZipFile(io.BytesIO(boot)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise shared.GateError("duplicate Boot ZIP entry")
        libraries = [n for n in names if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        for name in libraries:
            if not re.fullmatch(r"BOOT-INF/lib/[A-Za-z0-9_.+-]+\.jar", name):
                raise shared.GateError("unsafe nested library path")
        for module in modules:
            if module == ADMIN:
                original = artifact.with_name(artifact.name + ".original")
                if original.is_symlink():
                    raise shared.GateError("symlink original artifact")
                data = original.read_bytes()
                packaged_classes = class_entries(boot, "BOOT-INF/classes/")
            else:
                matches = [n for n in libraries if n.startswith("BOOT-INF/lib/" + module + "-")]
                if len(matches) != 1:
                    raise shared.GateError("missing/ambiguous packaged project module: " + module)
                original = root / module / "target" / Path(matches[0]).name
                if original.is_symlink():
                    raise shared.GateError("symlink original artifact")
                data = original.read_bytes()
                if data != archive.read(matches[0]):
                    raise shared.GateError("original/packaged module artifact mismatch: " + module)
                packaged_classes = class_entries(data)
            classes = class_entries(data)
            compiled = root / module / "target/classes"
            if compiled.is_symlink() or any(p.is_symlink() for p in compiled.rglob("*")):
                raise shared.GateError("symlink compiled class inventory")
            compiled_classes = {p.relative_to(compiled).as_posix()[:-6].replace("/", "."): shared.digest(p.read_bytes())
                                for p in compiled.rglob("*.class") if p.is_file() and not p.is_symlink()}
            if (not classes or classes != packaged_classes or classes != compiled_classes
                    or original.is_symlink() or any(p.is_symlink() for p in compiled.rglob("*"))):
                raise shared.GateError("compiled/original/packaged class inventory mismatch: " + module)
            staged = inputs / (module + ".jar")
            staged.write_bytes(data)  # Exact original bytes, not a repackaged analysis jar.
            paths.append(staged)
            report["artifacts"][module] = {"path": original.relative_to(root).as_posix(),
                "sha256": shared.digest(data), "class_count": len(classes),
                "evidence_path": staged.relative_to(output).as_posix()}
            for classname, sha in classes.items():
                if classname in index:
                    raise shared.GateError("ambiguous class owner: " + classname)
                index[classname] = {"owner": module, "sha256": sha}
        for name in libraries:
            if not any(name.startswith("BOOT-INF/lib/" + module + "-") for module in modules):
                (library_dir / Path(name).name).write_bytes(archive.read(name))
    write_json(output / "class-index.json", index)
    return modules, index, paths


def parse_output(text, index, inputs):
    references, missing, unknown, seen = {}, [], [], set()
    names = {p.name for p in inputs}
    for line in text.splitlines():
        if not line.strip():
            continue
        match = CLASS_ROW.fullmatch(line)
        if match:
            source, target, location = match.groups()
            if source not in index:
                unknown.append({"reason": "SOURCE_OWNER_UNRESOLVED", "line": line})
                continue
            seen.add(index[source]["owner"])
            if location == "not found":
                missing.append({"source_class": source, "target_class": target})
                continue
            if target in index:
                if Path(location).name != index[target]["owner"] + ".jar":
                    unknown.append({"reason": "TARGET_OWNER_LOCATION_MISMATCH", "line": line})
                    continue
                a, b = index[source]["owner"], index[target]["owner"]
                if a != b:
                    key = source + "->" + target
                    references[key] = {"source_class": source, "target_class": target,
                                       "source_owner": a, "target_owner": b,
                                       "source": source, "target": target, "resolved_type": target,
                                       "reference_kind": "JDEPS_CLASS_REFERENCE"}
            elif Path(location).name in names:
                unknown.append({"reason": "TARGET_CLASS_NOT_INDEXED", "line": line})
            continue
        summary = SUMMARY_ROW.fullmatch(line)
        if summary and Path(summary.group(1)).name in names:
            # Class rows, not the artifact summary, determine owner edges.
            if summary.group(2) == "not found":
                missing.append({"source_artifact": summary.group(1), "location": "not found"})
            continue
        unknown.append({"reason": "UNRECOGNIZED_JDEPS_OUTPUT", "line": line})
    edges = sorted({(r["source_owner"], r["target_owner"]) for r in references.values()})
    return references, edges, missing, unknown, seen


def collect(root, artifact, identity_path, output, env, runner=subprocess.run):
    report = {"schema": 1, "kind": "JDK21_JDEPS_OBSERVATION", "verdict": "OBSERVATION_ONLY",
              "source_sha": env.get("GITHUB_SHA"), "run_id": env.get("GITHUB_RUN_ID"),
              "run_attempt": env.get("GITHUB_RUN_ATTEMPT"), "artifacts": {}, "references": {},
              "module_edges": [], "missing_dependencies": [], "review_items": [],
              "graph_kind": "STANDARD_JDK21_RESOLVED_CLASS_REFERENCES", "graph_complete": False,
              "source_identity_verified": False, "input_artifacts_verified": False,
              "static_graph_complete": False, "scc": None, "baseline": None, "findings": [],
              "not_proven": ["reflection/runtime/XML dependencies", "business-domain ownership within System",
                             "baseline approval", "Java tests or business correctness"]}
    output.mkdir(parents=True, exist_ok=True)
    try:
        if any(output.iterdir()):
            raise shared.GateError("observation output directory must be fresh")
        root, artifact = root.resolve(), artifact.resolve()
        artifact.relative_to(root)
        identity = json.loads(identity_path.read_text())
        report["phase"] = "identity"
        report.update(bind_identity(root, artifact, identity, env))
        report["source_identity_verified"] = True
        report["build_identity_sha256"] = shared.digest(identity_path.read_bytes())
        report["phase"] = "version"
        tool = Path(env["JAVA_HOME"]) / "bin/jdeps"
        version = runner([str(tool), "--version"], capture_output=True, text=True, timeout=30,
                         env={**env, "LC_ALL": "C", "LANG": "C"})
        (output / "jdeps-version.txt").write_text(version.stdout + version.stderr)
        report["jdeps_version_exit_code"] = version.returncode
        if version.returncode or not re.fullmatch(r"21(?:\.[0-9]+)*(?:[-+][^\s]+)?\s*", version.stdout):
            raise shared.GateError("standard JDK21 jdeps required")
        report["jdeps_tool_sha256"] = shared.digest(tool.read_bytes())
        with tempfile.TemporaryDirectory(prefix="architecture-jdeps-") as tmp:
            libs = Path(tmp) / "lib"
            libs.mkdir()
            report["phase"] = "artifacts"
            modules, index, inputs = stage_inputs(root, artifact, output, libs, report)
            report["input_artifacts_verified"] = True
            classpath = os.pathsep.join(map(str, [*inputs, *sorted(libs.glob("*.jar"))]))
            args = [str(tool), "--multi-release", "21", "-verbose:class", "-filter:none", "--class-path",
                    classpath, *map(str, inputs)]
            write_json(output / "invocation.json", {"argv": args, "locale": "C", "timeout_seconds": 180})
            report["phase"] = "jdeps"
            result = runner(args, capture_output=True, text=True, timeout=180,
                            env={**env, "LC_ALL": "C", "LANG": "C"})
            (output / "jdeps.stdout.txt").write_text(result.stdout)
            (output / "jdeps.stderr.txt").write_text(result.stderr)
            report["jdeps_exit_code"] = result.returncode
            refs, edges, missing, unknown, seen = parse_output(result.stdout, index, inputs)
            report.update(references=refs, module_edges=edges, missing_dependencies=missing, review_items=unknown)
            if seen != set(modules):
                report["review_items"].append({"reason": "MODULE_OUTPUT_COVERAGE_INCOMPLETE", "owners_seen": sorted(seen)})
            if result.stderr.strip():
                report["review_items"].append({"reason": "JDEPS_STDERR_PRESENT"})
            report["static_graph_complete"] = (result.returncode == 0 and not missing and not report["review_items"])
            report["collection_status"] = "COLLECTED_STATIC_REFERENCES" if report["static_graph_complete"] else "UNKNOWN"
    except Exception as error:
        report["collection_status"] = "UNKNOWN"
        report["review_items"].append({"reason": "COLLECTION_ERROR", "detail": str(error)})
        # Preserve raw tool output even on timeout; never promote partial output.
        if isinstance(error, subprocess.TimeoutExpired):
            for name, data in (("jdeps.stdout.txt", error.stdout), ("jdeps.stderr.txt", error.stderr)):
                (output / name).write_bytes(data.encode() if isinstance(data, str) else data or b"")
    report["evidence_sha256"] = {p.relative_to(output).as_posix(): shared.digest(p.read_bytes())
                                for p in sorted(output.rglob("*")) if p.is_file() and p.name != "observation.json"}
    write_json(output / "observation.json", report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--artifact", required=True, type=Path)
    parser.add_argument("--identity", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = collect(args.source, args.artifact, args.identity, args.output, dict(os.environ))
        print("jdeps observation: " + report["collection_status"] + "; no baseline/SCC acceptance")
    except Exception as error:
        # Observation-only even if evidence storage itself fails. A missing report
        # can never be read as an accepted graph; retain the failure in CI logs.
        print("::warning::jdeps observation UNKNOWN; evidence not persisted: " + str(error))
    return 0  # Observation only; existing trusted incremental gates decide violations.


if __name__ == "__main__":
    raise SystemExit(main())
