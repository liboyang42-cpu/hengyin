#!/usr/bin/env python3
"""Verified logical backup and restore to an explicitly identified empty test database."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


BACKUP_FORMAT = 3
DEFINITION_ALGORITHM = "mysql8-column-charset-v1"


def mysql(args, sql, definition=False):
    # SHOW CREATE uses escaped batch cells, so embedded newlines/tabs cannot become records.
    options = ["--batch", "--skip-raw", "--column-names"] if definition else ["--batch", "--raw", "--skip-column-names"]
    run = subprocess.run(["mysql", "--defaults-extra-file=" + str(args.defaults_file),
                          "--default-character-set=utf8mb4", *options, "--database=" + args.database],
                         input=sql.encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if run.returncode:
        raise ValueError("MySQL command failed; no backup or restore is considered verified")
    return run.stdout.decode().rstrip("\n") if definition else run.stdout.decode().strip()


def identify(args):
    if not args.defaults_file.is_file() or args.defaults_file.stat().st_mode & 0o077:
        raise ValueError("MySQL defaults file must exist with owner-only permissions")
    identity = mysql(args, "SELECT @@server_uuid, VERSION(), DATABASE()").split("\t")
    if len(identity) != 3 or identity[0] != args.server_uuid or identity[2] != args.database:
        raise ValueError("Server UUID or database differs from the explicit target")
    return identity


def quoted(name):
    return "`" + name.replace("`", "``") + "`"


def batch_cell(value):
    """Decode mysql's batch escaping once, preserving literal backslashes and Unicode."""
    escapes = {"0": "\0", "n": "\n", "t": "\t", "r": "\r", "b": "\b", "Z": "\x1a", "\\": "\\"}
    result = []
    index = 0
    while index < len(value):
        if value[index] == "\\":
            index += 1
            if index == len(value) or value[index] not in escapes:
                raise ValueError("Unverifiable MySQL definition escaping")
            result.append(escapes[value[index]])
        else:
            result.append(value[index])
        index += 1
    return "".join(result)


def table_charset_definition(args, name, ddl):
    """Make only MySQL #110825's implicit utf8mb4 column charset explicit."""
    modes = {mode.strip().upper() for mode in mysql(args, "SELECT @@SESSION.sql_mode").split(",")}
    if modes & {"ANSI_QUOTES", "NO_BACKSLASH_ESCAPES"}:
        raise ValueError("Unsupported SQL mode for verifiable table definition quoting")
    raw = mysql(args, "SELECT JSON_ARRAY(COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME)"
                " FROM information_schema.COLUMNS WHERE BINARY TABLE_SCHEMA=BINARY DATABASE()"
                " AND BINARY TABLE_NAME=0x" + name.encode("utf-8").hex() + " ORDER BY ORDINAL_POSITION")
    rows = [json.loads(line) for line in raw.split("\n") if line]
    columns = {}
    for row in rows:
        if (not isinstance(row, list) or len(row) != 3 or not isinstance(row[0], str)
                or not row[0] or row[0] in columns or ((row[1] is None) != (row[2] is None))
                or any(value is not None and (not isinstance(value, str)
                       or not re.fullmatch("[a-zA-Z0-9_]+", value)) for value in row[1:])):
            raise ValueError("Unverifiable column charset metadata")
        columns[row[0]] = row[1:]
    # Locate column-level clauses without rewriting whitespace, quoted strings or comments.
    pattern = re.compile(r"\s+|--[ \t\r\n][^\n]*|#[^\n]*|/\*.*?\*/|"
                         r"'(?:\\.|''|[^'\\])*'|\"(?:\\.|\"\"|[^\"\\])*\"|"
                         r"`(?:``|[^`])*`|[a-zA-Z_][a-zA-Z0-9_$]*|[^\s]", re.S)
    tokens, depth = [], 0
    for match in pattern.finditer(ddl):
        value = match.group()
        if value.isspace() or value.startswith(("--", "#")):
            continue
        if value.startswith("/*"):
            tokens.append(("comment", value, match.start(), depth))
            continue
        if value in ("'", '"', "`") or (ddl[match.start():].startswith("/*") and not value.endswith("*/")):
            raise ValueError("Unterminated table definition token")
        kind = "identifier" if value.startswith("`") else "literal" if value[0] in "'\"" else "word"
        if value == ")":
            depth -= 1
            if depth < 0:
                raise ValueError("Unbalanced table definition")
        tokens.append((kind, value, match.start(), depth))
        if value == "(":
            depth += 1
    if depth:
        raise ValueError("Unbalanced table definition")
    opening = next((i for i, token in enumerate(tokens) if token[1] == "(" and token[3] == 0), None)
    closing = next((i for i, token in enumerate(tokens) if token[1] == ")" and token[3] == 0), None)
    if opening is None or closing is None or closing <= opening:
        raise ValueError("Unverifiable CREATE TABLE column list")
    boundaries = [opening] + [i for i in range(opening + 1, closing)
                              if tokens[i][1] == "," and tokens[i][3] == 1] + [closing]
    insertions, seen = [], set()
    for left, right in zip(boundaries, boundaries[1:]):
        part = tokens[left + 1:right]
        if not part:
            raise ValueError("Empty table definition declaration")
        first = part[0]
        if first[0] != "identifier":
            if first[1].upper() not in ("PRIMARY", "UNIQUE", "KEY", "CONSTRAINT", "CHECK", "FULLTEXT", "SPATIAL"):
                raise ValueError("Unknown table definition declaration")
            continue
        column = first[1][1:-1].replace("`" * 2, "`")
        if column not in columns or column in seen:
            raise ValueError("CREATE TABLE differs from column metadata")
        seen.add(column)
        charset, collation = columns[column]
        top = [token for token in part[1:] if token[3] == 1 and token[0] == "word"]
        collates = [i for i, token in enumerate(top) if token[1].upper() == "COLLATE"]
        charsets = [i for i, token in enumerate(top) if token[1].upper() in ("CHARACTER", "CHARSET")]
        if len(collates) > 1 or len(charsets) > 1:
            raise ValueError("Ambiguous column charset clauses")
        if charsets:
            index = charsets[0]
            if (top[index][1].upper() != "CHARACTER" or index + 2 >= len(top)
                    or top[index + 1][1].upper() != "SET" or top[index + 2][1] != charset):
                raise ValueError("Column charset differs from actual metadata")
        if collates:
            index = collates[0]
            if index + 1 >= len(top) or top[index + 1][1] != collation:
                raise ValueError("Column collation differs from actual metadata")
            if charset == "utf8mb4" and not charsets:
                if any(token[0] == "comment" and token[1].startswith("/*!") for token in part):
                    raise ValueError("Cannot canonicalize executable column comments")
                insertions.append(top[index][2])
    if seen != set(columns):
        raise ValueError("Column metadata is incomplete")
    for position in sorted(insertions, reverse=True):
        ddl = ddl[:position] + "CHARACTER SET utf8mb4 " + ddl[position:]
    return ddl, rows


def definition_hash(args, kind, name):
    rows = mysql(args, "SHOW CREATE " + kind + " " + quoted(name), definition=True).split("\n")
    if len(rows) != 2:
        raise ValueError("Unverifiable MySQL object definition")
    headers, values = (row.split("\t") for row in rows)
    if len(headers) != len(values) or len(set(headers)) != len(headers):
        raise ValueError("Unexpected MySQL definition fields")
    fields = dict(zip(headers, map(batch_cell, values)))
    identity_column = {"DATABASE": "Database", "TABLE": "Table", "VIEW": "View", "PROCEDURE": "Procedure",
                       "FUNCTION": "Function", "TRIGGER": "Trigger", "EVENT": "Event"}[kind]
    create_column = "SQL Original Statement" if kind == "TRIGGER" else "Create " + kind.title()
    if fields.get(identity_column) != name or not fields.get(create_column, "").lstrip().upper().startswith("CREATE "):
        # SHOW CREATE ROUTINE can return SQL NULL when the account cannot see the body.
        raise ValueError("Object definition is missing or not visible to the backup account")
    if kind == "TABLE":
        fields[create_column], fields["Column Charsets"] = table_charset_definition(args, name, fields[create_column])
    if kind == "TRIGGER":
        fields.pop("Created", None)  # Server-assigned creation time changes on a legitimate restore.
    return hashlib.sha256(json.dumps(fields, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def object_rows(args, columns, table, schema_column):
    raw = mysql(args, "SELECT JSON_ARRAY(" + columns + ") FROM information_schema." + table
                + " WHERE " + schema_column + "=DATABASE()")
    return [json.loads(line) for line in raw.split("\n") if line]


def snapshot(args):
    tables = {}
    views = []
    definitions = {"DATABASE:" + args.database: definition_hash(args, "DATABASE", args.database)}
    for name, kind in object_rows(args, "TABLE_NAME, TABLE_TYPE", "TABLES", "TABLE_SCHEMA"):
        if kind not in ("BASE TABLE", "VIEW"):
            raise ValueError("Unsupported schema object type")
        definitions[("VIEW:" if kind == "VIEW" else "TABLE:") + name] = definition_hash(
            args, "VIEW" if kind == "VIEW" else "TABLE", name)
        if kind == "VIEW":
            views.append(name)
            continue
        checksum = mysql(args, "CHECKSUM TABLE " + quoted(name) + " EXTENDED").split("\t")[-1]
        if checksum == "NULL":
            raise ValueError("A table has no verifiable checksum")
        tables[name] = {"rows": int(mysql(args, "SELECT COUNT(*) FROM " + quoted(name))), "checksum": checksum}
    counts = {}
    for table, columns, schema_column, fixed_kind in (
            ("ROUTINES", "ROUTINE_NAME, ROUTINE_TYPE", "ROUTINE_SCHEMA", None),
            ("TRIGGERS", "TRIGGER_NAME", "TRIGGER_SCHEMA", "TRIGGER"),
            ("EVENTS", "EVENT_NAME", "EVENT_SCHEMA", "EVENT")):
        rows = object_rows(args, columns, table, schema_column)
        counts[table] = len(rows)
        for row in rows:
            name, kind = (row[0], fixed_kind) if fixed_kind else row
            if kind not in ("PROCEDURE", "FUNCTION", "TRIGGER", "EVENT"):
                raise ValueError("Unsupported stored object type")
            definitions[kind + ":" + name] = definition_hash(args, kind, name)
    return {"tables": tables, "views": sorted(views), "objects": counts, "definitions": definitions}


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
                                  "--default-character-set=utf8mb4",
                                  "--single-transaction", "--routines", "--triggers", "--events",
                                  "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob", args.database],
                                 stdout=stream, stderr=subprocess.PIPE)
            stream.flush()
            os.fsync(stream.fileno())
            if run.returncode or partial.stat().st_size == 0 or snapshot(args) != before:
                raise ValueError("Dump failed, is empty, or source changed; quiesce writes and retry")
            manifest = {"formatVersion": BACKUP_FORMAT, "definitionAlgorithm": DEFINITION_ALGORITHM, "serverUuid": identity[0], "mysqlVersion": identity[1], "database": args.database,
                        "sha256": digest(partial), "snapshot": before}
            with receipt.open("x", encoding="utf-8") as result:
                os.chmod(receipt, 0o600)
                json.dump(manifest, result, ensure_ascii=False, indent=2)
            partial.rename(output)
        finally:
            if partial.exists():
                partial.unlink()
    print("Backup created with checksum receipt; restore drill still required")


def manifest_definitions(manifest, database):
    """Legacy or truncated receipts cannot authorize a restore write."""
    snapshot = manifest.get("snapshot", {}) if isinstance(manifest, dict) else {}
    proof = snapshot.get("definitions", {}) if isinstance(snapshot, dict) else {}
    valid = isinstance(proof, dict) and bool(proof) and isinstance(manifest, dict) and manifest.get("formatVersion") == BACKUP_FORMAT and manifest.get("definitionAlgorithm") == DEFINITION_ALGORITHM
    if valid:
        tables, views, counts = snapshot.get("tables"), snapshot.get("views"), snapshot.get("objects")
        valid = isinstance(tables, dict) and isinstance(views, list) and isinstance(counts, dict)
    if valid:
        expected = {"DATABASE:" + database} | {"TABLE:" + name for name in tables} | {"VIEW:" + name for name in views}
        stored = {key for key in proof if isinstance(key, str) and key.partition(":")[0] in
                  ("PROCEDURE", "FUNCTION", "TRIGGER", "EVENT") and key.partition(":")[2]}
        valid = set(proof) == expected | stored and all(isinstance(value, str) and re.fullmatch("[a-f0-9]{64}", value)
                                                       for value in proof.values())
        for plural, kinds in (("ROUTINES", ("PROCEDURE", "FUNCTION")), ("TRIGGERS", ("TRIGGER",)), ("EVENTS", ("EVENT",))):
            count = counts.get(plural)
            valid = valid and type(count) is int and count >= 0 and count == sum(key.partition(":")[0] in kinds for key in stored)
    if not valid:
        raise ValueError("Backup manifest lacks complete definition proof; keep the old backup and create a verified v3 backup with the explicit definition algorithm")
    return proof


def restore(args):
    manifest = json.loads(Path(str(args.backup) + ".json").read_text(encoding="utf-8"))
    proof = manifest_definitions(manifest, args.database)
    identity = identify(args)
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
    if target["definitions"]["DATABASE:" + args.database] != proof["DATABASE:" + args.database]:
        raise ValueError("Restore database definition differs; create the empty target with matching source options")
    with args.backup.open("rb") as stream:
        run = subprocess.run(["mysql", "--defaults-extra-file=" + str(args.defaults_file),
                              "--default-character-set=utf8mb4",
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
