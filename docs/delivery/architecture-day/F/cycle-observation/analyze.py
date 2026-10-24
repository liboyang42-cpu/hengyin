#!/usr/bin/env python3
"""Read-only observation, NOT a gate/baseline/Java parser or runtime call graph.

Reuse public 02e691 import/lexical/blob/SCC helpers and the source's XML writer
helper. Only write explicit report outputs; never run Java, SQL, CI or network.
"""
import argparse
import collections
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True

HELPER_SHA = "02e69111a83d19d2ad53051a44c629d5e0a5a38a"
HELPER_FILES = ["ci/architecture/dependencies.py", "ci/architecture/gate.py",
                "ci/real_storage_it.py", "ci/sql_splitter.py"]


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def digest(value):
    return hashlib.sha256(value).hexdigest()


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def ranks(mapping, count=15):
    return [{"name": name, "degree": len(values)} for name, values in
            sorted(mapping.items(), key=lambda item: (-len(item[1]), item[0]))[:count]]


def metrics(nodes, pairs, scc):
    incoming = {n: set() for n in nodes}
    outgoing = {n: set() for n in nodes}
    for source, target in pairs:
        outgoing[source].add(target)
        incoming[target].add(source)
    groups = scc(nodes, pairs)
    return {"nodes": len(nodes), "edges": len(pairs), "fan_in": ranks(incoming), "fan_out": ranks(outgoing),
            "nontrivial_scc": groups, "all_scc_count": len(nodes) - sum(map(len, groups)) + len(groups)}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def observe(root, sha, helper_root, output, all_references=False):
    if output == root or (root in output.parents and not str(output.relative_to(root)).startswith("docs/delivery/architecture-day/F/")):
        raise ValueError("outputs inside source checkout must stay in the F delivery directory")
    for checkout in (root, helper_root):
        origin = git(checkout, "remote", "get-url", "origin").decode().strip()
        if not re.fullmatch(r"https://github.com/liboyang42-cpu/hengyin(?:\.git)?", origin):
            raise ValueError("expected public hengyin checkout; unexpected origin is not printed")
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or git(root, "cat-file", "-t", sha).strip() != b"commit":
        raise ValueError("full immutable source commit required")
    if git(helper_root, "rev-parse", "HEAD").decode().strip() != HELPER_SHA:
        raise ValueError("helper must be the public immutable 02e691 checkout")
    helper_evidence = []
    for name in HELPER_FILES:
        raw = git(helper_root, "show", HELPER_SHA + ":" + name)
        if raw != (helper_root / name).read_bytes():
            raise ValueError("modified helper: " + name)
        helper_evidence.append({"path": name, "sha256": digest(raw)})
    for name in ("ci/architecture/gate.py", "ci/sql_splitter.py"):
        if (root / name).read_bytes() != git(root, "show", sha + ":" + name):
            raise ValueError("source helper differs from observed commit")
    dep = load("f_observed_dependency_helper", helper_root / "ci/architecture/dependencies.py")
    gate = load("f_observed_writer_helper", root / "ci/architecture/gate.py")
    prior = dep.inventory(root, sha)  # Existing POM/import query, no source gate.
    modules = prior["modules"]
    paths = [p for p in gate.tree(root, sha) if p.endswith(".java") and any(
             p.startswith(m + "/src/main/java/") for m in modules)]
    blobs = dep.blobs(root, sha, paths)
    types = collections.defaultdict(list)
    units, unknown = {}, []
    for path, raw in blobs.items():
        text = raw.decode("utf-8-sig")
        if re.search(r"\\u+[0-9a-fA-F]{4}", text) or '"""' in text:
            unknown.append({"path": path, "reason": "UNSUPPORTED_UNICODE_OR_TEXT_BLOCK"})
            continue
        code = dep.lexical.strip_java_comments(text, keep_strings=False)
        package = dep.PACKAGE.match(code)
        if not package:
            unknown.append({"path": path, "reason": "COMMENT_ONLY_OR_PACKAGE_UNKNOWN"})
            continue
        name = package[1] + "." + Path(path).stem
        types[name].append(path)
        units[path] = {"type": name, "package": package[1], "code": code, "raw": text}
    references, unused, module_pairs = [], [], set()
    class_pairs, package_pairs = set(), collections.defaultdict(list)
    type_in, type_out = collections.defaultdict(set), collections.defaultdict(set)
    for path, unit in units.items():
        code = unit["code"]
        body = dep.PACKAGE.sub("", dep.IMPORT.sub("", code))
        if len(types[unit["type"]]) != 1:
            unknown.append({"path": path, "reason": "AMBIGUOUS_SOURCE_TYPE"})
            continue
        for match in dep.IMPORT.finditer(code):
            target = match[2]
            if match[1] or target.endswith(".*"):
                unknown.append({"path": path, "reason": "STATIC_OR_WILDCARD_IMPORT_NOT_EXPANDED", "target": target})
                continue
            parts, resolved = target.split("."), None
            for length in range(len(parts), 0, -1):
                candidate = ".".join(parts[:length])
                if len(types.get(candidate, [])) == 1:
                    resolved = candidate
                    break
            if resolved is None:
                if target.startswith("com.chengyinhub."):
                    unknown.append({"path": path, "reason": "LOCAL_TYPE_UNRESOLVED", "target": target})
                continue
            if not re.search(r"\b" + re.escape(parts[-1]) + r"\b", body):
                unused.append({"path": path, "target": target})
                continue
            if resolved != target:
                unknown.append({"path": path, "reason": "NESTED_IMPORT_AGGREGATED_TO_OUTER_FILE_TYPE", "target": target})
            target_path = types[resolved][0]
            target_package = units[target_path]["package"]
            # Old lexical helper collapses multiline comments: never derive raw
            # line numbers from its cleaned text. Locate the exact declaration
            # in raw lines; ambiguous locations stay UNKNOWN.
            declaration = re.compile(r"^\s*import\s+" + re.escape(target) + r"\s*;")
            lines = [i for i, line in enumerate(unit["raw"].splitlines(), 1) if declaration.match(line)]
            ref = {"source": unit["type"], "target": resolved, "import_target": target,
                   "source_package": unit["package"], "target_package": target_package,
                   "source_module": path.split("/")[0], "target_module": target_path.split("/")[0],
                   "path": path, "target_path": target_path, "import_line": lines[0] if len(lines) == 1 else None}
            references.append(ref)
            if ref["source"] != ref["target"]:
                class_pairs.add((ref["source"], ref["target"]))
                type_in[ref["target"]].add(path)
                type_out[path].add(ref["target"])
            if ref["source_package"] != ref["target_package"]:
                package_pairs[(ref["source_package"], ref["target_package"])].append(ref)
            if ref["source_module"] != ref["target_module"]:
                module_pairs.add((ref["source_module"], ref["target_module"]))
        if re.search(r"\bcom\.chengyinhub\.", body):
            unknown.append({"path": path, "reason": "FQN_BODY_USAGE_NOT_EXPANDED"})
    pom_edges = []
    for module in modules:
        path = module + "/pom.xml"
        pom = gate.xml(gate.blob(root, sha, path))
        for entry in pom.findall("m:dependencies/m:dependency", dep.NS):
            target = entry.findtext("m:artifactId", "", dep.NS).strip()
            if target in modules and entry.findtext("m:groupId", "", dep.NS).strip() == "com.chengyinhub":
                pom_edges.append({"source": module, "target": target, "scope": entry.findtext("m:scope", "compile", dep.NS), "path": path})
    cycles = []
    for (left, right), rows in sorted(package_pairs.items()):
        if left >= right or (right, left) not in package_pairs:
            continue
        pair = {left, right}
        dependents = {r["path"] for r in references if r["target_package"] in pair and r["source_package"] not in pair}
        cycles.append({"packages": [left, right], "direct_outside_dependent_units": len(dependents),
                       "forward_import_records": len(rows), "reverse_import_records": len(package_pairs[(right, left)])})
    cycles.sort(key=lambda r: (-r["direct_outside_dependent_units"], -(r["forward_import_records"] + r["reverse_import_records"]), r["packages"]))
    details = {}
    xml = gate.inventory(root, sha, details)
    table_writers, writer_tables, write_edges = collections.defaultdict(set), collections.defaultdict(set), collections.defaultdict(list)
    unresolved_writes, unknown_effects = [], []
    for key, statement in xml["statements"].items():
        if statement["kind"] == "select":
            continue
        target = gate.literal_write_target(statement["kind"], details[key])
        if target is None:
            unresolved_writes.append(key)
            continue
        if details[key]["unknown"]:
            unknown_effects.append(key)
        owner = statement["owner"]
        table_writers[target].add(owner)
        writer_tables[owner].add(target)
        write_edges[(owner, target)].append({"statement": key, "fingerprint": statement["sha256"]})
    package_nodes = sorted({u["package"] for u in units.values()})
    type_nodes = sorted(n for n, p in types.items() if len(p) == 1)
    mapped_types = {r["owner"].split(":", 1)[1] for r in xml["statements"].values()}
    package_owners = {package: sorted({p.split("/")[0] for p, u in units.items() if u["package"] == package}) for package in package_nodes}
    declared_policy = json.loads(gate.blob(root, sha, "ci/architecture/owners.json"))["business_policy"]
    result = {"kind": "READ_ONLY_EXPLICIT_IMPORT_WITH_BODY_TOKEN_OBSERVATION", "source_sha": sha,
              "source_tree": git(root, "rev-parse", sha + "^{tree}").decode().strip(),
              "helper_sha": HELPER_SHA, "helper_files": helper_evidence, "graph_complete": False,
              "runtime_or_compiled_graph": "UNKNOWN; no Java/jdeps executed or current artifact loaded",
              "java_units": len(paths), "supported_units": len(units),
              "business_mapper_immediate_units": sum(p.startswith("chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/") and "/" not in p.rsplit("/mapper/", 1)[1] for p in paths),
              "used_import_records": len(references), "unused_explicit_import_records": len(unused),
              "unknown_counts": dict(sorted(collections.Counter(r["reason"] for r in unknown).items())),
              "existing_helper_unknown_counts": dict(sorted(collections.Counter(r["reason"] for r in prior["unknown"]).items())),
              "maven_declarations": {**metrics(modules, {(r["source"], r["target"]) for r in pom_edges}, dep.components), "records": pom_edges},
              "module_used_imports": {**metrics(modules, module_pairs, dep.components),
                 "records": [{"source": a, "target": b, "import_records": sum(r["source_module"] == a and r["target_module"] == b for r in references),
                    "witness": next(r for r in references if r["source_module"] == a and r["target_module"] == b)} for a, b in sorted(module_pairs)]},
              "package_used_imports": {**metrics(package_nodes, set(package_pairs), dep.components), "package_owner_modules": package_owners},
              "type_used_imports": metrics(type_nodes, class_pairs, dep.components),
              "type_source_unit_fan_in": ranks(type_in, 20), "file_distinct_type_fan_out": ranks(type_out, 20),
              "mapper_import_fan_in": ranks({n: v for n, v in type_in.items() if n in mapped_types}),
              "service_import_fan_in": ranks({n: v for n, v in type_in.items() if ".service." in n}),
              "ranked_reciprocal_package_cycles": cycles,
              "approved_policy_at_observed_source": {"coverage": declared_policy["coverage"], "fact_ids": declared_policy["scope"], "pure_writers": declared_policy["pure_writers"], "private_ports": declared_policy["private_ports"]},
              "writer_observation": {"literal_tables": len(table_writers), "technical_writer_namespaces": len(writer_tables),
                 "unknown_target_statement_count": len(unresolved_writes), "known_target_unknown_effect_statement_count": len(unknown_effects),
                 "unknown_target_statement_examples": unresolved_writes[:8], "known_target_unknown_effect_examples": unknown_effects[:8],
                 "multi_writer_tables": [{"table": t, "writers": sorted(w)} for t, w in sorted(table_writers.items()) if len(w) > 1],
                 "table_distinct_writer_fan_in": ranks(table_writers), "namespace_table_fan_out": ranks(writer_tables),
                 "authority": "OBSERVED_TECHNICAL_NAMESPACES_NOT_APPROVED_BUSINESS_OWNERS"},
              "not_proven": ["full Java type resolution/bytecode", "same-package implicit/FQN/static/wildcard/generated references",
                 "unused-import compiler effects", "method graph, Spring injection graph or runtime recursion",
                 "business domains from package names", "effective/profile/transitive Maven resolution",
                 "XML wiring/MyBatis annotation or dynamic SQL effects", "all table/field/command business owners", "absence of other cycles"]}
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "summary.json", result)
    with (output / "package-edges.tsv").open("w") as stream:
        stream.write("source_package\ttarget_package\timport_records\tdistinct_source_units\twitness\ttarget_type\n")
        for (source, target), rows in sorted(package_pairs.items()):
            r = rows[0]
            stream.write("\t".join(map(str, [source, target, len(rows), len({r["path"] for r in rows}),
                         r["path"] + ":" + str(r["import_line"]), r["target"]])) + "\n")
    with (output / "mapper-writes.tsv").open("w") as stream:
        stream.write("technical_namespace\tliteral_table\tstatement_count\twitness_statement\twitness_fingerprint\n")
        for (owner, table), rows in sorted(write_edges.items()):
            stream.write("\t".join([owner, table, str(len(rows)), rows[0]["statement"], rows[0]["fingerprint"]]) + "\n")
    write_json(output / "unknown.json", unknown)
    if all_references:
        with (output / "all-import-references.jsonl").open("w") as stream:
            for row in references:
                stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--helper-root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--all-references", action="store_true")
    args = parser.parse_args()
    result = observe(args.source.resolve(), args.sha, args.helper_root.resolve(), args.output.resolve(), args.all_references)
    print(json.dumps({"source": result["source_sha"], "units": result["java_units"], "supported": result["supported_units"],
          "maven_edges": result["maven_declarations"]["edges"], "module_import_edges": result["module_used_imports"]["edges"],
          "package_edges": result["package_used_imports"]["edges"], "package_nontrivial_scc": len(result["package_used_imports"]["nontrivial_scc"]),
          "type_nontrivial_scc": len(result["type_used_imports"]["nontrivial_scc"]), "graph_complete": False}))


if __name__ == "__main__":
    main()
