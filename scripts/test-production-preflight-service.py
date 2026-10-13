"""On Linux, inspect a real child process rather than the caller environment."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = ("CHENGYIN_DB_PASSWORD", "CHENGYIN_JWT_SECRET", "CHENGYIN_WXPAY_APIV3_KEY",
            "CHENGYIN_WXMINI_SECRET", "CHENGYIN_DRUID_PASSWORD", "COUPON_QR_SECRET",
            "ALARM_WEBHOOK", "CHENGYIN_OSS_ENDPOINT", "CHENGYIN_OSS_ACCESS_KEY_ID",
            "CHENGYIN_OSS_ACCESS_KEY_SECRET", "CHENGYIN_OSS_BUCKET_NAME", "CHENGYIN_TENCENT_MAP_KEY")


@unittest.skipUnless(sys.platform == "linux", "service /proc checks run on the VPS")
class ServiceEnvironmentTest(unittest.TestCase):
    def test_actual_service_environment_and_timezone_override(self):
        configured = dict.fromkeys(REQUIRED, "fixture-secret-never-print")
        configured.update(CHENGYIN_WITHDRAWAL_PII_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                          CHENGYIN_TENCENT_MAP_ENABLED="true", TZ="Asia/Shanghai")
        for changes, argument, caller_changes, expected in (
                ({}, "", {}, 0), ({"CHENGYIN_JWT_SECRET": ""}, "", {}, 1),
                ({}, "-Duser.timezone=UTC", {}, 1), ({"TZ": "UTC"}, "", {}, 1),
                ({}, "", {"COUPON_QR_SECRET": None}, 0),
                ({"COUPON_QR_SECRET": ""}, "", {}, 1),
                ({"COUPON_QR_SECRET": None}, "", {}, 1)):
            with self.subTest(changes=changes, argument=argument), tempfile.TemporaryDirectory() as temp:
                service_env = {**os.environ, **configured, **changes}
                service_env = {name: value for name, value in service_env.items() if value is not None}
                child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)", argument],
                                         env=service_env)
                try:
                    fake = Path(temp) / "systemctl"
                    fake.write_text("#!/bin/sh\nprintf '%s\\n' '" + str(child.pid) + "'\n")
                    fake.chmod(0o755)
                    caller_env = {**os.environ, **configured, **caller_changes}
                    caller_env = {name: value for name, value in caller_env.items() if value is not None}
                    caller_env["PATH"] = temp + os.pathsep + os.environ["PATH"]
                    run = subprocess.run(["bash", str(ROOT / "scripts/production-preflight.sh")],
                                         env=caller_env, capture_output=True, text=True)
                    self.assertEqual(expected, run.returncode, run.stdout + run.stderr)
                    self.assertNotIn("fixture-secret-never-print", run.stdout + run.stderr)
                finally:
                    child.terminate()
                    child.wait()


if __name__ == "__main__":
    unittest.main()
