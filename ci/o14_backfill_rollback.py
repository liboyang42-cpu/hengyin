#!/usr/bin/env python3
"""Preview or clear an explicitly reviewed O14 FILL batch; never execute migrations."""
import argparse
import datetime
import hashlib
import json
import os
import re
import sys
from pathlib import Path

try:
    from run_migration import assert_mysql_server_provenance
except ModuleNotFoundError:
    from ci.run_migration import assert_mysql_server_provenance

TABLES = ("cms_activity", "cms_topic")
LOG = "o14_city_code_backfill_log"
SOURCES = {"o14_v2:club_city", "o14_v2:parent_topic"}
IDENTIFIER = re.compile(r"[A-Za-z0-9_]{1,64}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
SERVER_UUID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\Z")


class Refusal(ValueError):
    """A safe, non-secret explanation suitable for the command output."""


def require(condition, message):
    if not condition:
        raise Refusal(message)


def exact_keys(value, keys, label):
    require(isinstance(value, dict) and set(value) == set(keys), label + " fields invalid")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def read_json(path, digest):
    require(isinstance(digest, str) and SHA256.fullmatch(digest), "SHA256 invalid")
    raw = Path(path).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == digest, "artifact SHA256 mismatch")
    try:
        return json.loads(raw, object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError):
        raise Refusal("artifact JSON invalid") from None


def target_key(row):
    require(row.get("target_table") in TABLES, "target table not allowed")
    require(type(row.get("row_id")) is int and 0 < row["row_id"] <= 2**63 - 1,
            "row id invalid")
    return row["target_table"], row["row_id"]


def load_manifest(path, digest, database, expected_count, before_snapshot):
    require(isinstance(database, str) and IDENTIFIER.fullmatch(database), "database invalid")
    require(type(expected_count) is int and expected_count > 0, "expected count invalid")
    manifest = read_json(path, digest)
    exact_keys(manifest, ("format_version", "database", "server_uuid", "expected_count",
                          "before_snapshot_sha256", "rows"), "manifest")
    require(type(manifest["format_version"]) is int and manifest["format_version"] == 1,
            "manifest version invalid")
    require(manifest["database"] == database, "manifest database mismatch")
    require(isinstance(manifest["server_uuid"], str)
            and SERVER_UUID.fullmatch(manifest["server_uuid"]), "server UUID invalid")
    require(type(manifest["expected_count"]) is int
            and manifest["expected_count"] == expected_count, "manifest count mismatch")
    require(isinstance(manifest["rows"], list)
            and len(manifest["rows"]) == expected_count, "manifest row count mismatch")
    keys = set()
    for row in manifest["rows"]:
        exact_keys(row, ("target_table", "row_id", "city_code", "source", "write_time"), "row")
        key = target_key(row)
        require(key not in keys, "duplicate manifest target")
        keys.add(key)
        require(isinstance(row["city_code"], str)
                and re.fullmatch(r"[0-9]{6}", row["city_code"]), "city code invalid")
        require(isinstance(row["source"], str) and row["source"] in SOURCES, "log source invalid")
        require(row["target_table"] != "cms_topic" or row["source"] == "o14_v2:club_city",
                "topic log source invalid")
        try:
            require(isinstance(row["write_time"], str)
                    and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}",
                                     row["write_time"]), "log timestamp invalid")
            datetime.datetime.strptime(row["write_time"], "%Y-%m-%d %H:%M:%S")
        except ValueError:
            raise Refusal("log timestamp invalid") from None

    before = read_json(before_snapshot, manifest["before_snapshot_sha256"])
    exact_keys(before, ("format_version", "database", "server_uuid", "captured_at", "rows"),
               "before snapshot")
    require(type(before["format_version"]) is int and before["format_version"] == 1,
            "before snapshot version invalid")
    require(before["database"] == database and before["server_uuid"] == manifest["server_uuid"],
            "before snapshot target mismatch")
    try:
        require(isinstance(before["captured_at"], str)
                and re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z",
                                 before["captured_at"]), "before snapshot timestamp invalid")
        datetime.datetime.strptime(before["captured_at"], "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        raise Refusal("before snapshot timestamp invalid") from None
    require(isinstance(before["rows"], list) and len(before["rows"]) == expected_count,
            "before snapshot count mismatch")
    before_keys = set()
    for row in before["rows"]:
        exact_keys(row, ("target_table", "row_id", "city_code"), "before row")
        key = target_key(row)
        require(key not in before_keys, "duplicate before snapshot target")
        before_keys.add(key)
        require(row["city_code"] is None, "only original NULL FILL rows can be cleared")
    require(before_keys == keys, "before snapshot rows do not match manifest")
    return manifest


def check_schema(cursor):
    names = TABLES + (LOG,)
    cursor.execute("SELECT table_name,engine,table_type FROM information_schema.tables "
                   "WHERE table_schema=DATABASE() AND table_name IN (%s,%s,%s)", names)
    require(set(cursor.fetchall()) == {(name, "InnoDB", "BASE TABLE") for name in names},
            "all target tables must be InnoDB base tables")
    cursor.execute("SELECT table_name,column_name,seq_in_index FROM information_schema.statistics "
                   "WHERE table_schema=DATABASE() AND index_name='PRIMARY' "
                   "AND table_name IN (%s,%s,%s)", names)
    require(set(cursor.fetchall()) == {(TABLES[0], "id", 1), (TABLES[1], "id", 1),
                                       (LOG, "target_table", 1), (LOG, "row_id", 2)},
            "target primary keys differ from O14 contract")
    cursor.execute("SELECT table_name FROM information_schema.columns "
                   "WHERE table_schema=DATABASE() AND table_name IN (%s,%s) "
                   "AND column_name='city_code' AND is_nullable='YES'", TABLES)
    require(set(cursor.fetchall()) == {(name,) for name in TABLES}, "city_code must be nullable")
    # Without TRIGGER privilege, an empty metadata result does not prove trigger absence.
    grantee = "CONCAT(QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',1)),'@'," \
              "QUOTE(SUBSTRING_INDEX(CURRENT_USER(),'@',-1)))"
    cursor.execute("SELECT COUNT(*) FROM information_schema.user_privileges "
                   "WHERE privilege_type='SELECT' AND grantee=" + grantee)
    require(cursor.fetchone() == (1,),
            "direct global SELECT privilege required to inspect incoming foreign keys")
    cursor.execute("SELECT '*' FROM information_schema.user_privileges WHERE privilege_type='TRIGGER' "
                   "AND grantee=" + grantee + " UNION SELECT '*' FROM information_schema.schema_privileges "
                   "WHERE table_schema=DATABASE() AND privilege_type='TRIGGER' AND grantee=" + grantee +
                   " UNION SELECT table_name FROM information_schema.table_privileges "
                   "WHERE table_schema=DATABASE() AND privilege_type='TRIGGER' AND grantee=" + grantee)
    visibility = {row[0] for row in cursor.fetchall()}
    require("*" in visibility or set(names) <= visibility,
            "direct TRIGGER privilege required to verify absence of target triggers")
    cursor.execute("SELECT COUNT(*) FROM information_schema.triggers "
                   "WHERE trigger_schema=DATABASE() AND event_object_table IN (%s,%s,%s)", names)
    require(cursor.fetchone() == (0,), "target triggers require separate review")
    cursor.execute("SELECT COUNT(*) FROM information_schema.key_column_usage "
                   "WHERE (table_schema=DATABASE() AND table_name IN (%s,%s) "
                   "AND column_name='city_code' AND referenced_table_name IS NOT NULL) "
                   "OR (referenced_table_schema=DATABASE() AND referenced_table_name IN (%s,%s) "
                   "AND referenced_column_name='city_code')", TABLES + TABLES)
    require(cursor.fetchone() == (0,), "city_code foreign keys require separate review")


def read_log(cursor, row):
    cursor.execute("SELECT city_code,source,write_time FROM o14_city_code_backfill_log "
                   "WHERE target_table=%s AND row_id=%s FOR UPDATE",
                   (row["target_table"], row["row_id"]))
    actual = cursor.fetchone()
    expected = (row["city_code"], row["source"],
                datetime.datetime.strptime(row["write_time"], "%Y-%m-%d %H:%M:%S"))
    require(actual == expected, "log ownership missing or drifted")


def read_city(cursor, row):
    # Identifiers only come from the manifest whitelist; values remain bound parameters.
    cursor.execute("SELECT city_code FROM `%s` WHERE id=%%s FOR UPDATE" % row["target_table"],
                   (row["row_id"],))
    actual = cursor.fetchone()
    require(actual is not None, "target row missing")
    return actual[0]


def rollback_batch(connection, manifest, apply=False):
    """Accept only a manifest returned by load_manifest; lock/recheck the complete batch."""
    rows = sorted(manifest["rows"], key=lambda row: (row["target_table"], row["row_id"]))
    try:
        with connection.cursor() as cursor:
            cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        connection.begin()
        with connection.cursor() as cursor:
            cursor.execute("SELECT DATABASE(),@@server_uuid,VERSION(),@@version_comment")
            database, server_uuid, version, comment = cursor.fetchone()
            require((database, server_uuid) == (manifest["database"], manifest["server_uuid"]),
                    "connected database/server differs from manifest")
            assert_mysql_server_provenance(version, comment)
            for row in rows:
                read_log(cursor, row)
                require(read_city(cursor, row) == row["city_code"],
                        "current value drifted or batch already withdrawn")
            # Row reads hold metadata locks until commit; inspect schema after acquiring them.
            check_schema(cursor)
            if apply:
                for row in rows:
                    cursor.execute("UPDATE `%s` SET city_code=NULL "
                                   "WHERE id=%%s AND BINARY city_code=BINARY %%s" % row["target_table"],
                                   (row["row_id"], row["city_code"]))
                    require(cursor.rowcount == 1, "guarded update count mismatch")
                for row in rows:
                    read_log(cursor, row)
                    require(read_city(cursor, row) is None, "transaction readback mismatch")
        if apply:
            connection.commit()
        else:
            connection.rollback()
    except Exception:
        connection.rollback()
        raise
    return {"status": "applied" if apply else "preview", "database": manifest["database"],
            "row_count": len(rows), "logs_preserved": True}


def connect_from_env(database):
    names = ("O14_MYSQL_HOST", "O14_MYSQL_PORT", "O14_MYSQL_DATABASE", "O14_MYSQL_USER",
             "O14_MYSQL_PASSWORD")
    require(all(name in os.environ for name in names), "explicit O14_MYSQL_* environment required")
    require(all(os.environ[name] for name in names[:-1]), "database environment incomplete")
    require(os.environ["O14_MYSQL_DATABASE"] == database, "environment database mismatch")
    require(os.environ["O14_MYSQL_PORT"].isdigit()
            and 0 < int(os.environ["O14_MYSQL_PORT"]) < 65536, "database port invalid")
    import pymysql
    return pymysql.connect(host=os.environ["O14_MYSQL_HOST"], port=int(os.environ["O14_MYSQL_PORT"]),
                           database=database, user=os.environ["O14_MYSQL_USER"],
                           password=os.environ["O14_MYSQL_PASSWORD"], charset="utf8mb4",
                           autocommit=True, connect_timeout=8, read_timeout=30, write_timeout=30)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--before-snapshot", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--expected-count", required=True, type=int)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    connection = None
    try:
        manifest = load_manifest(args.manifest, args.sha256, args.database, args.expected_count,
                                 args.before_snapshot)
        connection = connect_from_env(args.database)
        result = rollback_batch(connection, manifest, apply=args.apply)
        result["manifest_sha256"] = args.sha256
        print(json.dumps(result, sort_keys=True))
        return 0
    except Refusal as error:
        print("REFUSED: " + str(error), file=sys.stderr)
    except Exception:
        # Driver exceptions can contain hosts, SQL values or credentials.
        print("FAILED: artifact/database operation failed; no success claimed. "
              "If commit was attempted, verify its outcome before retrying.", file=sys.stderr)
    finally:
        if connection is not None:
            connection.close()
    return 1


if __name__ == "__main__":
    sys.exit(main())
