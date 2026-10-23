#!/usr/bin/env python3
"""Source provenance only. Never substitutes for XML, receipt, or database readback."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[4]
BASE = '26f3764ce0d81594be02c0eeace04aacdd483e58'
RUN_SOURCE = '2a2f37e8a7e7c357be390b586d889ec7535a1937'
A = '7e1fc9c26c9e0ce29c2489833bbefd121fbaf4b4'
H = 'c75ab442b572700df1d0289ba636f6f541168e4a'
TEST = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java'


def read(ref, path):
    return subprocess.check_output(['git', 'show', ref + ':' + path], cwd=ROOT)


def main():
    patch = read(H, 'docs/delivery/architecture-day/H/a-de31-recheck/unbacked-post.review.patch')
    addition = b''.join(line[1:] for line in patch.splitlines(keepends=True)
                        if line.startswith(b'+') and not line.startswith(b'+++'))
    executed = read(RUN_SOURCE, TEST)
    current = read(BASE, TEST)
    assert len(addition.splitlines()) == 51 and executed.count(addition) == 1
    assert executed.replace(addition, b'', 1) == current
    assert read(A, TEST) == executed
    assert len(re.findall(rb'@Test\b', executed)) == 95
    assert len(re.findall(rb'@Test\b', current)) == 94
    assert b'assertEquals(37, number("SELECT point FROM ums_member WHERE id=8")' in executed.splitlines()[406]
    paths = [
        'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsRegistrationController.java',
        'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CmsRegistrationServiceImpl.java',
        'chengyinhub-system/src/main/java/com/chengyinhub/business/service/ICmsRegistrationService.java',
        'chengyinhub-system/src/main/java/com/chengyinhub/business/mapper/CmsRegistrationMapper.java',
        'chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml',
    ]
    identities = {}
    for path in paths:
        before = read(RUN_SOURCE, path)
        assert before == read(BASE, path), path
        identities[path] = {'blob': subprocess.check_output(['git', 'rev-parse', RUN_SOURCE + ':' + path], cwd=ROOT, text=True).strip(),
                            'sha256': hashlib.sha256(before).hexdigest(), 'run_and_current_exactly_equal': True}
    workflow = read(RUN_SOURCE, '.github/workflows/real-storage-it.yml')
    runner = read(RUN_SOURCE, 'ci/real_storage_it.py')
    assert b'name: rs-class-${{ matrix.simple }}-a${{ github.run_attempt }}' in workflow
    assert b'path: out/' in workflow
    assert b'"receipt.json"' in runner and b'out / "surefire-reports"' in runner
    result = {'baseline': BASE, 'run_source': RUN_SOURCE, 'a_test_candidate': A, 'h_patch': H,
              'a95_source_byte_preserved': True, 'h_added_lines': 51,
              'run_source_tests': 95, 'current_source_tests': 94,
              'removing_exact_h_block_restores_current_test_file': True,
              'run_test_sha256': hashlib.sha256(executed).hexdigest(),
              'current_test_sha256': hashlib.sha256(current).hexdigest(),
              'production_files': identities,
              'artifact_layout_source': {'workflow_sha256': hashlib.sha256(workflow).hexdigest(), 'runner_sha256': hashlib.sha256(runner).hexdigest()},
              'runtime_result_provenance': 'ROOT_XML_RECEIPT_READBACK_REPORTED; A_ARTIFACT_DOWNLOAD_FORBIDDEN',
              'not_a_runtime_verification': True, 'java_executed_by_this_script': 0, 'mysql_executed_by_this_script': 0}
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
