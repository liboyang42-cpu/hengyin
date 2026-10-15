#!/usr/bin/env python3
"""Prepare one exact clean source commit, without parent objects, credentials or shared Git state."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess


def git(repo, *args, data=None):
    return subprocess.check_output(["git", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false",
                                   "-c", "core.attributesFile=/dev/null", "-C", str(repo), *args],
                                   input=data, stderr=subprocess.DEVNULL)


def inventory(repo, commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit): raise ValueError("Exact forty-character commit required")
    rows, objects = [], {commit}
    for entry in git(repo, "ls-tree", "-r", "-t", "-z", commit).split(b"\0"):
        if not entry: continue
        metadata, raw_path = entry.split(b"\t", 1); mode, kind, oid = metadata.decode().split()
        path = raw_path.decode("utf-8"); objects.add(oid)
        if kind == "tree": continue
        if kind != "blob" or mode not in {"100644", "100755", "120000"}: raise ValueError("Unexpanded Git entry refused")
        content = git(repo, "cat-file", "blob", oid)
        if content.startswith(b"version https://git-lfs.github.com/spec/v1\n"):
            raise ValueError("Unexpanded LFS asset refused")
        if mode == "120000":
            target = content.decode("utf-8"); resolved = PurePosixPath(path).parent / target
            level = 0
            for part in resolved.parts:
                level += -1 if part == ".." else 0 if part == "." else 1
                if level < 0: raise ValueError("Escaping source symlink refused")
            if PurePosixPath(target).is_absolute(): raise ValueError("Absolute source symlink refused")
        rows.append({"path": path, "mode": mode, "gitBlob": oid, "size": len(content),
                     "sha256": hashlib.sha256(content).hexdigest()})
    tree = git(repo, "rev-parse", commit + "^{tree}").decode().strip(); objects.add(tree)
    return tree, rows, objects


def prepare(repo, commit, destination):
    repo, destination = Path(repo).resolve(), Path(destination).resolve()
    common = Path(git(repo, "rev-parse", "--git-common-dir").decode().strip())
    if not common.is_absolute(): common = (repo / common).resolve()
    if destination == repo or repo in destination.parents or destination == common or common in destination.parents:
        raise ValueError("Output must be outside the source checkout and shared Git state")
    if git(repo, "rev-parse", "HEAD").decode().strip() != commit or git(repo, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Formal source requires exact clean HEAD")
    tree, rows, objects = inventory(repo, commit)
    destination.mkdir(exist_ok=False)
    subprocess.check_call(["git", "init", "--template=", "-q", str(destination)], stdout=subprocess.DEVNULL)
    # Explicit OIDs do not ask Git to walk parent history or reuse a whole reachable pack.
    packed = git(repo, "pack-objects", "--no-reuse-delta", "--no-reuse-object", "--stdout",
                 data=("\n".join(sorted(objects)) + "\n").encode())
    git(destination, "unpack-objects", "-q", data=packed)
    (destination / ".git/shallow").write_text(commit + "\n")
    (destination / ".git/HEAD").write_text(commit + "\n")
    git(destination, "checkout", "--force", "--detach", commit)
    git(destination, "fsck", "--connectivity-only", "--no-dangling")
    git(destination, "fsck", "--full", "--no-dangling")
    actual = {line.split()[0] for line in git(destination, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").decode().splitlines()}
    if actual != objects: raise ValueError("Unexpected or missing transferred Git objects")
    after_tree, after_rows, _ = inventory(destination, commit)
    if after_tree != tree or after_rows != rows or git(destination, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Transferred source tree or clean status differs")
    for row in rows:
        path = destination / row["path"]
        content = path.readlink().as_posix().encode() if row["mode"] == "120000" else path.read_bytes()
        if hashlib.sha256(content).hexdigest() != row["sha256"]: raise ValueError("Materialized source bytes differ")
    return {"schemaVersion": 1, "candidateCommit": commit, "candidateTree": tree,
            "scope": "SINGLE_COMMIT_FULL_TREE_NO_ANCESTOR_OBJECTS", "objects": sorted(objects),
            "fileCount": len(rows), "files": rows, "packSha256": hashlib.sha256(packed).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--destination", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--verified-tree-scan", required=True, type=Path)
    args = parser.parse_args()
    receipt = json.loads(args.verified_tree_scan.read_text())
    tree, rows, _ = inventory(args.root, args.commit)
    digest = hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()
    if (receipt.get("sourceCommit") != args.commit or receipt.get("sourceTree") != tree
            or receipt.get("sourceFilesSha256") != digest or receipt.get("scope") != "EXACT_COMMIT_SOURCE_TREE"
            or receipt.get("gitleaksExit") != 0 or receipt.get("findings") != 0 or receipt.get("semanticGuardReady") is not True):
        raise ValueError("Exact committed-tree secret scan receipt required before packaging")
    result = prepare(args.root, args.commit, args.destination)
    args.output.write_text(json.dumps(result, sort_keys=True, indent=2) + "\n")
    print(json.dumps({"candidateCommit": result["candidateCommit"], "fileCount": result["fileCount"],
                      "objectCount": len(result["objects"]), "scope": result["scope"]}))


if __name__ == "__main__": main()
