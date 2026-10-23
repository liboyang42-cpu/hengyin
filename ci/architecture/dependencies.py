#!/usr/bin/env python3
"""Declared Maven edges and a conservative explicit-import graph, not a Java AST.

Reuse the frozen gate's CI identity checks and existing Java lexical helper.
Unsupported Java is UNKNOWN; this graph cannot certify absence of all cycles.
"""
from __future__ import annotations

import argparse
import collections
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
FROZEN = "527adf3fe003b6e9022c255f9e7a3716a9aabe95"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


shared = load("wp01_shared_gate", HERE / "gate.py")
lexical = load("wp01_existing_java_lexical", ROOT / "ci/real_storage_it.py")
NAME = r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"
PACKAGE = re.compile(r"\A\s*package\s+(" + NAME + r")\s*;")
IMPORT = re.compile(r"\bimport\s+(static\s+)?(" + NAME + r"(?:\.\*)?)\s*;")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def blobs(root, sha, names):
    """Read immutable blobs in one git process; never follow filesystem symlinks."""
    if any("\n" in name or "\r" in name for name in names):
        raise shared.GateError("unsupported git pathname")
    requests = "".join(sha + ":" + name + "\n" for name in names).encode()
    result = subprocess.run(["git", "-C", str(root), "cat-file", "--batch"], input=requests, capture_output=True)
    if result.returncode:
        raise shared.GateError("cannot read source blobs")
    offset, found = 0, {}
    for name in names:
        end = result.stdout.index(b"\n", offset)
        header = result.stdout[offset:end].split()
        if len(header) != 3 or header[1] != b"blob":
            raise shared.GateError("missing source blob: " + name)
        size = int(header[2])
        offset = end + 1
        found[name] = result.stdout[offset:offset + size]
        offset += size + 1
    return found


def components(nodes, edges):
    """Six module nodes: simple reachability, O(V*(V+E)); no bytecode framework."""
    adjacency = {node: set() for node in nodes}
    for source, target in edges:
        adjacency[source].add(target)
    reach = {}
    for node in nodes:
        seen, pending = set(), [node]
        while pending:
            current = pending.pop()
            if current not in seen:
                seen.add(current)
                pending.extend(adjacency[current] - seen)
        reach[node] = seen
    groups = {tuple(sorted(other for other in nodes if other in reach[node] and node in reach[other]))
              for node in nodes}
    return sorted(group for group in groups if len(group) > 1 or (group[0], group[0]) in edges)


def inventory(root, sha):
    names = shared.tree(root, sha)
    root_pom = shared.xml(shared.blob(root, sha, "pom.xml"))
    modules = sorted(node.text.strip() for node in root_pom.findall("m:modules/m:module", NS))
    if not modules or any(not re.fullmatch(r"chengyinhub-[a-z0-9-]+", module) for module in modules):
        raise shared.GateError("unsupported/unresolved Maven module owners")
    selected = [name for name in names if name in {module + "/pom.xml" for module in modules}
                or any(name.startswith(module + "/src/main/java/") and name.endswith(".java") for module in modules)]
    files = blobs(root, sha, selected)
    types, packages, sources = collections.defaultdict(list), collections.defaultdict(set), {}
    unknown, references, edges = [], {}, {}
    for path, data in files.items():
        if not path.endswith(".java"):
            continue
        owner = path.split("/")[0]
        raw = data.decode("utf-8-sig")
        # Unicode escapes are expanded before Java lexing, even in comments.
        # Do not derive a type owner or any imports from this unsupported form.
        if re.search(r"\\u+[0-9a-fA-F]{4}", raw):
            unknown.append({"path": path, "reason": "JAVA_TEXT_BLOCK_OR_UNICODE_ESCAPE"})
            continue
        code = lexical.strip_java_comments(raw, keep_strings=False)
        package = PACKAGE.match(code)
        if not code.strip():  # Two current files are entirely commented out.
            continue
        if not package:
            unknown.append({"path": path, "reason": "UNRESOLVED_PACKAGE"})
            continue
        fqcn = package.group(1) + "." + Path(path).stem
        types[fqcn].append(owner)
        packages[package.group(1)].add(owner)
        sources[path] = (owner, raw, code)
    if root_pom.find("m:profiles", NS) is not None:
        unknown.append({"path": "pom.xml", "reason": "MAVEN_PROFILES_NOT_EVALUATED"})
    for module in modules:
        pom = shared.xml(files[module + "/pom.xml"])
        if pom.find("m:profiles", NS) is not None:
            unknown.append({"path": module + "/pom.xml", "reason": "MAVEN_PROFILES_NOT_EVALUATED"})
        for dep in pom.findall("m:dependencies/m:dependency", NS):
            artifact = dep.findtext("m:artifactId", "", NS).strip()
            group = dep.findtext("m:groupId", "", NS).strip()
            if "${" in artifact or "${" in group:
                unknown.append({"path": module + "/pom.xml", "reason": "MAVEN_DEPENDENCY_NOT_RESOLVED"})
            if artifact in modules and group == "com.chengyinhub":
                edges[(module, artifact)] = module + "/pom.xml#" + artifact
    for path, (owner, raw, code) in sources.items():
        package = PACKAGE.match(code).group(1)
        if len(packages[package]) > 1:
            unknown.append({"path": path, "reason": "SHARED_PACKAGE_IMPLICIT_TYPE_USAGE_NOT_RESOLVED"})
        # The existing helper is intentionally not extended into a Java parser.
        if '"""' in raw:
            unknown.append({"path": path, "reason": "JAVA_TEXT_BLOCK_OR_UNICODE_ESCAPE"})
            continue
        if re.search(r"\bimport\b", IMPORT.sub("", code)):
            unknown.append({"path": path, "reason": "UNRESOLVED_IMPORT_DECLARATION"})
        for match in IMPORT.finditer(code):
            target = match.group(2)
            if target.endswith(".*"):
                unknown.append({"path": path, "reason": "WILDCARD_IMPORT", "target": target})
                continue
            parts, resolved = target.split("."), None
            # Static members/nested types resolve through their known outer type.
            for length in range(len(parts), 0, -1):
                outer = ".".join(parts[:length])
                if outer in types:
                    resolved = outer
                    break
            if resolved is None:
                if target.startswith("com.chengyinhub."):
                    unknown.append({"path": path, "reason": "UNRESOLVED_LOCAL_IMPORT", "target": target})
                continue
            owners = set(types[resolved])
            if len(owners) != 1:
                unknown.append({"path": path, "reason": "AMBIGUOUS_TYPE_OWNER", "target": target})
                continue
            target_owner = next(iter(owners))
            if owner == target_owner:
                continue
            kind = "static" if match.group(1) else "type"
            key = path + "#" + kind + ":" + target
            references[key] = {"source_owner": owner, "target_owner": target_owner, "target": target,
                               "resolved_type": resolved,
                               "source": path, "import_kind": kind,
                               "sha256": shared.digest((kind + " " + target).encode())}
            edges.setdefault((owner, target_owner), key)
        body = PACKAGE.sub("", IMPORT.sub("", code))
        if re.search(r"\bcom\.chengyinhub\.", body):
            unknown.append({"path": path, "reason": "FULLY_QUALIFIED_USAGE_NOT_RESOLVED"})
    return {"source_sha": sha, "modules": modules,
            "package_owners": {package: sorted(owners) for package, owners in sorted(packages.items())},
            "references": references, "module_edges": sorted(edges), "edge_witnesses": edges,
            "scc": components(modules, edges), "unknown": sorted(unknown, key=lambda item: (item["path"], item["reason"], item.get("target", "")))}


def private(reference):
    return bool({"internal", "mapper"} & set(reference["resolved_type"].rsplit(".", 1)[0].split(".")))


def snapshot(data):
    return {"schema": 1, "source_sha": data["source_sha"], "modules": data["modules"],
            "package_owners": data["package_owners"], "module_edges": data["module_edges"], "scc": data["scc"],
            "private_imports": {key: value for key, value in sorted(data["references"].items()) if private(value)}}


def delta(before, after, changed):
    violations = []
    for key, reference in after["references"].items():
        if reference != before["references"].get(key) and private(reference):
            violations.append({"rule": "NEW_CROSS_MODULE_PRIVATE_IMPORT", "key": key, **reference})
    previous = {frozenset(group) for group in before["scc"]}
    for group in after["scc"]:
        if not any(set(group) <= old for old in previous):
            violations.append({"rule": "NEW_OR_EXPANDED_MODULE_SCC", "modules": group,
                               "new_edge_witnesses": [after["edge_witnesses"][edge] for edge in after["edge_witnesses"]
                                                      if edge not in before["edge_witnesses"] and set(edge) <= set(group)]})
    review = [item for item in after["unknown"] if item["path"] in changed]
    if before.get("modules") != after.get("modules"):
        review.append({"path": "pom.xml", "reason": "MAVEN_MODULE_OWNERS_CHANGED"})
    return {"findings": violations,
            "review_items": review,
            "graph_kind": "DECLARED_POM_AND_RESOLVED_EXPLICIT_IMPORTS_ONLY",
            "graph_complete": False, "candidate_scc": after["scc"],
            "not_proven": ["all bytecode/runtime dependencies", "intra-system business-domain ownership",
                           "additional top-level types not matching source filenames", "effective/inherited/profile Maven dependencies",
                           "implicit same-package cross-module usage", "reflection, generated code, XML wiring and inherited/transitive member dependencies"]}


def check(root, candidate, compare, context):
    if shared.git(root, "rev-parse", "HEAD").decode().strip() != candidate:
        raise shared.GateError("candidate HEAD mismatch")
    if shared.git(root, "status", "--porcelain", "--untracked-files=all").strip():
        raise shared.GateError("dirty candidate")
    shared.git(root, "merge-base", "--is-ancestor", shared.BASE, candidate)
    shared.comparison_base(root, candidate, compare, context)
    # Patch-based integration need not inherit the verifier's commit history.
    # Only the trusted checkout supplies the frozen inventory's Git objects.
    expected = shared.encode(snapshot(inventory(shared.REPO, FROZEN)))
    baseline = HERE / "dependency-baseline.json"
    if baseline.read_bytes() != expected or shared.blob(root, candidate, "ci/architecture/dependency-baseline.json") != expected:
        raise shared.GateError("DEPENDENCY_BASELINE_LAUNDERING")
    for name in ("dependencies.py", "gate.py"):
        trusted = (HERE / name).resolve()
        relative = trusted.relative_to(ROOT).as_posix()
        if shared.blob(root, candidate, relative) != trusted.read_bytes():
            raise shared.GateError("untrusted verifier change: " + relative)
    changed = set(shared.git(root, "diff", "--name-only", "--no-renames", compare, candidate).decode().splitlines())
    result = delta(inventory(root, compare), inventory(root, candidate), changed)
    return {**result, "candidate_sha": candidate, "comparison_base_sha": compare,
            "baseline_sha": FROZEN, "verdict": "BLOCKED" if result["findings"] else
            "PASS_WITH_UNKNOWN" if result["review_items"] or not result["graph_complete"] else "PASS"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("inventory", "source"))
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--candidate")
    parser.add_argument("--compare-base")
    args = parser.parse_args()
    try:
        result = snapshot(inventory(args.source, FROZEN)) if args.command == "inventory" else check(
            args.source, args.candidate, args.compare_base, shared.ci_context(os.environ))
        print(shared.encode(result).decode(), end="")
        return 1 if result.get("findings") else 0
    except (shared.GateError, OSError, ValueError, KeyError, TypeError, UnicodeError) as error:
        print(shared.encode({"verdict": "FAIL_CLOSED", "error": str(error)}).decode(), end="")
        return 1


if __name__ == "__main__":
    sys.exit(main())
