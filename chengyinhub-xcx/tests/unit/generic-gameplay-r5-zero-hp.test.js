const test = require('node:test');
const assert = require('node:assert/strict');
const zero = require('../../pages/publish/fabu/theme-runtime-zero-hp.js');
const support = require('../../pages/publish/utils/publish/gameplay-support-matrix.js');
const matrix = require('./fixtures/gameplay-support-matrix.json').data;
const clone = value => JSON.parse(JSON.stringify(value));
const rules = { hp: { enabled: true, max: 10 } };
const policies = zero.OPTIONS.map(row => row.key);
const base = () => ({ schemaVersion: 1, gameplayRuntime: { runtimeScope: 'PLAYER', survival: { actionLimitPolicy: 'BLOCK_OVERFLOW', recoveries: [{ recoveryKey: 'old', custom: [1] }], opaque: [2] } }, extra: true });
const configured = policy => { const config = base(), draft = zero.read(config); draft.policy = policy; return zero.apply(config, draft); };

for (const policy of policies) test('HP 五种策略序列化，仅写所选块且保留 survival 未知字段：' + policy, () => {
  const c = configured(policy), draft = zero.read(c);
  if (policy === 'CHALLENGE') { draft.challenge.challengeNodeId = 11; draft.challenge.gameplayKey = 'coinFlip'; }
  c.gameplayRuntime.survival.zeroMessage = '零点回复';
  const out = zero.apply(c, Object.assign(draft, { zeroMessage: '零点回复' }));
  assert.equal(zero.validate(out, rules, policies), '');
  assert.deepEqual(out.gameplayRuntime.survival.opaque, [2]); assert.equal(out.extra, true);
  const fields = ['zeroChallenge', 'zeroSteps', 'zeroWait'].filter(key => out.gameplayRuntime.survival[key]);
  assert.deepEqual(fields, policy === 'CHALLENGE' ? ['zeroChallenge'] : policy === 'STEPS' ? ['zeroSteps'] : policy === 'WAIT' ? ['zeroWait'] : []);
  assert.deepEqual(zero.apply(out, zero.read(out)), out);
});

for (const [name, survival] of Object.entries({
  legacyChallenge: { zeroHpPolicy: 'CHALLENGE', zeroChallenge: { challengeNodeId: 11, alternativeNodeIds: [12], restoreTo: 4, limit: 3, unknown: [4] }, zeroMessage: '旧文案', opaque: true },
  paid: { zeroHpPolicy: 'BLOCK_PAID', unknown: [3] }, end: { zeroHpPolicy: 'LEGACY_END', unknown: [4] },
})) test('旧策略只读往返不升级：' + name, () => {
  const c = { gameplayRuntime: { survival } }; assert.equal(zero.readOnly(c), true);
  const draft = zero.read(c); assert.deepEqual(zero.apply(c, draft), c); draft.policy = 'WAIT'; assert.deepEqual(zero.apply(c, draft), c);
});

for (const [label, policy, path, value, pattern] of [
  ['restore0', 'WAIT', 'zeroWait.restoreTo', 0, /恢复值/], ['restoreMax', 'WAIT', 'zeroWait.restoreTo', 11, /恢复值/],
  ['limit0', 'WAIT', 'zeroWait.limit', 0, /次数/], ['limit100', 'WAIT', 'zeroWait.limit', 100, /次数/],
  ['minute0', 'WAIT', 'zeroWait.minutes', 0, /分钟/], ['minuteOver', 'WAIT', 'zeroWait.minutes', 10081, /分钟/],
  ['step0', 'STEPS', 'zeroSteps.steps', 0, /步数/], ['stepOver', 'STEPS', 'zeroSteps.steps', 100001, /步数/],
  ['completion0', 'CHALLENGE', 'zeroChallenge.requiredCompletions', 0, /完成次数/], ['completion100', 'CHALLENGE', 'zeroChallenge.requiredCompletions', 100, /完成次数/],
  ['seconds0', 'CHALLENGE', 'zeroChallenge.withinSeconds', 0, /限时/], ['secondsOver', 'CHALLENGE', 'zeroChallenge.withinSeconds', 86401, /限时/],
  ['boolean', 'WAIT', 'zeroWait.minutes', true, /分钟/], ['decimal', 'STEPS', 'zeroSteps.steps', 1.5, /步数/], ['string', 'WAIT', 'zeroWait.minutes', '30', /分钟/],
  ['then', 'WAIT', 'zeroWait.thenPolicy', 'LEGACY_END', /用完/], ['version', 'WAIT', 'zeroWait.version', 2, /版本/],
  ['message', 'WAIT', 'zeroMessage', '字'.repeat(2001), /2000/],
]) test('HP 范围/枚举边界：' + label, () => {
  const c = configured(policy), survival = c.gameplayRuntime.survival;
  const parts = path.split('.'); if (parts.length === 1) survival[path] = value; else survival[parts[0]][parts[1]] = value;
  assert.match(zero.validate(c, rules, policies), pattern);
});

test('HP 关闭、策略不匹配、矩阵不支持 STEPS 均拒绝；选项显示真实原因', () => {
  const c = configured('WAIT'); assert.match(zero.validate(c, { hp: { enabled: false, max: 10 } }, policies), /开启/);
  c.gameplayRuntime.survival.zeroSteps = zero.read(c).steps; assert.match(zero.validate(c, rules, policies), /所选恢复参数/);
  const unsupported = matrix.zeroHpPolicies.generic.filter(key => key !== 'STEPS'); const options = zero.options(unsupported); const steps = options.find(row => row.key === 'STEPS'); assert.equal(steps.disabled, true); assert.match(steps.reason, /暂未开放/);
  assert.match(zero.validate(configured('STEPS'), rules, unsupported), /不支持/);
});

test('归零卡摘要来自当前已保存策略，五项均提供 label/sub/reason', () => {
  const c = configured('WAIT'); c.gameplayRuntime.survival.zeroMessage = '歇一会';
  assert.equal(zero.summary(c), '等待一段时间 · 30 分钟 · 歇一会');
  assert.equal(zero.summary({ gameplayRuntime: { survival: { zeroHpPolicy: 'LEGACY_END' } } }), '旧版结束');
  for (const row of zero.options(matrix.zeroHpPolicies.generic)) {
    assert.equal(typeof row.label, 'string'); assert.equal(typeof row.sub, 'string'); assert.equal(typeof row.reason, 'string');
  }
});

const node = (id, advanced) => ({ id, name: '站点' + id, template: { advancedConfigJson: JSON.stringify(advanced) } });
const nodes = [node(11, { coinFlip: { enabled: true } }), node(12, { qa: { enabled: true, mode: 'TYPE' } }), node(13, { ballShake: { enabled: true } }), node(14, { photoCheck: { enabled: true } })];
test('CHALLENGE v2 用 r5 proof 开放硬币，传感器仍要求客观题备选，站点与玩法必须匹配', () => {
  const c = configured('CHALLENGE'), row = c.gameplayRuntime.survival.zeroChallenge; row.challengeNodeId = 11; row.gameplayKey = 'coinFlip';
  assert.equal(zero.validate(c, rules, policies, nodes, matrix), '');
  assert.equal(support.challengeChoices(matrix, nodes, 2).primary.some(node => node.id === 11), true);
  assert.equal(support.challengeChoices(matrix, nodes).primary.some(node => node.id === 11), false);
  row.gameplayKey = 'ballShake'; assert.match(zero.validate(c, rules, policies, nodes, matrix), /匹配/);
  row.challengeNodeId = 13; assert.match(zero.validate(c, rules, policies, nodes, matrix), /备选/);
  row.alternativeNodeIds = [12]; assert.equal(zero.validate(c, rules, policies, nodes, matrix), '');
  row.alternativeNodeIds = [13]; assert.match(zero.validate(c, rules, policies, nodes, matrix), /重复/);
  row.alternativeNodeIds = [99]; assert.match(zero.validate(c, rules, policies, nodes, matrix), /真实/);
});

const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });
function page(config = base()) {
  const p = { data: { ...clone(zero.data), formData: { journeyRules: JSON.stringify({ schemaVersion: 1, hp: { enabled: true, initial: 10, max: 10 } }) }, supportMatrix: matrix }, config: clone(config), saves: 0,
    _runtimeConfig() { return clone(this.config); }, _zeroHpPolicyKeys() { return matrix.zeroHpPolicies.generic; }, _runtimeSavedNodes() { return nodes; }, _saveRuntime(c) { this.config = clone(c); this.saves++; }, _syncRuntimeStats() {},
    setData(patch) { for (const [path, value] of Object.entries(patch)) { const keys = path.split('.'); let at = this.data; for (const key of keys.slice(0, -1)) at = at[key]; at[keys.at(-1)] = value; } } };
  return Object.assign(p, zero.methods);
}
test('HP handler：退出不写，范围错误不写，合法 WAIT 写一次，B STEPS 可选', () => {
  const p = page(); p.openZeroHpEditor(); p.pickZeroHpPolicy(event({ key: 'WAIT' })); p.inputZeroHpField(event({ path: 'wait.minutes' }, '31')); p.closeZeroHpEditor(); assert.equal(p.saves, 0);
  p.openZeroHpEditor(); p.pickZeroHpPolicy(event({ key: 'STEPS' })); assert.equal(p.data.runtimeZeroEditor.draft.policy, 'STEPS');
  p.pickZeroHpPolicy(event({ key: 'WAIT' })); p.inputZeroHpField(event({ path: 'wait.restoreTo' }, '11')); assert.equal(p.saveZeroHpEditor(), false); assert.equal(p.saves, 0);
  p.inputZeroHpField(event({ path: 'wait.restoreTo' }, '5')); p.inputZeroHpField(event({ path: 'zeroMessage' }, '休息一下')); p.pickZeroHpThenPolicy(event({ block: 'wait' }, 1));
  assert.equal(p.saveZeroHpEditor(), true); assert.equal(p.saves, 1); assert.deepEqual(p.config.gameplayRuntime.survival.zeroWait, { version: 1, minutes: 30, restoreTo: 5, limit: 1, thenPolicy: 'BLOCK_HP_COST' }); assert.equal(p.config.gameplayRuntime.survival.zeroMessage, '休息一下');
});
test('HP handler：选节点冻结玩法 key；旧策略面板始终只读', () => {
  const p = page(); p.openZeroHpEditor(); p.pickZeroHpPolicy(event({ key: 'CHALLENGE' }));
  const index = p.data.runtimeChallengeNodes.findIndex(row => row.id === 11); p.pickZeroHpChallengeNode(event({}, index));
  assert.equal(p.data.runtimeZeroEditor.draft.challenge.gameplayKey, 'coinFlip'); assert.equal(p.saveZeroHpEditor(), true);
  const c = { gameplayRuntime: { survival: { zeroHpPolicy: 'LEGACY_END', opaque: true } } }; const old = page(c); old.openZeroHpEditor(); assert.equal(old.data.runtimeZeroEditor.readOnly, true); old.pickZeroHpPolicy(event({ key: 'WAIT' })); old.inputZeroHpField(event({ path: 'zeroMessage' }, '改')); assert.equal(old.saveZeroHpEditor(), true); assert.deepEqual(old.config, c); assert.equal(old.saves, 0);
});

test('B STEPS handler 保存 version1 步数恢复，矩阵缺能力时仍禁止选择', () => {
  const p = page(); p.openZeroHpEditor(); p.pickZeroHpPolicy(event({}, p.data.runtimeZeroHpOptions.findIndex(row => row.key === 'STEPS')));
  p.inputZeroHpField(event({ path: 'steps.steps' }, '2000')); assert.equal(p.saveZeroHpEditor(), true);
  assert.equal(p.config.gameplayRuntime.survival.zeroSteps.steps, 2000); assert.equal(p.config.gameplayRuntime.survival.zeroSteps.version, 1);
  const old = page(); old._zeroHpPolicyKeys = () => matrix.zeroHpPolicies.generic.filter(key => key !== 'STEPS'); old.openZeroHpEditor(); old.pickZeroHpPolicy(event({ key: 'STEPS' })); assert.equal(old.data.runtimeZeroEditor.draft.policy, 'BLOCK_HP_COST');
});


test('恢复候选复用后端 v2 白名单；B 完成桥之外的恢复边界逐项有禁用原因', () => {
  const java = require('node:fs').readFileSync(require('node:path').resolve(__dirname, '../../../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplayRuntimeConfig.java'), 'utf8');
  const values = java.match(/option\(challenge, "gameplayKey", ([^;]+)\);/)[1].match(/"([^"]+)"/g).map(value => JSON.parse(value));
  assert.deepEqual(support.ZERO_CHALLENGE_GAMEPLAYS, values);
  for (const row of matrix.types.filter(row => row.r5CompletionProof && !values.includes(row.section))) {
    const advanced = require('../../pages/publish/utils/publish/node-game-catalog.js').applyToConfig(require('../../pages/publish/utils/publish/advanced-game-config.js').defaultConfig(), row.key);
    const candidate = node(90, advanced), p = page(); p._runtimeSavedNodes = () => [candidate, ...nodes]; p.openZeroHpEditor();
    assert.match(support.choice(matrix, row.key, advanced, { challenge: true, challengeVersion: 2 }), /当前后端尚未接通/, row.key);
    const option = p.data.runtimeChallengeNodes[0]; assert.equal(option.id, 90); assert.equal(option.disabled, true, row.key); assert.match(option.reason, /当前后端尚未接通/, row.key);
    p.pickZeroHpPolicy(event({ key: 'CHALLENGE' })); p.pickZeroHpChallengeNode(event({}, 0)); assert.equal(p.data.runtimeZeroEditor.draft.challenge.challengeNodeId, null);
    p.pickZeroHpChallengeNode(event({}, p.data.runtimeChallengeNodes.findIndex(row => row.id === 11))); assert.equal(p.data.runtimeZeroEditor.draft.challenge.challengeNodeId, 11);
    const old = configured('CHALLENGE'); Object.assign(old.gameplayRuntime.survival.zeroChallenge, { challengeNodeId: 90, gameplayKey: row.section }); const saved = page(old); saved._runtimeSavedNodes = () => [candidate, ...nodes]; saved.openZeroHpEditor();
    assert.equal(saved.data.runtimeZeroEditor.draft.challenge.challengeNodeId, 90); assert.equal(saved.data.runtimeChallengeNodes[0].disabled, true); assert.match(saved.data.runtimeZeroEditor.error, /节点 90/); assert.equal(saved.saveZeroHpEditor(), false); assert.deepEqual(saved.config, old);
  }
});

test('HP handler：保存归零策略后同步章节结构，挑战节点移出普通任务；未保存/只读不触发', () => {
  const p = page(); let syncs = 0; p._syncStructureChapters = () => { syncs++; };
  p.openZeroHpEditor(); p.pickZeroHpPolicy(event({ key: 'CHALLENGE' }));
  p.pickZeroHpChallengeNode(event({}, p.data.runtimeChallengeNodes.findIndex(row => row.id === 11)));
  assert.equal(p.saveZeroHpEditor(), true); assert.equal(syncs, 1, '保存后调用现有章节同步');
  p.openZeroHpEditor(); p.pickZeroHpPolicy(event({ key: 'WAIT' })); p.inputZeroHpField(event({ path: 'wait.restoreTo' }, '11'));
  assert.equal(p.saveZeroHpEditor(), false); assert.equal(syncs, 1, '校验失败不同步');
  const old = page({ gameplayRuntime: { survival: { zeroHpPolicy: 'LEGACY_END' } } }); let oldSyncs = 0; old._syncStructureChapters = () => { oldSyncs++; };
  old.openZeroHpEditor(); assert.equal(old.saveZeroHpEditor(), true); assert.equal(oldSyncs, 0, '旧策略只读不改结构');
});
