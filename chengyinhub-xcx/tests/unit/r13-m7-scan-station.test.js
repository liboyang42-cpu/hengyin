// 第十三轮 m7:扫码站 = 主玩法 + 「扫码参与」。作者端与服务端同一口径:
// 扫码是到达证明(服务端只认这一站自己的码),任务由主玩法结算;参与方式必须存成扫码(validationMethod 4),
// 拒绝时说同一句人话。拍照站开了扫码参与也存 4(全游戏走查 bug 8)。
const test = require('node:test');
const { beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
const support = require('../../pages/publish/utils/publish/gameplay-support-matrix.js');
const matrix = require('./fixtures/gameplay-support-matrix.json').data;
const clone = value => JSON.parse(JSON.stringify(value));
const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });

const SCAN_WORDS = '扫码玩法的参与方式要选「扫码」';
const settled = { settled: true, chapterTasks: true, runtime: { runtimeScope: 'PLAYER' }, rules: [] };
const rules = { version: 1, pass: {}, fail: {} };
const scan = { enabled: true, kind: 'TEXT', reply: '欢迎来到旧码头' };
const qaText = { enabled: true, mode: 'TYPE', title: '门牌上的年份', answerText: '1931', maxTries: 0 };
const qaShot = { enabled: true, mode: 'SHOT', title: '拍下码头', maxTries: 0 };
const tpl = (advanced, validationMethod) => ({ validationMethod, advancedConfigJson: JSON.stringify(Object.assign({ schemaVersion: 1 }, advanced)) });

test('扫码站(文字问答 / 拍照问答 + 扫码参与)在任务主题里:选扫码通过,不选扫码说同一句人话', () => {
  for (const qa of [qaText, qaShot]) {
    const advanced = { qa, scan, outcomeRules: rules };
    assert.equal(support.mains(matrix, advanced).map(row => row.key).join(), qa.mode === 'TYPE' ? 'qaText' : 'qaShot', '扫码不是第二个玩法');
    assert.equal(support.validate(matrix, tpl(advanced, 4), settled), '', qa.mode);
    for (const method of [0, 2, 5]) assert.equal(support.validate(matrix, tpl(advanced, method), settled), SCAN_WORDS, qa.mode + ' vm' + method);
  }
});

test('作者端与服务端是同一句话,也没有引擎字段名', () => {
  const java = fs.readFileSync(path.join(__dirname, '../../../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/GameplaySupportMatrix.java'), 'utf8');
  assert.ok(java.includes('REASONS.put("扫码证明须使用 validationMethod=4", "' + SCAN_WORDS + '")'));
  assert.ok(java.includes('scanIsParticipation'));
  assert.doesNotMatch(SCAN_WORDS, /validationMethod|scan|R5|d20/);
  // 只开扫码、没有主玩法时,扫码仍然不能单独带结果规则结算(服务端 GameplayOutcomeRules 拒「扫码和分支不是主玩法」)
  assert.equal(matrix.types.find(row => row.key === 'scan').r5CompletionProof, false, '扫码没有打开能单独结算的开关');
});

let definition;
const app = { globalData: {}, tips() {}, getUserID: () => 9, getAuthorization: () => '', sendRequest() {} };
beforeEach(() => Object.assign(global.wx, require('../helpers/memory-storage.js')()));
global.getApp = () => app; global.Page = value => { definition = value; }; global.wx = { ...require('../helpers/memory-storage.js')(), getBackgroundAudioManager: () => ({ stop() {} }), showToast() {} };
function page() {
  delete require.cache[require.resolve('../../pages/publish/temp/index.js')]; require('../../pages/publish/temp/index.js');
  const p = Object.assign({}, definition, { data: clone(definition.data) });
  p.setData = function(patch, done) { for (const [key, value] of Object.entries(patch)) { const keys = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const k of keys.slice(0, -1)) at = at[k]; at[keys.at(-1)] = value; } if (done) done(); };
  p._refreshOutcomeContract = () => ({ items: [], errors: [] }); p._syncCheckDerived = () => {}; p.refreshPreviewState = () => {}; p.hasModule = () => false;
  p._supportMatrix = matrix; return p;
}

test('bug 8:拍照站开了扫码参与,旧值 2 也存成 4;改回定位到达存 5、直接参与存 2', () => {
  const p = page();
  p.fillFormWithTemplateData({ title: '报亭', validationMethod: 2, advancedConfigJson: JSON.stringify({ schemaVersion: 1, qa: qaShot, scan }) });
  assert.equal(p.data.gameKey, 'qaShot'); assert.equal(p.data.r5Editor.participation, 'scan', '编辑页显示的是扫码参与');
  assert.equal(p.prepareFormData().validationMethod, 4, '显示扫码就存扫码');
  p.pickParticipation(event({}, 1)); assert.equal(p.data.advanced.scan.enabled, false);
  assert.equal(p.prepareFormData().validationMethod, 5);
  p.pickParticipation(event({}, 0)); assert.equal(p.prepareFormData().validationMethod, 2);
  p.pickParticipation(event({}, 2)); assert.equal(p.data.advanced.scan.enabled, true);
  assert.equal(p.prepareFormData().validationMethod, 4);
});

test('文字问答 + 扫码参与:保存存 4,配置里扫码与主玩法并存,任务主题的参与方式列表里扫码可选、直接参与不可选', () => {
  const p = page();
  p.fillFormWithTemplateData({ title: '旧码头', validationMethod: 4, advancedConfigJson: JSON.stringify({ schemaVersion: 1, qa: qaText, scan, outcomeRules: rules }) });
  assert.equal(p.data.gameKey, 'qaText'); assert.equal(p.data.r5Editor.participation, 'scan');
  const saved = p.prepareFormData();
  assert.equal(saved.validationMethod, 4);
  const advanced = JSON.parse(saved.advancedConfigJson);
  assert.equal(advanced.scan.enabled, true); assert.equal(advanced.qa.mode, 'TYPE'); assert.deepEqual(advanced.outcomeRules, rules);
  assert.equal(support.validate(matrix, saved, settled), '');
  const r5 = require('../../pages/publish/temp/r5-editor.js');
  const view = r5.view ? r5.view(advanced, 4, matrix, settled) : null;
  if (view) {
    assert.equal(view.participationOptions.find(row => row.key === 'scan').reason, '');
    assert.ok(view.participationOptions.find(row => row.key === 'direct').reason);
  }
  assert.equal(cfg.parse(saved.advancedConfigJson).value.scan.enabled, true);
});
