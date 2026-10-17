#!/usr/bin/env python3
"""Verified logical backup and restore to an explicitly identified empty test database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def mysql(args, sql):
    run = subprocess.run(["mysql", "--defaults-extra-file=" + str(args.defaults_file),
                          "--batch", "--raw", "--skip-column-names", "--database=" + args.database],
                         input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if run.returncode:
        raise ValueError("MySQL command failed; no backup or restore is considered verified")
    return run.stdout.decode().strip()


def identify(args):
    if not args.defaults_file.is_file() or args.defaults_file.stat().st_mode & 0o077:
        raise ValueError("MySQL defaults file must exist with owner-only permissions")
    identity = mysql(args, "SELECT @@server_uuid, VERSION(), DATABASE()").split("\t")
    if len(identity) != 3 or identity[0] != args.server_uuid or identity[2] != args.database:
        raise ValueError("Server UUID or database differs from the explicit target")
    return identity


def snapshot(args):
    objects = mysql(args, "SHOW FULL TABLES")
    tables = {}
    views = []
    for line in objects.splitlines():
        name, kind = line.split("\t")
        if kind == "VIEW":
            views.append(name)
            continue
        quoted = "`" + name.replace("`", "``") + "`"
        checksum = mysql(args, "CHECKSUM TABLE " + quoted + " EXTENDED").split("\t")[-1]
        if checksum == "NULL":
            raise ValueError("A table has no verifiable checksum")
        tables[name] = {"rows": int(mysql(args, "SELECT COUNT(*) FROM " + quoted)), "checksum": checksum}
    counts = {}
    for kind in ("ROUTINES", "TRIGGERS", "EVENTS"):
        column = "ROUTINE_SCHEMA" if kind == "ROUTINES" else "TRIGGER_SCHEMA" if kind == "TRIGGERS" else "EVENT_SCHEMA"
        counts[kind] = int(mysql(args, "SELECT COUNT(*) FROM information_schema." + kind
                                + " WHERE " + column + "=DATABASE()"))
    return {"tables": tables, "views": sorted(views), "objects": counts}


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def backup(args):
    identity = identify(args)
    before = snapshot(args)
    output = args.output
    receipt = Path(str(output) + ".json")
    partial = Path(str(output) + ".partial")
    if output.exists() or receipt.exists():
        raise ValueError("Backup or receipt already exists; refusing to overwrite")
    # Only clean up a partial file created by this invocation.
    with partial.open("xb") as stream:
        os.chmod(partial, 0o600)
        try:
            run = subprocess.run(["mysqldump", "--defaults-extra-file=" + str(args.defaults_file),
                                  "--single-transaction", "--routines", "--triggers", "--events",
                                  "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob", args.database],
                                 stdout=stream, stderr=subprocess.PIPE)
            stream.flush()
            os.fsync(stream.fileno())
            if run.returncode or partial.stat().st_size == 0 or snapshot(args) != before:
                raise ValueError("Dump failed, is empty, or source changed; quiesce writes and retry")
            manifest = {"serverUuid": identity[0], "mysqlVersion": identity[1], "database": args.database,
                        "sha256": digest(partial), "snapshot": before}
            with receipt.open("x", encoding="utf-8") as result:
                os.chmod(receipt, 0o600)
                json.dump(manifest, result, ensure_ascii=False, indent=2)
            partial.rename(output)
        finally:
            if partial.exists():
                partial.unlink()
    print("Backup created with checksum receipt; restore drill still required")


def restore(args):
    identity = identify(args)
    manifest = json.loads(Path(str(args.backup) + ".json").read_text(encoding="utf-8"))
    if not args.confirm_test_target or args.database != manifest["database"]:
        raise ValueError("Confirm isolated test target and preserve the source database name for views")
    if mysql(args, "SELECT @@global.event_scheduler") not in ("OFF", "DISABLED"):
        raise ValueError("The isolated restore server must have its event scheduler disabled")
    if identity[0] == manifest["serverUuid"] or identity[1] != manifest["mysqlVersion"]:
        raise ValueError("Restore requires a different MySQL instance of the same version")
    if digest(args.backup) != manifest["sha256"]:
        raise ValueError("Backup hash differs from its receipt")
    target = snapshot(args)
    if target["tables"] or target["views"] or any(target["objects"].values()):
        raise ValueError("Restore target must be empty; existing data is never dropped")
    with args.backup.open("rb") as stream:
        run = subprocess.run(["mysql", "--defaults-extra-file=" + str(args.defaults_file),
                              "--database=" + args.database], stdin=stream, stderr=subprocess.PIPE,
                             stdout=subprocess.DEVNULL)
    if run.returncode or snapshot(args) != manifest["snapshot"]:
        raise ValueError("Restore failed verification; isolate target for investigation, do not retry over it")
    print("Restore verified: tables, views, row counts, checksums, routines, triggers and events agree")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("backup", "restore"):
        child = commands.add_parser(command)
        child.add_argument("--defaults-file", required=True, type=Path)
        child.add_argument("--database", required=True)
        child.add_argument("--server-uuid", required=True)
        child.add_argument("--output" if command == "backup" else "--backup", required=True, type=Path)
        if command == "restore":
            child.add_argument("--confirm-test-target", action="store_true")
    args = parser.parse_args()
    args.defaults_file = args.defaults_file.resolve()
    try:
        (backup if args.command == "backup" else restore)(args)
    except ValueError as error:
        parser.exit(1, "Backup/restore rejected: " + str(error) + "\n")
    except (OSError, KeyError):
        # 文件系统异常可能带私有路径，MySQL stderr 可能含端点；不写到共享日志。
        parser.exit(1, "Backup/restore failed: check private files, client availability and receipt\n")


if __name__ == "__main__":
    main()
