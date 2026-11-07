"""Exact R28 registration controls; source/graph fixtures, not runtime evidence."""
import copy
import json
import unittest

import collect_jdeps as producer
import gate
import test_gate as source_fixture

API = "com.chengyinhub.web.controller.api.ApiOrderCenterController"
QUERY = "com.chengyinhub.web.service.order.OrderCenterQueryApplication"
PAIRS = [
    ("B_ORDER_CENTER_API", "B_ORDER_CENTER_QUERY", API, QUERY),
    ("B_ORDER_CENTER_QUERY", "B_PAYMENT_FACADE", QUERY, "com.chengyinhub.business.service.IWechatPayService"),
    ("B_ORDER_CENTER_QUERY", "C_REGISTRATION_QUERY_APPLICATION", QUERY,
     "com.chengyinhub.web.service.registration.RegistrationQueryApplication"),
    ("B_ORDER_CENTER_QUERY", "E_CLM_SHARED_PROOF_PORT", QUERY,
     "com.chengyinhub.business.mapper.ClubMembershipMapper"),
]


class OrderCenterSourceTest(unittest.TestCase):
    setUp = source_fixture.BoundaryRegistrationTest.setUp
    write = source_fixture.BoundaryRegistrationTest.write
    commit = source_fixture.BoundaryRegistrationTest.commit
    prepare_policy = source_fixture.BoundaryRegistrationTest.prepare_policy
    source_cli = source_fixture.BoundaryRegistrationTest.source_cli
    java = source_fixture.BoundaryRegistrationTest.java

    def test_both_exact_primary_types_are_registered(self):
        trusted, _ = self.prepare_policy()
        for name in (API, QUERY):
            path = "chengyinhub-admin/src/main/java/" + name.replace(".", "/") + ".java"
            self.write(path, self.java(path))
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(0, status, report)
        self.assertEqual([], report["findings"])
        self.assertEqual("UNKNOWN", report["boundary_registration"]["semantic_read_only"])

    def test_new_nearby_type_is_not_registered_by_prefix(self):
        trusted, _ = self.prepare_policy()
        path = "chengyinhub-admin/src/main/java/com/chengyinhub/web/service/order/OtherOrderApplication.java"
        self.write(path, self.java(path))
        status, report = self.source_cli(trusted, self.commit())
        self.assertEqual(1, status, report)
        self.assertTrue(any(f["rule"] == "NEW_UNOWNED_BOUNDARY" and f["key"] == path
                            for f in report["findings"]))

    def test_candidate_cannot_remove_either_exact_owner(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for owner in ("B_ORDER_CENTER_API", "B_ORDER_CENTER_QUERY"):
            with self.subTest(owner=owner):
                data = copy.deepcopy(original)
                owners = data["technical_dependency_policy"]["owners"]
                owners[:] = [o for o in owners if o["id"] != owner]
                self.write("ci/architecture/owners.json", gate.encode(data))
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("POLICY_CHANGE", report["error"])


    def test_coedited_trusted_data_cannot_omit_new_exact_contracts(self):
        trusted, _ = self.prepare_policy()
        original = json.loads((trusted / "owners.json").read_text())
        for source, target, _, _ in PAIRS:
            with self.subTest(direction=(source, target)):
                data = copy.deepcopy(original)
                contracts = data["technical_dependency_policy"]["exact_directions"]
                contracts[:] = [c for c in contracts
                                if (c["source_owner"], c["target_owner"]) != (source, target)]
                payload = gate.encode(data)
                self.write("ci/architecture/owners.json", payload)
                (trusted / "owners.json").write_bytes(payload)
                status, report = self.source_cli(trusted, self.commit())
                self.assertEqual(1, status, report)
                self.assertIn("OWNER_DEPENDENCY_POLICY_INVALID", report["error"])



class OrderCenterDirectionTest(unittest.TestCase):
    def setUp(self):
        self.policy = gate.technical_dependency_policy()
        self.index = {}
        for owner in self.policy["owners"].values():
            for name in owner["members"]:
                self.index[name] = {"owner": owner["module"]}
        for owner in self.policy["owners"].values():
            for name in owner["forbidden_backrefs"]:
                self.index.setdefault(name, {"owner": "chengyinhub-system"})

    def check(self, pairs, policy=None):
        selected = {"fixture": {str(i): {"source_class": a, "target_class": b}
                                for i, (a, b) in enumerate(pairs)}}
        return producer.technical_dependency_findings(self.index, selected, policy or self.policy)

    def test_only_four_actual_class_pairs_are_allowed(self):
        owners = {"B_ORDER_CENTER_API", "B_ORDER_CENTER_QUERY"}
        self.assertEqual({(a, b) for a, b, _, _ in PAIRS},
                         {e for e in self.policy["allowed_directions"] if any(n in owners for n in e)})
        self.assertEqual({(a, b): {(source, target)} for a, b, source, target in PAIRS},
                         {e: refs for e, refs in self.policy["exact_directions"].items()
                          if any(n in owners for n in e)})
        forbidden = {
            "B_ORDER_CENTER_API": {"com.chengyinhub.business.mapper.CmsRegistrationMapper",
                                   "com.chengyinhub.business.mapper.ClubMembershipMapper"},
            "B_ORDER_CENTER_QUERY": {API, "com.chengyinhub.common.core.controller.BaseController",
                                     "com.chengyinhub.business.service.impl.WechatPaymentSdkGateway",
                                     "com.chengyinhub.business.service.impl.RefundServiceImpl"},
        }
        for id_, member in (("B_ORDER_CENTER_API", API), ("B_ORDER_CENTER_QUERY", QUERY)):
            owner = self.policy["owners"][id_]
            self.assertEqual({"id", "module", "members", "forbidden_backrefs"}, set(owner))
            self.assertEqual("chengyinhub-admin", owner["module"])
            self.assertEqual([member], owner["members"])
            self.assertEqual(forbidden[id_], set(owner["forbidden_backrefs"]))
        report = self.check([(a, b) for _, _, a, b in PAIRS])
        self.assertEqual([], report["findings"])
        self.assertEqual({(a, b) for a, b, _, _ in PAIRS}, set(map(tuple, report["owner_edges"])))
        self.assertFalse(report["graph_complete"])

    def test_each_missing_forward_direction_blocks(self):
        for a, b, source, target in PAIRS:
            with self.subTest(direction=(a, b)):
                policy = copy.deepcopy(self.policy)
                policy["allowed_directions"].remove((a, b))
                report = self.check([(source, target)], policy)
                self.assertIn("NEW_OWNER_DEPENDENCY_DIRECTION", {f["rule"] for f in report["findings"]})

    def test_query_back_to_api_is_not_granted_and_cycles_block(self):
        report = self.check([(API, QUERY), (QUERY, API)])
        self.assertTrue({"OWNER_GOD_BACKREFERENCE", "NEW_OWNER_DEPENDENCY_DIRECTION", "NEW_OWNER_CYCLE"}
                        <= {f["rule"] for f in report["findings"]})

    def test_api_locator_bypass_and_query_sdk_are_blocked(self):
        for source, target in ((API, "com.chengyinhub.business.mapper.CmsRegistrationMapper"),
                               (API, "com.chengyinhub.business.mapper.ClubMembershipMapper"),
                               (QUERY, "com.chengyinhub.business.service.impl.WechatPaymentSdkGateway")):
            with self.subTest(target=target):
                report = self.check([(source, target)])
                self.assertIn("OWNER_GOD_BACKREFERENCE", {f["rule"] for f in report["findings"]})

    def test_facade_interface_does_not_grant_concrete_implementation(self):
        report = self.check([(QUERY, "com.chengyinhub.business.service.impl.WechatPayServiceImpl")])
        self.assertIn("OWNER_REFERENCE_OUTSIDE_CONTRACT", {f["rule"] for f in report["findings"]})
