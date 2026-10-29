#!/usr/bin/env python3
"""Opt-in real JDK21 controls, only in root's approved existing GitHub Backend job.

No scanner/rules: reuse the immutable producer's ZIP index, parser and verdict.
Derived control module JARs are NEVER the tested application JAR. Their sources
are original Git blobs plus the recorded single fixture method, not a fake CI SHA.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

VERIFIER = "9782954919075c4a13d209f5f3ab4e3ff8f4e9e8"
BASE = "e4e9f6042ba34fb571c3bcfbfe59e248000ef637"
CASES = {
    "sdk-router": ("chengyinhub-system", "com.chengyinhub.business.service.impl.WechatPaymentSdkGateway",
                   "com.chengyinhub.business.service.impl.WechatPaymentCallbackRouter", "B_PAYMENT_SDK"),
    "dto-facade": ("chengyinhub-system", "com.chengyinhub.business.domain.dto.CashWithdrawTransferIntent",
                   "com.chengyinhub.business.service.impl.WechatPayServiceImpl", "A_TRANSFER_INTENT_DTO"),
    "common-framework": ("chengyinhub-common", "com.chengyinhub.common.utils.LogCorrelation",
                         "com.chengyinhub.framework.web.trace.RequestIdFilter", "F_LOG_CONTEXT"),
}
MARKER = "__architectureNegativeControl"
LEAF_SOURCES = {
    "architecture.leafcontrol.EmptyClass": b"package architecture.leafcontrol; public final class EmptyClass {}\n",
    "architecture.leafcontrol.EmptyInterface": b"package architecture.leafcontrol; public interface EmptyInterface {}\n",
}


class ControlFailure(ValueError):
    """Complete real tool evidence contradicts the expected negative/restored result."""


def require(value, message):
    if not value:
        raise ValueError(message)


def require_ci(env):
    require(env.get("GITHUB_ACTIONS") == "true" and env.get("GITHUB_REPOSITORY") == "liboyang42-cpu/hengyin",
            "NOT_RUN: root-approved GitHub-hosted Backend context required")
    require(re.fullmatch(r"[0-9a-f]{40}", env.get("GITHUB_SHA", "")), "actual full CI source SHA required")
    require(all(re.fullmatch(r"[1-9][0-9]*", env.get(k, "")) for k in ("GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")),
            "actual CI run/attempt required")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.PIPE)


def control_source(original, target):
    text = original.decode("utf-8")
    require(MARKER not in text and text.rstrip().endswith("}"), "fixture insertion preimage changed")
    require(re.fullmatch(r"(?:[A-Za-z_][A-Za-z0-9_]*\.)+[A-Za-z_][A-Za-z0-9_]*", target), "invalid target type")
    closing = text.rfind("}")
    method = "\n    // Isolated compile control only; never called, never deployed.\n" + \
             "    private static java.lang.Class<?> " + MARKER + "() { return " + target + ".class; }\n"
    return (text[:closing] + method + text[closing:]).encode("utf-8")


def tool(args, folder, stem, env):
    (folder / (stem + "-invocation.json")).write_text(json.dumps({"argv": list(map(str, args)), "timeout_seconds": 180}, indent=2) + "\n")
    try:
        result = subprocess.run(list(map(str, args)), cwd=folder, capture_output=True, text=True, timeout=180,
                                env={**env, "LC_ALL": "C", "LANG": "C"})
    except subprocess.TimeoutExpired as failure:
        for suffix, data in (("stdout", failure.stdout), ("stderr", failure.stderr)):
            (folder / (stem + "." + suffix + ".txt")).write_bytes(data.encode() if isinstance(data, str) else data or b"")
        raise
    (folder / (stem + ".stdout.txt")).write_text(result.stdout)
    (folder / (stem + ".stderr.txt")).write_text(result.stderr)
    return result


def load_verifier(path):
    require(git(path, "rev-parse", "HEAD").decode().strip() == VERIFIER, "reviewed exact source-row verifier required")
    require(not git(path, "status", "--porcelain", "--untracked-files=all").strip(), "dirty verifier")
    spec = importlib.util.spec_from_file_location("real_control_producer", path / "ci/architecture/collect_jdeps.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def jdk_tools(producer, output, env, report):
    tools = {name: Path(env["JAVA_HOME"]) / "bin" / name for name in ("javac", "jdeps")}
    report["jdk"] = {}
    for name, path in tools.items():
        version = tool([path, "--version"], output, name + "-version", env)
        require(version.returncode == 0 and re.fullmatch(r"(?:javac )?21(?:\.[0-9]+)*(?:[-+][^\s]+)?\s*", version.stdout), "actual JDK21 required")
        report["jdk"][name] = {"sha256": producer.shared.digest(path.read_bytes()), "version": version.stdout.strip()}
    return tools


def leaf_shape(producer, output, env, tools):
    folder = output / "leaf-shape"
    folder.mkdir()
    sources, classes = folder / "src", folder / "classes"
    sources.mkdir(); classes.mkdir()
    java_files = []
    for name, data in LEAF_SOURCES.items():
        path = sources / (name.replace(".", "/") + ".java")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data); java_files.append(path)
    result = tool([tools["javac"], "--release", "21", "-proc:none", "-implicit:none", "-sourcepath", "",
                   "-d", classes, *java_files], folder, "javac", env)
    require(result.returncode == 0, "real empty leaf compilation failed; shape UNKNOWN")
    artifact = folder / "leaf-controls.jar"
    with zipfile.ZipFile(artifact, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.writestr(path.relative_to(classes).as_posix(), path.read_bytes())
    actual = producer.class_entries(artifact.read_bytes())
    require(set(actual) == set(LEAF_SOURCES), "unexpected real leaf class inventory")
    # Test-only artifact label, not a production owner or new module/policy.
    index = {n: {"owner": artifact.stem, "sha256": sha} for n, sha in actual.items()}
    producer.write_json(folder / "class-index.json", index)
    result = tool([tools["jdeps"], "--multi-release", "21", "-verbose:class", "-filter:none",
                   "--class-path", artifact, artifact], folder, "jdeps", env)
    source_rows = set()
    references, edges, missing, unknown, seen = producer.parse_output(result.stdout, index, [artifact], source_rows=source_rows)
    reviews = producer.owner_source_reviews({"owners": {"TEST_ONLY_LEAF_SHAPE": {"members": list(LEAF_SOURCES)}}}, source_rows)
    rows = [m for m in map(producer.CLASS_ROW.fullmatch, result.stdout.splitlines()) if m]
    object_rows = {m.group(1): m.group(0) for m in rows if m.group(2) == "java.lang.Object" and m.group(3) == "java.base"}
    complete = result.returncode == 0 and not result.stderr.strip() and not missing and not unknown and not reviews and \
            not references and not edges and seen == {artifact.stem} and set(object_rows) == set(LEAF_SOURCES)
    report = {"kind": "REAL_JDK21_EMPTY_LEAF_RAW_SHAPE_ONLY", "verdict": "LEAF_SHAPE_CONFIRMED" if complete else "UNKNOWN",
              "sources_sha256": {n: producer.shared.digest(data) for n, data in LEAF_SOURCES.items()},
              "jar_sha256": producer.shared.digest(artifact.read_bytes()), "class_index": index,
              "actual_source_rows": sorted(source_rows), "actual_object_rows": object_rows,
              "project_references": references, "module_edges": edges, "missing_dependencies": missing,
              "parse_unknown": unknown, "owner_source_reviews": reviews,
              "not_proven": ["application Jar/startup/tests", "business ownership", "full graph or baseline approval"]}
    producer.write_json(folder / "result.json", report)
    require(complete, "actual class/interface leaf shape unconfirmed; never exempt missing rows")
    return report


def variant(producer, policy, inputs, libs, folder, profile, source, negative, env, tools):
    module, source_class, target_class, owner = profile
    folder.mkdir(parents=True)
    src = folder / "src" / (source_class.replace(".", "/") + ".java")
    src.parent.mkdir(parents=True)
    src.write_bytes(control_source(source, target_class) if negative else source)
    classes = folder / "classes"
    classes.mkdir()
    cp = os.pathsep.join(map(str, [*inputs, *sorted(libs.glob("*.jar"))]))
    compiled = tool([tools["javac"], "--release", "21", "-parameters", "-g", "-proc:none", "-implicit:none",
                     "-sourcepath", "", "-encoding", "UTF-8", "-classpath", cp, "-d", classes, src], folder, "javac", env)
    require(compiled.returncode == 0, "javac failed; this is NOT a boundary negative result")
    replacement = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
    require(replacement and all(n[:-6].replace("/", ".") == source_class or
            n[:-6].replace("/", ".").startswith(source_class + "$") for n in replacement), "unexpected javac output family")
    require(source_class.replace(".", "/") + ".class" in replacement, "actual scoped class not compiled")
    original = next(p for p in inputs if p.stem == module)
    case_jar = folder / original.name
    with zipfile.ZipFile(original) as archive, zipfile.ZipFile(case_jar, "w") as output:
        old_family = {n for n in archive.namelist() if n.endswith(".class") and
                      (n[:-6].replace("/", ".") == source_class or n[:-6].replace("/", ".").startswith(source_class + "$"))}
        require(old_family == set(replacement), "compiled class family differs from actual base module")
        for entry in archive.infolist():
            output.writestr(entry, replacement.get(entry.filename, archive.read(entry)))
    case_inputs = [case_jar if p == original else p for p in inputs]
    index = {}
    for path in case_inputs:
        for name, sha in producer.class_entries(path.read_bytes()).items():
            require(name not in index, "duplicate actual class owner")
            index[name] = {"owner": path.stem, "sha256": sha}
    producer.write_json(folder / "class-index.json", index)
    cp = os.pathsep.join(map(str, [*case_inputs, *sorted(libs.glob("*.jar"))]))
    result = tool([tools["jdeps"], "--multi-release", "21", "-verbose:class", "-filter:none", "--class-path", cp,
                   *case_inputs], folder, "jdeps", env)
    roots = {n for o in policy["owners"].values() for n in o["members"] + o["forbidden_backrefs"]}
    selected = {n: {} for n in index if any(n == r or n.startswith(r + "$") for r in roots)}
    source_rows = set()
    _, _, missing, unknown, seen = producer.parse_output(result.stdout, index, case_inputs, selected, source_rows)
    row_reviews = producer.owner_source_reviews(policy, source_rows)
    source_observed = source_class in source_rows
    complete = result.returncode == 0 and not result.stderr.strip() and not missing and not unknown and not row_reviews and source_observed and seen == {p.stem for p in inputs}
    verdict = producer.technical_dependency_findings(index, selected, policy, complete)
    exact = [f for f in verdict["findings"] if f["rule"] == "OWNER_GOD_BACKREFERENCE" and
             f["source_class"] == source_class and f["target_class"] == target_class and
             f["source_technical_owner"] == owner and f["source_sha256"] == index[source_class]["sha256"] and
             f["target_sha256"] == index[target_class]["sha256"]]
    report = {"kind": "DERIVED_REAL_JAVAC_CONTROL_NOT_TESTED_APPLICATION", "negative": negative,
              "source_class": source_class, "target_class": target_class,
              "original_git_source_sha256": producer.shared.digest(source), "compiled_source_sha256": producer.shared.digest(src.read_bytes()),
              "classpath_scope": "CONTROL_ONLY_FULL_REACTOR; no production POM/dependency change",
              "inputs": [{"path": str(p), "sha256": producer.shared.digest(p.read_bytes())} for p in case_inputs],
              "missing_dependencies": missing, "parse_unknown": unknown, "verdict": verdict,
              "actual_control_source_rows_observed": source_observed,
              "owner_source_reviews": row_reviews,
              "expected_raw_reference": selected.get(target_class, {}).get(source_class + "->" + target_class)}
    producer.write_json(folder / "result.json", report)
    require(complete and verdict["scope_complete"], "incomplete actual control input/raw; UNKNOWN, never an expected red")
    if negative:
        if not (verdict["verdict"] == "BLOCKED" and exact and report["expected_raw_reference"]):
            raise ControlFailure("expected exact forbidden edge did not block")
    else:
        if not (verdict["verdict"] == "PASS_DECLARED_OWNER_DIRECTIONS" and not report["expected_raw_reference"]):
            raise ControlFailure("restored real source did not return to scoped green")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source", "verifier", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("artifact", "identity", "observation"):
        parser.add_argument("--" + name, type=Path)
    parser.add_argument("--leaf-only", action="store_true", help="real empty-class/interface raw check; not a main artifact acceptance")
    args = parser.parse_args()
    root, output = args.source.resolve(), args.output.resolve()
    require(output.is_relative_to(root / "chengyinhub-admin/target"), "control output must be under ignored admin target")
    require(not output.exists(), "fresh control output required")
    output.mkdir(parents=True)
    report = {"kind": "REAL_JDK21_LEAF_ONLY_NOT_APPLICATION_ACCEPTANCE" if args.leaf_only else "REAL_COMPILED_BOUNDARY_CONTROLS",
              "verdict": "UNKNOWN", "cases": {},
              "not_proven": ["main candidate tests/startup", "production Common-to-Framework Maven build", "runtime/full graph or baseline approval"]}
    producer = None
    artifact_hash = None
    try:
        env = dict(os.environ)
        require_ci(env)  # Full controls additionally require original artifact/evidence verification.
        producer = load_verifier(args.verifier.resolve())
        policy = producer.shared.technical_dependency_policy()
        head = git(root, "rev-parse", "HEAD").decode().strip()
        require(head == env["GITHUB_SHA"] and not git(root, "status", "--porcelain", "--untracked-files=all").strip(), "actual clean CI source required")
        report["ci_identity"] = {"source_sha": head, "source_tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip(),
                                 "run_id": env["GITHUB_RUN_ID"], "run_attempt": env["GITHUB_RUN_ATTEMPT"]}
        report["verifier_sha"] = VERIFIER
        report["policy_sha256"] = producer.shared.digest((args.verifier / "ci/architecture/owners.json").read_bytes())
        report["tool_sha256"] = producer.shared.digest(Path(__file__).read_bytes())
        if args.leaf_only:
            tools = jdk_tools(producer, output, env, report)
            report["phase"] = "leaf-shape"
            leaf_shape(producer, output, env, tools)
            report["leaf_result"] = "leaf-shape/result.json"
            require(git(root, "rev-parse", "HEAD").decode().strip() == head and
                    not git(root, "status", "--porcelain", "--untracked-files=all").strip(), "leaf control changed source")
            report["verdict"] = "LEAF_SHAPE_CONFIRMED"
        else:
            require(all((args.artifact, args.identity, args.observation)), "actual original artifact/identity/observation required")
            artifact, identity, observation = [p.resolve() for p in (args.artifact, args.identity, args.observation)]
            identity_value = json.loads(identity.read_text())
            require(identity_value.get("startupPassedInThisJob") is True, "original Backend build/startup identity required")
            report["base_identity"] = producer.bind_identity(root, artifact, identity_value, env)
            artifact_hash = producer.shared.digest(artifact.read_bytes())
            git(root, "merge-base", "--is-ancestor", BASE, "HEAD")
            base_check = producer.check_private_ports(root, artifact, identity, observation, env,
                                                      producer.shared.business_policy()["private_ports"], policy)
            producer.write_json(output / "original-post-check.json", base_check)
            require(base_check["verdict"] == "PASS_SCOPED_COMPILED_REFERENCES", "base actual JAR/raw/index not verified green; controls NOT_RUN")
            staged, libs = output / "base", output / "external"
            staged.mkdir(); libs.mkdir()
            base_inputs = {"artifacts": {}}
            _, _, inputs = producer.stage_inputs(root, artifact, staged, libs, base_inputs)
            producer.write_json(staged / "artifacts.json", base_inputs)
            tools = jdk_tools(producer, output, env, report)
            report["phase"] = "leaf-shape"
            leaf_shape(producer, output, env, tools)
            report["leaf_result"] = "leaf-shape/result.json"
            for name, profile in CASES.items():
                module, source_class, target_class, owner = profile
                require(source_class in policy["owners"][owner]["members"] and target_class in policy["owners"][owner]["forbidden_backrefs"], "fixture does not match frozen policy")
                source = git(root, "show", "HEAD:" + module + "/src/main/java/" + source_class.replace(".", "/") + ".java")
                report["cases"][name] = {}
                for negative in (True, False):
                    label = "negative" if negative else "restored"
                    report["phase"] = name + "/" + label
                    result = variant(producer, policy, inputs, libs, output / name / label, profile, source, negative, env, tools)
                    report["cases"][name][label] = {"scope_verdict": result["verdict"]["verdict"], "result": name + "/" + label + "/result.json"}
            require(producer.shared.digest(artifact.read_bytes()) == artifact_hash and
                    git(root, "rev-parse", "HEAD").decode().strip() == head and
                    not git(root, "status", "--porcelain", "--untracked-files=all").strip(), "base artifact/source was changed")
            report["verdict"] = "EXPECTED_RED_THEN_RESTORED"
            report["original_tested_jar_preserved"] = True
    except ControlFailure as error:
        report["verdict"] = "CONTROL_FAILED"
        report["error"] = str(error)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        report["error"] = str(error)
    finally:
        # No fabricated class/index/raw evidence is ever produced on unavailable inputs.
        if producer is not None:
            report["evidence_sha256"] = {p.relative_to(output).as_posix(): producer.shared.digest(p.read_bytes())
                                        for p in sorted(output.rglob("*")) if p.is_file() and p.name != "controls.json"}
        (output / "controls.json").write_text(json.dumps(report, indent=2) + "\n")
    print("boundary controls: " + report["verdict"])
    return 0 if report["verdict"] in ("EXPECTED_RED_THEN_RESTORED", "LEAF_SHAPE_CONFIRMED") else 1 if report["verdict"] == "CONTROL_FAILED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
