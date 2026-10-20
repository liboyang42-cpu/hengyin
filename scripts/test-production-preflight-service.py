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


def configured_environment():
    configured = {name: "fixture-secret-never-print-" + str(index) for index, name in enumerate(REQUIRED)}
    configured.update(CHENGYIN_WITHDRAWAL_PII_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                      CHENGYIN_TENCENT_MAP_ENABLED="true", TZ="Asia/Shanghai",
                      CHENGYIN_WXPAY_REFUND_NOTIFY_URL="https://api.example.com/prod-api/api/registration/refund-callback",
                      CHENGYIN_WXPAY_NOTIFY_URL="https://api.example.com/prod-api/api/registration/callback",
                      CHENGYIN_SMS_ACCESS_KEY_ID="fixture-secret-never-print-sms-id",
                      CHENGYIN_SMS_ACCESS_KEY_SECRET="fixture-secret-never-print-sms-secret",
                      CHENGYIN_DEEPSEEK_API_KEY="fixture-secret-never-print-deepseek")
    return configured


@unittest.skipUnless(sys.platform == "linux", "service /proc checks run on the VPS")
class ServiceEnvironmentTest(unittest.TestCase):
    def test_actual_service_environment_and_timezone_override(self):
        # 每个变量取不同值：预检会拒绝密钥复用（F-L6-11）。
        configured = configured_environment()
        for changes, argument, caller_changes, expected in (
                ({}, "", {}, 0), ({"CHENGYIN_JWT_SECRET": ""}, "", {}, 1),
                ({}, "-Duser.timezone=UTC", {}, 1), ({"TZ": "UTC"}, "", {}, 1),
                ({}, "", {"COUPON_QR_SECRET": None}, 0),
                ({"COUPON_QR_SECRET": ""}, "", {}, 1),
                ({"COUPON_QR_SECRET": None}, "", {}, 1),
                ({"CHENGYIN_WXPAY_NOTIFY_URL": None}, "", {}, 1),
                ({"CHENGYIN_WXPAY_NOTIFY_URL": "http://api.example.com/prod-api/api/registration/callback"}, "", {}, 1),
                ({"CHENGYIN_WXPAY_NOTIFY_URL": "https://api.example.com/prod-api/api/registration/refund-callback"}, "", {}, 1)):
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

    def test_enabled_capabilities_require_their_actual_credentials(self):
        no_ai = {"CHENGYIN_DEEPSEEK_API_KEY": None, "AI_MODEL_ENABLED": "false", "AI_VISION_ENABLED": "false"}
        cases = (
            ({"CHENGYIN_SMS_ACCESS_KEY_ID": None}, 1),
            ({"CHENGYIN_SMS_ACCESS_KEY_SECRET": "  "}, 1),
            (no_ai, 0),
            ({**no_ai, "AI_MODEL_ENABLED": "true"}, 1),
            ({**no_ai, "AI_MODEL_ENABLED": "true", "AI_MODEL_PROVIDER": "mock"}, 1),
            ({**no_ai, "AI_MODEL_ENABLED": "true", "CHENGYIN_AI_API_KEY": "fixture-secret-never-print-model"}, 0),
            ({**no_ai, "AI_MODEL_ENABLED": "true", "AI_MODEL_APIKEY": "fixture-secret-never-print-model"}, 0),
            ({**no_ai, "AI_VISION_ENABLED": "true"}, 1),
            ({**no_ai, "AI_VISION_ENABLED": "true", "AI_VISION_APIKEY": "fixture-secret-never-print-vision"}, 0),
            ({**no_ai, "AI_PROVIDERS_DEEPSEEK_ENABLED": "true"}, 0),
            ({**no_ai, "AI_PROVIDERS_MINIMAX_ENABLED": "true"}, 1),
            ({**no_ai, "AI_PROVIDERS_MINIMAX_ENABLED": "true", "CHENGYIN_MINIMAX_API_KEY": "fixture-secret-never-print-minimax"}, 0),
            ({**no_ai, "CHENGYIN_IMAGESEG_ENABLED": "true"}, 1),
            ({**no_ai, "CHENGYIN_IMAGESEG_ENABLED": "true", "CHENGYIN_ALIYUN_VIAPI_AK_ID": "fixture-secret-never-print-image-id"}, 1),
            ({**no_ai, "CHENGYIN_IMAGESEG_ENABLED": "true", "CHENGYIN_ALIYUN_VIAPI_AK_ID": "fixture-secret-never-print-image-id", "CHENGYIN_ALIYUN_VIAPI_AK_SECRET": "fixture-secret-never-print-image-secret"}, 0),
            ({**no_ai, "CHENGYIN_IMAGESEG_ENABLED": "true", "AI_IMAGESEG_ENABLED": "false"}, 0),
            ({**no_ai, "AI_VOICE_ENABLED": "true"}, 1),
            ({**no_ai, "AI_VOICE_ENABLED": "true", "CHENGYIN_DASHSCOPE_API_KEY": "fixture-secret-never-print-voice"}, 1),
            ({**no_ai, "AI_VOICE_ENABLED": "true", "CHENGYIN_DASHSCOPE_API_KEY": "fixture-secret-never-print-voice", "AI_VOICE_WORKSPACEID": "fixture-workspace"}, 0),
            ({**no_ai, "CHENGYIN_ASR_ENABLED": "true"}, 1),
            ({**no_ai, "CHENGYIN_ASR_ENABLED": "true", "CHENGYIN_DASHSCOPE_API_KEY": "fixture-secret-never-print-voice"}, 0),
            ({**no_ai, "CHENGYIN_ASR_ENABLED": "true", "CHENGYIN_DASHSCOPE_API_KEY": "fixture-secret-never-print-voice", "AI_VOICE_REGION": "cn-shanghai"}, 1),
            ({"AI_MODEL_APIKEY": " ", "AI_VISION_ENABLED": "false"}, 1),
            ({**no_ai, "AI_MODEL_ENABLED": " true ", "CHENGYIN_AI_API_KEY": "fixture-secret-never-print-model"}, 0),
            ({**no_ai, "AI_VOICE_ENABLED": "true", "AI_VOICE_ASRENABLED": "bogus", "CHENGYIN_DASHSCOPE_API_KEY": "fixture-secret-never-print-voice", "AI_VOICE_WORKSPACEID": "fixture-workspace"}, 1),
        )
        for changes, expected in cases:
            with self.subTest(changed_names=tuple(changes), expected=expected):
                env = {**os.environ, **configured_environment(), **changes}
                env = {name: value for name, value in env.items() if value is not None}
                run = subprocess.run(["bash", str(ROOT / "scripts/production-preflight.sh"), "--env"],
                                     env=env, capture_output=True, text=True)
                self.assertEqual(expected, run.returncode, run.stdout + run.stderr)
                self.assertNotIn("fixture-secret-never-print", run.stdout + run.stderr)

    def test_service_capability_flags_are_not_taken_from_the_caller(self):
        for service_enabled, caller_enabled, expected in (("true", "false", 1), ("false", "true", 0)):
            with self.subTest(service_enabled=service_enabled), tempfile.TemporaryDirectory() as temp:
                service_env = {**os.environ, **configured_environment(), "AI_MODEL_ENABLED": "false",
                               "AI_VISION_ENABLED": service_enabled}
                service_env.pop("CHENGYIN_DEEPSEEK_API_KEY")
                child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], env=service_env)
                try:
                    fake = Path(temp) / "systemctl"
                    fake.write_text("#!/bin/sh\nprintf '%s\\n' '" + str(child.pid) + "'\n")
                    fake.chmod(0o755)
                    caller_env = {**os.environ, **configured_environment(), "AI_MODEL_ENABLED": "false",
                                  "AI_VISION_ENABLED": caller_enabled, "PATH": temp + os.pathsep + os.environ["PATH"]}
                    run = subprocess.run(["bash", str(ROOT / "scripts/production-preflight.sh")],
                                         env=caller_env, capture_output=True, text=True)
                    self.assertEqual(expected, run.returncode, run.stdout + run.stderr)
                    self.assertNotIn("fixture-secret-never-print", run.stdout + run.stderr)
                finally:
                    child.terminate()
                    child.wait()


if __name__ == "__main__":
    unittest.main()
