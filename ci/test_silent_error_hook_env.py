#!/usr/bin/env python3
"""自检从 Git 钩子启动时，不得改动调用者仓库配置。"""
import os
import pathlib
import subprocess
import tempfile

gate = pathlib.Path(__file__).resolve().with_name("silent-error-gate.js")
with tempfile.TemporaryDirectory(prefix="silent-error-hook-") as directory:
    root = pathlib.Path(directory)
    env = os.environ.copy()
    for key in subprocess.check_output(["git", "rev-parse", "--local-env-vars"], text=True).splitlines():
        env.pop(key, None)
    subprocess.run(["git", "init", "-q", directory], env=env, check=True)
    config = root / ".git/config"
    before = config.read_bytes()
    env["GIT_DIR"] = str(root / ".git")
    result = subprocess.run(["node", str(gate), "--selftest"], cwd=root, env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert config.read_bytes() == before, "临时仓库自检修改了调用者的 Git 配置"
    assert result.returncode == 0, result.stderr
print("通过：Git 钩子环境下自检成功，调用者仓库配置保持一致")
