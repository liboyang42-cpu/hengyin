#!/usr/bin/env python3
"""Small, conservative source/evidence gate. No Java/SQL semantic safety claim.

Run this file from a trusted gate checkout, with --source pointing at the
candidate checkout. Policy changes require a separate reviewed gate revision.
"""
from __future__ import annotations

import argparse
import collections
import functools
import hashlib
import importlib.util
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

BASE = "2ec68899099a1e250df0949a8383bf85b5d3ffe0"
BOUNDARY_BASE = "477696c8eb2fa24bd3d7febc896415bf0d1b67c0"
HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
SQL_SPLITTER_PATH = REPO / "ci/sql_splitter.py"
JAVA_MASK_PATH = REPO / "ci/real_storage_it.py"
SHA = re.compile(r"[0-9a-f]{40}\Z")
IDENT = r"[A-Za-z_][A-Za-z0-9_]*"
MAPPER = re.compile(r"chengyinhub-[^/]+/src/main/resources/mapper/.+\.xml\Z")
BAD_CASE = {"skipped", "failure", "error", "flakyFailure", "flakyError",
            "rerunFailure", "rerunError"}


class GateError(ValueError):
    pass


# Reviewed public checklist sources. Unlisted IDs and candidate-selected revisions never resolve.
COMMAND_SOURCES = {
    "registration-create-or-replay": BASE,
    "payment-callback-mutation": BASE,
    "registration-refund-dispatch": BASE,
    "coop-deposit-refund": BASE,
    "coop-original-produce": "54c524ca99204804b6b84af517359eabc8c6ee8d",
    "npc-asr-original-quota-handoff": "379d5615608663c47302598ba238447b264c2bba",
    "coop-original-manual-read": "e57f0a32037bf88b2abf1626cca4760714d88592",
}


def reviewed_command_source(command):
    """Bind checklist references to independent public source, never current candidate files."""
    expected = COMMAND_SOURCES.get(command.get("id"))
    declared = command.get("source_sha", BASE)
    if expected is None or declared != expected or not isinstance(declared, str) or not SHA.fullmatch(declared):
        raise GateError("COMMAND_SOURCE_MISMATCH: unreviewed checklist source")
    return expected


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        raise GateError("git %s failed: %s" % (args[0], result.stderr.decode(errors="replace").strip()))
    return result.stdout


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(data):
    return (json.dumps(data, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def encode_inventory(data):
    # One exact record per line: generated inventory stays inspectable without
    # tens of thousands of repeated pretty-print lines.
    lines = ["{"]
    for index, (key, value) in enumerate(sorted(data.items())):
        prefix = "  " + json.dumps(key) + ": "
        suffix = "," if index + 1 < len(data) else ""
        if isinstance(value, dict):
            lines.append(prefix + "{")
            for number, (name, record) in enumerate(sorted(value.items())):
                lines.append("    " + json.dumps(name) + ": " +
                             json.dumps(record, ensure_ascii=False, sort_keys=True) +
                             ("," if number + 1 < len(value) else ""))
            lines.append("  }" + suffix)
        else:
            lines.append(prefix + json.dumps(value) + suffix)
    return ("\n".join(lines) + "\n}\n").encode()


def tree(root, ref):
    # Immutable git blobs, never files from a possibly dirty checkout.
    if not SHA.fullmatch(ref):
        raise GateError("candidate must be a full lowercase commit SHA")
    names = git(root, "ls-tree", "-rz", "--name-only", ref).decode().split("\0")
    return [name for name in names if name]


def blob(root, ref, path):
    return git(root, "show", "%s:%s" % (ref, path))


def xml(data):
    if b"<!ENTITY" in data.upper():
        raise GateError("XML entity declarations are unsupported")
    try:
        return ET.fromstring(data)
    except ET.ParseError as error:
        raise GateError("invalid XML: %s" % error) from error


@functools.lru_cache(maxsize=1)
def sql_splitter():
    # The existing project scanner owns quoting/comments; no second SQL lexer.
    spec = importlib.util.spec_from_file_location("architecture_sql_splitter", SQL_SPLITTER_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def locking_select(node, namespace):
    """Only a static, single SELECT with an explicit terminal FOR UPDATE.

    Attribute safety is not a complete fresh-read or SQL correctness proof.
    Includes/dynamic SQL stay UNKNOWN even if their expanded text looks static.
    """
    # C14N normalizes XML attribute order/line endings, preserving SQL literal
    # whitespace. Exclude the outside tail: it belongs to the next XML sibling.
    clean = ET.Element(node.tag, node.attrib)
    clean.text = node.text
    clean.extend(node)
    canonical = ET.tostring(clean, encoding="unicode")
    record = {"namespace": namespace, "id": node.get("id"),
              "body_sha256": digest(ET.canonicalize(canonical, exclude_attrs={"id", "useCache", "flushCache"}).encode()),
              "statement_sha256": digest(ET.canonicalize(canonical).encode()),
              "useCache": node.get("useCache"), "flushCache": node.get("flushCache")}
    text = node.text or ""
    reasons = []
    if list(node):
        reasons.append("include_or_dynamic_xml")
    if "${" in text:
        reasons.append("text_substitution")
    # Replace only MyBatis value placeholders before using the shared scanner,
    # whose documented '#' line-comment limitation otherwise applies here.
    syntax_input = re.sub(r"#\{[^{}]*\}", "?", text)
    if "#" in syntax_input or "/*!" in text or "/*+" in text:
        reasons.append("unsupported_sql_token_or_executable_comment")
    splitter = sql_splitter()
    if splitter.unterminated_token(syntax_input) or splitter.delimiter_error(syntax_input):
        reasons.append("unterminated_or_delimiter_sql")
    code = splitter.split_code_statements_with_delimiters(syntax_input)
    syntax = code[0][0] if len(code) == 1 else ""
    if len(code) != 1 or not re.match(r"\s*select\b", syntax, re.I):
        reasons.append("not_single_literal_select")
    lock = re.search(r"\bfor\s+update\b", syntax, re.I)
    if lock and not re.search(r"\bfor\s+update(?:\s+nowait|\s+skip\s+locked)?\s*$", syntax, re.I):
        reasons.append("unsupported_lock_suffix")
    record.update(analysis="UNKNOWN" if reasons else "LOCKING_SELECT" if lock else "NOT_LOCKING_SELECT",
                  unknown=sorted(set(reasons)))
    return record


def locking_debt_proposal(frozen, details):
    """Public-base observation for human approval, NEVER an exemption list."""
    return {"schema": 1, "base_sha": frozen["base_sha"],
            "status": "PROPOSAL_NOT_APPROVED_NOT_AN_ALLOWLIST",
            "normalization": "XML C14N; SQL whitespace/literals preserved; namespace/id explicit",
            "statements": {key: details[key]["locking_select"]
                           for key, statement in sorted(frozen["statements"].items())
                           if statement["kind"] == "select"
                           and details[key]["locking_select"]["analysis"] == "LOCKING_SELECT"
                           and (details[key]["locking_select"]["useCache"] != "false"
                                or details[key]["locking_select"]["flushCache"] != "true")}}


def sql_shape(node, fragments, namespace, details=None):
    """Fingerprint statement + transitive includes; hints are NOT SQL parsing.

    Dynamic tags, substitutions and incomplete literal shapes remain UNKNOWN.
    Even LITERAL_SHAPE does not establish business authorization or atomicity.
    """
    dependencies = {}
    unknown = set()

    def expand(item, stack=(), current_namespace=namespace):
        parts = [item.text or ""]
        for child in item:
            if child.tag == "include":
                ref = child.get("refid", "")
                ref = ref if "." in ref else current_namespace + "." + ref
                if ref in stack or ref not in fragments:
                    unknown.add("unresolved_or_cyclic_include:" + ref)
                else:
                    fragment = fragments[ref]
                    dependencies[ref] = digest(ET.tostring(fragment))
                    parts.append(expand(fragment, stack + (ref,), ref.rsplit(".", 1)[0]))
                if list(child):
                    unknown.add("include_properties")
            else:
                unknown.add("dynamic_tag:" + child.tag)
                parts.append(expand(child, stack, current_namespace))
            parts.append(child.tail or "")
        return " ".join(parts)

    text = expand(node)
    if "${" in text:
        unknown.add("text_substitution")
    hints = sorted(set(re.findall(
        r"\b(?:update|insert\s+into|delete\s+from|replace\s+into)\s+`?(%s)`?" % IDENT,
        text, re.I)))
    # Only SET's left-hand sides; WHERE predicate columns are not write fields.
    assignment = re.search(r"\bset\b(.*?)(?:\bwhere\b|$)", text, re.I | re.S)
    columns = sorted(set(re.findall(r"\b(%s)\s*=" % IDENT, assignment.group(1)))) if assignment else []
    # INSERT column hints only for an immediately following literal column list.
    match = re.search(r"\binsert\s+into\s+`?%s`?\s*\(([^)]+)\)" % IDENT, text, re.I)
    if match:
        columns = sorted(set(columns + re.findall(IDENT, match.group(1))))
    if len(hints) != 1 or node.tag not in {"insert", "update", "delete"}:
        unknown.add("unsupported_sql_shape")
    if details is not None:
        details.update(text=text, leading=node.text or "", unknown=sorted(unknown))
    fingerprint = digest(ET.tostring(node) + encode(dependencies))
    return {"sha256": fingerprint, "table_hints": hints, "column_hints": columns,
            "analysis": "UNKNOWN" if unknown else "LITERAL_SHAPE_ONLY",
            "unknown": sorted(unknown)}


def inventory(root, ref, sql_details=None):
    names = tree(root, ref)
    mappers, fragments = {}, {}
    poms = {}
    for path in names:
        if MAPPER.fullmatch(path):
            node = xml(blob(root, ref, path))
            if node.tag != "mapper" or not node.get("namespace"):
                raise GateError("invalid mapper: " + path)
            mappers[path] = node
            for fragment in node.findall("sql"):
                key = node.get("namespace") + "." + fragment.get("id", "")
                if key in fragments:
                    raise GateError("duplicate SQL fragment: " + key)
                fragments[key] = fragment
        if path == "pom.xml" or re.fullmatch(r"chengyinhub-[^/]+/pom.xml", path):
            pom = xml(blob(root, ref, path))
            # Compare complete dependency declarations, including versions/scopes,
            # dependencyManagement and plugin dependencies. Multiplicity retained.
            poms[path] = sorted(ET.tostring(dep).decode().strip()
                                for dep in pom.iter() if dep.tag.split("}")[-1] == "dependency")
    statements, owners = {}, {}
    for path, mapper in sorted(mappers.items()):
        seen = set()
        namespace = mapper.get("namespace")
        owner = path.split("/")[0] + ":" + namespace
        for node in mapper:
            if node.tag not in {"insert", "update", "delete", "select"}:
                continue
            name = node.get("id")
            if not name or name in seen:
                raise GateError("missing/duplicate statement id: " + path)
            seen.add(name)
            details = {}
            shape = sql_shape(node, fragments, namespace, details)
            # Selects are fingerprinted too: SQL can mutate via select/affectData.
            shape.update({"kind": node.tag, "owner": owner})
            statements[path + "#" + name] = shape
            if sql_details is not None:
                if node.tag == "select":
                    details["locking_select"] = locking_select(node, namespace)
                sql_details[path + "#" + name] = details
            if node.tag == "select":
                continue
            for table in shape["table_hints"]:
                record = owners.setdefault(table, {})
                record.setdefault(owner, set()).update(shape["column_hints"])
    return {"schema": 1, "base_sha": ref, "dependencies": poms,
            "statements": statements,
            "observed_table_field_writers": {
                table: {owner: sorted(cols) for owner, cols in sorted(records.items())}
                for table, records in sorted(owners.items())}}


def dependency_counts(declarations):
    """Maven coordinates + scope; whitespace/order/version changes are not additions."""
    result = collections.Counter()
    for declaration in declarations:
        dep = xml(declaration.encode())
        fields = {child.tag.split("}")[-1]: (child.text or "").strip() for child in dep}
        key = (fields.get("groupId", ""), fields.get("artifactId", ""),
               fields.get("type", "jar"), fields.get("classifier", ""))
        result[(key, fields.get("scope", "compile"))] += 1
    return result


def literal_sql_prefix(details):
    """Reuse the SQL scanner for ordinary comments, preserving quoted tokens.

    Only the fixed prefix before dynamic XML/substitution is evidence. This is
    not a complete target/effects parser; executable comments remain UNKNOWN.
    Value placeholders must not be mistaken for '#' comment syntax.
    """
    dynamic = [reason for reason in details["unknown"] if reason != "unsupported_sql_shape"]
    text = details["leading"] if dynamic else details["text"]
    text = re.sub(r"#\{[^{}]*\}", "?", text)
    splitter = sql_splitter()
    if splitter.delimiter_error(text):
        return ""
    statements = splitter.split_statements_with_delimiters(text)
    return statements[0][0] if statements else ""


def literal_write_operation(details):
    mutation = re.match(r"\s*(insert|replace|update|delete)\b", literal_sql_prefix(details), re.I)
    return mutation.group(1).lower() if mutation else None


def literal_write_target(kind, details):
    # Determine the real literal operation even when the MyBatis tag differs.
    # Only single-table leading forms, not regex table hints or dynamic effects.
    text = literal_sql_prefix(details)
    operation = literal_write_operation(details)
    target = r"(?:`(?P<quoted>" + IDENT + r")`|(?P<plain>" + IDENT + r"))"
    patterns = {
        "update": r"^\s*update\s+(?:low_priority\s+)?(?:ignore\s+)?" + target + r"(?=\s+set\b|\s*$)",
        "delete": r"^\s*delete\s+(?:low_priority\s+)?(?:quick\s+)?(?:ignore\s+)?from\s+" + target + r"(?=\s+where\b|\s*$)",
        "insert": r"^\s*insert\s+(?:(?:low_priority|delayed|high_priority)\s+)?(?:ignore\s+)?(?:into\s+)?" + target + r"(?=\s|\(|$)",
        "replace": r"^\s*replace\s+(?:(?:low_priority|delayed)\s+)?(?:into\s+)?" + target + r"(?=\s|\(|$)",
    }
    match = re.match(patterns.get(operation, r"(?!)"), text, re.I)
    return (match.group("quoted") or match.group("plain")) if match else None


def scope_violation(kind, details):
    """Recognize only a literal broad UPDATE/DELETE. No authorization claim."""
    dynamic = [reason for reason in details["unknown"] if reason != "unsupported_sql_shape"]
    if kind not in {"update", "delete"} or dynamic:
        return None
    # Reuse the project's existing quote/comment-aware SQL scanner.
    splitter = sql_splitter()
    text = details["text"]
    if splitter.unterminated_token(text) or splitter.delimiter_error(text) or "/*!" in text:
        return None
    code = splitter.split_code_statements_with_delimiters(text)
    if len(code) != 1:
        return None
    syntax = code[0][0]
    if re.search(r"\b(select|join|union|call)\b", syntax, re.I):
        return None
    where = re.search(r"\bwhere\b(.*)", syntax, re.I | re.S)
    if where is None:
        return "literal UPDATE/DELETE has no WHERE scope"
    if re.fullmatch(r"\s*(?:1\s*=\s*1|true)\s*", where.group(1), re.I):
        return "literal UPDATE/DELETE has an unconditional WHERE"
    return None


def business_policy():
    """Reviewed whole-table facts/pure writers and exact private compiled ports.

    Private ports do not infer new business facts, table/field owners or commands.

    Both files come from HERE (the independent trusted verifier), never candidate
    observations. check_source protects their exact bytes before using them.
    """
    def require(condition):
        if not condition:
            raise GateError("BUSINESS_POLICY_INVALID: expected explicit whole-table facts/commands/pure writers")

    def name(value, pattern):
        return isinstance(value, str) and re.fullmatch(pattern, value) is not None

    try:
        owners = json.loads((HERE / "owners.json").read_text())
        commands = json.loads((HERE / "commands.json").read_text())["business_commands"]
        policy = owners["business_policy"]
        require(set(policy) == {"schema", "coverage", "scope", "facts", "pure_writers", "private_ports"})
        require(type(policy["schema"]) is int and policy["schema"] == 1 and policy["coverage"] == "PARTIAL")
        facts, writers, entries, ports = {}, {}, {}, {}
        require(isinstance(policy["facts"], list) and policy["facts"])
        for fact in policy["facts"]:
            require(set(fact) == {"id", "table", "coverage", "business_owner"})
            require(name(fact["id"], r"[A-Z][A-Z0-9_]*") and name(fact["table"], IDENT))
            require(name(fact["business_owner"], r"[A-Z][A-Z0-9_]*") and fact["coverage"] == "whole_table")
            require(fact["table"] not in facts and fact["id"] not in {f["id"] for f in facts.values()})
            facts[fact["table"]] = fact
        require(isinstance(policy["scope"], list) and sorted(policy["scope"]) == sorted(f["id"] for f in facts.values()))
        require(isinstance(commands, list) and commands)
        for command in commands:
            require(set(command) == {"id", "business_owner", "writes", "public_methods"})
            require(name(command["id"], r"[a-z][a-z0-9-]*") and command["id"] not in entries)
            require(name(command["business_owner"], r"[A-Z][A-Z0-9_]*"))
            require(isinstance(command["writes"], list) and command["writes"] and len(set(command["writes"])) == len(command["writes"]))
            require(all(any(f["id"] == id_ and f["business_owner"] == command["business_owner"] for f in facts.values()) for id_ in command["writes"]))
            require(isinstance(command["public_methods"], list) and command["public_methods"])
            require(all(name(method, r"(?:" + IDENT + r"\.)+" + IDENT + "#" + IDENT) for method in command["public_methods"]))
            entries[command["id"]] = command
        require(isinstance(policy["pure_writers"], list) and policy["pure_writers"])
        for writer in policy["pure_writers"]:
            require(set(writer) == {"module", "namespace", "business_owner", "command_id"})
            require(writer["module"] in owners["modules"] and name(writer["namespace"], r"(?:" + IDENT + r"\.)+" + IDENT))
            key = writer["module"] + ":" + writer["namespace"]
            require(key not in writers and writer["command_id"] in entries)
            require(writer["business_owner"] == entries[writer["command_id"]]["business_owner"])
            writers[key] = writer
        require(all(sum(f["id"] in entries[w["command_id"]]["writes"] for w in writers.values()) == 1 for f in facts.values()))
        require(isinstance(policy["private_ports"], list) and len(policy["private_ports"]) == 3)
        for port in policy["private_ports"]:
            require(set(port) == {"target", "owner", "consumer"})
            require(port["owner"] in owners["modules"])
            require(all(name(port[field], r"(?:" + IDENT + r"\.)+" + IDENT) for field in ("target", "consumer")))
            writer = writers.get(port["owner"] + ":" + port["target"])
            if writer is not None:
                require(port["consumer"] in {m.split("#")[0] for m in entries[writer["command_id"]]["public_methods"]})
            require(port["target"] not in ports)
            ports[port["target"]] = {"owner": port["owner"], "consumer": port["consumer"]}
        # A namespace writer and a one-consumer private port are distinct contracts.
        # Keep all three private ports; shared intent storage needs explicit exact consumers instead.
        for writer in writers.values():
            if writer["namespace"] in ports:
                require(ports[writer["namespace"]]["owner"] == writer["module"])
                continue
            if "technical_dependency_policy" not in owners:
                raise GateError("OWNER_DEPENDENCY_POLICY_INVALID: missing shared storage binding")
            storage = [o for o in owners["technical_dependency_policy"]["owners"]
                       if o["module"] == writer["module"] and o["members"] == [writer["namespace"]]]
            require(len(storage) == 1 and bool(storage[0].get("allowed_consumers")))
            require({m.split("#")[0] for m in entries[writer["command_id"]]["public_methods"]}
                    <= set(storage[0]["allowed_consumers"]))
        return {"facts": facts, "writers": writers, "commands": entries, "private_ports": ports}
    except GateError:
        raise
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        raise GateError("BUSINESS_POLICY_INVALID: " + str(error)) from error



def technical_dependency_policy():
    """Trusted explicit class cut only; never infer business owners from packages."""
    def require(value):
        if not value:
            raise GateError("OWNER_DEPENDENCY_POLICY_INVALID")
    try:
        policy = json.loads((HERE / "owners.json").read_text())["technical_dependency_policy"]
        require(set(policy) == {"schema", "coverage", "observed_source", "meaning", "owners", "allowed_directions", "exact_directions"})
        require(type(policy["schema"]) is int and policy["schema"] == 1 and policy["coverage"] == "PARTIAL")
        require(isinstance(policy["observed_source"], str) and SHA.fullmatch(policy["observed_source"]))
        modules = json.loads((HERE / "owners.json").read_text())["modules"]
        require(isinstance(policy["owners"], list) and policy["owners"])
        owners, members = {}, {}
        fqcn = r"(?:" + IDENT + r"\.)+" + IDENT
        # Only the actual G rollback callback; retain the old grammar for every other binding.
        rollback_callback = "com.chengyinhub.business.service.impl.CoopSettlementManualCases$1"
        for owner in policy["owners"]:
            fields = {"id", "module", "members", "forbidden_backrefs"}
            require(fields <= set(owner) and set(owner) <= fields | {"restricted_packages", "allowed_consumers"})
            if "allowed_consumers" in owner:
                consumers = owner["allowed_consumers"]
                require(len(owner["members"]) == 1 and isinstance(consumers, list) and consumers
                        and len(set(consumers)) == len(consumers))
                require(all(isinstance(n, str) and (re.fullmatch(fqcn, n) or n == rollback_callback) for n in consumers))
            restricted = owner.get("restricted_packages", {})
            require(isinstance(restricted, dict))
            for package, exceptions in restricted.items():
                require(isinstance(package, str) and re.fullmatch(fqcn + r"\.", package))
                require(isinstance(exceptions, list) and len(exceptions) == len(set(exceptions)))
                require(all(isinstance(n, str) and re.fullmatch(fqcn + r"(?:\$" + IDENT + r")*", n)
                            and n.startswith(package) for n in exceptions))
            id_ = owner["id"]
            require(isinstance(id_, str) and re.fullmatch(r"[A-Z][A-Z0-9_]*", id_) and id_ not in owners)
            require(owner["module"] in modules)
            for field in ("members", "forbidden_backrefs"):
                require(isinstance(owner[field], list) and owner[field] and len(owner[field]) == len(set(owner[field])))
                require(all(isinstance(n, str) and (re.fullmatch(fqcn, n) or (field == "members" and n == rollback_callback)) for n in owner[field]))
            for member in owner["members"]:
                require(member not in members)
                members[member] = id_
            owners[id_] = owner
        require(isinstance(policy["allowed_directions"], list))
        edges = set()
        for edge in policy["allowed_directions"]:
            require(isinstance(edge, list) and len(edge) == 2 and all(n in owners for n in edge) and edge[0] != edge[1])
            require(tuple(edge) not in edges)
            edges.add(tuple(edge))
        # These four new contracts approve exact binary references, not every
        # member/companion within an owner group. Missing limits cannot disable them.
        required_exact = {("G_CASH_CLAIM", "B_VERIFIED_PAYMENT_FACT_DTO"),
                          ("B_PAYMENT_ROUTER", "B_VERIFIED_PAYMENT_FACT_DTO"),
                          ("B_VERIFIED_PAYMENT_FACT_STORE", "B_PAYMENT_ORDER_LOCK"),
                          ("B_PAYMENT_INTENTS", "B_VERIFIED_PAYMENT_FACT_STORE")}
        require(isinstance(policy["exact_directions"], list))
        exact = {}
        binary = fqcn + r"(?:\$[A-Za-z0-9_]+)*"
        for contract in policy["exact_directions"]:
            require(set(contract) == {"source_owner", "target_owner", "references"})
            edge = (contract["source_owner"], contract["target_owner"])
            require(edge in required_exact and edge in edges and edge not in exact)
            references = contract["references"]
            require(isinstance(references, list) and references)
            pairs = set()
            for reference in references:
                require(isinstance(reference, list) and len(reference) == 2
                        and all(isinstance(n, str) and re.fullmatch(binary, n) for n in reference))
                require(tuple(reference) not in pairs)
                require(all(any(name == member or name.startswith(member + "$")
                                for member in owners[owner]["members"])
                            for name, owner in zip(reference, edge)))
                pairs.add(tuple(reference))
            exact[edge] = pairs
        require(set(exact) == required_exact)
        # This new responsibility cut is explicitly acyclic. Do not bless a
        # candidate-generated SCC or reclassify global legacy debt as approved.
        pending = set(owners)
        while pending:
            leaves = {n for n in pending if not any(a == n and b in pending for a, b in edges)}
            require(leaves)
            pending -= leaves
        return {"owners": owners, "allowed_directions": edges, "exact_directions": exact,
                "observed_source": policy["observed_source"]}
    except GateError:
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        raise GateError("OWNER_DEPENDENCY_POLICY_INVALID: " + str(error)) from error


def business_write_findings(key, statement, kind, details, previous, previous_details, policy):
    """Reuse literal targets, not column hints; compare only this statement edge.

    A fixed leading table can be certain while dynamic fields/effects remain
    UNKNOWN. No method call graph, semantic SQL parser, or field policy here.
    """
    table = literal_write_target(kind, details)
    review, blockers = [], []
    if not table or details["unknown"]:
        review.append({"key": key, "reason": "UNKNOWN_BUSINESS_WRITE_EFFECT",
                       "detail": {"literal_target": table, "unknown": details["unknown"],
                                  "not_proven": "complete targets/fields/branches and service call paths"}})
    if table not in policy["facts"]:
        if table:
            review.append({"key": key, "reason": "BUSINESS_WRITE_OUTSIDE_POLICY", "detail": table})
        return blockers, review

    def edge(record, info):
        if not record:
            return None
        operation = record["kind"]
        # Preserve REPLACE vs INSERT as distinct capability, including select
        # tags with a literal leading DML. Complex/unknown SQL is not proven equal.
        operation = literal_write_operation(info) or operation
        target_kind = "insert" if operation == "replace" else operation
        return record["owner"], operation, literal_write_target(target_kind, info)

    current_edge = edge(statement, details)
    if current_edge == edge(previous, previous_details):
        return blockers, review  # Existing debt is not new permission or baseline.
    fact = policy["facts"][table]
    writer = policy["writers"].get(statement["owner"])
    rule = None
    if writer is None:
        rule = "UNDECLARED_WRITE_BINDING"
    elif (writer["business_owner"] != fact["business_owner"]
          or fact["id"] not in policy["commands"][writer["command_id"]]["writes"]):
        rule = "NEW_BUSINESS_OWNER_WRITE"
    if rule:
        blockers.append({"rule": rule, "key": key,
                         "detail": {"fact_id": fact["id"], "table": table, "operation": current_edge[1],
                                    "expected_business_owner": fact["business_owner"],
                                    "technical_writer": statement["owner"],
                                    "declared_business_owner": writer["business_owner"] if writer else None}})
    return blockers, review


def statement_write_owners():
    """Two reviewed recovery edges, never a namespace/table-wide waiver or SQL debt baseline."""
    path = "chengyinhub-system/src/main/resources/mapper/business/WechatVerifiedPaymentFactMapper.xml"
    expected = {path + "#insertRecovery": "insert", path + "#completeRecovery": "update"}
    try:
        records = json.loads((HERE / "owners.json").read_text())["statement_write_owners"]
        if not isinstance(records, list) or len(records) != 2:
            raise GateError("STATEMENT_OWNER_POLICY_INVALID")
        bindings = {}
        for record in records:
            if (set(record) != {"key", "owner", "table", "kind", "sha256", "reviewed_source", "contract"}
                    or record["key"] not in expected or record["key"] in bindings
                    or record["kind"] != expected[record["key"]]
                    or record["owner"] != "chengyinhub-system:com.chengyinhub.business.mapper.WechatVerifiedPaymentFactMapper"
                    or record["table"] != "wx_payment_callback_outbox"
                    or record["contract"] != "B_ORIGINAL_FACT_RECOVERY"
                    or not re.fullmatch(r"[0-9a-f]{64}", record["sha256"])
                    or not SHA.fullmatch(record["reviewed_source"])):
                raise GateError("STATEMENT_OWNER_POLICY_INVALID")
            bindings[record["key"]] = record
        return bindings
    except (OSError, ValueError, TypeError, KeyError) as error:
        raise GateError("STATEMENT_OWNER_POLICY_INVALID: " + str(error)) from error


def static_findings(base, current, changed, sql_details, base_details, policy=None, statement_owners=None):
    blockers, review = [], []
    statement_owners = statement_owners or {}
    limited_edges = {(r["table"], r["owner"]) for r in statement_owners.values()}
    known_owners = {}
    for key, statement in base["statements"].items():
        table = literal_write_target(statement["kind"], base_details[key])
        if table:
            known_owners.setdefault(table, set()).add(statement["owner"])
    for pom, dependencies in current["dependencies"].items():
        previous = dependency_counts(base["dependencies"].get(pom, []))
        actual = dependency_counts(dependencies)
        uses = {"compile": {"compile", "runtime", "test"}, "provided": {"compile", "test"},
                "runtime": {"runtime", "test"}, "test": {"test"}, "system": {"compile", "test"}}
        old_remaining, new_remaining = previous - actual, actual - previous
        old_coordinates = collections.Counter()
        new_coordinates = collections.Counter()
        for (coordinate, _), count in previous.items():
            old_coordinates[coordinate] += count
        for (coordinate, _), count in actual.items():
            new_coordinates[coordinate] += count
        expanded_scope = any(coordinate == old_coordinate and scope in uses and old_scope in uses
                             and bool(uses[scope] - uses[old_scope])
                             for coordinate, scope in new_remaining for old_coordinate, old_scope in old_remaining)
        if new_coordinates - old_coordinates or expanded_scope:
            blockers.append({"rule": "DEPENDENCY_EXPANSION", "key": pom,
                             "detail": "new dependency coordinate/scope or increased declaration count"})
    for key, statement in current["statements"].items():
        operation = literal_write_operation(sql_details[key]) or statement["kind"]
        operation = "insert" if operation == "replace" else operation
        target = literal_write_target(operation, sql_details[key])
        binding = statement_owners.get(key)
        reviewed = bool(binding and all((binding["owner"] == statement["owner"], binding["kind"] == operation,
                                       binding["table"] == target, binding["sha256"] == statement["sha256"])))
        # Check before comparison-base equality: a reviewed namespace must never launder a third statement.
        if binding and not reviewed:
            blockers.append({"rule": "STATEMENT_OWNER_BINDING_CHANGED", "key": key,
                             "detail": "reviewed recovery statement identity/body changed; independent review required"})
        elif not binding and (target, statement["owner"]) in limited_edges:
            blockers.append({"rule": "UNREVIEWED_STATEMENT_WRITER", "key": key,
                             "detail": "the two approved recovery edges grant no other statement write"})
        if statement == base["statements"].get(key):
            continue
        details = sql_details[key]
        kind = statement["kind"]
        if kind == "select":
            lock = details["locking_select"]
            if lock["analysis"] == "UNKNOWN":
                review.append({"key": key, "reason": "UNKNOWN_LOCKING_SELECT", "detail": lock})
            elif lock["analysis"] == "LOCKING_SELECT" and (lock["useCache"] != "false" or lock["flushCache"] != "true"):
                blockers.append({"rule": "LOCKING_SELECT_CACHE", "key": key,
                                 "detail": "explicit FOR UPDATE requires useCache=false AND flushCache=true",
                                 "statement": lock})
        kind = literal_write_operation(details) or kind
        kind = "insert" if kind == "replace" else kind
        if kind == "select":
            review.append({"key": key, "reason": "SQL_QUERY_CHANGED: effects/locking are not semantically parsed"})
            continue
        table = literal_write_target(kind, details)
        owners = known_owners.get(table, set())
        if owners and statement["owner"] not in owners and not reviewed:
            blockers.append({"rule": "NEW_TABLE_WRITER", "key": key,
                             "detail": table + " <- " + statement["owner"]})
        if policy is not None:
            new_blockers, new_review = business_write_findings(
                key, statement, kind, details, base["statements"].get(key), base_details.get(key), policy)
            blockers.extend(new_blockers)
            review.extend(new_review)
        violation = scope_violation(kind, details)
        if violation:
            blockers.append({"rule": "UNSCOPED_WRITE", "key": key, "detail": violation})
        review.append({"key": key, "reason": "UNKNOWN" if details["unknown"] or not table else "WRITE_CONTRACT_REVIEW",
                       "detail": "normal PR review: scope/identity/CAS/intent/recovery/atomicity as applicable"})
    for key in base["statements"].keys() - current["statements"].keys():
        review.append({"key": key, "reason": "SQL_REMOVED: review receipt/recovery callers"})
    for path in sorted(changed):
        if MAPPER.fullmatch(path):
            # Keeps fragment-only and unresolved dynamic refid edits visible.
            review.append({"key": path, "reason": "MAPPER_XML_CHANGED: dynamic fragment reachability/metadata need PR review"})
        elif path.endswith(".sql"):
            review.append({"key": path, "reason": "UNKNOWN_SQL_SCRIPT: use existing migration gates and PR review"})
        elif path.endswith("pom.xml") or path.startswith(".mvn/") or path in {"mvnw", "mvnw.cmd"}:
            review.append({"key": path, "reason": "BUILD_CHANGED: effective dependencies/test settings are not fully parsed"})
    order = lambda item: (item.get("rule", ""), item["key"])
    return sorted(blockers, key=order), sorted(review, key=order)


def manual_base(root, candidate, context, details=None):
    """Fetch the real event's default branch; never trust a candidate input/ref.

    A fresh single-ref fetch binds origin's tracking ref to FETCH_HEAD. No cached
    ref or candidate-parent fallback is allowed, including when the fetch fails.
    """
    payload = context["payload"]
    repository = payload.get("repository", {})
    if (context["event"] != "workflow_dispatch"
            or repository.get("full_name") != "liboyang42-cpu/hengyin"
            or not SHA.fullmatch(candidate or "")
            or candidate != context["checkout_sha"]):
        raise GateError("manual candidate does not match trusted CI event/repository")
    branch = repository.get("default_branch")
    if not isinstance(branch, str) or not branch or branch.startswith("-"):
        raise GateError("missing/invalid trusted default branch")
    head_ref = "refs/heads/" + branch
    git(root, "check-ref-format", head_ref)
    origin = git(root, "remote", "get-url", "--all", "origin").decode().splitlines()
    if origin not in (["https://github.com/liboyang42-cpu/hengyin.git"],
                      ["https://github.com/liboyang42-cpu/hengyin"]):
        raise GateError("origin must be the fixed public repository HTTPS remote")
    tracking = "refs/remotes/origin/" + branch
    git(root, "fetch", "--no-tags", "--no-recurse-submodules", "origin",
        "+" + head_ref + ":" + tracking)
    target = git(root, "rev-parse", "--verify", tracking + "^{commit}").decode().strip()
    fetched = git(root, "rev-parse", "--verify", "FETCH_HEAD^{commit}").decode().strip()
    if not SHA.fullmatch(target) or target != fetched:
        raise GateError("default branch tracking ref does not match freshly fetched origin")
    bases = git(root, "merge-base", "--all", candidate, target).decode().splitlines()
    if (not bases or len(set(bases)) != len(bases)
            or any(not SHA.fullmatch(base) for base in bases)
            or (len(bases) > 1 and (candidate in bases or target in bases))):
        raise GateError("ambiguous manual merge-base evidence is invalid")
    for base in bases:
        git(root, "merge-base", "--is-ancestor", base, candidate)
        git(root, "merge-base", "--is-ancestor", base, target)
    if len(bases) == 1:
        selected, mode = bases[0], "UNIQUE_MERGE_BASE"
    elif len(bases) == 2:
        # A bounded conservative comparison, not an arbitrary choice of one LCA.
        # No recursive fallback, virtual merge, history mutation or snapshot change.
        common = git(root, "merge-base", "--all", *bases).decode().splitlines()
        if len(common) != 1 or not SHA.fullmatch(common[0]) or common[0] in bases:
            raise GateError("ambiguous manual merge-bases have no unique strict common ancestor")
        selected = common[0]
        for base in bases:
            git(root, "merge-base", "--is-ancestor", selected, base)
        git(root, "merge-base", "--is-ancestor", BASE, selected)
        mode = "UNIQUE_STRICT_COMMON_ANCESTOR_OF_TWO_MERGE_BASES"
    else:
        raise GateError("ambiguous manual merge-base count exceeds the bounded two-base policy")
    if details is not None:
        details.update(mode=mode, default_branch=branch, default_tip_sha=target,
                       merge_bases=sorted(bases), comparison_base_sha=selected)
    return selected


def comparison_base(root, candidate, supplied, context, details=None):
    """Validate the CI event's comparison point, never an arbitrary caller SHA.

    GitHub supplies the trusted event; local fixtures call this function directly.
    A process that controls the verifier or CI event is outside this trust boundary.
    """
    if not SHA.fullmatch(supplied or ""):
        raise GateError("comparison base must be a full lowercase commit SHA")
    event, payload, checkout = context["event"], context["payload"], context["checkout_sha"]
    if not SHA.fullmatch(checkout or ""):
        raise GateError("invalid CI checkout SHA")
    if payload.get("repository", {}).get("full_name") != "liboyang42-cpu/hengyin":
        raise GateError("wrong CI repository")
    if event == "pull_request":
        pr = payload["pull_request"]
        if candidate != pr["head"]["sha"]:
            raise GateError("PR candidate must equal trusted event head SHA")
        target = pr["base"]["sha"]
        if not SHA.fullmatch(target or ""):
            raise GateError("invalid PR target SHA")
        # Also bind the CI checkout to the PR head or the exact synthetic merge.
        parents = git(root, "show", "-s", "--format=%P", checkout).decode().strip().split()
        if checkout != candidate and parents != [target, candidate]:
            raise GateError("CI checkout is neither PR head nor its exact synthetic merge")
        bases = git(root, "merge-base", "--all", candidate, target).decode().splitlines()
        if len(bases) != 1:
            raise GateError("ambiguous PR merge-base requires review")
        expected = bases[0]
    elif event == "push":
        if candidate != checkout or payload.get("after") != candidate:
            raise GateError("push candidate does not match trusted event")
        expected = payload["before"]
    elif event == "workflow_dispatch":
        expected = manual_base(root, candidate, context, details)
    else:
        raise GateError("unsupported CI event")
    if expected != supplied:
        raise GateError("COMPARISON_BASE_MISMATCH: supplied base differs from trusted CI event/merge-base")
    git(root, "merge-base", "--is-ancestor", supplied, candidate)
    return supplied


def ci_context(environ):
    if environ.get("GITHUB_ACTIONS") != "true" or environ.get("GITHUB_REPOSITORY") != "liboyang42-cpu/hengyin":
        raise GateError("trusted GitHub CI context required; no arbitrary local comparison-base default")
    return {"event": environ["GITHUB_EVENT_NAME"], "checkout_sha": environ["GITHUB_SHA"],
            "payload": json.loads(Path(environ["GITHUB_EVENT_PATH"]).read_text())}


def check_source(root, candidate, compare_base, context, baseline_path=HERE / "baseline.json"):
    if not SHA.fullmatch(candidate):
        raise GateError("candidate must be a full lowercase commit SHA")
    if git(root, "rev-parse", "HEAD").decode().strip() != candidate:
        raise GateError("candidate HEAD mismatch")
    if git(root, "status", "--porcelain", "--untracked-files=all").strip():
        raise GateError("candidate checkout is dirty; commit/isolate changes before verification")
    git(root, "merge-base", "--is-ancestor", BASE, candidate)
    comparison_details = {}
    comparison_base(root, candidate, compare_base, context, comparison_details)
    frozen_details = {}
    frozen = inventory(root, BASE, frozen_details)
    if baseline_path.read_bytes() != encode_inventory(frozen):
        raise GateError("BASELINE_LAUNDERING: baseline must exactly reproduce pinned public git blobs")
    proposal = HERE / "locking-select-baseline.proposal.json"
    if proposal.read_bytes() != encode_inventory(locking_debt_proposal(frozen, frozen_details)):
        raise GateError("LOCKING_BASELINE_LAUNDERING: proposal must exactly reproduce pinned public git blobs")
    # Protect executable verifier, exceptions and all active owner/command data.
    # Documentation/test edits alone are not violations. CI executes trusted
    # selftests, not arbitrary additional files supplied by the candidate.
    names = set(tree(root, candidate))
    for filename in ("gate.py", "baseline.json", "locking-select-baseline.proposal.json", "owners.json", "commands.json", "collect_jdeps.py"):
        relative = "ci/architecture/" + filename
        if relative not in names or blob(root, candidate, relative) != (HERE / filename).read_bytes():
            raise GateError("POLICY_CHANGE: candidate changed trusted policy: " + relative)
    policy = business_policy()
    technical = technical_dependency_policy()
    changed = set(git(root, "diff", "--name-only", "--no-renames", compare_base, candidate).decode().splitlines())
    details = {}
    current = inventory(root, candidate, details)
    previous_details = {}
    previous = inventory(root, compare_base, previous_details)
    blockers, review = static_findings(previous, current, changed, details, previous_details, policy,
                                       statement_write_owners())
    boundary_blockers, boundary_review, boundary_scope = boundary_findings(root, candidate, changed, names, technical, policy)
    blockers.extend(boundary_blockers)
    review.extend(boundary_review)
    return {"schema": 2, "candidate_sha": candidate, "comparison_base_sha": compare_base,
            "manual_comparison": comparison_details if context["event"] == "workflow_dispatch" else None,
            "legacy_baseline_sha": BASE, "ci_checkout_sha": context["checkout_sha"],
            "verdict": "BLOCKED" if blockers else "PASS_WITH_REVIEW_ITEMS" if review else "PASS",
            "findings": blockers, "review_items": review, "changed_files": sorted(changed),
            "boundary_registration": boundary_scope,
            "business_policy": {"coverage": "PARTIAL", "fact_ids": sorted(f["id"] for f in policy["facts"].values())},
            "technical_dependencies": {"coverage": "PARTIAL", "owners": sorted(technical["owners"]), "compiled_inputs": "NOT_CHECKED_BY_SOURCE_GATE"},
            "not_proven": ["Java/SQL authorization semantics", "transaction atomicity",
                           "complete fresh-read semantics (RR current SQL/transactions still require tests)",
                           "persistent intent/recovery", "Java or real storage test execution",
                           "business service call paths and writes outside declared facts"]}


@functools.lru_cache(maxsize=1)
def java_mask():
    # Existing stdlib-only scanner, from the trusted checkout, never candidate code.
    spec = importlib.util.spec_from_file_location("architecture_java_mask", JAVA_MASK_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.strip_java_comments


def boundary_type(root, ref, path):
    """Narrow primary-type role detection, not Java/type/annotation semantics."""
    code = java_mask()(blob(root, ref, path).decode("utf-8"), keep_strings=False)
    expected = path.split("/src/main/java/", 1)[1][:-5].replace("/", ".")
    package = re.search(r"\bpackage\s+([A-Za-z_][\w.]*)\s*;", code)
    primary = re.search(r"\b(?:class|interface|record|enum)\s+(" + IDENT + r")\b", code)
    if (not package or not primary or expected != package[1] + "." + primary[1]
            or re.search(r"\\u[0-9a-fA-F]{4}", code)):
        return {"type": expected, "role": "UNKNOWN"}
    prefix = code[:primary.start()]
    role = bool((re.search(r"\bpublic\b", prefix)
                 and re.search(r"(?:Service(?:Impl)?|Controller|Application|Mapper)$", primary[1]))
                or re.search(r"@(?:[\w]+\.)*(?:Service|Controller|RestController|Mapper|Repository)\b", prefix))
    if not role and re.search(r"@(?:[\w]+\.)*(?:Service|Controller|RestController|Mapper|Repository|Component)\b",
                              code):
        return {"type": expected, "role": "UNKNOWN"}
    return {"type": expected, "role": "BOUNDARY" if role else "OTHER"}


def boundary_findings(root, candidate, changed, names, technical, business):
    """New boundary identities only; old unowned code is debt, not new permission."""
    try:
        policy = json.loads((HERE / "owners.json").read_text())["boundary_registration"]
        roots = policy["scope_roots"]
        if (set(policy) != {"schema", "legacy_source", "scope_roots"} or type(policy["schema"]) is not int or policy["schema"] != 1
                or policy["legacy_source"] != BOUNDARY_BASE or not isinstance(roots, list) or not roots
                or len(set(roots)) != len(roots) or any(not re.fullmatch(
                    r"chengyinhub-(?:admin|system)/src/main/java/com/chengyinhub/(?:business|web)/[a-z/]+/", p) for p in roots)):
            raise GateError("BOUNDARY_POLICY_INVALID")
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise GateError("BOUNDARY_POLICY_INVALID: " + str(error)) from error
    legacy = set(tree(root, BOUNDARY_BASE))
    registered = {(o["module"], n) for o in technical["owners"].values() for n in o["members"]}
    registered.update((b["owner"], n) for target, b in business["private_ports"].items()
                      for n in (target, b["consumer"]))
    blockers, review = [], []
    for path in sorted(names):
        if not path.endswith(".java") or not any(path.startswith(p) for p in roots):
            continue
        # Untouched snapshot identities need no reclassification or mass registration.
        if path in legacy and path not in changed:
            continue
        current = boundary_type(root, candidate, path)
        if current["role"] == "UNKNOWN":
            review.append({"key": path, "reason": "BOUNDARY_ROLE_UNKNOWN", "type": current["type"]})
        if current["role"] != "BOUNDARY" or (path.split("/")[0], current["type"]) in registered:
            continue
        previous = boundary_type(root, BOUNDARY_BASE, path) if path in legacy else None
        if previous == current:
            review.append({"key": path, "reason": "LEGACY_UNOWNED_BOUNDARY", "type": current["type"],
                           "legacy_source": BOUNDARY_BASE, "legacy_sha256": digest(blob(root, BOUNDARY_BASE, path))})
        else:
            blockers.append({"rule": "NEW_UNOWNED_BOUNDARY", "key": path, "type": current["type"],
                             "detail": "register exact technical owner in an independently reviewed verifier"})
    return blockers, review, {"coverage": "SCOPED_PRIMARY_BOUNDARY_ROLES", "legacy_source": BOUNDARY_BASE,
                             "scope_roots": roots, "semantic_read_only": "UNKNOWN"}


def readback(plan, receipt, reports, assert_path=REPO / "ci/assert_it_ran.py"):
    """Trusted CI plan + job receipt + report bytes. Not self-attestation proof.

    CI must construct the plan outside candidate code and attest storage identity.
    This read-only step checks identity and report consistency, not provenance by
    cryptography. Existing real_storage_it.py remains the real storage executor.
    """
    expected_keys = {"candidate_sha", "run_id", "run_attempt", "suites"}
    if set(plan) != expected_keys or not SHA.fullmatch(str(plan.get("candidate_sha", ""))):
        raise GateError("invalid trusted test plan")
    if any(not re.fullmatch(r"[1-9][0-9]*", str(plan.get(key, ""))) for key in ("run_id", "run_attempt")):
        raise GateError("run identity must be positive integers")
    suites = plan["suites"]
    if not isinstance(suites, dict) or not suites:
        raise GateError("missing expected suite plan")
    for name, count in suites.items():
        if (not re.fullmatch(r"(?:[A-Za-z_]\w*\.)+[A-Za-z_]\w*(?:\$[A-Za-z_]\w*)*", name)
                or type(count) is not int or count < 1):
            raise GateError("expected full suite names and positive exact counts")
    if set(receipt) != expected_keys | {"reports_sha256", "maven_exit_code"}:
        raise GateError("invalid receipt fields")
    for key in expected_keys:
        if receipt[key] != plan[key]:
            raise GateError("CANDIDATE_OR_RUN_MISMATCH: " + key)
    if type(receipt["maven_exit_code"]) is not int or receipt["maven_exit_code"] != 0:
        raise GateError("Maven did not succeed")
    if reports.is_symlink() or not reports.is_dir():
        raise GateError("missing/symlink report directory")
    files = sorted(reports.glob("TEST-*.xml"))
    if any(path.is_symlink() for path in files):
        raise GateError("symlink report rejected")
    hashes = {path.name: digest(path.read_bytes()) for path in files}
    if not hashes or hashes != receipt["reports_sha256"]:
        raise GateError("missing/changed/unexpected report bytes")
    roots = [xml(path.read_bytes()) for path in files]
    # Reuse the established missing/zero/skip/failure/error check first.
    spec = importlib.util.spec_from_file_location("architecture_assert_it_ran", assert_path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    problems = module.enforce(str(reports), [n.rsplit(".", 1)[-1] for n in suites])
    if problems:
        raise GateError("; ".join(problems))
    found = {}
    for root in roots:
        name = root.get("name")
        if root.tag != "testsuite" or name in found or name not in suites:
            raise GateError("duplicate/foreign/unsupported suite: " + str(name))
        expected = suites[name]
        if any(root.get(key) != str(value) for key, value in
               {"tests": expected, "failures": 0, "errors": 0, "skipped": 0}.items()):
            raise GateError("suite totals mismatch: " + name)
        cases = root.findall("testcase")
        if len(cases) != expected or len({(c.get("classname"), c.get("name")) for c in cases}) != expected:
            raise GateError("missing/duplicate test cases: " + name)
        if any(not case.get("name") or case.get("classname") != name for case in cases):
            raise GateError("testcase identity mismatch: " + name)
        if any(element.tag in BAD_CASE for element in root.iter()):
            raise GateError("skipped/failed/flaky test case: " + name)
        found[name] = expected
    if found != suites:
        raise GateError("missing full-name suite reports")
    return {"candidate_sha": plan["candidate_sha"], "run_id": plan["run_id"],
            "run_attempt": plan["run_attempt"], "verdict": "REPORTS_CONSISTENT",
            "suites": found, "not_proven": ["storage identity", "test semantic coverage",
                                              "report producer trust", "real business chain"]}



def compiled_ports(root, artifact, identity, observation, env):
    # Both parser and policy come from this immutable trusted checkout. No Java
    # execution here; the same Backend job must run the trusted producer first.
    spec = importlib.util.spec_from_file_location("trusted_private_ports", HERE / "collect_jdeps.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.check_private_ports(root, artifact, identity, observation, env,
                                      business_policy()["private_ports"], technical_dependency_policy())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    snapshot = subs.add_parser("inventory")
    snapshot.add_argument("--source", type=Path, required=True)
    source = subs.add_parser("source")
    source.add_argument("--source", type=Path, required=True)
    source.add_argument("--candidate", required=True)
    source.add_argument("--compare-base", required=True)
    manual = subs.add_parser("manual-base")
    manual.add_argument("--source", type=Path, required=True)
    manual.add_argument("--candidate", required=True)
    evidence = subs.add_parser("evidence")
    evidence.add_argument("--plan", type=Path, required=True)
    evidence.add_argument("--receipt", type=Path, required=True)
    evidence.add_argument("--reports", type=Path, required=True)
    compiled = subs.add_parser("compiled-ports")
    compiled.add_argument("--source", type=Path, required=True)
    compiled.add_argument("--artifact", type=Path, required=True)
    compiled.add_argument("--identity", type=Path, required=True)
    compiled.add_argument("--observation", type=Path, required=True)
    compiled.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "inventory":
            print(encode_inventory(inventory(args.source, BASE)).decode(), end="")
            return 0
        elif args.command == "manual-base":
            print(manual_base(args.source, args.candidate, ci_context(os.environ)))
            return 0
        elif args.command == "source":
            result = check_source(args.source, args.candidate, args.compare_base, ci_context(os.environ))
        elif args.command == "compiled-ports":
            result = compiled_ports(args.source, args.artifact, args.identity, args.observation, dict(os.environ))
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(encode(result))
            print(encode(result).decode(), end="")
            return 1 if result["findings"] else 2 if result["verdict"] == "UNKNOWN" else 0
        else:
            result = readback(json.loads(args.plan.read_text()), json.loads(args.receipt.read_text()), args.reports)
        print(encode(result).decode(), end="")
        return 1 if result.get("findings") else 0
    except (GateError, OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        print("architecture gate FAIL-CLOSED: " + str(error), file=sys.stderr)
        print(encode({"verdict": "FAIL_CLOSED", "error": str(error)}).decode(), end="")
        return 1


if __name__ == "__main__":
    sys.exit(main())
