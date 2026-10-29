"""SOURCE ONLY: pure controls; no Docker, HTTP, database or provider execution."""
import copy
import datetime
import decimal
import importlib.util
import json
import tempfile
from pathlib import Path
import unittest
from unittest import mock

path = Path(__file__).with_name("capacity_mixed_probe.py")
spec = importlib.util.spec_from_file_location("capacity_mixed", path)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class CapacityMixedControls(unittest.TestCase):
    def test_scope_is_manual_exact_hosted_child_only(self):
        env = {
            "GITHUB_ACTIONS": "true", "RUNNER_OS": "Linux",
            "RUNNER_ENVIRONMENT": "github-hosted",
            "GITHUB_WORKFLOW": "Isolated authenticated Redis recovery",
            "GITHUB_JOB": "isolated-authenticated-redis-recovery",
            "GITHUB_REPOSITORY": "liboyang42-cpu/hengyin",
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_WORKFLOW_REF":
                "liboyang42-cpu/hengyin/.github/workflows/"
                "isolated-authenticated-redis-recovery.yml@refs/heads/codex/test",
            "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_SHA": "a" * 40,
            "CHENGYIN_CI_AUTH_REDIS_RECOVERY": "true",
            "CHENGYIN_CI_CAPACITY_MIXED": "true",
        }
        with mock.patch.object(probe.sys, "platform", "linux"):
            probe.validate_env(env)
            for key, value in (
                ("GITHUB_EVENT_NAME", "pull_request"),
                ("GITHUB_SHA", "a" * 7),
                ("GITHUB_RUN_ATTEMPT", "0"),
                ("CHENGYIN_CI_CAPACITY_MIXED", "false"),
                ("RUNNER_ENVIRONMENT", "self-hosted"),
                ("DOCKER_HOST", "ssh://foreign"),
                ("GITHUB_JOB", "backend"),
            ):
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.validate_env(dict(env, **{key: value}))

    def test_profile_does_not_upgrade_existing_caps_or_pool(self):
        objects = []
        for index in range(3):
            memory = (1024 ** 3, 1024 ** 3, 64 * 1024 ** 2)[index]
            objects.append({
                "Id": str(index + 1) * 64,
                "HostConfig": {
                    "Memory": memory, "MemorySwap": memory,
                    "NanoCpus": (10 ** 9, 10 ** 9, 0)[index],
                    "Privileged": False, "PortBindings": {},
                },
                "Config": {"Cmd": [
                    "--spring.config.location=file:/fixture/application.properties",
                    "--spring.datasource.druid.master.url=jdbc:mysql://owned/schema",
                ]},
            })
        probe.validate_profile(objects, probe.PROFILE_PROPERTIES)
        for alteration in ("memory", "swap", "cpu", "port", "privileged", "pool"):
            changed = copy.deepcopy(objects)
            host = changed[0]["HostConfig"]
            if alteration == "memory":
                host["Memory"] *= 2
            elif alteration == "swap":
                host["MemorySwap"] *= 2
            elif alteration == "cpu":
                host["NanoCpus"] *= 2
            elif alteration == "port":
                host["PortBindings"] = {"8080/tcp": [{"HostPort": "8080"}]}
            elif alteration == "privileged":
                host["Privileged"] = True
            else:
                changed[0]["Config"]["Cmd"].append("--spring.datasource.druid.maxActive=20")
            with self.subTest(alteration=alteration), self.assertRaises(RuntimeError):
                probe.validate_profile(changed, probe.PROFILE_PROPERTIES)

    def test_insufficient_headroom_is_not_run_not_a_smaller_load(self):
        limits = [{"memoryBytes": 1024 ** 3}] * 2 + [{"memoryBytes": 64 * 1024 ** 2}]
        probe.admit(limits, 4 * 1024 ** 3, 1024 ** 3, 2, 100)
        for memory, disk, cpus in (
            (1024, 1024 ** 3, 2), (4 * 1024 ** 3, 1, 2),
            (4 * 1024 ** 3, 1024 ** 3, 1),
        ):
            with self.subTest(memory=memory, disk=disk, cpus=cpus):
                with self.assertRaises(probe.NotRun):
                    probe.admit(limits, memory, disk, cpus, 100)

    def test_http_200_and_json_shape_are_not_success_proof(self):
        expected = [("capacity-0", "0")]
        good = {"code": 200, "data": [{"dictLabel": "capacity-0", "dictValue": "0"}]}
        self.assertEqual("SUCCESS", probe.classify("DICT", 200, good, expected))
        for body in (
            {"code": 200}, {"code": 200, "data": []},
            {"code": 200, "data": [{"dictLabel": "other", "dictValue": "0"}]},
            {"code": 200, "data": ["wrong"]},
        ):
            with self.subTest(body=body):
                self.assertEqual("CONTRACT_MISMATCH",
                                 probe.classify("DICT", 200, body, expected))
        self.assertEqual("CONTRACT_MISMATCH",
                         probe.classify("ACTOR", 200, {"code": 200, "user": {"userId": 2}}))
        self.assertEqual("UNKNOWN", probe.classify("DICT", 200, {"code": "200"}))

    def test_auth_rejection_system_failure_and_unknown_are_separate(self):
        self.assertEqual("LEGAL_AUTH_REJECTION",
                         probe.classify("RELEASE", 200, {"code": 401}))
        self.assertEqual("SYSTEM_FAILURE",
                         probe.classify("RELEASE", 200, {"code": 500}))
        self.assertEqual("UNKNOWN", probe.classify("RELEASE", 200, {"code": 409}))
        self.assertEqual("UNKNOWN", probe.classify("RELEASE", 200, None))
        # The projection never returns dynamic response data or failure messages.
        canary = "secret-canary-response"
        result = probe.classify("DICT", 500, {"code": 500, "msg": canary, "sql": canary})
        self.assertNotIn(canary, json.dumps(result))

    def test_physical_overlap_cannot_be_inferred_from_slow_threads(self):
        probe.assert_fault_overlap(3, True)
        for count, paused in ((0, True), (2, True), (4, True), (3, False), ("3", True)):
            with self.subTest(count=count, paused=paused), self.assertRaises(RuntimeError):
                probe.assert_fault_overlap(count, paused)

    def test_counts_latency_and_throughput_do_not_merge_unknown_or_timeout(self):
        records = [
            {"category": category, "elapsedSeconds": float(index + 1)}
            for index, category in enumerate((
                "SUCCESS", "LEGAL_AUTH_REJECTION", "UNKNOWN",
                "TRANSPORT_TIMEOUT", "SYSTEM_FAILURE"))
        ]
        report = probe.distribution(records, 10)
        self.assertEqual(1, report["counts"]["UNKNOWN"])
        self.assertEqual(1, report["counts"]["TRANSPORT_TIMEOUT"])
        self.assertEqual(1, report["counts"]["LEGAL_AUTH_REJECTION"])
        self.assertEqual(.1, report["successfulRequestsPerSecond"])
        self.assertEqual(.3, report["completedResponsesPerSecond"])
        self.assertEqual(3, report["p50Seconds"])
        self.assertEqual(5, report["p99Seconds"])
        self.assertEqual(5, sum(report["counts"].values()))

    def test_curve_has_no_extra_submission_queue_or_arbitrary_scope(self):
        observed = []
        def request(kind, value):
            observed.append((kind, value))
            return {"category": "SUCCESS", "elapsedSeconds": .01}
        items = [("ACTOR", None)] * probe.REQUESTS_PER_STAGE
        report = probe.run_curve(items, request, 3, lambda: self.fail("unexpected stop"))
        self.assertEqual(24, len(observed))
        self.assertEqual(24, report["counts"]["SUCCESS"])
        self.assertLessEqual(report["maxOutstandingClientFutures"], 3)
        self.assertEqual(0, report["additionalClientWaitingQueue"])
        self.assertEqual("UNKNOWN_NOT_MEASURED", report["serverConcurrentRequests"])
        for concurrency, work in ((20, items), (3, items[:-1])):
            with self.assertRaises(RuntimeError):
                probe.run_curve(work, request, concurrency, lambda: None)

    def test_fixed_category_protocol_rejects_gameplay_aliases_and_arbitrary_values(self):
        self.assertEqual(('SUCCESS', 'LEGAL_AUTH_REJECTION', 'SYSTEM_FAILURE', 'UNKNOWN',
                          'TRANSPORT_TIMEOUT', 'TRANSPORT_ERROR', 'CONTRACT_MISMATCH', 'NOT_SENT'), probe.CATEGORIES)
        for category in ('accepted', 'authorized-rejection', 'UNKNOWN_OR_CONTRACT_FAILURE',
                         'UNREVIEWED_CATEGORY', '', None):
            with self.subTest(category=category), self.assertRaisesRegex(RuntimeError, 'unknown observation category'):
                probe.distribution([{'category': category, 'elapsedSeconds': .01}], 1)

    def test_final_validation_rejects_partial_foreign_or_missing_evidence(self):
        identity = {"sourceSha": "a" * 40}
        for report in (
            {}, {"result": "PASS", "identity": identity},
            {"result": probe.PASS_OBSERVATION, "identity": {"sourceSha": "b" * 40}},
            {"result": probe.PASS_OBSERVATION, "identity": identity,
             "initial": {"id": 1}, "final": {"id": 2}},
        ):
            with self.subTest(report=report), self.assertRaises(RuntimeError):
                probe.validate_observation(report, identity)

    def test_finalization_cannot_promote_an_original_failure(self):
        # Earlier checks are isolated here only to exercise first-error preservation;
        # this is a pure protocol control, never physical cleanup proof.
        report = {
            "result": probe.PASS_OBSERVATION, "taskName": "owned",
            "schema": "owned-schema", "firstFailure": {"type": "OriginalSchemaFailure"},
        }
        path = mock.Mock()
        path.read_text.return_value = json.dumps(report)
        out = mock.Mock()
        out.__truediv__ = mock.Mock(return_value=path)
        written = []
        with mock.patch.object(probe, "source_identity", return_value={}), \
                mock.patch.object(probe, "validate_observation"), \
                mock.patch.object(probe.auth, "write_report",
                                  side_effect=lambda out, name, value: written.append(copy.deepcopy(value))), \
                mock.patch.object(probe.subprocess, "run") as inspect:
            with self.assertRaises(RuntimeError):
                probe.finalize("owned", "owned-schema", out, 98)
        inspect.assert_not_called()
        self.assertEqual(98, written[0]["originalExitCode"])
        self.assertEqual({"type": "OriginalSchemaFailure"}, written[0]["firstFailure"])
        self.assertEqual("FAIL_PARTIAL", written[0]["result"])


class GameplayProfileControls(unittest.TestCase):
    """Synthetic protocol controls only: no compiled child, Docker, Java or stores run here."""
    def profile(self):
        objects = []
        for index, suffix in enumerate(("app", "mysql", "redis")):
            memory = (1024 ** 3, 1024 ** 3, 64 * 1024 ** 2)[index]
            objects.append({
                "Id": str(index + 1) * 64,
                "HostConfig": {"Memory": memory, "MemorySwap": memory,
                               "NanoCpus": (10 ** 9, 10 ** 9, 0)[index],
                               "Privileged": False, "PortBindings": {}},
                "State": {"Pid": 100 + index, "StartedAt": "original-" + suffix},
                "Config": {},
                "NetworkSettings": {"Networks": {"owned": {"NetworkID": "4" * 64,
                                                          "IPAddress": "172.19.0." + str(index + 2)}}},
            })
        objects[0]["Config"] = {
            "Entrypoint": ["/trace/strace"],
            "Env": ["GITHUB_SHA=" + "a" * 40, "CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=true"],
            "Cmd": ["-A", "-f", "-s", "0", "-e", "trace=connect,sendto,sendmsg",
                    "-o", "/evidence/gameplay-network.trace", "java", "-Xmx512m",
                    "-Dlogging.config=/fixture/logback.xml", "-Dlogback.configurationFile=/fixture/logback.xml",
                    "-Dloader.path=/recovery-testclasses",
                    "-Dloader.main=com.chengyinhub.business.it.GameplayProcessRecoveryChild",
                    "-cp", "/application.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"],
        }
        binding = {key: "b" * 64 for key in ("jarSha256", "childSourceSha256", "childClassSha256",
                                            "testClassTreeSha256", "configSha256", "fixturePropertiesSha256")}
        binding.update(sourceSha="a" * 40, appCid=objects[0]["Id"], appPid=100,
                       appStartedAt="original-app", networkId="4" * 64,
                       storeProcessIdentity={suffix: {"id": obj["Id"], "Pid": obj["State"]["Pid"],
                           "StartedAt": obj["State"]["StartedAt"], "networkId": "4" * 64}
                           for suffix, obj in zip(("mysql", "redis"), objects[1:])})
        binding["boot"] = {key: binding[key] for key in ("sourceSha", "jarSha256", "childClassSha256", "configSha256")}
        binding["boot"].update(bootId="12345678-1234-1234-1234-123456789abc", javaPid=21, port=8080)
        binding["actorProof"] = {"schema": "owned-schema", "serverUuid": "owned-uuid", "seedBootId": "original-seed",
            "identitiesSha256": "b" * 64, "columns": ["id"], "rowsSha256": "b" * 64,
            "authority": {}, "actors": {label: {"memberId": member, "tokenSha256": "b" * 64, "rowSha256": "b" * 64}
                       for label, member in (("a", 7), ("b", 8))}}
        return objects, binding

    def admission(self):
        return mock.patch.dict(probe.os.environ, {"GITHUB_SHA": "a" * 40,
            "CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY": "true", "CHENGYIN_CI_GAMEPLAY_CAPACITY": "true"})

    def test_recovered_gameplay_is_explicit_and_never_guessed_by_default_profile(self):
        objects, binding = self.profile()
        with self.admission():
            self.assertEqual(3, len(probe.validate_profile(objects, probe.PROFILE_PROPERTIES, binding)))
            with self.assertRaisesRegex(RuntimeError, "wrong application fixture"):
                probe.validate_profile(objects, probe.PROFILE_PROPERTIES)
            for invalid in ({}, dict(binding, arbitraryProfile="other")):
                with self.subTest(invalid=list(invalid)), self.assertRaises(RuntimeError):
                    probe.validate_profile(objects, probe.PROFILE_PROPERTIES, invalid)
            for key in ("CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY", "CHENGYIN_CI_GAMEPLAY_CAPACITY"):
                with mock.patch.dict(probe.os.environ, {key: "false"}), self.assertRaises(RuntimeError):
                    probe.validate_profile(objects, probe.PROFILE_PROPERTIES, binding)

    def test_gameplay_cannot_override_command_source_boot_stores_or_existing_budget(self):
        objects, binding = self.profile()
        with self.admission():
            for field, value in (("Memory", 2 * 1024 ** 3), ("MemorySwap", 2 * 1024 ** 3),
                                 ("NanoCpus", 2 * 10 ** 9), ("Privileged", True),
                                 ("PortBindings", {"8080/tcp": [{"HostPort": "8080"}]})):
                changed = copy.deepcopy(objects); changed[0]["HostConfig"][field] = value
                with self.subTest(field=field), self.assertRaises(RuntimeError):
                    probe.validate_profile(changed, probe.PROFILE_PROPERTIES, binding)
            for addition in ("--spring.config.location=file:/other.properties", "--spring.datasource.druid.maxActive=20",
                             "-Dloader.main=Other", "--server.port=8081"):
                changed = copy.deepcopy(objects); changed[0]["Config"]["Cmd"].append(addition)
                with self.subTest(addition=addition), self.assertRaises(RuntimeError):
                    probe.validate_profile(changed, probe.PROFILE_PROPERTIES, binding)
            for key, value in (("sourceSha", "c" * 40), ("jarSha256", "c" * 64),
                               ("appCid", "c" * 64), ("appPid", 101), ("networkId", "c" * 64)):
                with self.subTest(key=key), self.assertRaises(RuntimeError):
                    probe.validate_profile(objects, probe.PROFILE_PROPERTIES, dict(binding, **{key: value}))
            for key, value in (("port", 8081), ("javaPid", 0), ("bootId", "not-a-boot")):
                changed = copy.deepcopy(binding); changed["boot"][key] = value
                with self.subTest(boot=key), self.assertRaises(RuntimeError):
                    probe.validate_profile(objects, probe.PROFILE_PROPERTIES, changed)
            changed = copy.deepcopy(objects); changed[1]["State"]["StartedAt"] = "restarted"
            with self.assertRaises(RuntimeError):
                probe.validate_profile(changed, probe.PROFILE_PROPERTIES, binding)
            for variable in ("SPRING_APPLICATION_JSON={}", "JAVA_TOOL_OPTIONS=-Xmx1g", "LOADER_MAIN=Other"):
                changed = copy.deepcopy(objects); changed[0]["Config"]["Env"].append(variable)
                with self.subTest(variable=variable), self.assertRaises(RuntimeError):
                    probe.validate_profile(changed, probe.PROFILE_PROPERTIES, binding)

    def test_actual_profile_rechecks_fixed_mounted_inputs_and_original_proc(self):
        objects, binding = self.profile()
        with tempfile.TemporaryDirectory() as directory, self.admission():
            root = Path(directory).resolve()
            private = root / "private/gameplay"; private.mkdir(parents=True)
            out = root / "evidence"; out.mkdir()
            files = {}
            def put(relative, content, mounted=None):
                path = root / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content); path.chmod(0o600)
                if mounted:
                    files[mounted] = path
                return path
            jar = put("chengyinhub-admin/target/chengyinhub-admin.jar", b"synthetic jar control")
            properties = put("ci/upgrade-startup/application.properties", b"synthetic fixed properties control",
                             "/fixture/application.properties")
            source = put("chengyinhub-admin/src/test/java/" + probe.GAMEPLAY_CHILD_PATH + ".java", b"synthetic source control")
            manifest = {}
            for name, content in ((probe.GAMEPLAY_CHILD_PATH + ".class", b"synthetic main bytecode control"),
                                  (probe.GAMEPLAY_CHILD_PATH + "$Nested.class", b"synthetic nested bytecode control")):
                put("chengyinhub-admin/target/test-classes/" + name, content)
                path = put("evidence/gameplay-child-classes/" + name, content, "/recovery-testclasses/" + name)
                manifest[name] = probe.file_digest(path)["sha256"]
            binding.update(jarSha256=probe.file_digest(jar)["sha256"],
                           fixturePropertiesSha256=probe.file_digest(properties)["sha256"],
                           childSourceSha256=probe.file_digest(source)["sha256"],
                           childClassSha256=manifest[probe.GAMEPLAY_CHILD_PATH + ".class"],
                           testClassTreeSha256=probe.digest(manifest))
            config = {key: binding[key] for key in ("sourceSha", "jarSha256", "childClassSha256")}
            config.update(schema="owned-schema", serverUuid="owned-uuid",
                          jdbcUrl="jdbc:mysql://172.19.0.3:3306/owned-schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                          redisHost="172.19.0.4", tokenSecret="private-config-canary", capacityItems=72)
            config_file = put("private/gameplay/config.json", json.dumps(config).encode(), "/recovery-private/config.json")
            binding["configSha256"] = probe.file_digest(config_file)["sha256"]
            binding["boot"].update({key: binding[key] for key in ("jarSha256", "childClassSha256", "configSha256")})
            put("private/gameplay/ready.json", json.dumps(binding["boot"]).encode(), "/recovery-private/ready.json")
            identities_file = put("private/gameplay/identities.json", json.dumps({
                "a": {"memberId": 7, "token": "aaa.bbb.ccc"}, "b": {"memberId": 8, "token": "ddd.eee.fff"}}).encode(),
                "/recovery-private/identities.json")
            binding["actorProof"]["identitiesSha256"] = probe.file_digest(identities_file)["sha256"]
            objects[0]["Mounts"] = [{"Type": "bind", "Destination": target, "Source": str(path), "RW": writable}
                for target, path, writable in (("/application.jar", jar, False), ("/fixture", properties.parent, False),
                    ("/recovery-testclasses", out / "gameplay-child-classes", False), ("/recovery-private", private, True))]
            subject = object.__new__(probe.Probe)
            subject.name, subject.app, subject.jar, subject.out = "owned", objects[0]["Id"], jar, out
            subject.private, subject.schema, subject.saved = private.parent / "credentials.json", "owned-schema", {"serverUuid": "owned-uuid"}
            subject.identity = {"sourceSha": binding["sourceSha"]}
            subject.gameplay_binding = binding; subject.properties_sha256 = binding["fixturePropertiesSha256"]
            command = objects[0]["Config"]["Cmd"]
            argv = b"\0".join(item.encode() for item in command[command.index("java"):]) + b"\0"
            changed_mounts = {}
            def observed(args):
                if args == probe.D + ["exec", subject.app, "cat", "/proc/21/cmdline"]:
                    return argv
                self.assertEqual(probe.D + ["exec", subject.app, "sha256sum"], args[:len(probe.D) + 3])
                return "\n".join(changed_mounts.get(target, probe.file_digest(files[target])["sha256"])
                                  + "  " + target for target in args[len(probe.D) + 3:]).encode()
            with mock.patch.object(probe, "ROOT", root), mock.patch.object(probe.auth, "command", side_effect=observed):
                evidence = subject.application_profile(objects, 21)
                self.assertEqual("GAMEPLAY_PROCESS_RECOVERY", evidence["entry"])
                self.assertEqual(binding["configSha256"], evidence["configSha256"])
                self.assertNotIn("private-config-canary", json.dumps(evidence))
                for path in (jar, properties, source, config_file, identities_file,
                             root / ("chengyinhub-admin/target/test-classes/" + probe.GAMEPLAY_CHILD_PATH + ".class")):
                    original = path.read_bytes(); path.write_bytes(original + b" changed")
                    try:
                        with self.subTest(path=path.name), self.assertRaises(RuntimeError):
                            subject.application_profile(objects, 21)
                    finally:
                        path.write_bytes(original)
                for target in files:
                    changed_mounts[target] = "f" * 64
                    try:
                        with self.subTest(mount=target), self.assertRaises(RuntimeError):
                            subject.application_profile(objects, 21)
                    finally:
                        changed_mounts.clear()
                ready_file = files["/recovery-private/ready.json"]
                original_config, original_ready = config_file.read_bytes(), ready_file.read_bytes()
                original_config_sha = binding["configSha256"]
                for key, value in (("schema", "another-schema"), ("redisHost", "192.0.2.1"),
                                   ("jdbcUrl", "jdbc:mysql://192.0.2.1/foreign"), ("capacityItems", 0)):
                    altered = dict(config, **{key: value}); config_file.write_text(json.dumps(altered))
                    binding["configSha256"] = binding["boot"]["configSha256"] = probe.file_digest(config_file)["sha256"]
                    ready_file.write_text(json.dumps(binding["boot"]))
                    try:
                        with self.subTest(config=key), self.assertRaises(RuntimeError):
                            subject.application_profile(objects, 21)
                    finally:
                        config_file.write_bytes(original_config); ready_file.write_bytes(original_ready)
                        binding["configSha256"] = binding["boot"]["configSha256"] = original_config_sha
                extra = root / ("evidence/gameplay-child-classes/" + probe.GAMEPLAY_CHILD_PATH + "$Extra.class")
                extra.write_bytes(b"unbound synthetic class")
                try:
                    with self.assertRaises(RuntimeError):
                        subject.application_profile(objects, 21)
                finally:
                    extra.unlink()
                with mock.patch.object(probe.auth, "command", return_value=argv.replace(b"-Xmx512m", b"-Xmx1g")), self.assertRaises(RuntimeError):
                    subject.application_profile(objects, 21)
                objects[0]["Mounts"][2]["RW"] = True
                with self.assertRaises(RuntimeError):
                    subject.application_profile(objects, 21)
                objects[0]["Mounts"][2]["RW"] = False
                subject.gameplay_binding = None
                original_argv = ["java", "-Xmx512m", "-Dlogging.config=/fixture/logback.xml",
                    "-Dlogback.configurationFile=/fixture/logback.xml", "-jar", "/application.jar",
                    "--spring.config.location=file:/fixture/application.properties", "--spring.messages.basename=i18n/messages",
                    "--spring.datasource.druid.master.url=jdbc:mysql://172.19.0.3:3306/owned-schema?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    "--spring.data.redis.host=172.19.0.4"]
                objects[0]["Config"]["Cmd"] = ["-f", "-s", "0", "-e", "trace=connect,sendto,sendmsg",
                    "-o", "/evidence/network.trace"] + original_argv
                argv = b"\0".join(item.encode() for item in original_argv) + b"\0"
                self.assertEqual("ORIGINAL_AUTHENTICATED_FIXTURE", subject.application_profile(objects, 21)["entry"])
                objects[0]["Config"]["Cmd"] = list(probe.GAMEPLAY_COMMAND) + [
                    "--spring.config.location=file:/fixture/application.properties"]
                with self.assertRaisesRegex(RuntimeError, "wrong original application entry"):
                    subject.application_profile(objects, 21)


class GameplayNoCashActorControls(unittest.TestCase):
    """Synthetic row/protocol controls, not proof of an executed member insert or SQL admission."""
    def facts(self):
        identities = {label: {"memberId": member, "token": label + "aa.bbb.ccc"}
                      for label, member in (("a", 990005), ("b", 990006))}
        records = []
        for index, label in enumerate(("a", "b")):
            records.append({"id": identities[label]["memberId"], "phone": "13800000" + ("901" if index == 0 else "902"),
                "nickname": "进程恢复夹具" + str(index), "wx_open_id": "recovery-12345678-1234-1234-1234-123456789abc",
                "password": "", "app_open_id": None, "union_id": None, "apple_id": None,
                "role": "player", "user_type": 1, "status": 1, "del_flag": 0, "level_id": 0,
                "balance": decimal.Decimal("0.00"), "point": 0, "freeze_end_time": None,
                "deregister_status": "NORMAL", "deregister_apply_time": None, "deregister_execute_after": None,
                "login_time": datetime.datetime(2026, 10, 25, 12, 0), "avatar": "", "update_time": None})
        columns = list(records[0])
        rows = [tuple(record[column] for column in columns) for record in records]
        authority = {table: {"rows": 1, "sha256": "b" * 64, "ddlSha256": "c" * 64}
                     for table in probe.AUTHORITY_TABLES}
        authority["accountCredentials"] = "d" * 64
        return identities, columns, rows, authority

    def test_only_exact_original_source_seeded_players_have_zero_cash_proof(self):
        identities, columns, rows, authority = self.facts()
        proof = probe.gameplay_actor_proof(identities, columns, rows, "schema", "uuid", "seed-boot", "a" * 64, authority)
        self.assertEqual({990005, 990006}, {actor["memberId"] for actor in proof["actors"].values()})
        self.assertNotIn("13800000", json.dumps(proof))
        self.assertNotIn("aaa.bbb.ccc", json.dumps(proof))
        changes = (("balance", decimal.Decimal("0.01")), ("balance", None), ("balance", "0.00"),
                   ("point", 1), ("point", None), ("point", False), ("role", "merchant"), ("user_type", 2),
                   ("level_id", 1), ("status", 2), ("del_flag", 2), ("deregister_status", "PENDING"),
                   ("freeze_end_time", datetime.datetime(2026, 10, 26)), ("phone", "13800000999"),
                   ("nickname", "foreign"), ("wx_open_id", "foreign-openid"), ("password", "injected"),
                   ("app_open_id", "linked"), ("login_time", None))
        for field, value in changes:
            changed = [list(row) for row in rows]; changed[0][columns.index(field)] = value
            with self.subTest(field=field, value=value), self.assertRaises(probe.NoCashFixtureError):
                probe.gameplay_actor_proof(identities, columns, changed, "schema", "uuid", "seed-boot", "a" * 64, authority)
        for changed in ([], rows[:1], rows + [rows[0]], [rows[0], rows[0]]):
            with self.subTest(rows=len(changed)), self.assertRaises(probe.NoCashFixtureError):
                probe.gameplay_actor_proof(identities, columns, changed, "schema", "uuid", "seed-boot", "a" * 64, authority)
        foreign = copy.deepcopy(identities); foreign["a"]["memberId"] = 77
        with self.assertRaises(probe.NoCashFixtureError):
            probe.gameplay_actor_proof(foreign, columns, rows, "schema", "uuid", "seed-boot", "a" * 64, authority)
        self.assertNotEqual(probe.member_rows_digest(columns, rows),
            probe.member_rows_digest(columns, [tuple(str(value) if isinstance(value, decimal.Decimal) else value for value in row) for row in rows]))

    def test_admission_rereads_original_full_rows_authority_and_every_empty_economic_store(self):
        identities, columns, rows, authority = self.facts()
        objects, binding = GameplayProfileControls().profile()
        with tempfile.TemporaryDirectory() as directory, GameplayProfileControls().admission():
            private = Path(directory).resolve() / "gameplay"; private.mkdir()
            path = private / "identities.json"; path.write_text(json.dumps(identities)); path.chmod(0o600)
            proof = probe.gameplay_actor_proof(identities, columns, rows, "schema", "uuid", "seed-boot",
                                              probe.file_digest(path)["sha256"], authority)
            binding["actorProof"] = proof
            subject = object.__new__(probe.Probe)
            subject.gameplay_binding, subject.objects = binding, objects
            subject.private, subject.schema, subject.saved = private.parent / "credentials.json", "schema", {"serverUuid": "uuid"}
            subject.member_columns, subject.member_rows = columns, rows
            subject.rows = mock.Mock(return_value=[(0,)])
            protected = {table: {"rows": 0, "sha256": "b" * 64, "ddlSha256": "c" * 64}
                         for table in probe.auth.FINANCIAL_TABLES + probe.GAMEPLAY_EMPTY_TABLES}
            protected["ums_member"]["rows"] = 2; protected.update(copy.deepcopy(authority))
            subject.require_gameplay_no_cash(protected)
            subject.rows.assert_called_with("SELECT COUNT(*) FROM refund_application")
            for table in tuple(table for table in probe.auth.FINANCIAL_TABLES if table != "ums_member") + probe.GAMEPLAY_EMPTY_TABLES:
                changed = copy.deepcopy(protected); changed[table]["rows"] = 1
                with self.subTest(table=table), self.assertRaisesRegex(probe.NoCashFixtureError, "GAMEPLAY_UNEXPECTED_ECONOMIC_ROWS"):
                    subject.require_gameplay_no_cash(changed)
            for table in probe.AUTHORITY_TABLES + ("accountCredentials",):
                changed = copy.deepcopy(protected)
                if table == "accountCredentials": changed[table] = "f" * 64
                else: changed[table]["sha256"] = "f" * 64
                with self.subTest(authority=table), self.assertRaisesRegex(probe.NoCashFixtureError, "GAMEPLAY_ORIGINAL_AUTHORITY_CHANGED"):
                    subject.require_gameplay_no_cash(changed)
            changed_rows = [list(row) for row in rows]; changed_rows[0][columns.index("avatar")] = "late author mutation"
            subject.member_rows = changed_rows
            with self.assertRaisesRegex(probe.NoCashFixtureError, "GAMEPLAY_ORIGINAL_MEMBER_FACT_CHANGED"):
                subject.require_gameplay_no_cash(protected)
            subject.member_rows = rows
            for field, value in (("schema", "foreign"), ("serverUuid", "foreign")):
                old = proof[field]; proof[field] = value
                try:
                    with self.subTest(scope=field), self.assertRaises(probe.NoCashFixtureError):
                        subject.require_gameplay_no_cash(protected)
                finally: proof[field] = old
            original = path.read_bytes(); altered = copy.deepcopy(identities); altered["a"]["token"] = "late.bbb.ccc"
            path.write_text(json.dumps(altered))
            with self.assertRaises(probe.NoCashFixtureError): subject.require_gameplay_no_cash(protected)
            path.write_bytes(original)
            subject.rows.return_value = [(1,)]
            with self.assertRaisesRegex(probe.NoCashFixtureError, "GAMEPLAY_REFUND_FIXTURE_NOT_EMPTY"):
                subject.require_gameplay_no_cash(protected)

    def test_default_still_rejects_two_gameplay_members_and_saves_safe_first_failure(self):
        subject = mock.Mock()
        subject.gameplay_binding, subject.holder, subject.pause_intent = None, None, False
        protected = {table: {"rows": 0, "sha256": "b" * 64} for table in probe.auth.FINANCIAL_TABLES}
        protected["ums_member"]["rows"] = 2
        subject.protected.return_value = protected
        written = []
        with mock.patch.object(probe, "Probe", return_value=subject), mock.patch.object(probe.auth, "write_report",
                side_effect=lambda out, name, value: written.append(copy.deepcopy(value))):
            with self.assertRaisesRegex(RuntimeError, "financial fixture not empty; no cash workload permitted"):
                probe.observe("owned", "schema", Path("unused"), Path("unused"), 1234)
        subject.curves.assert_not_called(); subject.mixed.assert_not_called()
        self.assertEqual(protected, written[-1]["protectedBefore"])
        self.assertEqual("DEFAULT_FINANCIAL_NOT_EMPTY", written[-1]["firstFailure"]["reasonCode"])
        self.assertEqual(["ums_member"], written[-1]["firstFailure"]["tables"])
        self.assertEqual("FAIL_PARTIAL", written[-1]["result"])

    def test_finalization_retains_business_admission_code_before_cleanup_failure(self):
        first = {"type": "NoCashFixtureError", "reasonCode": "GAMEPLAY_MEMBER_POINTS_NOT_ZERO", "tables": ["ums_member"]}
        report = {"result": "FAIL_PARTIAL", "firstFailure": first, "protectedBefore": {
            "ums_member": {"rows": 2, "sha256": "b" * 64}}}
        path = mock.Mock(); path.read_text.return_value = json.dumps(report)
        out = mock.Mock(); out.__truediv__ = mock.Mock(return_value=path)
        written = []
        with mock.patch.object(probe, "source_identity", return_value={}), \
                mock.patch.object(probe.auth, "write_report", side_effect=lambda out, name, value: written.append(copy.deepcopy(value))), \
                mock.patch.object(probe.subprocess, "run") as inspect:
            with self.assertRaises(RuntimeError): probe.finalize("owned", "schema", out, 1)
        inspect.assert_not_called()
        self.assertEqual(first, written[-1]["firstFailure"])
        self.assertEqual("FAIL_PARTIAL", written[-1]["businessResultBeforeCleanup"])
        self.assertEqual(report["protectedBefore"], written[-1]["protectedBefore"])
        self.assertIn("finalizationFailure", written[-1])


class PackagedBusinessContractControls(unittest.TestCase):
    """Synthetic reducer controls, NOT actual JAR/cgroup/SQL/cleanup execution proof."""
    def evidence(self):
        identity = {"sourceSha": "a" * 40, "sourceTree": "b" * 40, "runId": "123", "runAttempt": "1"}
        name = "cy-auth-" + "c" * 32
        schema = "cy_e2e_auth_" + name[8:]
        identities, columns, rows, authority = GameplayNoCashActorControls().facts()
        protected = {table: {"rows": 0, "sha256": "d" * 64, "ddlSha256": "e" * 64}
                     for table in set(probe.auth.FINANCIAL_TABLES + probe.AUTHORITY_TABLES + probe.GAMEPLAY_EMPTY_TABLES)}
        protected.update(copy.deepcopy(authority))
        protected["ums_member"]["rows"] = 2
        actor = probe.gameplay_actor_proof(identities, columns, rows, schema,
            "12345678-1234-1234-1234-123456789abc", "source-seed", "f" * 64, authority)
        classes = {name: {"sha256": "1" * 64, "origin": "PACKAGED_APPLICATION_JAR"}
                   for name in probe.PACKAGED_BUSINESS_CLASSES}
        boot = {"appCid": "2" * 64, "javaPid": 7, "hostPid": 100, "startTicks": 901,
                "startedAt": "2026-10-29T00:00:00Z", "imageId": "sha256:" + "9" * 64,
                "argvSha256": "3" * 64, "jarSha256": "4" * 64, "packagedClasses": classes,
                "classLoadingEvidence": "UNKNOWN_NO_RUNTIME_CLASS_LOAD_EVENTS",
                "mainClass": probe.PACKAGED_BUSINESS_CLASSES[0],
                "launcher": "org.springframework.boot.loader.launch.JarLauncher",
                "cgroup": {"memoryBytes": 1024 ** 3, "memorySwapBytes": 1024 ** 3,
                    "nanoCpus": 10 ** 9, "pidsLimit": 256, "readOnlyRootfs": True, "datasourceMaxActive": 3,
                    "actualReadback": {"memoryMaxBytes": 1024 ** 3, "swapMaxBytes": 0,
                        "cpuMaxQuota": 100000, "cpuMaxPeriod": 100000, "pidsMax": 256}}}
        second = copy.deepcopy(boot)
        second.update(appCid="b" * 64, hostPid=101, startTicks=902, startedAt="2026-10-29T00:00:01Z")
        stores = {kind: {"id": digit * 64, "Pid": pid, "StartedAt": "original-store", "networkId": "7" * 64}
                  for kind, digit, pid in (("mysql", "5", 200), ("redis", "6", 300))}
        core = {"status": "BUSINESS_ASSERTIONS_PASSED", "realHttpRequests": 144,
                "expectedHttpRequests": 144, "newIntents": 72, "waves": []}
        facts = []
        for index, concurrency in enumerate(probe.CONCURRENCY):
            intent_sha, fact_sha = str(index + 1) * 64, str(index + 4) * 64
            wave = {"status": "ASSERTIONS_PASSED", "concurrency": concurrency,
                    "newCommands": 24, "readReplayForeign": 24,
                    "originalIntentSha256": intent_sha, "committedGameFactsSha256": fact_sha}
            for field in ("writeCurve", "readbackCurve"):
                records = [{"category": "LEGAL_AUTH_REJECTION" if field == "readbackCurve" and i % 3 == 2 else "SUCCESS",
                            "elapsedSeconds": .01,
                            "kind": "command" if field == "writeCurve" else ("replay", "receipt", "foreign")[i % 3],
                            "fixtureKey": "CAPACITY_%02d" % (index * 24 + i)} for i in range(24)]
                wave[field] = {**probe.distribution(records, .5), "records": records,
                    "configuredConcurrency": concurrency, "maxOutstandingClientFutures": concurrency,
                    "additionalClientWaitingQueue": 0, "serverConcurrentRequests": "UNKNOWN_NOT_MEASURED"}
            core["waves"].append(wave)
            facts.append({"originalIntentSha256": intent_sha, "committedGameFactsSha256": fact_sha,
                          "afterReadbackGameFactsSha256": fact_sha, "receiptCount": 24})
        subject = {"identity": identity, "taskName": name, "schema": schema,
            "serverUuid": actor["serverUuid"], "networkId": "7" * 64, "jar": {"sha256": "4" * 64, "size": 100},
            "classEvidence": classes, "boots": [boot, second], "stores": {"before": stores, "after": copy.deepcopy(stores)},
            "actorProof": actor, "protectedBefore": protected, "protectedAfter": copy.deepcopy(protected), "waveFacts": facts,
            "takeover": {"boundary": "THIRD_WAVE_SQL_COMMITTED_BEFORE_READBACK", "oldAppCid": boot["appCid"],
                "newAppCid": second["appCid"], "oldProcessAbsent": True, "exitCode": 137, "oomKilled": False,
                "factsBeforeSha256": facts[2]["committedGameFactsSha256"], "factsAfterSha256": facts[2]["committedGameFactsSha256"],
                "oldReadRequests": 0, "newReadRequests": 24}}
        fault = {"stage": "FAULT_HELD", "category": "LEGAL_AUTH_REJECTION", "elapsedSeconds": 5.01,
                 "originalIntentSha256": "8" * 64, "beforeFactsSha256": "9" * 64,
                 "afterFactsSha256": "9" * 64, "resultSha256": None}
        recovery = dict(fault, stage="RECOVERED", category="SUCCESS", elapsedSeconds=.01, resultSha256="a" * 64)
        mixed = {"taskName": name, "schema": schema, "ownedIds": [second["appCid"], stores["mysql"]["id"], stores["redis"]["id"]],
                 "networkId": subject["networkId"], "jarBefore": subject["jar"], "protectedBefore": protected,
                 "protectedAfter": protected, "initial": {"applicationJavaPid": second["javaPid"], "databaseUuid": actor["serverUuid"]},
                 "mixed": {"businessReadProbes": [fault, recovery]}}
        # Only this synthetic control copies values. Real caller MUST independently read
        # historical physical/JAR/SQL evidence; this is explicitly not that caller.
        verified = copy.deepcopy(subject)
        verified.update(originalExit=0, mixedObservation=mixed, cleanup={
            "result": "EXACT_IDS_AND_NAMES_ABSENT", "networkId": subject["networkId"],
            "networkAbsent": True, "oldProcessAbsent": True, "oldContainerAbsent": True,
            "ownedRemoved": [{"name": name + "-" + suffix, "id": cid, "independentNoSuch": True}
                             for suffix, cid in zip(("app", "mysql", "redis"), mixed["ownedIds"])]})
        return core, subject, verified

    def reduce(self, core, subject, verified):
        # The existing mixed validator has its own retained tests. Isolate only the
        # added join here; no fake Docker/JAR/native SQL claim is made by this mock.
        with mock.patch.object(probe, "source_identity", return_value=subject["identity"]), \
                mock.patch.object(probe, "validate_observation") as original:
            result = probe.packaged_business_summary(core, subject, verified)
            original.assert_called_once_with(verified["mixedObservation"], subject["identity"])
            return result

    def test_three_original_curves_and_two_fault_reads_have_distinct_denominators(self):
        result = self.reduce(*self.evidence())
        self.assertEqual((144, 2, 146, 72), tuple(result[key] for key in (
            "businessHttpRequests", "businessFaultReadRequests", "totalBusinessHttpRequests", "newIntents")))
        self.assertEqual(6, len(result["curves"]))
        self.assertEqual(144, result["total"]["requestsRecorded"])
        self.assertEqual(24, result["total"]["counts"]["LEGAL_AUTH_REJECTION"])
        self.assertFalse(result["wholeSystemCapacityAccepted"])
        self.assertFalse(result["q4q8Complete"])
        self.assertIn("UNKNOWN", result["boots"][0]["classLoadingEvidence"])

    def test_full_source_identity_is_projected_without_losing_runner_binding(self):
        core, subject, verified = self.evidence()
        current = dict(subject["identity"], runnerOs="Linux", runnerEnvironment="github-hosted")
        with mock.patch.object(probe, "source_identity", return_value=current), \
                mock.patch.object(probe, "validate_observation") as original:
            result = probe.packaged_business_summary(core, subject, verified)
            original.assert_called_once_with(verified["mixedObservation"], current)
        self.assertEqual(subject["identity"], result["identity"])

    def test_new_binding_is_explicit_and_old_mode_cannot_silently_upgrade(self):
        objects, original = GameplayProfileControls().profile()
        binding = copy.deepcopy(original)
        binding.update(subjectProfile=probe.PACKAGED_PROFILE, packagedSubject={"physicalProof": "required later by capture"})
        binding["boot"]["bootId"] = objects[0]["Id"] + ":901"
        with GameplayProfileControls().admission(), \
                mock.patch.dict(probe.os.environ, {"CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY": "true"}):
            probe.validate_gameplay_binding(objects, original)
            with self.assertRaisesRegex(RuntimeError, "wrong gameplay application"):
                probe.validate_gameplay_binding(objects, binding)
            objects[0]["Config"]["Cmd"] = list(probe.PACKAGED_COMMAND)
            probe.validate_gameplay_binding(objects, binding)
            for flag in ("", "false", "invalid"):
                with mock.patch.dict(probe.os.environ, {"CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY": flag}), \
                        self.assertRaises(RuntimeError):
                    probe.validate_gameplay_binding(objects, binding)
            objects[0]["Config"]["Env"].append("CLASSPATH=/foreign/test-classes")
            with self.assertRaisesRegex(RuntimeError, "classpath"):
                probe.validate_gameplay_binding(objects, binding)

    def test_foreign_or_missing_owner_readback_cannot_self_approve(self):
        for field in ("jar", "identity", "classEvidence", "waveFacts", "protectedBefore", "actorProof"):
            core, subject, verified = self.evidence()
            del verified[field]
            with self.subTest(field=field), self.assertRaises(RuntimeError):
                self.reduce(core, subject, verified)
        core, subject, verified = self.evidence()
        verified["boots"][1]["appCid"] = "f" * 64
        with self.assertRaisesRegex(RuntimeError, "OWNER_READBACK"):
            self.reduce(core, subject, verified)

    def test_matching_forged_subject_cannot_change_main_budget_facts_or_takeover(self):
        changes = [lambda s: s["boots"][0].update(launcher="org.springframework.boot.loader.launch.PropertiesLauncher"),
                   lambda s: s["boots"][0].update(mainClass=probe.GAMEPLAY_CHILD),
                   lambda s: s["boots"][0]["cgroup"].update(memoryBytes=2 * 1024 ** 3),
                   lambda s: s["boots"][0]["cgroup"]["actualReadback"].update(swapMaxBytes=1024),
                   lambda s: s["boots"][0].update(classLoadingEvidence="ALL_CLASSES_LOADED"),
                   lambda s: s["classEvidence"].pop(probe.PACKAGED_BUSINESS_CLASSES[2]),
                   lambda s: s["waveFacts"][1].update(receiptCount=0),
                   lambda s: s["waveFacts"][2].update(afterReadbackGameFactsSha256="e" * 64),
                   lambda s: s["protectedAfter"]["oms_order"].update(rows=1),
                   lambda s: s["stores"]["after"]["redis"].update(id="f" * 64),
                   lambda s: s["takeover"].update(oldProcessAbsent=False),
                   lambda s: s["takeover"].update(newReadRequests=23)]
        for index, change in enumerate(changes):
            core, subject, verified = self.evidence()
            change(subject)
            verified.update(copy.deepcopy(subject))
            with self.subTest(index=index), self.assertRaises(RuntimeError):
                self.reduce(core, subject, verified)

    def test_fake_success_unknown_duplicate_or_inflated_curve_is_not_promoted(self):
        for change in (
            lambda c: c["waves"][0]["writeCurve"]["records"][0].update(category="UNKNOWN"),
            lambda c: c["waves"][1]["readbackCurve"]["records"][0].update(fixtureKey="CAPACITY_00"),
            lambda c: c["waves"][2]["writeCurve"].update(successfulRequestsPerSecond=999),
            lambda c: c["waves"][0]["writeCurve"].update(maxOutstandingClientFutures=20),
            lambda c: c.update(realHttpRequests=240),
        ):
            core, subject, verified = self.evidence()
            change(core)
            with self.assertRaises(RuntimeError):
                self.reduce(core, subject, verified)

    def test_original_failure_unknown_cleanup_or_foreign_mixed_cannot_promote(self):
        for change in (
            lambda v: v.update(originalExit=7),
            lambda v: v["cleanup"].update(networkAbsent=False),
            lambda v: v["cleanup"]["ownedRemoved"][0].update(id="e" * 64),
            lambda v: v["mixedObservation"].update(networkId="e" * 64),
            lambda v: v["mixedObservation"]["mixed"]["businessReadProbes"][1].update(originalIntentSha256="e" * 64),
        ):
            core, subject, verified = self.evidence()
            change(verified)
            with self.assertRaises(RuntimeError):
                self.reduce(core, subject, verified)

    def test_probe_projection_rejects_canary_unknown_and_changed_original_facts(self):
        _, _, verified = self.evidence()
        fault, recovery = verified["mixedObservation"]["mixed"]["businessReadProbes"]
        self.assertIs(fault, probe.validate_business_probe(fault, "FAULT_HELD"))
        self.assertIs(recovery, probe.validate_business_probe(recovery, "RECOVERED"))
        for changed in (dict(fault, category="UNKNOWN"), dict(fault, elapsedSeconds=float("nan")),
                        dict(fault, category="SUCCESS"),
                        dict(fault, afterFactsSha256="e" * 64), dict(fault, response="private-canary"),
                        dict(recovery, resultSha256=None), dict(recovery, category="SYSTEM_FAILURE")):
            with self.assertRaises(RuntimeError) as raised:
                probe.validate_business_probe(changed, changed["stage"])
            self.assertNotIn("private-canary", str(raised.exception))

    def test_native_owner_must_match_independently_captured_process_and_jar(self):
        _, subject, _ = self.evidence()
        boot = subject["boots"][1]
        snapshot = {"boot": boot, "identity": subject["identity"],
                    "networkId": subject["networkId"], "classEvidence": subject["classEvidence"]}
        native = {
            "cid": boot["appCid"], "javaPid": boot["javaPid"], "hostJavaPid": boot["hostPid"],
            "hostInitPid": 101, "startedAt": boot["startedAt"], "imageId": boot["imageId"],
            "javaStartTicks": str(boot["startTicks"]), "javaArgvSha256": boot["argvSha256"],
            "jarSha256": boot["jarSha256"], "sourceSha": subject["identity"]["sourceSha"],
            "networkId": subject["networkId"], "javaMain": boot["mainClass"],
            "productionClassSha256": {key: row["sha256"] for key, row in subject["classEvidence"].items()},
            "memoryBytes": 1024 ** 3, "memorySwapBytes": 1024 ** 3, "nanoCpus": 10 ** 9,
            "pidsLimit": 256, "actualMemoryMaxBytes": 1024 ** 3, "actualSwapMaxBytes": 0,
            "actualPidsMax": 256, "testClasspathMountedIntoSubject": False,
            "allObservedJvmThreadsInSameCgroup": True, "workerThreadsObserved": 50,
        }
        binding = {"appPid": 101, "packagedSubject": native}
        probe.validate_packaged_owner(snapshot, binding)
        for key, wrong in (("cid", "f" * 64), ("javaPid", 999), ("hostInitPid", 999),
                           ("jarSha256", "f" * 64), ("javaArgvSha256", "f" * 64),
                           ("actualSwapMaxBytes", 1024), ("testClasspathMountedIntoSubject", True),
                           ("allObservedJvmThreadsInSameCgroup", False)):
            with self.subTest(key=key), self.assertRaisesRegex(RuntimeError, "OWNER_READBACK"):
                probe.validate_packaged_owner(snapshot, {"appPid": 101, "packagedSubject": dict(native, **{key: wrong})})
        with self.assertRaisesRegex(RuntimeError, "OWNER_READBACK"):
            probe.validate_packaged_owner(snapshot, {"appPid": 101, "packagedSubject": dict(native, productionClassSha256={})})

    def test_capture_reads_actual_pid_mount_jar_classes_and_cgroup_not_subject_flags(self):
        # File/JDBC/command doubles only. This exercises the real capture function;
        # it is not a claim that the fixtures below are compiled or physically run.
        import io
        import zipfile
        core, subject, _ = self.evidence()
        identities, columns, members, _ = GameplayNoCashActorControls().facts()
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory).resolve() / "application.jar"
            system_bytes = io.BytesIO()
            with zipfile.ZipFile(system_bytes, "w") as system:
                for name in probe.PACKAGED_BUSINESS_CLASSES[2:]:
                    system.writestr(name.replace(".", "/") + ".class", b"synthetic-class-bytes")
            with zipfile.ZipFile(jar, "w") as outer:
                outer.writestr("BOOT-INF/classes/git.properties", "git.commit.id.full=" + subject["identity"]["sourceSha"] + "\n")
                outer.writestr("META-INF/MANIFEST.MF", "Main-Class: org.springframework.boot.loader.launch.JarLauncher\r\n"
                               "Start-Class: com.chengyinhub.RuoYiApplication\r\n\r\n")
                outer.writestr("BOOT-INF/lib/chengyinhub-system-test.jar", system_bytes.getvalue())
                for name in probe.PACKAGED_BUSINESS_CLASSES[:2]:
                    outer.writestr("BOOT-INF/classes/" + name.replace(".", "/") + ".class", b"synthetic-main-bytes")
            app = {"Id": "2" * 64, "Image": "sha256:" + "9" * 64,
                   "HostConfig": {"Memory": 1024 ** 3, "MemorySwap": 1024 ** 3, "NanoCpus": 10 ** 9,
                                  "PidsLimit": 256, "ReadonlyRootfs": True},
                   "Mounts": [{"Destination": "/application.jar", "Type": "bind", "Source": str(jar), "RW": False}],
                   "State": {"StartedAt": "2026-10-29T00:00:00Z"}, "Config": {},
                   "NetworkSettings": {"Networks": {subject["taskName"]: {"IPAddress": "172.20.0.2"}}}}
            stores = [{"Id": digit * 64, "State": {"Pid": pid, "StartedAt": "original-store"},
                       "NetworkSettings": {"Networks": {subject["taskName"]: {"IPAddress": ip}}}}
                      for digit, pid, ip in (("5", 200, "172.20.0.3"), ("6", 300, "172.20.0.4"))]
            argv = probe.PACKAGED_ARGV
            private = jar.parent / "gameplay"
            private.mkdir()
            identities_path = private / "identities.json"
            identities_path.write_text(json.dumps(identities)); identities_path.chmod(0o600)
            subject["actorProof"]["identitiesSha256"] = probe.file_digest(identities_path)["sha256"]
            config = {"sourceSha": subject["identity"]["sourceSha"], "jarSha256": probe.file_digest(jar)["sha256"],
                "childClassSha256": "c" * 64, "schema": subject["schema"], "serverUuid": subject["serverUuid"],
                "jdbcUrl": "jdbc:mysql://172.20.0.3:3306/" + subject["schema"]
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                "redisHost": "172.20.0.4", "tokenSecret": "s" * 64}
            config_path = private / "config.json"
            config_path.write_text(json.dumps(config)); config_path.chmod(0o600)
            settings = {"spring.datasource.druid.master.url": config["jdbcUrl"],
                        "spring.data.redis.host": config["redisHost"],
                        "token.secret": config["tokenSecret"], "coupon.qr-secret": config["tokenSecret"]}
            properties_path = private / "packaged.properties"
            properties_path.write_text("\n".join(key + "=" + value for key, value in settings.items()) + "\n")
            properties_path.chmod(0o600)
            app["Mounts"].append({"Destination": "/recovery-private", "Type": "bind", "Source": str(private), "RW": False})
            app["Config"]["Cmd"] = list(probe.PACKAGED_COMMAND)
            fixture = mock.Mock(packaged_subject=True, name="unused")
            fixture.name, fixture.schema, fixture.jar = subject["taskName"], subject["schema"], jar
            fixture.private = jar.parent / "credentials.json"
            fixture.identity = subject["identity"]
            fixture.saved = {"serverUuid": subject["serverUuid"]}
            fixture.gameplay_binding = {"boot": {"javaPid": 7, "bootId": app["Id"] + ":901"},
                **{key: config[key] for key in ("sourceSha", "jarSha256", "childClassSha256")},
                "configSha256": probe.file_digest(config_path)["sha256"]}
            fixture.jar_identity.return_value = probe.file_digest(jar)
            fixture.inspect.return_value = ([app] + stores, {"Id": subject["networkId"]})
            fixture.protected.return_value = subject["protectedBefore"]
            fixture.rows.return_value = ((subject["serverUuid"], subject["schema"]),)
            fixture.db = mock.MagicMock()
            cursor = fixture.db.cursor.return_value.__enter__.return_value
            cursor.description = [(key,) for key in columns]
            cursor.fetchmany.return_value = members
            def command(args):
                if args[-1] == "/proc/7/cmdline":
                    return b"\0".join(item.encode() for item in argv) + b"\0"
                if args[-1] == "/proc/7/stat":
                    return ("7 (java) " + " ".join(["S"] + ["0"] * 18 + ["901"])).encode()
                if "top" in args:
                    return b"PID COMMAND\n100 java\n"
                if args[-1] == "/recovery-private/packaged.properties":
                    return (probe.file_digest(properties_path)["sha256"] + "  /recovery-private/packaged.properties\n").encode()
                self.fail("unexpected command")
            reads = {"/proc/100/status": "NSpid:\t100\t7\n", "/proc/100/cgroup": "0::/source-owned\n",
                "/sys/fs/cgroup/source-owned/cpu.max": "100000 100000\n",
                "/sys/fs/cgroup/source-owned/memory.max": str(1024 ** 3),
                "/sys/fs/cgroup/source-owned/memory.swap.max": "0", "/sys/fs/cgroup/source-owned/pids.max": "256"}
            original_read_text = Path.read_text
            def read_text(path):
                return reads[str(path)] if str(path) in reads else original_read_text(path)
            with mock.patch.object(probe, "validate_gameplay_binding"), \
                    mock.patch.object(probe, "source_identity", return_value=fixture.identity), \
                    mock.patch.object(probe.auth, "command", side_effect=command), \
                    mock.patch.object(Path, "read_text", autospec=True, side_effect=read_text):
                observed = probe.capture_packaged_subject(fixture, identities, subject["actorProof"])
                self.assertEqual(100, observed["boot"]["hostPid"])
                self.assertEqual(901, observed["boot"]["startTicks"])
                self.assertEqual(0, observed["boot"]["cgroup"]["actualReadback"]["swapMaxBytes"])
                self.assertEqual(set(probe.PACKAGED_BUSINESS_CLASSES), set(observed["classEvidence"]))
                self.assertNotIn("13800000", json.dumps(observed))
                self.assertNotIn("aaa.bbb.ccc", json.dumps(observed))
                for wrong in (((subject["serverUuid"], "foreign_schema"),),
                              (("foreign_uuid", subject["schema"]),),
                              ((subject["serverUuid"], subject["schema"]),) * 2):
                    fixture.rows.return_value = wrong
                    with self.subTest(identity=wrong), self.assertRaisesRegex(RuntimeError, "NATIVE_DATABASE"):
                        probe.capture_packaged_subject(fixture, identities, subject["actorProof"])
                fixture.rows.return_value = ((subject["serverUuid"], subject["schema"]),)
                reads["/sys/fs/cgroup/source-owned/memory.max"] = str(2 * 1024 ** 3)
                with self.assertRaisesRegex(RuntimeError, "ACTUAL_CGROUP"):
                    probe.capture_packaged_subject(fixture, identities, subject["actorProof"])
                reads["/sys/fs/cgroup/source-owned/memory.max"] = str(1024 ** 3)
                app["Mounts"][0]["RW"] = True
                with self.assertRaisesRegex(RuntimeError, "WRITABLE_JAR"):
                    probe.capture_packaged_subject(fixture, identities, subject["actorProof"])

    def test_real_mixed_body_reuses_actor_slot_and_calls_only_two_physical_windows(self):
        from concurrent.futures import Future
        fixture = object.__new__(probe.Probe)
        fixture.packaged_subject = True
        fixture.schema, fixture.redis, fixture.port = "schema", "r" * 64, 1234
        fixture.logged_at = probe.time.monotonic()
        fixture.report, fixture.expected, fixture.nonce, fixture.initial = {}, {}, "qa_owned_", {"same": True}
        fixture.remember = mock.Mock()
        fixture.save = mock.Mock()
        fixture.stamp = mock.Mock(return_value=fixture.initial)
        fixture.sample = mock.Mock(return_value={})
        fixture.blocked = mock.Mock(side_effect=[3, 3, 0])
        original = dict.fromkeys(probe.auth.LEASE_COLUMNS)
        original.update(refund_no=None, payout_status=2)
        claimed = dict(original, payout_lease_owner=1, payout_lease_token="private-lease")
        released = copy.deepcopy(original)
        fixture.refund = mock.Mock(side_effect=[original, claimed, claimed, released, released])
        fixture.http = mock.Mock(return_value=({"category": "SUCCESS"}, {"data": "private-lease"}))
        fixture.rows = mock.Mock(side_effect=[[(0,)], [(1,)], [(0,)]])
        fixture.db = mock.MagicMock()
        holder = mock.MagicMock()
        holder.cursor.return_value.__enter__.return_value.fetchone.return_value = (71, "schema", "root@owned")
        paused, release_count, stages, submissions = [False], [0], [], []
        def command(args):
            if "pause" in args:
                paused[0] = True
                return b""
            if "unpause" in args:
                paused[0] = False
                return b""
            if "inspect" in args:
                return json.dumps([{"State": {"Paused": paused[0]}}]).encode()
            if args[-1] == "PING":
                return b"PONG"
            self.fail("unexpected external command")
        def request(kind, value=None):
            self.assertNotEqual("ACTOR", kind)
            if kind == "RELEASE":
                release_count[0] += 1
                return {"category": ("LEGAL_AUTH_REJECTION", "SUCCESS", "SYSTEM_FAILURE")[release_count[0] - 1]}
            return {"category": "SUCCESS"}
        fixture.request = mock.Mock(side_effect=request)
        _, _, evidence = self.evidence()
        probe_records = evidence["mixedObservation"]["mixed"]["businessReadProbes"]
        def business(stage):
            self.assertEqual(stage == "FAULT_HELD", paused[0])
            stages.append(stage)
            return copy.deepcopy(probe_records[0 if stage == "FAULT_HELD" else 1])
        fixture.business_probe = business
        class FixedPool:
            def __init__(inner, max_workers):
                self.assertEqual(6, max_workers)
            def submit(inner, fn, *args):
                submissions.append(args)
                result = Future()
                result.set_result(fn(*args))
                return result
            def shutdown(inner, **kwargs):
                pass
        with mock.patch.object(probe.concurrent.futures, "ThreadPoolExecutor", FixedPool), \
                mock.patch.object(probe.threading, "Timer"), mock.patch.object(probe.auth, "database", return_value=holder), \
                mock.patch.object(probe.auth, "command", side_effect=command):
            fixture.mixed()
        self.assertEqual(["FAULT_HELD", "RECOVERED"], stages)
        self.assertEqual(6, len(submissions))
        self.assertEqual(2, len(fixture.report["mixed"]["businessReadProbes"]))
        self.assertTrue(fixture.report["mixed"]["faultRowUnchanged"])
        self.assertTrue(fixture.report["mixed"]["originalReleaseDurablyVerified"])
        self.assertTrue(fixture.report["mixed"]["releaseReplayRejectedAndFullRowUnchanged"])


class CapacityEntryCleanupControls(unittest.TestCase):
    def test_actual_exit_trap_preserves_failure_and_waits_for_private_cleanup(self):
        source = Path(__file__).with_name("authenticated-redis-recovery.sh").read_text()
        cleanup = "cleanup() {\n" + source.split("cleanup() {\n", 1)[1].split(
            "\n}\ntrap cleanup EXIT", 1)[0] + "\n}"
        for body, outer, private, finalizer, expected, passed, gameplay in (
            (0, 0, 0, 0, 0, 0, "false"),
            (7, 0, 0, 1, 7, 7, "false"),
            (0, 1, 0, 1, 1, 1, "false"),
            (0, 0, 1, 1, 1, 1, "false"),
            (0, 0, 0, 1, 1, 0, "false"),
            (0, 0, 0, 0, 0, 0, "true"),
            (7, 0, 0, 1, 7, 7, "true"),
            (0, 1, 0, 1, 1, 1, "true"),
            (0, 0, 1, 1, 1, 1, "true"),
            (0, 0, 0, 1, 1, 0, "true"),
            (0, 0, 0, 0, 0, 0, "packaged"),
            (7, 0, 0, 1, 7, 7, "packaged"),
            (0, 1, 0, 1, 1, 1, "packaged"),
            (0, 0, 1, 1, 1, 1, "packaged"),
            (0, 0, 0, 1, 1, 0, "packaged"),
        ):
            with self.subTest(body=body, outer=outer, private=private, finalizer=finalizer, gameplay=gameplay):
                # Only the real EXIT function runs; all external effects are shell stubs.
                script = """
set -u
ROOT=/unused-source-fixture
RUN_NAME=owned
SCHEMA=owned_schema
OUT=/unused-evidence
PRIVATE=/unused-private
CHENGYIN_CI_CAPACITY_MIXED=true
CHENGYIN_CI_GAMEPLAY_PROCESS_RECOVERY=GAMEPLAY_MODE
CHENGYIN_CI_PACKAGED_GAMEPLAY_CAPACITY=PACKAGED_MODE
CHENGYIN_CI_RESTORE_HTTP_STARTUP=false
python3() {
  case "$3" in
    cleanup) printf 'OUTER_CLEANUP\\n'; return OUTER_STATUS ;;
    finalize|packaged-finalize)
      if [[ "$2" == */gameplay_process_recovery_probe.py ]]; then
        printf 'FINALIZE:%s\\n' "$7"
      else
        printf 'FINALIZE:%s\\n' "$8"
      fi
      return FINALIZER_STATUS ;;
    *) return 99 ;;
  esac
}
rm() { return 0; }
rmdir() { printf 'PRIVATE_CLEANUP\\n'; return PRIVATE_STATUS; }
""".replace("OUTER_STATUS", str(outer)).replace(
                    "PRIVATE_STATUS", str(private)).replace("FINALIZER_STATUS", str(finalizer)).replace(
                    "GAMEPLAY_MODE", "true" if gameplay == "packaged" else gameplay).replace(
                    "PACKAGED_MODE", "true" if gameplay == "packaged" else "false")
                result = probe.subprocess.run(
                    ["bash", "-c", script + cleanup + "\ntrap cleanup EXIT\nexit " + str(body)],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)
                lines = result.stdout.splitlines()
                self.assertIn("FINALIZE:" + str(passed), lines)
                self.assertEqual(1, sum(line.startswith("FINALIZE:") for line in lines))
                self.assertLess(lines.index("OUTER_CLEANUP"), lines.index("PRIVATE_CLEANUP"))
                self.assertLess(lines.index("PRIVATE_CLEANUP"), lines.index("FINALIZE:" + str(passed)))


if __name__ == "__main__":
    unittest.main()
