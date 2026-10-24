"""Exact SQL Merkle proof of a source commit, without transferring ancestor or unrelated blobs."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess

SHA = re.compile(r"[0-9a-f]{40}\Z")


def object_oid(kind, data):
    return hashlib.sha1(kind.encode() + b" " + str(len(data)).encode() + b"\0" + data).hexdigest()


def tree_entries(data):
    entries, offset = {}, 0
    while offset < len(data):
        space = data.index(b" ", offset); nul = data.index(b"\0", space)
        mode = data[offset:space].decode("ascii")
        name = data[space + 1:nul].decode("utf-8")
        if name in {"", ".", ".."} or "/" in name or name in entries or nul + 21 > len(data):
            raise ValueError("Invalid Git tree entry")
        entries[name] = (mode, data[nul + 1:nul + 21].hex()); offset = nul + 21
    return entries


def sql_path(path):
    p = PurePosixPath(path)
    return not p.is_absolute() and ".." not in p.parts and p.suffix == ".sql" and (
        p.parts[:2] == ("chengyinhub-admin", "sql") or p.parts[:1] == ("sql",))


class SourceCustody:
    def __init__(self, directory, commit, migration_pattern, explicit_sql, expected_migrations, seed_files):
        self.directory = Path(directory)
        if not SHA.fullmatch(commit): raise ValueError("Exact source SHA required")
        self.index = json.loads((self.directory / "index.json").read_text())
        if self.index.get("schemaVersion") != 1 or self.index.get("sourceCommit") != commit:
            raise ValueError("Custody source identity mismatch")
        self.objects = self.index["objects"]; self.used = set()
        if any(not SHA.fullmatch(oid) or kind not in {"commit", "tree", "blob"} for oid, kind in self.objects.items()):
            raise ValueError("Invalid custody object index")
        files = {p.name for p in (self.directory / "objects").iterdir() if p.is_file()}
        if files != set(self.objects): raise ValueError("Custody object inventory mismatch")
        raw = self.read_object(commit, "commit")
        match = re.match(rb"tree ([0-9a-f]{40})\n", raw)
        if not match: raise ValueError("Invalid source commit tree")
        root = match[1].decode()
        sql_tree = self.resolve(root, "chengyinhub-admin/sql", directory=True)
        actual_paths = self.paths(sql_tree, "chengyinhub-admin/sql")
        selected = {p: oid for p, oid in actual_paths.items() if migration_pattern.fullmatch(p) or p in explicit_sql}
        if set(selected) != set(expected_migrations): raise ValueError("Source migration path set mismatch")
        if any(not sql_path(path) for path in seed_files): raise ValueError("Non-SQL custody source refused")
        self.files = {}
        for path, oid in selected.items(): self.files[path] = self.read_object(oid, "blob")
        for path in seed_files: self.files[path] = self.read_object(self.resolve(root, path), "blob")
        for hashes in (expected_migrations, seed_files):
            for path, expected in hashes.items():
                if hashlib.sha256(self.files[path]).hexdigest() != expected: raise ValueError("Source SHA256 mismatch: " + path)
        if self.used != set(self.objects): raise ValueError("Unrelated custody objects refused")
        self.migrations = {p: hashlib.sha256(self.files[p]).hexdigest() for p in selected}
        self.commit = commit

    def read_object(self, oid, kind):
        if self.objects.get(oid) != kind: raise ValueError("Missing or wrong source object type")
        path = self.directory / "objects" / oid
        if path.is_symlink(): raise ValueError("Source object symlink refused")
        data = path.read_bytes()
        if object_oid(kind, data) != oid: raise ValueError("Git object hash mismatch")
        self.used.add(oid); return data

    def resolve(self, root, path, directory=False):
        if PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts: raise ValueError("Unsafe source path")
        parts = PurePosixPath(path).parts; current = root
        for i, name in enumerate(parts):
            entries = tree_entries(self.read_object(current, "tree"))
            if name not in entries: raise ValueError("Missing source path: " + path)
            mode, current = entries[name]
            expected = "40000" if i < len(parts) - 1 or directory else None
            if expected and mode != expected or not expected and mode not in {"100644", "100755"}:
                raise ValueError("Unsafe source path mode")
        return current

    def paths(self, oid, prefix):
        result = {}
        for name, (mode, child) in tree_entries(self.read_object(oid, "tree")).items():
            path = prefix + "/" + name
            if mode == "40000": result.update(self.paths(child, path))
            elif mode in {"100644", "100755"}: result[path] = child
            else: raise ValueError("Nonregular SQL source tree entry refused")
        return result


def export_proof(repo, commit, output, migration_pattern, explicit_sql, seed_paths):
    """Copy only original commit/path trees and selected SQL blobs; never a policy-wide archive."""
    repo, output = Path(repo), Path(output)
    repo, output = repo.resolve(), output.resolve()
    common = Path(subprocess.check_output(["git", "rev-parse", "--git-common-dir"], cwd=repo).decode().strip())
    if not common.is_absolute(): common = (repo / common).resolve()
    if output == repo or repo in output.parents or output == common or common in output.parents:
        raise ValueError("Custody output must be outside source and shared Git state")
    if not SHA.fullmatch(commit): raise ValueError("Exact source SHA required")
    if any(not sql_path(path) for path in seed_paths): raise ValueError("Non-SQL source refused")
    output.mkdir(exist_ok=False); (output / "objects").mkdir()
    objects = {}
    def read(oid, kind):
        data = subprocess.check_output(["git", "cat-file", kind, oid], cwd=repo)
        if object_oid(kind, data) != oid: raise ValueError("Source object does not match original Git SHA")
        (output / "objects" / oid).write_bytes(data); objects[oid] = kind; return data
    raw = read(commit, "commit"); match = re.match(rb"tree ([0-9a-f]{40})\n", raw)
    if not match: raise ValueError("Invalid source commit")
    root = match[1].decode()
    def resolve(path):
        oid = root; parts = PurePosixPath(path).parts
        for i, name in enumerate(parts):
            mode, oid = tree_entries(read(oid, "tree"))[name]
            if i < len(parts) - 1 and mode != "40000": raise ValueError("Non-directory source ancestor")
        return mode, oid
    def sql_tree(oid, prefix):
        for name, (mode, child) in tree_entries(read(oid, "tree")).items():
            path = prefix + "/" + name
            if mode == "40000": sql_tree(child, path)
            elif mode in {"100644", "100755"}:
                if migration_pattern.fullmatch(path) or path in explicit_sql: read(child, "blob")
            else: raise ValueError("Nonregular SQL source refused")
    mode, tree = resolve("chengyinhub-admin/sql")
    if mode != "40000": raise ValueError("Missing source SQL directory")
    sql_tree(tree, "chengyinhub-admin/sql")
    for path in seed_paths:
        mode, oid = resolve(path)
        if mode not in {"100644", "100755"}: raise ValueError("Nonregular source refused")
        read(oid, "blob")
    (output / "index.json").write_text(json.dumps({"schemaVersion": 1, "sourceCommit": commit, "objects": objects}, sort_keys=True, indent=2) + "\n")
    return {"sourceCommit": commit, "objectCount": len(objects), "scope": "EXACT_SQL_MERKLE_PROOF_NOT_FULL_POLICY_TREE_OR_HISTORY"}
