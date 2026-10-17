import os
import pathlib
import subprocess
import sys
import tempfile
import unittest


class VpsRunnerGuardTest(unittest.TestCase):
    def reject(self, system, settings, java_version="17"):
        runner = pathlib.Path(__file__).with_name("generic-gameplay-http-e2e.sh")
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            calls = root / "calls"
            for command in ("uname", "mvn", "java", "mysql", "redis-server", "redis-cli", "python3", "rg"):
                body = "#!/bin/sh\n"
                if command == "uname":
                    body += "echo " + system + "\n"
                elif command == "java":
                    body += 'echo \'openjdk version "' + java_version + '.0.1"\' >&2\n'
                else:
                    body += 'echo "' + command + '" >> "' + str(calls) + '"\nexit 97\n'
                path = root / command
                path.write_text(body)
                path.chmod(0o700)
            environment = {key: value for key, value in os.environ.items()
                           if not key.startswith("CHENGYIN_")}
            environment.update(settings)
            environment["PATH"] = str(root) + ":/usr/bin:/bin"
            result = subprocess.run(["/bin/bash", str(runner)], env=environment,
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertFalse(calls.exists(), "Rejected runs must not touch build/database/Redis tools")

    def test_mac_linux_without_vps_flag_and_missing_target_are_rejected(self):
        self.reject("Darwin", {})
        self.reject("Linux", {})
        self.reject("Linux", {"CHENGYIN_VPS_TEST_RUN": "1"})

    def test_foreign_target_invalid_port_and_wrong_java_are_rejected(self):
        settings = {"CHENGYIN_VPS_TEST_RUN": "1", "CHENGYIN_MYBATIS_IT_HOST": "127.0.0.1",
                    "CHENGYIN_MYBATIS_IT_PORT": "33080"}
        self.reject("Linux", dict(settings, CHENGYIN_MYBATIS_IT_HOST="example.invalid"))
        self.reject("Linux", dict(settings, CHENGYIN_MYBATIS_IT_PORT="0"))
        self.reject("Linux", settings)

    def test_schema_execution_requires_explicit_vps_before_planning_or_mysql(self):
        schema = pathlib.Path(__file__).with_name("generic-gameplay-fresh-schema.py")
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith("CHENGYIN_")}
        with tempfile.TemporaryDirectory() as directory:
            report = pathlib.Path(directory) / "report.json"
            result = subprocess.run([sys.executable, str(schema), "--execute", "--report", str(report)],
                                    env=environment, capture_output=True, text=True, timeout=10)
            self.assertEqual(2, result.returncode, result.stderr)
            self.assertIn("dedicated test VPS", result.stderr)
            self.assertFalse(report.exists())


if __name__ == "__main__":
    unittest.main()
