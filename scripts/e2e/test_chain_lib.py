import unittest

from scripts.e2e.chain_lib import (FORBIDDEN, load_base, load_db_config, validate_db_config,
                                   verify_connected_database)


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
        with self.assertRaises(SystemExit):
            validate_db_config({"host": "47.103.124.64", "database": "cy_e2e_52"})
        verify_connected_database(self.Connection("cy_e2e_52"), "cy_e2e_52")
        with self.assertRaisesRegex(RuntimeError, "cy_e2e_51.*cy_e2e_52"):
            verify_connected_database(self.Connection("cy_e2e_51"), "cy_e2e_52")

    def test_base_defaults_to_local_backend_and_refuses_production_override(self):
        self.assertEqual("http://127.0.0.1:8099", load_base({}))
        self.assertEqual("http://127.0.0.1:18099",
                         load_base({"CHENGYIN_E2E_BASE": "http://127.0.0.1:18099/"}))
        for host in sorted(FORBIDDEN):
            with self.assertRaises(SystemExit):
                load_base({"CHENGYIN_E2E_BASE": "http://%s:8080" % host})
        with self.assertRaises(SystemExit):
            load_base({"CHENGYIN_E2E_BASE": "not-a-url"})


if __name__ == "__main__":
    unittest.main()
