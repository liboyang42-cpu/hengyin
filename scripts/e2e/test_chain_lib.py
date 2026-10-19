import hashlib
import unittest

from scripts.e2e.chain_lib import FORBIDDEN_SHA256, load_db_config, validate_db_config, verify_connected_database


class ChainLibDatabaseConfigTest(unittest.TestCase):
    class Connection:
        def __init__(self, database):
            self.database = database

        def cursor(self):
            outer = self

            class Cursor:
                def execute(self, sql):
                    if sql != "SELECT DATABASE()":
                        raise AssertionError(sql)

                def fetchone(self):
                    return (outer.database,)

                def close(self):
                    pass

            return Cursor()

    def test_environment_selects_one_database_with_local_defaults(self):
        defaults = load_db_config({})
        self.assertEqual(("127.0.0.1", 3316, "root", "", "cy_e2e_51"), (
            defaults["host"], defaults["port"], defaults["user"], defaults["password"],
            defaults["database"],
        ))
        configured = load_db_config({
            "CHENGYIN_E2E_DB_HOST": "localhost",
            "CHENGYIN_E2E_DB_PORT": "4416",
            "CHENGYIN_E2E_DB_USER": "tester",
            "CHENGYIN_E2E_DB_PASSWORD": "secret",
            "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_52",
        })
        self.assertEqual(("localhost", 4416, "tester", "secret", "cy_e2e_52"), (
            configured["host"], configured["port"], configured["user"],
            configured["password"], configured["database"],
        ))

    def test_production_host_and_wrong_connected_schema_fail_closed(self):
        # 生产地址不写明文:夹具地址在运行时算哈希,走同一条哈希比对路径。
        fixture_host = "203.0.113.7"
        denylist = frozenset({hashlib.sha256(fixture_host.encode("utf-8")).hexdigest()})
        with self.assertRaises(SystemExit):
            validate_db_config({"host": fixture_host, "database": "cy_e2e_52"}, {}, denylist)
        with self.assertRaises(SystemExit):
            validate_db_config({"host": " %s " % fixture_host, "database": "cy_e2e_52"}, {}, denylist)
        validate_db_config({"host": fixture_host, "database": "cy_e2e_52"}, {}, frozenset())
        with self.assertRaises(SystemExit):
            validate_db_config({"host": fixture_host, "database": "cy_e2e_52"},
                               {"CHENGYIN_FORBIDDEN_DB_HOSTS": "198.51.100.1, " + fixture_host}, frozenset())
        self.assertEqual(frozenset({
            "d8c695fe7dd5f6ffc048b72c96621f7e7d13d3ec23b76aab523514183e7474d9",
            "ce1a8527392b68bab1ab6a91238a5e6909cbbb937b9eb077d1025499a59f03fe",
        }), FORBIDDEN_SHA256)
        verify_connected_database(self.Connection("cy_e2e_52"), "cy_e2e_52")
        with self.assertRaisesRegex(RuntimeError, "cy_e2e_51.*cy_e2e_52"):
            verify_connected_database(self.Connection("cy_e2e_51"), "cy_e2e_52")


if __name__ == "__main__":
    unittest.main()
