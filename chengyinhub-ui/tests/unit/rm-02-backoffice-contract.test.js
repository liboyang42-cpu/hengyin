const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const read = rel => fs.readFileSync(path.join(uiRoot, rel), 'utf8');

function loadAdminStatus() {
  const src = read('src/utils/admin-status.js');
  const body = src.replace(/export function /g, 'function ') +
    '\nreturn { topicLifecycleMeta, topicCheckStatusMeta, topicUserStatusMeta, templateAuditMeta, templatePublishMeta };';
  return new Function(body)();
}

test('OWN-E: 视频悬赏与主持小费运营页具备完整只读/参考/版本冲突与禁兑付护栏', () => {
  const bounty = read('src/views/business/videobounty/index.vue');
  const hosttip = read('src/views/business/hosttip/index.vue');
  assert.match(bounty, /参考值，非已批准费率/);
  assert.match(bounty, /GROUP_VERIFY_VERSION_CONFLICT/);
  assert.match(bounty, /ACCRUAL_GROUP_UNVERIFIED/);
  assert.match(bounty, /RULE_LOCKED_BY_ACCRUAL/);
  assert.doesNotMatch(bounty, /现金兑付开关|立即打款/);
  assert.match(hosttip, /listHostTipIntents/);
  assert.doesNotMatch(hosttip, /标已付|markPaid|payout/i);
});

test('OWN-U03-04: admin-status 与 topic/audit 页统一 status/checkStatus/userStatus/lifecycle 口径', () => {
  const m = loadAdminStatus();
  assert.equal(m.topicCheckStatusMeta(0).label, '待审核');
  assert.equal(m.topicCheckStatusMeta(1).label, '审核通过');
  assert.equal(m.topicCheckStatusMeta(2).label, '审核驳回');
  assert.equal(m.topicCheckStatusMeta(3).label, '升级审核');
  assert.equal(m.topicLifecycleMeta(3).label, '售票中');
  assert.equal(m.topicUserStatusMeta(1).label, '用户端已上架');

  const topicIndex = read('src/views/business/topic/index.vue');
  const topicDetail = read('src/views/business/topic/detail.vue');
  const topicWorkspace = read('src/views/business/topic/workspace.vue');
  const auditView = read('src/views/business/audit/index.vue');

  assert.doesNotMatch(topicIndex, /审核失败/);
  assert.match(topicDetail, /unifiedCheckMeta/);
  assert.match(topicDetail, /unifiedLifecycleMeta/);
  assert.match(topicDetail, /publishedConfigVersion/);
  assert.match(topicWorkspace, /publishedVersionLabel/);
  assert.match(auditView, /detailRevisionDiff/);
  assert.match(auditView, /detailUnifiedStatus/);
  assert.match(auditView, /审核版本冲突：已有他人先行处理/);
});

test('OWN-UG-12: PlayHelp 只读面板接入且秘密字段不向非审核员开放或导出', () => {
  const api = read('src/api/business/playhelp.js');
  const panel = read('src/components/PlayHelpReadonlyPanel/index.vue');
  const topicDetail = read('src/views/business/topic/detail.vue');
  const topicWorkspace = read('src/views/business/topic/workspace.vue');
  const auditView = read('src/views/business/audit/index.vue');
  const tplDetail = read('src/views/business/membertemplate/detail.vue');

  assert.match(api, /\/business\/playhelp\/topic\/\$\{topicId\}\/config/);
  assert.match(api, /\/business\/playhelp\/run\/\$\{runId\}/);
  assert.doesNotMatch(api, /method:\s*['"](post|put|delete)['"]/i);

  assert.match(panel, /getTopicHelpConfig/);
  assert.match(panel, /getPlayHelpRunLedger/);
  assert.match(panel, /回执号/);
  assert.match(panel, /净分/);
  assert.doesNotMatch(panel, /<el-button[^>]*>.*导出|triggerHint|exportExcel/i);

  assert.match(topicDetail, /<play-help-readonly-panel/);
  assert.match(topicWorkspace, /<play-help-readonly-panel/);
  assert.match(auditView, /<play-help-readonly-panel/);

  assert.match(tplDetail, /canViewAuditSecrets/);
  assert.match(tplDetail, /仅审核可见（运营只读已隔离，不可导出）/);
});
