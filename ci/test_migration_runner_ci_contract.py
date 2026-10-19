import ast
import importlib.util
import os
import pathlib
import re
import socket
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock


REPO = pathlib.Path(__file__).parents[1]


class MigrationRunnerCiContractTest(unittest.TestCase):
    def assert_backend_mysql_service_image_matches_runner_required_version(
            self, workflow, required_version):
        backend_job = workflow.split("\n  backend:\n", 1)[1].split(
            "\n  mini_program:\n", 1)[0]
        # 两种形态都认,判据仍是「backend 段内那个 MySQL 版本」:
        #   · services: 形态 —— image: mysql:X
        #   · 手动起容器形态 —— env 里的 MYSQL_IMAGE: mysql:X
        # 2026-08-27 起 backend 跑在自托管 macOS runner 上,而 GitHub 官方明确
        # 「service containers 必须用 Linux runner」,所以 services: 用不了、改成 docker run。
        # 版本必须与 runner 精确版本闸一致这条**没有放松**,只是换了个地方写。
        service_image = re.search(
            r"(?m)^\s+(?:image|MYSQL_IMAGE):\s*mysql:([^\s#]+)\s*$", backend_job)

        self.assertIsNotNone(
            service_image, "backend job 必须锁定 MySQL 镜像版本(services image 或 env MYSQL_IMAGE)")
        self.assertEqual(
            required_version,
            service_image.group(1),
            "backend MySQL service image 必须与 runner 精确版本闸保持一致",
        )

    def backend_job(self, workflow):
        return workflow.split("\n  backend:\n", 1)[1].split(
            "\n  mini_program:\n", 1)[0]

    def assert_mybatis_it_container_is_per_run_unique(self, backend_job):
        """并发安全:MyBatis IT 的容器名与宿主端口都不能写死。

        ★ 为什么这条必须是门禁而不是「改一次就好」(2026-08-27):
        concurrency group 按 ref 分组 ⇒ 不同分支的 CI 本来就允许并发。
        今天没出事,只是因为这台 Mac 上 chengyin 仓库【只注册了一个 runner】——
        单 runner 意外充当了互斥锁。一旦加第二个 runner(队列积压时这是显然的解法),
        写死的名字和端口会让:
          · run B 的 `docker rm -f <固定名>` 删掉 run A 正在跑测试的容器;
          · 或 run A 的 JDBC 连上 run B 的容器,读到别人的数据。
        后者【不是响亮的失败,是串数据】,比红更危险 —— 所以要门禁,不能靠记性。
        """
        run_id = "${{ github.run_id }}"
        # 值里含 ${{ github.run_id }},带空格 —— 不能用 \\S+,要吃到行尾
        name_decl = re.search(r"(?m)^\s+IT_NAME:[ \t]*(.+?)[ \t]*$", backend_job)
        self.assertIsNotNone(
            name_decl, "backend job 必须用 env IT_NAME 声明 MyBatis IT 容器名")
        self.assertIn(
            run_id, name_decl.group(1),
            "MyBatis IT 容器名必须含 github.run_id —— 写死的名字在并发时会互删容器")

        self.assertNotRegex(
            backend_job, r"--name\s+chengyin-mybatis-it(?![-\w])",
            "不得用固定容器名 chengyin-mybatis-it")
        self.assertNotRegex(
            backend_job, r"-p\s+\"?127\.0\.0\.1:\d+:3306",
            "不得把宿主端口写死 —— 必须动态取空闲端口($IT_PORT)")
        self.assertIn(
            'echo "IT_PORT=$IT_PORT" >> "$GITHUB_ENV"', backend_job,
            "动态端口必须写进 GITHUB_ENV,后续步骤才拿得到")

    def load_entry(self, path):
        spec = importlib.util.spec_from_file_location("chengyin_migration_entry", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    @staticmethod
    def squeeze(text):
        """折叠换行/缩进，让契约断言只锁 SQL 语义而不锁排版。"""
        return " ".join(text.split())

    def h8_rate_migration_text(self):
        return (REPO / "chengyinhub-admin" / "sql"
                / "migration_20260815_tandianri_h8_rate_convergence.sql").read_text(
                    encoding="utf-8")

    def transactional_migrations(self):
        """所有声明 requires=transaction 的迁移 + 官方分句器切出的语句。"""
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        splitter = self.load_entry(REPO / "ci" / "sql_splitter.py")
        found = []
        for path in sorted((REPO / "chengyinhub-admin" / "sql").glob("migration_*.sql")):
            text = path.read_text(encoding="utf-8")
            if runner.required_transaction_declared(text):
                found.append((path, splitter.split_statements(text)))
        return runner, found

    def temporary_table_reopen_offenders(self):
        """MySQL 同一语句不允许二次打开同一 TEMPORARY 表(ERROR 1137 Can't reopen table)。"""
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        splitter = self.load_entry(REPO / "ci" / "sql_splitter.py")
        offenders = []
        for path in sorted((REPO / "chengyinhub-admin" / "sql").glob("migration_*.sql")):
            created = []
            statements = splitter.split_statements(path.read_text(encoding="utf-8"))
            for index, statement in enumerate(statements, 1):
                masked = runner._mask_literals_and_comments(
                    runner.executable_statement(statement))
                for name in created:
                    hits = len(re.findall(
                        r"(?<![A-Za-z0-9_.`])" + re.escape(name) + r"(?![A-Za-z0-9_])",
                        masked, re.IGNORECASE))
                    if hits > 1:
                        offenders.append("%s stmt#%d refs %s x%d"
                                         % (path.name, index, name, hits))
                create = runner._TEMPORARY_TABLE_NAME.match(masked.lstrip())
                if create:
                    created.append(create.group("name").replace("`", ""))
        return offenders

    def run_atomic_gate_with_unittest_result(self, output, returncode):
        with tempfile.TemporaryDirectory() as temp_dir:
            fake_python = pathlib.Path(temp_dir) / "python3"
            fake_python.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s' \"$FAKE_UNITTEST_OUTPUT\"\n"
                "exit \"$FAKE_UNITTEST_EXIT\"\n",
                encoding="utf-8",
            )
            fake_python.chmod(0o755)
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
                listener.bind(("127.0.0.1", 0))
                listener.listen(1)
                port = listener.getsockname()[1]
                env = dict(os.environ)
                env.update({
                    "PATH": str(fake_python.parent) + os.pathsep + env["PATH"],
                    "CHENGYIN_TEST_MYSQL_HOST": "127.0.0.1",
                    "CHENGYIN_TEST_MYSQL_PORT": str(port),
                    "CHENGYIN_TEST_MYSQL_USER": "root",
                    "CHENGYIN_TEST_MYSQL_PASSWORD": "",
                    "FAKE_UNITTEST_OUTPUT": output,
                    "FAKE_UNITTEST_EXIT": str(returncode),
                    "TMPDIR": temp_dir,
                })
                result = subprocess.run(
                    ["bash", str(REPO / "ci" / "funds-mysql-it-gate.sh"),
                     "atomic-migration"],
                    cwd=str(REPO),
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    [], list(pathlib.Path(temp_dir).glob("chengyin-atomic-mysql-it.*")))
                return result

    def test_global_entry_is_thin_and_requires_the_target_versioned_worktree(self):
        entry_path = REPO / "ci" / "run_migration_skill_entry.py"
        source = entry_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        function_names = {
            node.name for node in tree.body if isinstance(node, ast.FunctionDef)
        }

        # 2026-08-19:+normalize_remote_url,并把行数上限从 90 抬到 120 —— 抬的是【薄】的量尺,
        # 不是薄本身:新增的是一个纯字符串归一函数 + 说明为什么(见该函数注释),
        # 依旧没有 SQL、没有连库、没有第二个 runner 实现。
        # 2026-08-28:120 → 130。同样只抬量尺:CI 迁云效后身份判据要多认一个托管地址,
        # 且云效克隆出的 origin 内嵌凭据(https://<账号>:<令牌>@codeup...)必须剥掉,
        # 否则永远匹配不上(run 32 的 git remote -v 实测)。新增的仍是纯字符串处理,
        # 没有新函数、没有 SQL、没有连库。
        # ⚠️ 「薄」不是只靠这一个数守的 —— 下面的函数名集合精确相等、assertNotIn pymysql /
        #    INFORMATION_SCHEMA、必须有 os.execv,那几条才是真防线,本次一条没动。
        self.assertLessEqual(len(source.splitlines()), 130)
        self.assertEqual(
            {
                "current_git_root", "normalize_remote_url", "is_chengyin_repo",
                "resolve_runner", "_load_runner_module", "__getattr__", "main",
            },
            function_names,
        )
        self.assertIn('"ci" / "run_migration.py"', source)
        self.assertIn("liboyang42-cpu/chengyin.git", source)
        self.assertIn("os.execv", source)
        self.assertNotIn("pymysql", source)
        self.assertNotIn("INFORMATION_SCHEMA", source)
        entry = self.load_entry(entry_path)
        with mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(entry, "current_git_root", return_value=None), \
                mock.patch.object(entry, "FALLBACK_REPO", pathlib.Path("/missing")):
            with self.assertRaises(SystemExit) as raised:
                entry.resolve_runner()
        self.assertIn("worktree", str(raised.exception))
        self.assertIn("CHENGYIN_REPO", str(raised.exception))

    def test_repo_identity_is_the_remote_url_not_the_remote_name(self):
        """★ 仓库身份判据只认 URL,不认远端叫什么名字、带不带 .git。

        PR #741 的 Migration safety gate 就红在这:CI 里 actions/checkout 只建一个叫
        **origin** 的远端、URL 还**不带 .git**,而原判据问的是「有没有一个叫 github 的远端
        且 URL 全等」⇒ 判 False ⇒ resolve_runner 抛「找不到版本化 runner」。
        本地不红只因为本地恰好有那个叫 github 的远端。

        这三条把 CI 那个形态、本地那个形态、以及**别的仓库**分别钉住 ——
        少最后一条,判据就会退化成「有远端就算数」。
        """
        entry = self.load_entry(REPO / "ci" / "run_migration_skill_entry.py")

        def repo_with_remote(temp_dir, name, url):
            root = pathlib.Path(temp_dir)
            (root / "ci").mkdir(parents=True, exist_ok=True)
            (root / "ci" / "sql_splitter.py").write_text("# fixture\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
            subprocess.run(["git", "-C", str(root), "remote", "add", name, url], check=True)
            return root

        # ① CI 形态:远端叫 origin、URL 不带 .git
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin", "https://github.com/liboyang42-cpu/chengyin")
            self.assertTrue(entry.is_chengyin_repo(root),
                            "CI 形态(origin + 不带 .git)必须被认成城瘾仓库")

        # Fresh public snapshot has a distinct approved repository identity.
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin", "https://github.com/liboyang42-cpu/hengyin.git")
            self.assertTrue(entry.is_chengyin_repo(root))

        # ② 本地形态:远端叫 github、SSH 写法带 .git
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "github", "git@github.com:liboyang42-cpu/chengyin.git")
            self.assertTrue(entry.is_chengyin_repo(root), "本地形态必须仍被认成城瘾仓库")

        # ③ 云效/Codeup 形态:CI 迁到云效后工作区从 Codeup 检出,远端是 codeup.aliyun.com
        #   2026-08-27 实测:云效 run 31 的迁移安全门禁就红在这
        #   (只认 GitHub ⇒ 判 False ⇒ 「找不到版本化 runner」)。
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin",
                "https://codeup.aliyun.com/6a1cf1a96bb471afdf23d855/chengyin.git")
            self.assertTrue(entry.is_chengyin_repo(root),
                            "Codeup 形态必须被认成城瘾仓库(CI 已迁云效)")

        # ④ Codeup + 不带 .git(和 ① 同理,归一化要吃掉这个差异)
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin",
                "https://codeup.aliyun.com/6a1cf1a96bb471afdf23d855/chengyin")
            self.assertTrue(entry.is_chengyin_repo(root),
                            "Codeup 不带 .git 也必须被认")

        # ⑤ 云效 CI 实际形态:origin 内嵌凭据 https://<账号>:<令牌>@codeup.../chengyin.git
        #   2026-08-28 run 32 的 git remote -v 实测就是这个。不剥凭据段则永远匹配不上,
        #   ⇒ 只加 Codeup 地址【不够】,#897 第一版就漏了这一层。
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin",
                "https://6a1cede15e7217876d496a06:FAKE_TOKEN_FOR_TEST@codeup.aliyun.com"
                "/6a1cf1a96bb471afdf23d855/chengyin.git")
            self.assertTrue(entry.is_chengyin_repo(root),
                            "云效 CI 的内嵌凭据形态必须被认(否则迁移门禁永远红)")

        # ⑥ ★负控:别的仓库不许被认成城瘾仓库 —— 否则薄入口会去加载别人的 runner
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin", "https://github.com/someone-else/other-repo.git")
            self.assertFalse(entry.is_chengyin_repo(root), "非城瘾仓库被误认 —— 身份判据形同虚设")

        # ⑦ ★负控:一个远端都没有(裸 git init)也不许通过
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            (root / "ci").mkdir(parents=True, exist_ok=True)
            (root / "ci" / "sql_splitter.py").write_text("# fixture\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
            self.assertFalse(entry.is_chengyin_repo(root), "无远端仓库被误认")

        # ⑧ ★负控:Codeup 上【别的】仓库也不许被认 —— 放宽托管地址不等于放宽仓库身份
        with tempfile.TemporaryDirectory() as temp_dir:
            root = repo_with_remote(
                temp_dir, "origin",
                "https://codeup.aliyun.com/6a1cf1a96bb471afdf23d855/chengyin-app.git")
            self.assertFalse(entry.is_chengyin_repo(root),
                             "同域名下的别的仓库被误认 —— 身份判据被放宽了")

    def test_imported_entry_lazily_exposes_runner_public_api(self):
        entry = REPO / "ci" / "run_migration_skill_entry.py"
        with mock.patch.dict(os.environ, {"CHENGYIN_REPO": str(REPO)}):
            module = self.load_entry(entry)
            self.assertTrue(str(module.DEFAULT_CONFIG).endswith(
                "chengyinhub-admin/src/main/resources/application-druid.yml"))
            self.assertTrue(callable(module.connect))

    def _import_entry_in_clean_subprocess(self, entry, repo, extra=""):
        """在仓库外 cwd + 干净 sys.path 下导入入口并取 connect,复现 audit_sys_job.py 的真实处境。

        ⚠️ 上面那条 test_imported_entry_lazily_exposes_runner_public_api 曾长期恒绿:
        跑测试时 ci/ 已经在 sys.path 上,把 runner 顶部 `from sql_splitter import ...`
        的缺失路径整个盖住了。真实调用方(audit_sys_job.py,从任意 cwd 起)没有那层
        待遇,于是「手动落库的真防线」自己开箱就 ModuleNotFoundError。
        必须另起子进程、清掉 PYTHONPATH,断言才不是橡皮图章。
        """
        script = textwrap.dedent(f"""
            import importlib.util
            spec = importlib.util.spec_from_file_location(
                "entry", {str(entry)!r})
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            assert callable(module.connect), "connect 不可调用"
            print("ENTRY_IMPORT_OK")
        """) + extra
        env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        env["CHENGYIN_REPO"] = str(repo)
        with tempfile.TemporaryDirectory() as outside:
            return subprocess.run(
                [sys.executable, "-c", script], cwd=outside,
                capture_output=True, text=True, env=env)

    def test_imported_entry_works_without_ci_on_syspath(self):
        result = self._import_entry_in_clean_subprocess(
            REPO / "ci" / "run_migration_skill_entry.py", REPO)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ENTRY_IMPORT_OK", result.stdout)

    def test_negative_control_entry_without_syspath_fix_is_rejected(self):
        """负控:摘掉 sys.path 补丁后,同一条断言必须变红。"""
        entry_src = (REPO / "ci" / "run_migration_skill_entry.py").read_text(
            encoding="utf-8")
        anchor = "        sys.path.insert(0, str(pathlib.Path(runner).resolve().parent))\n"
        self.assertIn(anchor, entry_src, "负控锚点失效:sys.path 补丁形状已变")
        with tempfile.TemporaryDirectory() as temp_dir:
            broken_repo = pathlib.Path(temp_dir) / "repo"
            shutil.copytree(REPO / "ci", broken_repo / "ci")
            subprocess.run(["git", "-C", str(broken_repo), "init", "-q"], check=True)
            subprocess.run(
                ["git", "-C", str(broken_repo), "remote", "add", "github",
                 "https://github.com/liboyang42-cpu/chengyin.git"], check=True)
            broken_entry = broken_repo / "ci" / "run_migration_skill_entry.py"
            broken_entry.write_text(entry_src.replace(anchor, ""), encoding="utf-8")
            result = self._import_entry_in_clean_subprocess(
                broken_entry, broken_repo)
        self.assertNotEqual(result.returncode, 0, "摘掉补丁后仍然通过 = 断言是橡皮图章")
        self.assertIn("ModuleNotFoundError", result.stderr)

    def test_imported_entry_unknown_attribute_does_not_load_runner(self):
        entry = REPO / "ci" / "run_migration_skill_entry.py"
        module = self.load_entry(entry)
        with mock.patch.object(
                module, "_load_runner_module",
                side_effect=AssertionError("unknown attribute loaded runner")):
            self.assertFalse(hasattr(module, "__all__"))

    def test_entry_byte_drift_is_rejected_before_loading_runner(self):
        entry = REPO / "ci" / "run_migration_skill_entry.py"
        with tempfile.TemporaryDirectory() as temp_dir:
            mutated = pathlib.Path(temp_dir) / "run_migration.py"
            mutated.write_bytes(entry.read_bytes() + b"\n# drift\n")
            with mock.patch.dict(os.environ, {"CHENGYIN_REPO": str(REPO)}):
                module = self.load_entry(mutated)
                with self.assertRaisesRegex(SystemExit, "漂移"):
                    _ = module.DEFAULT_CONFIG

    # 迁移安全 job 必须跑的整份契约清单。新增迁移契约文件时【同时】改这里与 ci.yml，
    # 否则下面那条负控会红 —— 这正是本条断言存在的意义。
    MIGRATION_UNITTEST_FILES = (
        "ci/test_run_migration.py",
        "ci/test_migration_runner_ci_contract.py",
        "ci/test_migration_h8_registration_columns.py",
        "ci/test_migration_h8_rate_scope.py",
        "ci/test_migration_h8_detail_move.py",
        "ci/test_mapper_h8_column_isolation.py",
        "ci/test_migration_h8_publisher_guard.py",
        "scripts/e2e/test_build_schema.py",
        "scripts/e2e/test_chain_lib.py",
        "scripts/e2e/test_chain_merchant_club.py",
        "scripts/e2e/test_chain_platform.py",
        "scripts/e2e/test_chain_sale_close.py",
    )

    def ci_workflow_text(self):
        return (REPO / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")

    def assert_ci_runs_migration_unittest_suite(self, workflow):
        """接线断言必须锁【完整清单】，不能只锁前缀子串。

        旧写法 assertIn 的是前两个文件拼出来的子串，而后加的契约文件是【追加在它后面】的 ——
        把新文件从 ci.yml 里摘掉，那条断言照样绿(第四轮 Standards P3-1 实测过)。
        改成整行相等 + 恰好一条，摘掉任何一个都会红。
        """
        expected = "python3 -m unittest -v " + " ".join(self.MIGRATION_UNITTEST_FILES)
        commands = [" ".join(line.split())[len("run: "):]
                    for line in workflow.splitlines()
                    if " ".join(line.split()).startswith("run: python3 -m unittest -v ")]
        self.assertEqual(1, len(commands), "迁移契约的 unittest 接线必须恰好出现一次")
        self.assertEqual(expected, commands[0])

    def test_regular_ci_names_versioned_runner_unit_suite(self):
        self.assert_ci_runs_migration_unittest_suite(self.ci_workflow_text())

    def test_接线里静默摘掉任一迁移契约文件必须红(self):
        workflow = self.ci_workflow_text()
        command = "python3 -m unittest -v " + " ".join(self.MIGRATION_UNITTEST_FILES)
        self.assertIn(command, workflow, "原文里必须就是这条完整命令，否则负控无从谈起")
        for dropped in self.MIGRATION_UNITTEST_FILES:
            with self.subTest(dropped=dropped):
                remaining = [f for f in self.MIGRATION_UNITTEST_FILES if f != dropped]
                mutated = workflow.replace(
                    command, "python3 -m unittest -v " + " ".join(remaining), 1)
                self.assertNotEqual(workflow, mutated, "变异未生效，负控本身是假绿")
                with self.assertRaises(AssertionError):
                    self.assert_ci_runs_migration_unittest_suite(mutated)

    def test_mybatis_it_container_and_port_are_per_run_unique(self):
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")
        self.assert_mybatis_it_container_is_per_run_unique(
            self.backend_job(workflow))

    def test_negative_control_写死容器名或端口必须判红(self):
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")
        job = self.backend_job(workflow)
        # 负控 1:容器名退回写死
        with self.assertRaises(AssertionError):
            self.assert_mybatis_it_container_is_per_run_unique(
                job.replace('--name "$IT_NAME"', "--name chengyin-mybatis-it"))
        # 负控 2:宿主端口退回写死
        with self.assertRaises(AssertionError):
            self.assert_mybatis_it_container_is_per_run_unique(
                job.replace('-p "127.0.0.1:$IT_PORT:3306"',
                            "-p 127.0.0.1:3307:3306"))
        # 负控 3:IT_NAME 不含 run_id
        with self.assertRaises(AssertionError):
            self.assert_mybatis_it_container_is_per_run_unique(
                re.sub(r"(?m)^(\s+IT_NAME:).*$", r"\1 chengyin-mybatis-it", job))

    def test_backend_mysql_service_image_matches_runner_required_version(self):
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        required_version = runner._REQUIRED_MYSQL_VERSION
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")
        self.assert_backend_mysql_service_image_matches_runner_required_version(
            workflow, required_version)

    def test_backend_mysql_tag漂移到8_0时即使前方有正确版本诱饵也必须红(self):
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        required_version = runner._REQUIRED_MYSQL_VERSION
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")
        prefix, backend_and_rest = workflow.split("\n  backend:\n", 1)
        backend_job, suffix = backend_and_rest.split("\n  mini_program:\n", 1)
        drifted_backend = backend_job.replace(
            "MYSQL_IMAGE: mysql:" + required_version, "MYSQL_IMAGE: mysql:8.0", 1).replace(
            "image: mysql:" + required_version, "image: mysql:8.0", 1)
        decoy = ("\n  backend_decoy:\n    services:\n      mysql:\n"
                 "        image: mysql:" + required_version + "\n")
        drifted_workflow = (prefix + decoy + "\n  backend:\n" + drifted_backend
                            + "\n  mini_program:\n" + suffix)

        with self.assertRaises(AssertionError):
            self.assert_backend_mysql_service_image_matches_runner_required_version(
                drifted_workflow, required_version)

    def test_backend_mysql_版本锁被整个删掉时必须红(self):
        """docker run 形态下有人把 MYSQL_IMAGE 那行删了、改成硬写在命令里 —— 版本就不再被锁住。"""
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        required_version = runner._REQUIRED_MYSQL_VERSION
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")
        prefix, backend_and_rest = workflow.split("\n  backend:\n", 1)
        backend_job, suffix = backend_and_rest.split("\n  mini_program:\n", 1)
        stripped = re.sub(
            r"(?m)^\s+(?:image|MYSQL_IMAGE):\s*mysql:[^\s#]+\s*$", "", backend_job)
        self.assertNotEqual(stripped, backend_job, "负控没改到东西,版本锁的写法多半变了")

        with self.assertRaises(AssertionError):
            self.assert_backend_mysql_service_image_matches_runner_required_version(
                prefix + "\n  backend:\n" + stripped + "\n  mini_program:\n" + suffix,
                required_version)

    def test_h8_rate_migration_requires_transaction_and_exactly_one_merchant_target(self):
        runner = self.load_entry(REPO / "ci" / "run_migration.py")
        migration = REPO / "chengyinhub-admin" / "sql" / \
            "migration_20260815_tandianri_h8_rate_convergence.sql"
        sql_text = migration.read_text(encoding="utf-8")
        splitter = self.load_entry(REPO / "ci" / "sql_splitter.py")

        self.assertTrue(runner.required_transaction_declared(sql_text))
        runner.validate_transaction_statements(splitter.split_statements(sql_text))
        self.assertRegex(
            sql_text,
            r"(?s)SELECT COUNT\(\*\) FROM settlement_detail merchant.*?"
            r"merchant\.receiver_type = 'merchant'.*?merchant\.status = 0\s*\) <> 1",
            "每条旧 creator 待结明细必须恰好命中一条 merchant，不得 0/N 条静默过闸",
        )

    def test_h8_rate_migration_requires_points_migration_before_persistent_writes(self):
        """顺序 guard 必须留着，但只能用同 schema 事实——runner 在 --transaction 下拒 information_schema。"""
        sql_text = self.h8_rate_migration_text()
        preflight = self.squeeze(sql_text.split("UPDATE settlement_detail AS detail", 1)[0])

        self.assertIn("ORDER 2/2", sql_text)
        self.assertNotIn("information_schema", sql_text)
        # ORDER 1/2 建的三样东西缺任一都必须在首次持久写前撞红:
        # 列缺失 → ERROR 1054;唯一键缺失 → ERROR 1176;专用规则行缺失 → guard 主键冲突。
        self.assertIn("rule_version >= 1", preflight)
        self.assertIn("publisher_settlement_topic_id", preflight)
        self.assertIn("FORCE INDEX (uk_publisher_settlement_topic)", preflight)
        self.assertRegex(
            preflight,
            r"FROM cms_points_setting .*?id = 15.*?event_type = 15.*?value = 100",
            "费率 migration 必须在首次持久写前验证 H8 积分 migration 的专用规则",
        )

    def test_transactional_migrations_pass_official_runner_preflight(self):
        """dry-run 走不到 assert_innodb_references；跨 schema 引用只有这条契约能红。"""
        runner, migrations = self.transactional_migrations()

        self.assertTrue(migrations, "至少要有一份 requires=transaction 迁移被本契约覆盖")
        for path, statements in migrations:
            with self.subTest(migration=path.name):
                runner.validate_transaction_statements(statements)
                runner.referenced_persistent_tables(statements, "chengyinhubdb")

    def test_no_migration_reopens_a_temporary_table_within_one_statement(self):
        self.assertEqual(
            [], self.temporary_table_reopen_offenders(),
            "同一语句多次引用自建临时表在 MySQL 上必报 ERROR 1137 Can't reopen table")

    def test_h8_rate_migration_never_rewrites_settled_topic_creator_details(self):
        """H8 锁定第 9 条:已结不可变。主题腿必须与活动腿一样有 is_settlement 闸。"""
        squeezed = self.squeeze(self.h8_rate_migration_text())

        self.assertEqual(
            2,
            squeezed.count("registration.owner_type = 1 AND topic.is_settlement = 0"),
            "守卫与候选集两处都必须把主题腿限定在未结算主题")
        self.assertEqual(
            2,
            squeezed.count("JOIN cms_topic topic ON registration.owner_type = 1"
                           " AND topic.id = registration.owner_id"),
            "主题腿必须真的 JOIN cms_topic 才能读到 is_settlement")
        self.assertNotIn("(registration.owner_type = 1 OR", squeezed)

    def test_h8_rate_migration_merges_creator_rate_from_live_prevalue(self):
        """「全并」是前后关系,不是三组绝对值;写死目标值 = 恒真自证。"""
        squeezed = self.squeeze(self.h8_rate_migration_text())

        self.assertIn(
            "SET merchant_rate = merchant_rate + creator_rate, creator_rate = 0.0000",
            squeezed, "必须由语义自带断言地合并 live 前值")
        self.assertNotRegex(squeezed, r"SET merchant_rate = 0\.\d+")
        self.assertNotRegex(squeezed, r"SET platform_commission_rate = 0\.\d+")
        self.assertIn("CREATE TEMPORARY TABLE tandianri_h8_rate_before", squeezed)
        for assertion in (
                "rule.merchant_rate <> rate_before.merchant_rate + rate_before.creator_rate",
                "rule.creator_rate <> 0",
                "rule.platform_commission_rate <> rate_before.platform_commission_rate",
        ):
            self.assertIn(assertion, squeezed, "回读必须比前值快照,不能比写死值")

    def test_h8_rate_migration_rejects_activities_without_resolvable_cash_payee(self):
        """现金腿硬抛的存量对称闸:收款人不可解析的正销售未结活动必须拦在切规则之前。"""
        preflight = self.squeeze(
            self.h8_rate_migration_text().split("UPDATE settlement_detail AS detail", 1)[0])

        self.assertRegex(
            preflight,
            r"FROM cms_activity activity LEFT JOIN ums_member member"
            r" ON member\.id = activity\.member_id JOIN cms_registration registration"
            r" ON registration\.owner_type = 2 AND registration\.owner_id = activity\.id"
            r" WHERE activity\.del_flag = 0"
            r" AND activity\.status = 1"
            r" AND activity\.is_settlement = 0"
            r" AND \(activity\.topic_id IS NULL"
            r" OR EXISTS \(SELECT 1 FROM cms_topic source_topic"
            r" WHERE source_topic\.id = activity\.topic_id"
            r" AND source_topic\.product_type IS NOT NULL"
            r" AND source_topic\.product_type <> 2\)\)"
            r" AND \(activity\.member_id IS NULL OR member\.id IS NULL\)"
            r" AND registration\.payment_status = 2"
            r" AND registration\.registration_status = 2"
            r" AND registration\.payable_amount > 0",
            "存量正销售未结活动缺可用现金收款人时必须撞 guard，且不得猜收款人；"
            "目标集必须收窄到 selectActivitiesForSettlement 的运行时可达候选，否则是假红",
        )

    def test_h8_rate_migration_moves_all_pending_creator_producer_shapes(self):
        migration = REPO / "chengyinhub-admin" / "sql" / \
            "migration_20260815_tandianri_h8_rate_convergence.sql"
        sql_text = migration.read_text(encoding="utf-8")

        self.assertIn("registration.owner_type = 1", sql_text)
        self.assertIn("registration.owner_type = 2", sql_text)
        self.assertIn("tandianri_h8_pending_creator_move", sql_text)

    def test_h8_rate_migration_rejects_unresolved_positive_sales_publishers(self):
        migration = REPO / "chengyinhub-admin" / "sql" / \
            "migration_20260815_tandianri_h8_rate_convergence.sql"
        sql_text = migration.read_text(encoding="utf-8")
        preflight = sql_text.split("UPDATE settlement_detail AS detail", 1)[0]

        self.assertRegex(
            preflight,
            r"(?s)topic\.member_id IS NULL.*?registration\.payment_status = 2"
            r".*?registration\.registration_status = 2.*?registration\.payable_amount > 0",
            "已有正向销售但发布者未解析时不得切换 H8 资金规则",
        )

    def test_h8_points_migration_rejects_generated_snapshot_columns_before_persistent_alter(self):
        migration = REPO / "chengyinhub-admin" / "sql" / \
            "migration_20260815_tandianri_h8_publisher_points_funds.sql"
        sql_text = migration.read_text(encoding="utf-8")
        preflight = sql_text.split("SET @h8_rule_version_exists", 1)[0]

        self.assertIn("ORDER 1/2", sql_text)

        for column in ("points_rule_id", "points_rule_version", "points_rule_value"):
            blocks = [block for block in preflight.split("OR EXISTS")
                      if "column_name = '" + column + "'" in block]
            self.assertEqual(1, len(blocks), column + " 必须有唯一前置结构 guard")
            for required in ("column_default", "extra", "generation_expression"):
                self.assertIn(
                    required, blocks[0],
                    column + " 前置 guard 缺少 " + required,
                )

    def test_atomic_gate_inputs_route_to_backend_and_migration_jobs(self):
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        backend_filter = workflow.split("            backend:\n", 1)[1].split(
            "            ui:\n", 1)[0]
        migration_filter = workflow.split("            migration:\n", 1)[1].split(
            "      - name: Verify CI cost routing contract\n", 1)[0]
        backend_paths = (
            "ci/run_migration.py",
            "ci/run_migration_skill_entry.py",
            "ci/sql_splitter.py",
            "ci/test_run_migration.py",
            "ci/test_tandianri_mysql_integration.py",
            "ci/test_circle_theme_seed_repair_mysql_integration.py",
            "ci/test_club_constraint_null_safety_mysql_integration.py",
            "ci/test_merchant_candidate_schema_repair_mysql_integration.py",
            "ci/test_merchant_crm_delivery_check_mysql_integration.py",
            "ci/test_quartz_jobs_pause_repair_mysql_integration.py",
            "ci/test_migration_historical_schema_forward_fix_mysql_integration.py",
            "ci/test_merchant_offer_withdrawal_columns_mysql_integration.py",
            "ci/test_community_legacy_owner_mysql_integration.py",
            "ci/test_community_legacy_comment_like_counts_mysql_integration.py",
            "ci/test_migration_runner_ci_contract.py",
            "ci/funds-mysql-it-gate.sh",
        )
        migration_paths = (
            "ci/run_migration.py",
            "ci/run_migration_skill_entry.py",
            "ci/sql_splitter.py",
            "ci/test_run_migration.py",
            "ci/test_tandianri_mysql_integration.py",
            "ci/test_circle_theme_seed_repair_mysql_integration.py",
            "ci/test_club_constraint_null_safety_mysql_integration.py",
            "ci/test_merchant_candidate_schema_repair_mysql_integration.py",
            "ci/test_merchant_crm_delivery_check_mysql_integration.py",
            "ci/test_quartz_jobs_pause_repair_mysql_integration.py",
            "ci/test_migration_historical_schema_forward_fix_mysql_integration.py",
            "ci/test_merchant_offer_withdrawal_columns_mysql_integration.py",
            "ci/test_community_legacy_owner_mysql_integration.py",
            "ci/test_community_legacy_comment_like_counts_mysql_integration.py",
            "ci/test_migration_*.py",
            "scripts/e2e/**",
            "sql/base_schema_20260818.sql",
            "sql/base_seed_20260818.sql",
        )
        for path in backend_paths:
            self.assertIn("- '%s'" % path, backend_filter)
        for path in migration_paths:
            self.assertIn("- '%s'" % path, migration_filter)

        migration_job = workflow.split("  migration_safety:\n", 1)[1].split(
            "\n  backend:\n", 1)[0]
        self.assertIn("fetch-depth: 0", migration_job)
        self.assertIn("scripts/e2e/test_build_schema.py", migration_job)

    def test_mysql_gate_explicitly_names_atomic_runner_it(self):
        gate = (REPO / "ci" / "funds-mysql-it-gate.sh").read_text(encoding="utf-8")
        workflow = (REPO / ".github" / "workflows" / "ci.yml").read_text(
            encoding="utf-8")

        self.assertIn(
            "ci.test_tandianri_mysql_integration.TandianriAtomicMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_circle_theme_seed_repair_mysql_integration."
            "CircleThemeSeedRepairMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_club_constraint_null_safety_mysql_integration."
            "ClubConstraintNullSafetyMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_merchant_candidate_schema_repair_mysql_integration."
            "MerchantCandidateSchemaRepairMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_merchant_crm_delivery_check_mysql_integration."
            "MerchantCrmDeliveryCheckMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_quartz_jobs_pause_repair_mysql_integration."
            "QuartzJobsPauseRepairMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_migration_historical_schema_forward_fix_mysql_integration."
            "HistoricalSchemaForwardFixMysqlIntegrationTest",
            gate,
        )
        self.assertIn(
            "ci.test_merchant_offer_withdrawal_columns_mysql_integration."
            "MerchantOfferWithdrawalColumnsMysqlIntegrationTest",
            gate,
        )
<<<<<<< public
        self.assertIn("ci.test_community_legacy_owner_mysql_integration.CommunityLegacyOwnerMysqlIntegrationTest", gate)
        self.assertIn("ci.test_community_legacy_comment_like_counts_mysql_integration.CommunityLegacyCommentLikesMysqlIntegrationTest", gate)
        self.assertIn('"$atomic_ran" -lt 68', gate)
        self.assertIn('"$atomic_ran" -lt 41', gate)
=======
        self.assertIn('"$atomic_ran" -lt 42', gate)
>>>>>>> private-github/featu
        self.assertIn("bash ci/funds-mysql-it-gate.sh atomic-migration", workflow)
        self.assertIn("CHENGYIN_TEST_MYSQL_HOST: 127.0.0.1", workflow)

    def test_atomic_mysql_it_class_keeps_at_least_five_discoverable_tests(self):
        source = (REPO / "ci" / "test_tandianri_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        atomic_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "TandianriAtomicMysqlIntegrationTest")
        test_methods = [
            node.name for node in atomic_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 5)

    def test_circle_repair_mysql_it_keeps_three_discoverable_tests(self):
        source = (REPO / "ci" / "test_circle_theme_seed_repair_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        repair_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "CircleThemeSeedRepairMysqlIntegrationTest")
        test_methods = [
            node.name for node in repair_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 3)

    def test_club_constraint_null_safety_it_keeps_four_discoverable_tests(self):
        source = (REPO / "ci" / "test_club_constraint_null_safety_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        repair_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "ClubConstraintNullSafetyMysqlIntegrationTest")
        test_methods = [
            node.name for node in repair_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 4)

    def test_merchant_candidate_schema_repair_it_keeps_nine_discoverable_tests(self):
        source = (REPO / "ci" / "test_merchant_candidate_schema_repair_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        repair_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "MerchantCandidateSchemaRepairMysqlIntegrationTest")
        test_methods = [
            node.name for node in repair_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 9)

    def test_merchant_crm_delivery_check_it_keeps_five_discoverable_tests(self):
        source = (REPO / "ci" / "test_merchant_crm_delivery_check_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        check_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "MerchantCrmDeliveryCheckMysqlIntegrationTest")
        test_methods = [
            node.name for node in check_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 5)

    def test_quartz_jobs_pause_repair_it_keeps_seven_discoverable_tests(self):
        source = (REPO / "ci" / "test_quartz_jobs_pause_repair_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        repair_class = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == "QuartzJobsPauseRepairMysqlIntegrationTest")
        test_methods = [
            node.name for node in repair_class.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            and node.name.startswith("test_")
        ]
        self.assertGreaterEqual(len(test_methods), 7)

<<<<<<< public
    def test_atomic_gate_accepts_sixty_eight_unskipped_tests_and_rejects_skips(self):
        result = self.run_atomic_gate_with_unittest_result(
            "Ran 68 tests in 0.001s\n\nOK\n", 0)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("实际执行 68 条", result.stdout + result.stderr)
        for count, skips in ((41, 41), (68, 1), (68, 68)):
            with self.subTest(count=count, skipped=skips):
                skipped = self.run_atomic_gate_with_unittest_result(
                    "Ran %d tests in 0.001s\n\nOK (skipped=%d)\n" % (count, skips), 0)
                self.assertNotEqual(0, skipped.returncode)
                self.assertIn("skipped=0", skipped.stdout + skipped.stderr)

    def test_atomic_gate_rejects_old_thirty_five_and_incomplete_forty_tests(self):
        for count in (35, 40, 41, 48, 49, 56, 63, 64, 67):
=======
    def test_atomic_gate_accepts_forty_two_unskipped_tests_and_rejects_skips(self):
        result = self.run_atomic_gate_with_unittest_result(
            "Ran 42 tests in 0.001s\n\nOK\n", 0)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("实际执行 42 条", result.stdout + result.stderr)
        skipped = self.run_atomic_gate_with_unittest_result(
            "Ran 42 tests in 0.001s\n\nOK (skipped=42)\n", 0)
        self.assertNotEqual(0, skipped.returncode)
        self.assertIn("skipped=0", skipped.stdout + skipped.stderr)

    def test_atomic_gate_rejects_old_or_incomplete_test_counts(self):
        for count in (35, 40, 41):
>>>>>>> private-github/featu
            with self.subTest(count=count):
                result = self.run_atomic_gate_with_unittest_result(
                    "Ran %d tests in 0.001s\n\nOK\n" % count, 0)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("实际只跑了 %d 条" % count, result.stdout + result.stderr)

<<<<<<< public
    def test_community_owner_mysql_it_is_fail_closed_and_keeps_eight_tests(self):
        source = (REPO / "ci/test_community_legacy_owner_mysql_integration.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        suite = next(node for node in tree.body if isinstance(node, ast.ClassDef)
                     and node.name == "CommunityLegacyOwnerMysqlIntegrationTest")
        tests = [node.name for node in suite.body if isinstance(node, ast.FunctionDef)
                 and node.name.startswith("test_")]
        self.assertGreaterEqual(len(tests), 8)
        self.assertNotIn("SkipTest", source)
        self.assertNotIn("skipUnless", source)
        self.assertIn("cy_community_legacy_owner_it_", source)

    def test_community_comment_likes_mysql_it_keeps_nineteen_unskipped_native_tests(self):
        source = (REPO / "ci/test_community_legacy_comment_like_counts_mysql_integration.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        suite = next(node for node in tree.body if isinstance(node, ast.ClassDef)
                     and node.name == "CommunityLegacyCommentLikesMysqlIntegrationTest")
        tests = [node.name for node in suite.body if isinstance(node, ast.FunctionDef)
                 and node.name.startswith("test_")]
        self.assertEqual(19, len(tests))
        self.assertNotIn("SkipTest", source)
        self.assertNotIn("skipUnless", source)
        self.assertNotIn("skipIf", source)
        self.assertIn("cy_community_legacy_it_", source)

    def test_offer_withdrawal_mysql_it_keeps_six_discoverable_tests(self):
=======
    def test_offer_withdrawal_mysql_it_keeps_seven_discoverable_tests(self):
>>>>>>> private-github/featu
        source = (REPO / "ci" / "test_merchant_offer_withdrawal_columns_mysql_integration.py").read_text(
            encoding="utf-8")
        tree = ast.parse(source)
        repair_class = next(node for node in tree.body if isinstance(node, ast.ClassDef)
                            and node.name == "MerchantOfferWithdrawalColumnsMysqlIntegrationTest")
        test_methods = [node.name for node in repair_class.body
                        if isinstance(node, ast.FunctionDef) and node.name.startswith("test_")]
        self.assertGreaterEqual(len(test_methods), 7)
        self.assertIn("test_existing_ordinary_migrations_replay_without_duplicate_columns", test_methods)
        self.assertNotIn("SkipTest", source)
        self.assertNotIn("skipUnless", source)

    def test_offer_withdrawal_guard_preserves_ordinary_migration_compatibility(self):
        sql = (REPO / "chengyinhub-admin/sql"
               / "migration_20261003_merchant_offer_withdrawal_columns.sql").read_text(encoding="utf-8")
        self.assertEqual(2, self.squeeze(sql).count(
            "column_name = 'withdraw_required' AND data_type = 'tinyint'"))
        ordinary = (REPO / "chengyinhub-admin/sql"
                    / "migration_ordinary_chapter_offer_withdraw_20261003.sql").read_text(encoding="utf-8")
        self.assertIn("ADD COLUMN withdraw_required TINYINT NULL", self.squeeze(ordinary))
        source = (REPO / "ci"
                  / "test_merchant_offer_withdrawal_columns_mysql_integration.py").read_text(encoding="utf-8")
        for filename in (
            "migration_ordinary_chapter_offer_withdraw_20261003.sql",
            "migration_ordinary_chapter_application_replacement_20261003.sql",
        ):
            self.assertIn(filename, source)

    def test_atomic_gate_rejects_only_thirty_four_tests(self):
        result = self.run_atomic_gate_with_unittest_result(
            "Ran 34 tests in 0.001s\n\nOK\n", 0)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("实际只跑了 34 条", result.stdout + result.stderr)

    def test_atomic_gate_rejects_unparseable_test_count(self):
        result = self.run_atomic_gate_with_unittest_result("OK without count\n", 0)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("无法唯一解析", result.stdout + result.stderr)

    def test_atomic_gate_preserves_unittest_failure_exit_code(self):
        result = self.run_atomic_gate_with_unittest_result(
            "Ran 8 tests in 0.001s\n\nFAILED (failures=1)\n", 7)

        self.assertEqual(7, result.returncode, result.stdout + result.stderr)

    def test_settlement_evidence_seams_have_only_named_production_consumers(self):
        source_root = REPO / "chengyinhub-system" / "src" / "main" / "java"
        service_refs = {
            path.relative_to(REPO).as_posix()
            for path in source_root.rglob("*.java")
            if "TopicSettlementEvidenceService" in path.read_text(encoding="utf-8")
        }
        self.assertEqual({
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "TopicSettlementEvidenceService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "TopicWorkspaceServiceImpl.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "CoopSettlementClaimServiceImpl.java",
        }, service_refs)

        shell_query_refs = {
            path.relative_to(REPO).as_posix()
            for path in source_root.rglob("*.java")
            if "IncludingActivityShell" in path.read_text(encoding="utf-8")
        }
        self.assertEqual({
            "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/"
            "CmsRegistrationMapper.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/"
            "CoopGroupVerifyMapper.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "TopicSettlementEvidenceService.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "MerchantFulfillmentReadServiceImpl.java",
            "chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/"
            "CoopInviteServiceImpl.java",
        }, shell_query_refs)

        workspace = (source_root / "com" / "chengyinhub" / "business" / "service"
                     / "impl" / "TopicWorkspaceServiceImpl.java").read_text(
                         encoding="utf-8")
        self.assertIn("settlementEvidenceService.readTopic(topicId)", workspace)

        cash_settlement_sources = (
            "CmsTopicServiceImpl.java",
            "CmsActivityServiceImpl.java",
            "SettlementServiceImpl.java",
        )
        for filename in cash_settlement_sources:
            source = (source_root / "com" / "chengyinhub" / "business" / "service"
                      / "impl" / filename).read_text(encoding="utf-8")
            self.assertNotIn("TopicWorkspaceService", source)
            self.assertNotIn("TopicSettlementEvidenceService", source)
            self.assertNotIn("IncludingActivityShell", source)

    def test_atomic_mysql_gate_rejects_non_local_target_before_socket(self):
        env = dict(os.environ)
        env.update({
            "CHENGYIN_TEST_MYSQL_HOST": "database.example.invalid",
            "CHENGYIN_TEST_MYSQL_PORT": "3306",
            "CHENGYIN_TEST_MYSQL_USER": "root",
            "CHENGYIN_TEST_MYSQL_PASSWORD": "",
        })

        result = subprocess.run(
            ["bash", str(REPO / "ci" / "funds-mysql-it-gate.sh"), "atomic-migration"],
            cwd=str(REPO),
            env=env,
            capture_output=True,
            text=True,
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("只允许显式 localhost scratch", result.stdout + result.stderr)

    def test_mysql_it_cannot_skip_and_reuses_runner_provenance_policy(self):
        source = (REPO / "ci" / "test_tandianri_mysql_integration.py").read_text(
            encoding="utf-8")

        self.assertNotIn("skipUnless", source)
        self.assertNotIn("SkipTest", source)
        self.assertIn(
            "from ci.run_migration import assert_mysql_server_provenance", source)
        self.assertIn(
            "assert_mysql_server_provenance(cls.mysql_version, version_comment)",
            source,
        )
        self.assertNotIn('"mysql community server" in', source)
        self.assertNotIn('"mysql enterprise server" in', source)
        self.assertIn("only Oracle MySQL 8.0.45 is accepted", source)

    def test_target_sql_machine_declares_transaction(self):
        sql = (REPO / "chengyinhub-admin" / "sql"
               / "migration_20260814_tandianri_product_type_backfill.sql").read_text(
                   encoding="utf-8")
        self.assertEqual(1, sql.count("-- runner: requires=transaction"))

    def test_daily_health_roam_stop_migration_keeps_machine_fail_closed_guards(self):
        sql = (REPO / "chengyinhub-admin" / "sql"
               / "migration_20260822_disable_untrusted_roam_arrival.sql").read_text(
                   encoding="utf-8")
        self.assertIn("SET status = 'DISABLED'", sql)
        self.assertIn("status = 'MANUAL_REVIEW'", sql)
        self.assertIn("ck_untrusted_roam_evidence_revoked CHECK (observed_rows = 0)", sql)
        self.assertIn("ck_untrusted_roam_rewards_held CHECK (observed_rows = 0)", sql)

    def test_daily_health_team_invite_migration_is_replayable_and_cryptographic(self):
        sql = (REPO / "chengyinhub-admin" / "sql"
               / "migration_20260822_play_team_invite_code.sql").read_text(
                   encoding="utf-8")
        self.assertIn("RANDOM_BYTES(16)", sql)
        self.assertGreaterEqual(sql.count("information_schema"), 5)
        self.assertGreaterEqual(sql.count("PREPARE "), 3)
        self.assertGreaterEqual(sql.count("-- runner: assert=scalar-true"), 3)

    def test_migration_assertion_supersession_requires_landed_replacement(self):
        audit = self.load_entry(REPO / "ci" / "audit_migrations_applied.py")
        false_assertions = [
            ("migration_old.sql", "断言为假"),
            ("migration_unrelated.sql", "断言为假"),
        ]
        mapping = {"migration_old.sql": "migration_repair.sql"}
        known = {"migration_old.sql", "migration_unrelated.sql", "migration_repair.sql"}

        remaining, superseded = audit.apply_assertion_supersessions(
            false_assertions, ["migration_repair.sql"], mapping, known)

        self.assertEqual([("migration_unrelated.sql", "断言为假")], remaining)
        self.assertEqual(
            [("migration_old.sql", "migration_repair.sql")], superseded)

        remaining, superseded = audit.apply_assertion_supersessions(
            false_assertions, [], mapping, known)
        self.assertEqual(false_assertions, remaining)
        self.assertEqual([], superseded)

    def test_migration_assertion_supersession_config_fails_closed(self):
        audit = self.load_entry(REPO / "ci" / "audit_migrations_applied.py")

        with self.assertRaisesRegex(ValueError, "不存在"):
            audit.apply_assertion_supersessions(
                [("migration_old.sql", "断言为假")],
                ["migration_repair.sql"],
                {"migration_missing.sql": "migration_repair.sql"},
                {"migration_old.sql", "migration_repair.sql"},
            )

    def test_daily_health_approval_repair_declares_superseded_assertions(self):
        audit = self.load_entry(REPO / "ci" / "audit_migrations_applied.py")
        mapping = audit.read_supersessions()
        repair = "migration_approval_governance_request_id_repair_20260830.sql"

        self.assertEqual(repair, mapping["migration_business_audit_event_20260823.sql"])
        self.assertEqual(repair, mapping["migration_approval_governance_20260828.sql"])

        sql = (REPO / "chengyinhub-admin" / "sql" / repair).read_text(encoding="utf-8")
        self.assertNotIn("\nCREATE TRIGGER", sql)
        self.assertIn("MODIFY COLUMN request_id VARCHAR(64) NOT NULL", sql)
        self.assertIn("-- runner: assert=scalar-true", sql)

        mapper = (REPO / "chengyinhub-system" / "src" / "main" / "resources"
                  / "mapper" / "business" / "BusinessAuditEventMapper.xml").read_text(
                      encoding="utf-8")
        self.assertIn("COALESCE(NULLIF(#{requestId}, ''), SHA2(CONCAT_WS", mapper)


if __name__ == "__main__":
    unittest.main()
