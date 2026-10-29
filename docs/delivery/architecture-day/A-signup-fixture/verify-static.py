"""Fixture source preservation only; never executes Java, DB or notifications."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[4]
base = 'b6267613693d0c067040002c3df431ac39b35feb'
path = 'chengyinhub-admin/src/test/java/com/chengyinhub/business/service/RegistrationFundsMybatisIntegrationTest.java'
before = subprocess.check_output(['git', 'show', base + ':' + path], cwd=root, text=True)
after = (root / path).read_text()
normalized = after
for statement in ('import com.chengyinhub.business.mapper.CmsTopicMapper;\n',
                  'import com.chengyinhub.business.domain.CmsTopic;\n',
                  'import org.springframework.jdbc.core.BeanPropertyRowMapper;\n'):
    assert normalized.count(statement) == 1
    normalized = normalized.replace(statement, '', 1)
assert normalized.count('        wireSignupNoticeFixture(target);\n') == 1
normalized = normalized.replace('        wireSignupNoticeFixture(target);\n', '', 1)
start = normalized.index("    /** Notification persistence is covered by D's dedicated IT;")
end = normalized.index('    @BeforeEach', start)
normalized = normalized[:start] + normalized[end:]
normalized = normalized.replace('// (保留原夹具：建单时活动未绑定主题，随后才补齐归因冻结所需事实。)',
                                '// (本套件的 service fixture 没有接 cmsTopicMapper，建单期间 activity.topic_id 必须保持为空。)')
assert normalized == before
assert len(re.findall(r'@Test\b', before)) == len(re.findall(r'@Test\b', after)) == 92
print('PASS SOURCE: all 92 existing test bodies/assertions, real funds mapper wiring, DDL and helpers preserved; only fixture addition/imports/comment differ')
assert 'when(intents.findIntentId(org.mockito.ArgumentMatchers.anyString())).thenReturn(null)' in after
assert 'when(im.deliverSystemNotification(org.mockito.ArgumentMatchers.anyLong())).thenReturn(1L)' in after
assert 'new BeanPropertyRowMapper<>(CmsTopic.class)' in after and 'SELECT * FROM cms_topic WHERE id = ?' in after
assert 'ReflectionTestUtils.setField(target, "systemNotificationIntents", intents)' in after
assert 'ReflectionTestUtils.setField(target, "cmsTopicMapper", topics)' in after
print('PASS SOURCE: explicit absent intent and positive receipt/delivery stubs; topic adapter reads actual existing fixture rows, not invented facts')
changes = set(subprocess.check_output(['git', 'diff', '--name-only', base], cwd=root, text=True).splitlines())
changes.update(subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines())
assert {p for p in changes if not p.startswith('docs/delivery/architecture-day/A-signup-fixture/')} <= {path}, changes
for directory, expected in (('/workspace/hengyin-a-wp12', 'dee20f264de16d7b155c414cd5ebcfffff82908d'),
                            ('/workspace/hengyin-a-merchant-cancel', '1364aee323c6684e5ad97e3fe4264900866775c6'),
                            ('/workspace/hengyin-a-trigger-delimiter', '2f01f4eb7cb10b5dd89bafd18c90204f7a95b362')):
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=directory, text=True).strip() == expected
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=directory)
subprocess.run(['git', 'diff', '--check', base], cwd=root, check=True)
print('PASS SOURCE: only assigned test fixture and delivery docs changed; production/D/B/G/CI/schema unchanged; frozen candidates clean')
print('NOT RUN: Java compilation, all 92 Java/MySQL tests, real notification atomicity, D18, CI dispatch')
