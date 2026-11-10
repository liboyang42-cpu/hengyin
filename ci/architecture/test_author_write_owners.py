"""Exact technical registration controls; no SQL/authorization/compiled claims."""
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("author_receipt_policy", HERE / "gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class AuthorWriteOwnerTest(unittest.TestCase):
    def test_only_exact_forward_roles_and_consumers_are_registered(self):
        policy = gate.technical_dependency_policy()
        owners = policy["owners"]
        api, service, storage = ("M_AUTHOR_WRITE_RECEIPT_API", "M_AUTHOR_WRITE_RECEIPT", "M_AUTHOR_WRITE_RECEIPT_STORAGE")
        self.assertEqual(["com.chengyinhub.web.controller.api.ApiAuthorWriteReceiptController"], owners[api]["members"])
        self.assertEqual(["com.chengyinhub.business.service.support.AuthorWriteReceiptService"], owners[service]["members"])
        self.assertEqual(["com.chengyinhub.business.mapper.AuthorWriteReceiptMapper"], owners[storage]["members"])
        self.assertEqual(owners[service]["members"], owners[storage]["allowed_consumers"])
        callers = ["com.chengyinhub.web.controller.api." + name for name in
                   ("ApiActivityController", "ApiTemplateController", "ApiAiNpcController", "ApiAuthorWriteReceiptController",
                    "ApiPresetController")]
        self.assertEqual(callers, owners[service]["allowed_consumers"])
        edges = {e for e in policy["allowed_directions"] if any(n in (api, service, storage) for n in e)}
        self.assertEqual({(api, service), (service, storage)}, edges)

    def test_storage_peer_consumer_reverse_edge_and_cycle_remain_blocked(self):
        policy = gate.technical_dependency_policy()
        ids = ("M_AUTHOR_WRITE_RECEIPT_API", "M_AUTHOR_WRITE_RECEIPT", "M_AUTHOR_WRITE_RECEIPT_STORAGE")
        types = [policy["owners"][n]["members"][0] for n in ids]
        index = {t: {"owner": policy["owners"][n]["module"]} for n, t in zip(ids, types)}
        spec = importlib.util.spec_from_file_location("author_receipt_dependencies", HERE / "collect_jdeps.py")
        collector = importlib.util.module_from_spec(spec); spec.loader.exec_module(collector)
        def check(edges):
            refs = {"synthetic-rule-only": {str(i): {"source_class": a, "target_class": b, "line": "rule-only"}
                                             for i, (a, b) in enumerate(edges)}}
            return collector.technical_dependency_findings(index, refs, policy)
        forward = [(types[0], types[1]), (types[1], types[2])]
        self.assertEqual([], check(forward)["findings"])
        self.assertEqual("UNKNOWN", check(forward)["verdict"])
        self.assertIn("EXACT_STORAGE_PORT_CONSUMER", {f["rule"] for f in check(forward + [(types[0], types[2])])["findings"]})
        rules = {f["rule"] for f in check(forward + [(types[2], types[1])])["findings"]}
        self.assertTrue({"OWNER_GOD_BACKREFERENCE", "NEW_OWNER_DEPENDENCY_DIRECTION", "NEW_OWNER_CYCLE"} <= rules)
