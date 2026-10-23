#!/usr/bin/env python3
"""Read-only source checks; never starts Java, a database, or CI."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
BASE = "6788c741c43d9516aa7c691ca93110677c7e1acb"
ADMIN = "chengyinhub-admin/src/"
SYSTEM = "chengyinhub-system/src/"


def original(path):
    return subprocess.check_output(["git", "show", BASE + ":" + path], cwd=ROOT, text=True)


def read(path):
    return (ROOT / path).read_text()


def mask(source):
    return re.sub(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
                  lambda match: " " * len(match[0]), source)


def test_bodies(source):
    clean = mask(source)
    result = {}
    pattern = r"@Test\s+(?:public\s+)?void\s+(\w+)\s*\([^)]*\)\s*(?:throws\s+[^\{]+)?\{"
    for match in re.finditer(pattern, clean):
        end, depth = match.end(), 1
        while depth:
            if clean[end] == "{":
                depth += 1
            elif clean[end] == "}":
                depth -= 1
            end += 1
        result[match[1]] = source[match.start():end]
    return result


def sha(path):
    return hashlib.sha256((ROOT / path).read_bytes()).hexdigest()


def main():
    controllers = [ADMIN + "main/java/com/chengyinhub/web/controller/business/" + name + ".java"
                   for name in ("CmsRegistrationController", "CmsRegistrationMerchantController")]
    provenance = json.loads(read("ci/prelaunch-seed-provenance.json"))["sourceFiles"]
    bindings = []
    for path in controllers:
        before, after = original(path), read(path)
        permission_pattern = r"@PreAuthorize\([^\n]+\)"
        assert re.findall(permission_pattern, before) == re.findall(permission_pattern, after), path
        route_pattern = r"@(?:Get|Put|Post|Delete|Request)Mapping[^\n]*"
        assert re.findall(route_pattern, before) == re.findall(route_pattern, after), path
        bindings.append({"path": path, "base_sha256": hashlib.sha256(before.encode()).hexdigest(),
                         "candidate_sha256": sha(path), "registered_sha256": provenance[path],
                         "provenance_refresh_required": provenance[path] != sha(path),
                         "permission_annotation_count": len(re.findall(permission_pattern, after)),
                         "permission_and_route_annotations_unchanged": True})

    body = read(controllers[0]).split("private static CmsRegistration editableRegistrationFields", 1)[1]
    body = body.split("return editable;", 1)[0]
    copied = re.findall(r"editable\.set(\w+)\(request\.get(\w+)\(\)\)", body)
    allowed = {"Id", "ActivityTitle", "TicketName", "RealName", "Phone", "Email", "Address", "AddressId"}
    assert {a for a, b in copied} == allowed and all(a == b for a, b in copied)
    domain = read(SYSTEM + "main/java/com/chengyinhub/business/domain/CmsRegistration.java")
    assert all("get" + name + "(" in domain and "set" + name + "(" in domain for name in allowed)

    mapper = SYSTEM + "main/resources/mapper/business/CmsRegistrationMerchantMapper.xml"
    xml = ET.fromstring(read(mapper))
    query = xml.find("select[@id='selectForDeletionByIdForUpdate']")
    assert query is not None
    assert query.attrib["useCache"] == "false" and query.attrib["flushCache"] == "true"
    assert "where id = #{id} for update" in "".join(query.itertext())
    statement_ids = [node.attrib["id"] for node in xml if "id" in node.attrib]
    assert len(statement_ids) == len(set(statement_ids))

    service = read(SYSTEM + "main/java/com/chengyinhub/business/service/impl/CmsRegistrationMerchantServiceImpl.java")
    deletion = service.split("public int deleteCmsRegistrationMerchantByIds", 1)[1].split("return deleted;", 1)[0]
    assert "TreeSet<Long> ordered" in deletion
    assert deletion.index("selectForDeletionByIdForUpdate") < deletion.index("canViewTopic")
    assert deletion.index("canViewTopic") < deletion.index("getAuditStatus") < deletion.index("deleteCmsRegistrationMerchantByIds")
    assert "deleted != ordered.size()" in deletion
    for method in ("deleteCmsRegistrationMerchantByIds", "deleteCmsRegistrationMerchantById"):
        assert re.search(r"@Transactional\(rollbackFor = Exception.class\)\s+public int " + method + r"\(", service)

    inventory = []
    suites = [("web/controller/business/CmsRegistrationControllerSafetyTest.java", 10, 13),
              ("business/service/RegistrationFundsMybatisIntegrationTest.java", 92, 94),
              ("business/it/MerchantRegistrationCancellationMySqlIT.java", 8, 15)]
    for suffix, expected_old, expected_new in suites:
        path = ADMIN + "test/java/com/chengyinhub/" + suffix
        old, new = test_bodies(original(path)), test_bodies(read(path))
        assert len(old) == expected_old and len(new) == expected_new, path
        assert all(new.get(name) == source for name, source in old.items()), path
        inventory.append({"path": path, "original_tests_byte_unchanged": len(old),
                          "candidate_source_test_count": len(new), "new_methods": sorted(set(new) - set(old)),
                          "java_executed_this_batch": 0})
    assert 'P0AcceptanceSchema.sql(source, "CmsRegistrationMerchantMapper")' in read(
        ADMIN + "test/java/com/chengyinhub/business/it/MerchantRegistrationCancellationMySqlIT.java")

    protected = [SYSTEM + "main/java/com/chengyinhub/business/service/impl/" + name + ".java"
                 for name in ("CmsRegistrationServiceImpl", "RefundServiceImpl", "WechatPayServiceImpl",
                              "RegistrationCheckoutServiceImpl", "CmsTopicServiceImpl",
                              "CoopSettlementClaimServiceImpl", "TopicOperationsScopeAuthorizer")]
    assert all(read(path) == original(path) for path in protected)
    output = {"baseline": BASE, "controller_bindings": bindings, "test_sources": inventory,
              "source_checks": "PASS", "xml_lock_cache_flags": "PASS", "protected_sources_unchanged": protected,
              "java_compilation": "NOT_RUN", "mysql_execution": "NOT_RUN", "ci_dispatched": False,
              "production_ddl_executed": False}
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
