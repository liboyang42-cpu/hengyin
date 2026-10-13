#!/usr/bin/env python3
"""Thin global-skill entry for the versioned repository migration runner."""
import importlib.util
import os
import pathlib
import subprocess
import sys


FALLBACK_REPO = pathlib.Path("/Users/arlan/Downloads/chengyin")
EXPECTED_REMOTES = ("https://github.com/liboyang42-cpu/hengyin.git",
                    "https://github.com/liboyang42-cpu/chengyin.git",
                    "https://codeup.aliyun.com/6a1cf1a96bb471afdf23d855/chengyin.git")  # 同一仓库两个托管地址;判据没放宽,身份契约七条钉住
_RUNNER_MODULE = None


def current_git_root():
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
    )
    if result.returncode == 0 and result.stdout.strip():
        return pathlib.Path(result.stdout.strip())
    return None


def normalize_remote_url(url):
    """归一写法:ssh/https、带不带 .git、尾斜杠、**URL 内嵌凭据**。

    云效 CI 克隆出的 origin 形如 https://<账号>:<令牌>@codeup.aliyun.com/... ——
    不剥掉凭据段,身份判据永远匹配不上(云效 run 32 的 git remote -v 实测)。
    """
    url = (url or "").strip()
    url = url.replace("git@github.com:", "https://github.com/", 1).replace(
        "git@codeup.aliyun.com:", "https://codeup.aliyun.com/", 1)
    scheme, sep, rest = url.partition("://")
    if sep and "@" in rest.split("/", 1)[0]:
        url = scheme + sep + rest.split("@", 1)[1]
    url = url.rstrip("/")
    if url.endswith(".git"):
        url = url[: -len(".git")]
    return url


def is_chengyin_repo(root):
    """身份判据 = **任意一个**远端的 URL 归一后等于期望仓库。

    2026-08-19 修:原来问的是「有没有一个名字叫 github 的远端且 URL 全等」,
    那是把本机的远端命名习惯当成了仓库身份。CI 里只有 origin 且 URL 不带 .git ⇒
    判 False ⇒ 「找不到版本化 runner」(PR #741 就红在这)。
    意图没放宽:仍只认那一个仓库,只是不再要求它叫什么名字。
    判据由 test_repo_identity_is_the_remote_url_not_the_remote_name 四条钉住。
    """
    if not (root / "ci" / "sql_splitter.py").is_file():
        return False
    result = subprocess.run(
        ["git", "-C", str(root), "config", "--get-regexp", r"^remote\..*\.url$"],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return False
    expected = {normalize_remote_url(u) for u in EXPECTED_REMOTES}
    for line in result.stdout.splitlines():
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        if normalize_remote_url(parts[1]) in expected:
            return True
    return False


def resolve_runner():
    explicit_repo = os.environ.get("CHENGYIN_REPO")
    roots = [pathlib.Path(explicit_repo)] if explicit_repo else []
    roots.extend([current_git_root(), FALLBACK_REPO])
    this_file = pathlib.Path(__file__).resolve()
    for root in roots:
        if root is None:
            continue
        root = root.resolve()
        candidate = root / "ci" / "run_migration.py"
        if (is_chengyin_repo(root) and candidate.is_file()
                and candidate.resolve() != this_file):
            template = root / "ci" / "run_migration_skill_entry.py"
            if (not template.is_file()
                    or this_file.read_bytes() != template.read_bytes()):
                raise SystemExit(
                    "全局 migration 薄入口与当前仓库模板发生字节漂移；"
                    "已在加载 runner 和连接数据库前拒绝。请先同步 "
                    f"{template}")
            return candidate
    raise SystemExit(
        "找不到版本化 runner: ci/run_migration.py；请在城瘾 worktree 内运行，"
        "或设置 CHENGYIN_REPO 指向已检出的仓库。")


def _load_runner_module():
    global _RUNNER_MODULE
    if _RUNNER_MODULE is None:
        runner = resolve_runner()
        # runner 顶部 `from sql_splitter import ...` 要 ci/ 在 sys.path 上:main() 的
        # os.execv 由解释器自动补,这条路径没有 ⇒ audit_sys_job.py(手动落库的真防线)
        # 开箱即 ModuleNotFoundError(2026-08-21 实测;负控在 ci 契约测试里)。
        sys.path.insert(0, str(pathlib.Path(runner).resolve().parent))
        spec = importlib.util.spec_from_file_location(
            "chengyin_run_migration_impl", runner)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _RUNNER_MODULE = module
    return _RUNNER_MODULE


def __getattr__(name):
    """Lazily expose only the public names consumed by the audit wrapper."""
    if name not in ("DEFAULT_CONFIG", "connect"):
        raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
    return getattr(_load_runner_module(), name)


def main():
    runner = resolve_runner()
    os.execv(sys.executable, [sys.executable, str(runner), *sys.argv[1:]])


if __name__ == "__main__":
    main()
