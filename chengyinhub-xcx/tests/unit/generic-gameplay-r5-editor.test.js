const test = require('node:test');
const assert = require('node:assert/strict');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
const after = require('../../pages/publish/utils/publish/outcome-after.js');
const tools = require('../../pages/publish/utils/publish/template-tools.js');
const support = require('../../pages/publish/utils/publish/gameplay-support-matrix.js');
const r5 = require('../../pages/publish/temp/r5-editor.js');
const matrix = require('./fixtures/gameplay-support-matrix.json').data;
const clone = value => JSON.parse(JSON.stringify(value));
const event = (dataset = {}, value) => ({ currentTarget: { dataset }, detail: { value } });
const slots = [{ key: 'vital', kind: 'VALUE', label: '体温', required: true }, { key: 'item_1', kind: 'ITEM', label: '物资', required: true }];
const base = () => ({ qa: { enabled: true, mode: 'PICK', title: '题目', maxTries: 0, options: [{ id: 'a', label: 'A', correct: true }, { id: 'b', label: 'B', correct: false }] }, interactionRules: { resourceSlots: clone(slots) }, outcomeRules: { version: 1, pass: {}, fail: {} } });

for (const [label, raw] of Object.entries({
  unknown: { schemaVersion: 1, future: { list: [{ id: 'z', opaque: [1, { key: true }] }] }, qa: { enabled: true, mode: 'PICK', title: 'q', options: [{ id: 'a', label: 'A', correct: true, unknown: [3] }, { id: 'b', label: 'B', correct: false }] } },
  timerVariant: { schemaVersion: 1, check: { enabled: true, skill: '观察', checkId: 'c', successText: 'ok', failText: 'no' }, timer: { enabled: true, durationSeconds: 31, timeoutResult: 'FAILED', unknown: 8 }, variants: [{ when: { op: 'GTE', var: 'sys.hp', value: 3 }, relax: { 'check.dc': -2 }, unknown: [1] }] },
  scan: { schemaVersion: 1, scan: { enabled: true, kind: 'OVERLAY', reply: 'hi', overlayUrl: 'https://example.com/scan.png', arMode: 'NONE', unknown: [9] } },
  cost: { schemaVersion: 1, interactionRules: { hpCost: 3, costOptions: [{ hpCost: 2, opaque: [4] }], resourceCosts: [{ itemId: 'old', quantity: 3 }], future: true }, interactionTools: { allowTrade: true }, slowTask: { enabled: true, title: '旧任务', days: 3 } },
  profile: { schemaVersion: 1, profile: { enabled: true, title: '角色', avatar: { enabled: true }, questions: [{ key: 'role', label: '角色', kind: 'pick', required: false, unknown: 'keep', options: [{ key: 'a', label: 'A', unknown: [3] }], override: { requires: [{ op: 'GTE', var: 'counter.a', value: 2 }], option: 'a', unknown: 'preserve' } }] } },
  dice: { schemaVersion: 1, diceRoll: { enabled: true, mode: 'd6', diceCount: 2, faces: ['A', 'B', 'C', 'D', 'E', 'F'], legacy: 42 } },
  removed: { schemaVersion: 1, silentOrder: { enabled: true, secret: 'old' }, diyName: { enabled: true, extra: [1] }, musicCorner: { enabled: true, audioUrl: 'https://example.com/a.mp3' }, multiplayer: { enabled: true, minPlayers: 2, extra: [1] } },
})) {
  test('旧稿 parse→serializeChanges 完整深相等：' + label, () => {
    const json = JSON.stringify(raw), parsed = cfg.parse(json).value;
    assert.deepEqual(JSON.parse(cfg.serializeChanges(parsed, json)), raw);
    assert.equal(catalog.dropStaleTimer(parsed), parsed);
    assert.deepEqual(JSON.parse(cfg.serializeChanges(parsed, json)), raw);
  });
}

test('编辑一个字段仍保留 timer/variants 和未知选项键；新增列表行也不丢字段', () => {
  const raw = base(); delete raw.outcomeRules; raw.schemaVersion = 1;
  raw.qa.options[0].opaque = [1, { data: 2 }]; raw.timer = { enabled: true, durationSeconds: 31, timeoutResult: 'FAILED', unknown: 8 }; raw.variants = [{ when: { op: 'GTE', var: 'sys.hp', value: 3 }, relax: { 'timer.durationSeconds': '+30' }, opaque: true }];
  const model = cfg.parse(raw).value; model.qa.title = '改题目';
  let result = JSON.parse(cfg.serializeChanges(model, JSON.stringify(raw)));
  assert.deepEqual(result.qa.options, raw.qa.options); assert.deepEqual(result.timer, raw.timer); assert.deepEqual(result.variants, raw.variants);
  model.qa.options.push({ id: 'c', label: 'C', correct: false, opaque: [5] });
  result = JSON.parse(cfg.serializeChanges(model, JSON.stringify(raw)));
  assert.deepEqual(result.qa.options[0].opaque, raw.qa.options[0].opaque); assert.deepEqual(result.qa.options[2].opaque, [5]);
  assert.equal(result.outcomeRules, undefined, '未用 after 不自动升级');
});

test('目录仅基础项、形式全部映射到现有存储段，扫码/移除规则不进入 picker', () => {
  assert.equal(catalog.BASE_GAMES.length, 23);
  const expected = { choice: ['qaPick', 'pricePair', 'blindTaste', 'predict'], photo: ['qaShot', 'photoCheck', 'objectCard'], random: ['coin', 'dice', 'random'], check: ['check', 'd20'], sign: ['note', 'dailySign'] };
  for (const [key, forms] of Object.entries(expected)) {
    assert.deepEqual(catalog.BASE_GAMES.find(row => row.key === key).forms.map(row => row.gameKey), forms);
    for (const form of forms) {
      const selected = catalog.applyToConfig(cfg.defaultConfig(), form);
      assert.equal(catalog.detectGame(selected), form);
      assert.equal(catalog.selection(form).baseKey, key);
    }
  }
  assert.equal(catalog.detectGame({ scan: { enabled: true } }), '');
  assert.equal(catalog.GAME_SECTIONS.includes('scan'), false);
  assert.equal(catalog.BASE_GAMES.some(row => ['scan', 'branch', 'pricePair', 'blindTaste'].includes(row.key)), false);
  // 2026-10-11：费用（含物资）回到工具目录，新增奖励与线索（写既有 interactionRules 字段）
  assert.deepEqual(tools.TOOLS.map(row => row.key), ['cost', 'reward', 'clue', 'uses', 'vote']);
  assert.deepEqual(catalog.MODIFIERS.map(row => row.key), ['timer', 'timeWindow', 'leaderboard', 'multiplayer']);
  assert.equal(catalog.MODIFIERS.find(row => row.key === 'multiplayer').reason, '规则待定义');
  assert.equal(catalog.BASE_GAMES.find(row => row.key === 'bingo').reason, '在主题里配置');
});

for (const [label, change, pattern] of [
  ['cost 0', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 0 }]; }, /1 至 999/],
  ['cost 1000', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 1000 }]; }, /1 至 999/],
  ['cost decimal', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 1.5 }]; }, /1 至 999/],
  ['cost string', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: '2' }]; }, /1 至 999/],
  ['cost boolean', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: true }]; }, /1 至 999/],
  ['unknown VALUE', c => { c.outcomeRules.pass.costs = [{ statKey: 'sys.hp', amount: 1 }]; }, /VALUE/],
  ['ITEM forbidden', c => { c.outcomeRules.pass.costs = [{ statKey: 'item_1', amount: 1 }]; }, /VALUE/],
  ['duplicate cost', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 1 }, { statKey: 'vital', amount: 2 }]; }, /重复/],
  ['effect 0', c => { c.outcomeRules.pass.effects = [{ var: 'vital', op: 'INC', value: 0 }]; }, /1 至 999/],
  ['effect wrong op', c => { c.outcomeRules.pass.effects = [{ var: 'vital', op: 'SET', value: 1 }]; }, /只能/],
  ['effect ITEM', c => { c.outcomeRules.pass.effects = [{ var: 'item_1', op: 'FLOOR', value: 1 }]; }, /VALUE/],
  ['reply too long', c => { c.outcomeRules.pass.reply = '字'.repeat(2001); }, /2000/],
  ['unknown next', c => { c.outcomeRules.pass.nextStepId = 'start'; }, /本节点/],
  ['missing fail', c => { delete c.outcomeRules.fail; }, /通过和没通过/],
  ['participants fail', c => { c.interactionRules.targetScope = 'PARTICIPANTS'; }, /已参与的队伍/],
  ['multi local', c => { c.qa.multi = true; c.qa.options[0].after = {}; }, /多选/],
  ['draw multiple local', c => { delete c.qa; c.random = { enabled: true, drawCount: 2, items: [{ id: 'a', after: {} }, { id: 'b' }] }; }, /多张/],
  ['vote incompatible', c => { c.interactionTools = { plans: [{ title: '继续' }] }; }, /投票/],
  ['legacy paid conflict', c => { c.interactionRules.hpCost = 1; c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 1 }]; }, /旧费用/],
  ['multiple mains', c => { c.coinFlip = { enabled: true }; }, /一个主玩法/],
  ['too many rows', c => { c.outcomeRules.pass.costs = Array.from({ length: 21 }, () => ({ statKey: 'vital', amount: 1 })); }, /20/],
  ['unknown outcome field', c => { c.outcomeRules.future = []; }, /字段/],
  ['unknown cost field', c => { c.outcomeRules.pass.costs = [{ statKey: 'vital', amount: 1, bypass: true }]; }, /字段/],
]) test('之后校验：' + label, () => { const c = base(); change(c); assert.match(after.validate(c), pattern); });

for (const key of ['profile', 'album', 'note', 'dailySign']) test('一次完成只允许 pass：' + key, () => {
  const c = { [key]: { enabled: true }, outcomeRules: { version: 1, pass: {} } };
  assert.equal(after.validate(c), ''); c.outcomeRules.fail = {}; assert.match(after.validate(c), /完成型/);
});

test('作者编辑顶层与局部 after opt-in，旧 fb/effects 保留，局部稳定 ID 不变', () => {
  const c = base(); delete c.outcomeRules; c.qa.options[0].fb = '旧回复'; c.qa.options[0].effects = [{ op: 'INC', var: 'counter.old', value: 1 }];
  const result = after.apply(c, 'qa.options.0.after', { reply: '选后', costs: [{ statKey: 'vital', amount: 1 }], effects: [{ var: 'vital', op: 'FLOOR', value: 3 }], next: '' });
  assert.equal(result.error, undefined); assert.equal(result.value.outcomeRules.version, 1);
  const persisted = JSON.parse(cfg.serialize(cfg.parse(result.value).value));
  assert.deepEqual(persisted.qa.options[0].after, { reply: '选后', costs: [{ statKey: 'vital', amount: 1 }], effects: [{ var: 'vital', op: 'FLOOR', value: 3 }] });
  assert.equal(persisted.qa.options[0].id, 'a'); assert.equal(persisted.qa.options[0].fb, '旧回复'); assert.deepEqual(persisted.qa.options[0].effects, c.qa.options[0].effects);
  assert.equal(tools.reservedRefs(result.value).has('vital'), true);
  assert.match(after.apply(c, 'outcomeRules.pass', { ...after.empty(), next: 'start' }).error, /本节点/);
});

test('骰子字符串面仅作者编辑时转 action/after；新结果可序列化而旧两骰形式只读', () => {
  const c = { diceRoll: { enabled: true, mode: 'd6', diceCount: 1, faces: ['A', 'B', 'C', 'D', 'E', 'F'] }, interactionRules: { resourceSlots: clone(slots) } };
  const result = after.apply(c, 'diceRoll.faces.0.after', { ...after.empty(), reply: '正好' });
  assert.equal(result.error, undefined); assert.deepEqual(result.value.diceRoll.faces[0], { action: 'A', after: { reply: '正好', costs: [], effects: [] } });
  const saved = JSON.parse(cfg.serialize(cfg.parse(result.value).value)); assert.deepEqual(saved.diceRoll.faces, result.value.diceRoll.faces);
  c.diceRoll.diceCount = 2; assert.match(after.apply(c, 'diceRoll.faces.0.after', after.empty()).error, /此形式/);
});

test('timer 的超时完成只在 outcomeRules opt-in 后允许', () => {
  const c = cfg.parse(base()).value; c.timer = { enabled: true, durationSeconds: 30, timeoutResult: 'COMPLETED' };
  assert.equal(cfg.validate(c), ''); delete c.outcomeRules; assert.match(cfg.validate(c), /须先配置/);
});

test('支持矩阵用真实 r5CompletionProof，不借旧 proof；扫码不抢主玩法', () => {
  for (const row of matrix.types.filter(row => row.r5CompletionProof)) {
    const game = catalog.findGame(row.key); const c = catalog.applyToConfig(cfg.defaultConfig(), game.key); c.outcomeRules = { version: 1, pass: {}, fail: {} };
    assert.equal(support.choice(matrix, row.key, c), '', row.key);
  }
  const c = base(); c.scan = { enabled: true, kind: 'TEXT', reply: '欢迎' };
  assert.equal(support.mains(matrix, c)[0].key, 'qaPick'); assert.equal(support.validate(matrix, { validationMethod: 4, advancedConfigJson: c }), '');
  assert.match(support.toolPolicy(matrix, 'qaPick', 'vote', c, {}), /结果规则/);
  assert.match(support.toolPolicy(matrix, 'qaPick', 'vote', c, { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } }), /结果规则/);
});

let definition;
const app = { globalData: {}, tips() {}, getAuthorization: () => '', sendRequest() {} };
global.getApp = () => app; global.Page = value => { definition = value; }; global.wx = { getStorageSync() {}, setStorageSync() {}, getBackgroundAudioManager: () => ({ stop() {} }), showToast() {} };
function page() {
  delete require.cache[require.resolve('../../pages/publish/temp/index.js')]; require('../../pages/publish/temp/index.js');
  const p = Object.assign({}, definition, { data: clone(definition.data) });
  p.setData = function(patch, done) { for (const [path, value] of Object.entries(patch)) { const keys = path.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data; for (const key of keys.slice(0, -1)) at = at[key]; at[keys.at(-1)] = value; } if (done) done(); };
  p._refreshOutcomeContract = () => ({ items: [], errors: [] }); p._syncCheckDerived = () => {}; p.refreshPreviewState = () => {}; p.hasModule = () => false;
  p._supportMatrix = matrix; return p;
}

test('真实页面：参与方式独立，形式切换不覆盖扫码/GPS，open→save 不迁移旧扫码', () => {
  const p = page(); p.fillFormWithTemplateData({ title: '旧扫码', validationMethod: 4, advancedConfigJson: JSON.stringify({ schemaVersion: 1, scan: { enabled: true, kind: 'TEXT', reply: '欢迎', opaque: [3] } }) });
  assert.equal(p.data.r5Editor.participation, 'scan'); assert.equal(p.data.gameKey, '');
  assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), { schemaVersion: 1, scan: { enabled: true, kind: 'TEXT', reply: '欢迎', opaque: [3] } });
  p.pickR5Game(event({}, 0)); assert.equal(p.data.gameKey, 'qaPick'); assert.equal(p.data.formData.validationMethod, 4); assert.equal(p.data.advanced.scan.enabled, true);
  p.pickR5Form(event({}, 1)); assert.equal(p.data.gameKey, 'pricePair'); assert.equal(p.data.formData.validationMethod, 4);
  p.pickParticipation(event({}, 1)); assert.equal(p.data.formData.validationMethod, 5); assert.equal(p.data.advanced.scan.enabled, false);
  p.pickR5Form(event({}, 0)); assert.equal(p.data.formData.validationMethod, 5);
});

test('真实页面：玩法/扫码/工具面板退出恢复快照，未用 after 不新增结果块', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value; delete p.data.advanced.outcomeRules; p.data.gameKey = 'qaPick'; p.data.gameSection = 'qa'; p._refreshR5Editor();
  const before = clone(p.data.advanced); p.openGameConfig(); p._setFormState({ 'advanced.qa.title': '未保存' }); p.closeGameConfig(); assert.deepEqual(p.data.advanced, before); assert.equal(p.data.gameConfigOpen, false);
  p.openScanSheet(); p._setFormState({ 'advanced.scan.reply': '未保存' }); p.closeScanSheet(); assert.deepEqual(p.data.advanced, before);
  p.openR5ToolSheet(event({ key: 'timer' })); p._setFormState({ 'advanced.timer.durationSeconds': 70 }); p.closeR5ToolSheet(); assert.deepEqual(p.data.advanced, before);
  assert.equal(p.data.advanced.outcomeRules, undefined);
});

test('类别接受下标；取消玩法恢复呈现与计时视图，取消 after 保留原先未引用的资源', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value; delete p.data.advanced.outcomeRules;
  p.setData(p._gameSlotPatch(p.data.advanced)); p._refreshR5Editor();
  p.pickR5Category(event({}, 4)); assert.equal(p.data.r5Editor.category, '挑战');
  p.pickR5Category(event({ category: '全部' })); assert.equal(p.data.r5Editor.category, '全部');
  const view = { timerAvailable: p.data.timerAvailable, presentChoice: p.data.presentChoice, presentInlineLocked: p.data.presentInlineLocked };
  p.openGameConfig(); p.pickR5Game(event({ key: 'album' })); p.closeGameConfig();
  for (const key of Object.keys(view)) assert.equal(p.data[key], view[key], key);
  const before = clone(p.data.advanced);
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.addAfterRow(event({ kind: 'costs' }));
  p.pickAfterRowStat(event({ kind: 'costs', index: 0 }, 0)); p.inputAfterRow(event({ kind: 'costs', index: 0, field: 'amount' }, '1'));
  // 内层提交后仍可取消外层玩法面板，原先的未引用槽不能被清理。
  p.closeAfterSheet(); p.openGameConfig(); p.openAfterSheet(event({ path: 'outcomeRules.pass' }));
  p.addAfterRow(event({ kind: 'costs' })); p.pickAfterRowStat(event({ kind: 'costs', index: 0 }, 0));
  p.inputAfterRow(event({ kind: 'costs', index: 0, field: 'amount' }, '1')); assert.equal(p.saveAfterSheet(), true);
  p.closeGameConfig(); assert.deepEqual(p.data.advanced, before);
});

test('真实页面：资源下标写稳定 key，失败不提交草稿，完成只提交合法 after', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value; delete p.data.advanced.outcomeRules; p.data.gameKey = 'qaPick'; p._refreshR5Editor();
  p.openAfterSheet(event({ path: 'outcomeRules.fail' })); p.addAfterRow(event({ kind: 'costs' }));
  assert.equal(p.saveAfterSheet(), false); assert.equal(p.data.advanced.outcomeRules, undefined); assert.equal(p.data.afterEditor.show, true);
  p.pickAfterRowStat(event({ kind: 'costs', index: 0 }, 0)); p.inputAfterRow(event({ kind: 'costs', index: 0, field: 'amount' }, '2'));
  p.addAfterRow(event({ kind: 'effects' })); p.pickAfterRowStat(event({ kind: 'effects', index: 0 }, 0)); p.pickAfterRowOp(event({ index: 0 }, 1)); p.inputAfterRow(event({ kind: 'effects', index: 0, field: 'value' }, '3'));
  assert.equal(p.saveAfterSheet(), true); assert.deepEqual(p.data.advanced.outcomeRules.fail.costs, [{ statKey: 'vital', amount: 2 }]); assert.deepEqual(p.data.advanced.outcomeRules.fail.effects, [{ var: 'vital', op: 'FLOOR', value: 3 }]);
  assert.equal(p.data.afterEditor.show, false); assert.equal(p.data.r5Editor.gameSummary.includes('选项问答'), true);
});

test('稳定身份重排和加行保留 profile.required 与复杂 override；未知数据不串行', () => {
  const raw = { schemaVersion: 1, profile: { enabled: true, title: '角色', questions: [
    { key: 'a', kind: 'pick', label: 'A', required: false, opaque: ['A'], options: [{ key: 'x', label: 'X', custom: ['X'] }, { key: 'y', label: 'Y' }], override: { requires: [{ var: 'counter.a', op: 'GTE', value: 2 }, { var: 'counter.b', op: 'LTE', value: 5 }], option: 'x', opaque: 'overrideA' } },
    { key: 'b', kind: 'text', label: 'B', required: true, maxLength: 20, opaque: ['B'] },
  ] } };
  const model = cfg.parse(raw).value; model.profile.questions.reverse();
  let saved = JSON.parse(cfg.serializeChanges(model, JSON.stringify(raw)));
  assert.deepEqual(saved.profile.questions, raw.profile.questions.toReversed());
  model.profile.questions.push({ key: 'c', kind: 'text', label: 'C', maxLength: 20, required: true, opaque: ['C'] });
  saved = JSON.parse(cfg.serializeChanges(model, JSON.stringify(raw)));
  assert.deepEqual(saved.profile.questions.slice(0, 2), raw.profile.questions.toReversed()); assert.deepEqual(saved.profile.questions[2].opaque, ['C']);
});

test('编辑骰面标题保留 after；取消 after 新建的资源也还原', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value; p.data.gameKey = 'qaPick'; p._refreshR5Editor();
  p.data.advanced.diceRoll.faces[0] = { action: '旧', after: { reply: '旧after' }, opaque: [3] };
  p.onDiceFace(event({ index: 0 }, '新')); assert.deepEqual(p.data.advanced.diceRoll.faces[0], { action: '新', after: { reply: '旧after' }, opaque: [3] });
  const slotsBefore = clone(p.data.advanced.interactionRules.resourceSlots);
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.declareAfterValueSlot(event({}, '新增数值')); assert.equal(p.data.advanced.interactionRules.resourceSlots.length, slotsBefore.length + 1);
  p.closeAfterSheet(); assert.deepEqual(p.data.advanced.interactionRules.resourceSlots, slotsBefore);
});


test('输入挑战已接通统一 timer，旧 seconds 只读回显保存', () => {
  const p = page(); p._refreshR5Editor();
  const item = p.data.r5Editor.gameItems.find(row => row.key === 'typeIn');
  assert.equal(item.disabled, false); assert.equal(item.reason, '');
  p.pickGame(event({ key: 'typeIn' })); assert.equal(p.data.gameKey, 'typeIn');
  const raw = { schemaVersion: 1, typeIn: { enabled: true, title: '输入', target: '目标', seconds: 19, unknown: [2] } };
  p.fillFormWithTemplateData({ title: '旧输入', validationMethod: 0, advancedConfigJson: JSON.stringify(raw) });
  assert.equal(p.data.gameKey, 'typeIn'); assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), raw);
  assert.equal(p.data.r5Editor.legacyRows.find(row => row.path === 'typeIn.seconds').displayValue, '19');
});

// 2026-10-30 所有玩法都内嵌:扫码、反应等原「只能全屏」段配 inline 不再报错,两种呈现都合法
test('扫码与原只能全屏段配 inline / fullscreen 都合法', () => {
  const c = cfg.parse({ schemaVersion: 1, present: 'inline', scan: { enabled: true, kind: 'TEXT', reply: '欢迎' }, qa: { enabled: true, mode: 'TYPE', title: '问题', answerText: '答案' } }).value;
  assert.equal(cfg.presentError(c), '');
  c.qa.enabled = false; c.reaction = { enabled: true }; assert.equal(cfg.presentError(c), '');
  c.present = 'fullscreen'; assert.equal(cfg.presentError(c), '');
  assert.equal(cfg.presentError({ present: 'inline', scan: { enabled: true } }), '');
});


test('旧直接/GPS模板没有主玩法仍保留验证方式和完成字段', () => {
  for (const method of [0, 5, 6, 7]) {
    const p = page(), raw = { title: '旧方式', validationMethod: method };
    p.fillFormWithTemplateData(raw); const saved = p.prepareFormData();
    for (const key of Object.keys(raw)) assert.equal(saved[key], raw[key], key);
  }
});

test('已接通 R5 结果的玩法允许成功次数，旧无桥形式保持禁用', () => {
  const c = { coinFlip: { enabled: true }, outcomeRules: { version: 1, pass: {}, fail: {} } };
  assert.equal(support.toolPolicy(matrix, 'coin', 'uses', c), '');
  delete c.outcomeRules; assert.match(support.toolPolicy(matrix, 'coin', 'uses', c), /确认/);
});


test('B 反馈下一段：矩阵声明后按过滤下标引用本节点步骤，旧矩阵拒绝且不丢原文', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value;
  p.data.advanced.branch.steps = [{ id: 'feedback_a', title: '第一段', body: '第一段反馈', options: [] }, { id: 'feedback_b', title: '第二段', body: '第二段反馈', options: [] }];
  p._refreshR5Editor(); assert.equal(p.data.r5Editor.nextDisabledReason, '');
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 2));
  assert.equal(p.data.afterEditor.draft.next, 'feedback_b'); assert.equal(p.saveAfterSheet(), true);
  assert.equal(p.data.advanced.outcomeRules.pass.nextStepId, 'feedback_b');
  p._refreshR5Editor(); p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 99));
  assert.equal(p.data.afterEditor.draft.next, 'feedback_b'); p.pickAfterNextStep(event({}, 0)); assert.equal(p.saveAfterSheet(), true);
  assert.equal(p.data.advanced.outcomeRules.pass.nextStepId, undefined);
  p.data.advanced.outcomeRules.pass.nextStepId = 'feedback_b'; const before = clone(p.data.advanced);
  const old = clone(matrix); delete old.outcomeRules; p._supportMatrix = old; p._refreshR5Editor();
  assert.equal(p.data.r5Editor.afterRows.find(row => row.path === 'outcomeRules.pass').disabled, true);
  assert.match(support.validate(old, { advancedConfigJson: before }), /暂未开放/); assert.deepEqual(p.data.advanced, before);
  const malformed = base(); malformed.branch = { steps: [{ id: 'bad space' }] }; malformed.outcomeRules.pass.nextStepId = 'bad space'; assert.match(after.validate(malformed), /有效步骤/);
});

test('物理返回先取消内层 after/工具/扫码，再取消玩法面板并恢复快照', () => {
  const p = page(); p.data.advanced = cfg.parse(base()).value; p._refreshR5Editor(); const before = clone(p.data.advanced);
  p.openGameConfig(); p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.declareAfterValueSlot(event({}, '新资源')); p.onNativeBackGuard();
  assert.equal(p.data.afterEditor.show, false); assert.equal(p.data.gameConfigOpen, true); assert.deepEqual(p.data.advanced, before);
  p.openR5ToolSheet(event({ key: 'timer' })); p._setFormState({ 'advanced.timer.durationSeconds': 71 }); p.onNativeBackGuard(); assert.deepEqual(p.data.advanced, before);
  p.openScanSheet(); p._setFormState({ 'advanced.scan.reply': '取消' }); p.onNativeBackGuard(); assert.deepEqual(p.data.advanced, before);
  p._setFormState({ 'advanced.qa.title': '取消外层' }); p.onNativeBackGuard(); assert.equal(p.data.gameConfigOpen, false); assert.deepEqual(p.data.advanced, before);
});


for (const timed of [false, true]) test('新选输入挑战不打开 after：创建保存重开只由统一 timer 限时 ' + timed, () => {
  const p = page(); p._refreshR5Editor(); p.pickR5Game(event({ key: 'typeIn' }));
  assert.deepEqual(p.data.advanced.outcomeRules, { version: 1, pass: {}, fail: {} });
  p.openGameConfig(); p.onAdvancedField(event({ section: 'typeIn', field: 'target' }, '请输入这句话'));
  if (timed) { p.openR5ToolSheet(event({ key: 'timer' })); p.onAdvancedField(event({ section: 'timer', field: 'durationSeconds', numeric: '1' }, '37')); assert.equal(p.saveR5ToolSheet(), true); }
  assert.equal(p.data.afterEditor.show, false); assert.equal(p.saveGameConfig(), true);
  const saved = p.prepareFormData(), persisted = JSON.parse(saved.advancedConfigJson);
  assert.deepEqual(persisted.outcomeRules, { version: 1, pass: {}, fail: {} }); assert.equal(persisted.timer.enabled, timed);
  if (timed) assert.equal(persisted.timer.durationSeconds, 37);
  const reopened = page(); reopened.fillFormWithTemplateData(saved); reopened.openGameConfig(); assert.equal(reopened.saveGameConfig(), true);
  assert.deepEqual(JSON.parse(reopened.prepareFormData().advancedConfigJson), persisted);
});
for (const timed of [false, true]) test('未新选的旧输入挑战打开保存重开仍保留 seconds 与原版本 ' + timed, () => {
  const raw = { schemaVersion: 1, typeIn: { enabled: true, title: '旧输入', target: '旧目标', seconds: 19, unknown: [4] } };
  if (timed) raw.timer = { enabled: true, durationSeconds: 31, timeoutResult: 'FAILED' };
  const p = page(); p.fillFormWithTemplateData({ title: '旧稿', validationMethod: 0, advancedConfigJson: JSON.stringify(raw) });
  p.openGameConfig(); assert.equal(p.saveGameConfig(), true); const saved = p.prepareFormData(); assert.deepEqual(JSON.parse(saved.advancedConfigJson), raw);
  const reopened = page(); reopened.fillFormWithTemplateData(saved); reopened.openGameConfig(); assert.equal(reopened.saveGameConfig(), true); assert.deepEqual(JSON.parse(reopened.prepareFormData().advancedConfigJson), raw);
});

test('真实 Page 新建两段本站反馈，after 选第二段保存/重开；选项引用稳定段 ID', () => {
  const p = page(); const raw = { schemaVersion: 1, qa: base().qa };
  p.fillFormWithTemplateData({ title: '反馈节点', validationMethod: 0, advancedConfigJson: JSON.stringify(raw) }); p.openGameConfig();
  while (p.data.advanced.branch.steps.length) p.removeBranchStep(event({ index: 0 }));
  p.addBranchStep(); p.addBranchStep(); const ids = p.data.advanced.branch.steps.map(row => row.id); assert.equal(ids.length, 2); assert.notEqual(ids[0], ids[1]);
  for (let i = 0; i < 2; i++) { p.updateBranchStep(event({ index: i, field: 'title' }, '反馈第' + (i + 1) + '段')); p.updateBranchStep(event({ index: i, field: 'body' }, '本站结果说明' + i)); }
  assert.equal(p.data.advanced.branch.enabled, true); assert.equal(p.data.advanced.branch.startStepId, ids[0]);
  p.addBranchOption(event({ index: 0 })); p.updateBranchOption(event({ stepindex: 0, optionindex: 0, field: 'label' }, '继续看反馈')); p.pickBranchNextStep(event({ stepindex: 0, optionindex: 0 }, 1));
  assert.equal(p.data.advanced.branch.steps[0].options[0].nextStepId, ids[1]);
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 2)); assert.equal(p.saveAfterSheet(), true); assert.equal(p.saveGameConfig(), true);
  const saved = p.prepareFormData(), persisted = JSON.parse(saved.advancedConfigJson); assert.equal(persisted.outcomeRules.pass.nextStepId, ids[1]); assert.equal(persisted.branch.enabled, true);
  const reopened = page(); reopened.fillFormWithTemplateData(saved); reopened.openGameConfig(); reopened.openAfterSheet(event({ path: 'outcomeRules.pass' })); assert.equal(reopened.data.afterEditor.draft.next, ids[1]);
  assert.equal(reopened.data.r5Editor.nextStepOptions[2].label, '反馈第2段'); reopened.closeAfterSheet(); assert.equal(reopened.saveGameConfig(), true); assert.deepEqual(JSON.parse(reopened.prepareFormData().advancedConfigJson), persisted);
  if (process.env.R5_FE_FIX_FIXTURE_PATH) require('node:fs').writeFileSync(process.env.R5_FE_FIX_FIXTURE_PATH, saved.advancedConfigJson);
});

test('反馈配置取消不 opt-in；删除被引用分段必须修正 after，完成型反馈只创建 pass', () => {
  const p = page(); const raw = base(); delete raw.outcomeRules; p.fillFormWithTemplateData({ title: '旧稿', advancedConfigJson: JSON.stringify(raw) }); const before = clone(p.data.advanced);
  p.openGameConfig(); p.addBranchStep(); p.closeGameConfig(); assert.deepEqual(p.data.advanced, before);
  p.openGameConfig(); p.addBranchStep(); p.updateBranchStep(event({ index: 0, field: 'body' }, '有效反馈')); p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 1)); assert.equal(p.saveAfterSheet(), true);
  p.removeBranchStep(event({ index: 0 })); assert.equal(p.saveGameConfig(), false); assert.match(p.data.r5Editor.error, /本节点/);
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 0)); assert.equal(p.saveAfterSheet(), true); assert.equal(p.saveGameConfig(), true);
  const once = page(); once.fillFormWithTemplateData({ title: '笔记', advancedConfigJson: JSON.stringify({ schemaVersion: 1, note: { enabled: true, title: '笔记', prompt: '记录' } }) }); once.openGameConfig(); once.addBranchStep(); assert.deepEqual(once.data.advanced.outcomeRules, { version: 1, pass: {} }); assert.equal(once.saveGameConfig(), true);
});

test('旧独立分支打开保存不迁移；编辑正文保留稳定结果与未知字段', () => {
  const raw = { schemaVersion: 1, branch: { enabled: true, startStepId: 'old', steps: [{ id: 'old', title: '旧段', body: '旧正文', terminal: true, outcomeCode: 'FOUND_CLOCK', outcomeLabel: '找到钟楼', options: [], opaque: [7] }], opaque: [9] } };
  const p = page(); p.fillFormWithTemplateData({ title: '旧分支', advancedConfigJson: JSON.stringify(raw) }); assert.equal(p.data.gameSection, 'branch');
  p.openGameConfig(); assert.equal(p.saveGameConfig(), true); assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), raw);
  p.openGameConfig(); p.updateBranchStep(event({ index: 0, field: 'body' }, '新正文')); assert.equal(p.saveGameConfig(), true);
  const expected = clone(raw); expected.branch.steps[0].body = '新正文'; assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), expected); assert.equal(p.data.advanced.outcomeRules, undefined);
});

for (const [key, baseKey] of [['typeIn', 'typeIn'], ['note', 'sign'], ['coin', 'random']]) test('反馈分段重开不抢占主玩法 ' + key, () => {
  const p = page(); p.openGameConfig(); p.pickR5Game(event({ key: baseKey }));
  if (key === 'typeIn') p.onAdvancedField(event({ section: 'typeIn', field: 'target' }, '目标'));
  if (key === 'note') for (const field of ['title', 'prompt']) p.onAdvancedField(event({ section: 'note', field }, '留言'));
  if (key === 'coin') for (const side of ['heads', 'tails']) p.onAdvancedField(event({ section: 'coinFlip.' + side, field: 'action' }, side));
  p.addBranchStep(); assert.equal(p.saveGameConfig(), true, p.data.r5Editor.error);
  assert.equal(catalog.detectGame(p.data.advanced), key);
  const reopened = page(); reopened.fillFormWithTemplateData(p.prepareFormData()); reopened.openGameConfig(); assert.equal(reopened.data.gameKey, key); assert.equal(reopened.data.r5Editor.baseKey, baseKey); assert.equal(reopened.saveGameConfig(), true);
});

test('R5 换形式保留已选本站反馈，旧独立分支换玩法仍退出分支', () => {
  const p = page(); p.fillFormWithTemplateData({ title: '反馈', advancedConfigJson: JSON.stringify({ qa: base().qa }) }); p.openGameConfig(); p.addBranchStep();
  p.updateBranchStep(event({ index: 1, field: 'body' }, '有效反馈'));
  p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 2)); assert.equal(p.saveAfterSheet(), true);
  const before = clone(p.data.advanced.branch), next = p.data.advanced.outcomeRules.pass.nextStepId;
  p.pickR5Form(event({}, 1)); assert.equal(p.data.gameKey, 'pricePair'); assert.deepEqual(p.data.advanced.branch, before); assert.equal(p.data.advanced.outcomeRules.pass.nextStepId, next);
  p.pickR5Form(event({}, 0)); assert.equal(p.saveGameConfig(), true);
  const old = cfg.defaultConfig(); old.branch.enabled = true; assert.equal(catalog.applyToConfig(old, 'qaPick').branch.enabled, false);
});

test('旧主玩法与分支共存时，编辑分支不隐式改成 R5 反馈', () => {
  const raw = { qa: base().qa, branch: { enabled: true, startStepId: 'old', steps: [{ id: 'old', terminal: true, outcomeCode: 'OLD_END', outcomeLabel: '旧结局', body: '旧', options: [], opaque: [8] }] } };
  const p = page(); p.fillFormWithTemplateData({ title: '旧混合', advancedConfigJson: JSON.stringify(raw) }); assert.equal(p.data.gameSection, 'qa');
  p.openGameConfig(); p.updateBranchStep(event({ index: 0, field: 'body' }, '新正文')); assert.equal(p.saveGameConfig(), true);
  const expected = clone(raw); expected.branch.steps[0].body = '新正文'; assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), expected); assert.equal(p.data.advanced.outcomeRules, undefined);
});

test('after 默认占位与空白反馈禁用，有内容才可选；选后内容清空不能保存', () => {
  const p = page(); p.fillFormWithTemplateData({ title: '反馈', advancedConfigJson: JSON.stringify({ qa: base().qa }) }); p.openGameConfig(); p.openAfterSheet(event({ path: 'outcomeRules.pass' }));
  assert.equal(p.data.r5Editor.nextStepOptions[1].reason, '先在反馈分段里配置内容'); p.pickAfterNextStep(event({}, 1)); assert.equal(p.data.afterEditor.draft.next, ''); assert.equal(p._r5AfterNextPicked, false);
  p.updateBranchStep(event({ index: 0, field: 'body' }, ' \n ')); assert.equal(p.data.r5Editor.nextStepOptions[1].reason, '先在反馈分段里配置内容');
  p.updateBranchStep(event({ index: 0, field: 'body' }, '有反馈内容')); p.pickAfterNextStep(event({}, 1)); assert.equal(p.data.afterEditor.draft.next, 'start');
  p.updateBranchStep(event({ index: 0, field: 'body' }, '')); assert.equal(p.saveAfterSheet(), false); assert.equal(p.data.afterEditor.error, '先在反馈分段里配置内容'); p.closeAfterSheet();
});

test('显式选择已有内容段并保存 after 才启用 branch，补合法起点并回显第二段', () => {
  for (const startStepId of ['', 'removed']) {
    const raw = { schemaVersion: 1, qa: base().qa, branch: { enabled: false, startStepId, steps: [{ id: 'feedback_a', title: '第一段', body: '第一段内容', terminal: true, outcomeCode: 'A', options: [] }, { id: 'feedback_b', title: '第二段', body: '第二段内容', terminal: true, outcomeCode: 'B', options: [] }] } };
    const p = page(); p.fillFormWithTemplateData({ title: '待启用反馈', advancedConfigJson: JSON.stringify(raw) }); p.openGameConfig(); p.openAfterSheet(event({ path: 'outcomeRules.pass' }));
    assert.equal(p.data.advanced.branch.enabled, false); p.pickAfterNextStep(event({}, 2)); assert.equal(p.saveAfterSheet(), true); assert.equal(p.data.advanced.branch.enabled, true); assert.equal(p.data.advanced.branch.startStepId, 'feedback_a'); assert.equal(p.saveGameConfig(), true);
    const saved = p.prepareFormData(), reopened = page(); reopened.fillFormWithTemplateData(saved); reopened.openGameConfig(); reopened.openAfterSheet(event({ path: 'outcomeRules.pass' })); assert.equal(reopened.data.afterEditor.draft.next, 'feedback_b'); assert.equal(reopened.data.advanced.branch.enabled, true); reopened.closeAfterSheet(); assert.equal(reopened.saveGameConfig(), true); assert.deepEqual(JSON.parse(reopened.prepareFormData().advancedConfigJson), JSON.parse(saved.advancedConfigJson));
    if (process.env.R5_FE_FIX_FIXTURE_PATH) require('node:fs').writeFileSync(process.env.R5_FE_FIX_FIXTURE_PATH, saved.advancedConfigJson);
  }
});

test('未新选反馈的旧稿保持原样；退出 after 取消显式选择与启用动作', () => {
  const raw = { schemaVersion: 1, qa: base().qa, outcomeRules: { version: 1, pass: { reply: '旧after', costs: [], effects: [], nextStepId: 'old' }, fail: {} }, branch: { enabled: false, startStepId: 'old', steps: [{ id: 'old', body: '', terminal: true, outcomeCode: 'OLD', options: [], opaque: [8] }] } };
  const p = page(); p.fillFormWithTemplateData({ title: '旧反馈', advancedConfigJson: JSON.stringify(raw) }); p.openGameConfig(); p.openAfterSheet(event({ path: 'outcomeRules.pass' })); assert.equal(p.data.afterEditor.draft.next, 'old'); assert.equal(p.saveAfterSheet(), true); assert.equal(p.saveGameConfig(), true); assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), raw);
  p.openGameConfig(); p.updateBranchStep(event({ index: 0, field: 'body' }, '新内容')); const before = clone(p.data.advanced); p.openAfterSheet(event({ path: 'outcomeRules.pass' })); p.pickAfterNextStep(event({}, 1)); p.closeAfterSheet(); assert.deepEqual(p.data.advanced, before); assert.equal(p._r5AfterNextPicked, false); p.closeGameConfig(); assert.deepEqual(JSON.parse(p.prepareFormData().advancedConfigJson), raw);
});

test('真实页面：独立模板投票可声明并保存资源，采用上下文才检查共享范围', () => {
  const p = page(); const raw = base(); delete raw.outcomeRules; delete raw.interactionRules;
  p.fillFormWithTemplateData({ title: '可复用投票', validationMethod: 3, advancedConfigJson: JSON.stringify(raw) });
  p._refreshSupportMatrix();
  assert.equal(p.data.r5Editor.toolOptions.find(row => row.key === 'vote').reason, '');
  assert.match(p.data.supportConditions.vote, /采用.*绑定/);
  const voteIndex = p.data.r5Editor.toolOptions.findIndex(row => row.key === 'vote');
  p.addR5Tool(event({}, voteIndex)); assert.equal(p.data.toolSheetOpen, true);
  p.addVotePlan();
  p.updateVotePlan(event({ plan: 0, field: 'title' }, '继续前进'));
  p.addVoteMaterial(event({ plan: 0 }));
  p.updateVotePlan(event({ plan: 0, material: 0, field: 'label' }, '补给'));
  p.updateVotePlan(event({ plan: 0, material: 0, field: 'quantity' }, '2'));
  assert.equal(p.saveR5ToolSheet(), true, JSON.stringify({ toolError: p.data.toolError, panelError: p.data.r5Editor.error }));
  const saved = p.prepareFormData();
  let request; const sendRequest = app.sendRequest;
  try { app.sendRequest = value => { request = value; }; p.saveDraft(); }
  finally { app.sendRequest = sendRequest; }
  assert.equal(request.url, '/api/template/draft');
  assert.equal(request.method, 'POST');
  assert.equal(JSON.parse(request.data).advancedConfigJson, saved.advancedConfigJson);
  const config = JSON.parse(saved.advancedConfigJson);
  assert.equal(config.interactionTools.plans[0].title, '继续前进');
  assert.equal(config.interactionTools.plans[0].materialCost[0].quantity, 2);
  assert.equal(config.interactionRules.resourceSlots[0].kind, 'ITEM');
  const next = page(); next.fillFormWithTemplateData(saved); next._refreshSupportMatrix();
  assert.equal(next.data.supportError, '');
  assert.deepEqual(JSON.parse(next.prepareFormData().advancedConfigJson), config);
  next._supportContext = { runtime: { runtimeScope: 'PLAYER' } }; next._refreshSupportMatrix();
  assert.match(next.data.r5Editor.toolOptions.find(row => row.key === 'vote').reason, /组队/);
  assert.match(next.data.supportError, /WORLD_SHARED|组队/);
  assert.doesNotMatch(next.data.supportConditions.vote, /采用.*绑定/);
  next._supportContext = { runtime: { runtimeScope: 'TEAM', worldMode: 'WORLD_SHARED' } }; next._refreshSupportMatrix();
  assert.equal(next.data.supportError, '');
});

test('独立模板无上下文仍拦真实冲突，空主题上下文也不当作可运行', () => {
  assert.equal(support.toolPolicy(matrix, 'qaPick', 'vote', {}, {}), '');
  assert.match(support.toolPolicy(matrix, 'qaPick', 'vote', {}, { runtime: {} }), /组队/);
  assert.match(support.toolPolicy(matrix, 'qaPick', 'vote', { interactionRules: { limit: 2 } }, {}), /投票/);
  assert.match(support.toolPolicy(matrix, 'estimate', 'vote', {}, {}), /完成/);
});

test('R5 输入挑战不显示旧的限时秒数输入；只有旧版配置保留该字段', () => {
  const wxml = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  const grid = wxml.match(/<view class="cg-adv-grid" wx:if="\{\{advanced\.outcomeRules\.version !== 1\}\}">\n\s*<view><view class="cg-cfg-label">限时（3 至 120 秒）[\s\S]*?<\/view>\n\s*<block wx:else><view class="cg-cfg-label">次数上限/);
  assert.ok(grid, '旧版配置：秒数与次数同在两列栅格；R5（outcomeRules v1）不显示秒数，次数整行显示，限时只由限时挑战附加项设置');
});

// ===== 2026-10-11 选项问答：判断答案对错、选项反馈图文、设置内联（主人批准 HTML 原型） =====
const qaLegacy = () => ({ schemaVersion: 1, qa: { enabled: true, mode: 'PICK', title: '这座桥建于哪一年?', lead: '', multi: false, reveal: true, maxTries: 2, xp: 10,
  options: [{ id: 'o1', label: '1912 年', fb: '早了', correct: false }, { id: 'o2', label: '1937 年', fb: '对了', correct: true, effects: [{ var: '', op: 'ADD_TAG', value: 'tag.m_bridge' }] }] } });
const openQa = raw => { const p = page(); p.fillFormWithTemplateData({ title: '选项问答', advancedConfigJson: JSON.stringify(raw) }); return p; };

test('旧模板没有判断设置：默认判断开启，保存不补字段，正确答案仍必填', () => {
  const p = openQa(qaLegacy());
  assert.equal(p.data.advanced.qa.judge, undefined);
  const saved = JSON.parse(p.prepareFormData().advancedConfigJson);
  assert.equal('judge' in saved.qa, false, '旧配置不被改写');
  assert.equal(saved.qa.options[1].fb, '对了'); assert.equal(saved.qa.reveal, true); assert.equal(saved.qa.maxTries, 2);
  const noCorrect = clone(p.data.advanced); noCorrect.qa.options.forEach(o => { o.correct = false; });
  assert.match(cfg.validate(noCorrect, {}), /正确答案/);
});

test('关闭判断：只写 judge=false，正确答案/可答次数/亮答案/多选原样保留；不要求正确答案；切回恢复', () => {
  const p = openQa(qaLegacy());
  p.toggleQaJudge(event({}, false));
  const off = JSON.parse(p.prepareFormData().advancedConfigJson).qa;
  assert.equal(off.judge, false); assert.equal(off.maxTries, 2); assert.equal(off.reveal, true); assert.deepEqual(off.options.map(o => o.correct), [false, true]);
  const none = clone(p.data.advanced); none.qa.options.forEach(o => { o.correct = false; });
  assert.equal(cfg.validate(none, {}), '', '不判断对错时不需要正确答案');
  p.toggleQaJudge(event({}, true));
  const on = JSON.parse(p.prepareFormData().advancedConfigJson).qa;
  assert.equal('judge' in on, false); assert.deepEqual(on.options.map(o => o.correct), [false, true]); assert.equal(on.maxTries, 2);
});

test('作答规则下拉：选择方式、可以答几次、次数用完后', () => {
  const p = openQa(qaLegacy());
  p.pickQaAnswerMode(event({}, 1)); assert.equal(p.data.advanced.qa.multi, true);
  p.pickQaMaxTries(event({}, 3)); assert.equal(p.data.advanced.qa.maxTries, 3);
  p.pickQaReveal(event({}, 0)); assert.equal(p.data.advanced.qa.reveal, false);
  p.pickQaAnswerMode(event({}, 0)); assert.equal(p.data.advanced.qa.multi, false);
  assert.equal(p.data.advanced.qa.options.filter(o => o.correct).length, 1, '回到单选只留一个正确答案');
});

test('选项反馈图片：最多 3 张，第 4 张被拒；查看大图、更换、删除；保存回读保留；“记住这个选择”不受影响', () => {
  const p = openQa(qaLegacy());
  let n = 0, toasts = []; const urls = () => 'https://img.example/fb' + (++n) + '.jpg';
  app.chooseImage = cb => cb([urls()]);
  const toast = require('../../utils/toast.js'); const realToast = toast;
  for (let i = 0; i < 3; i++) p.addQaFbImg(event({ index: 0 }));
  assert.equal(p.data.advanced.qa.options[0].fbImgs.length, 3);
  const showToast = wx.showToast; wx.showToast = o => toasts.push(o && o.title); p.addQaFbImg(event({ index: 0 })); wx.showToast = showToast;
  assert.equal(p.data.advanced.qa.options[0].fbImgs.length, 3, '第 4 张被拒');
  let previewed; wx.previewImage = o => { previewed = o; };
  p.tapQaFbImg(event({ index: 0, img: 1 }));
  assert.deepEqual(p.data.pickerSheet.options.map(o => o.label), ['查看大图', '更换']);
  p.onPickerSheetSelect(event({ value: 'preview', label: '查看大图' }));
  assert.equal(p.data.pickerSheet.open, false);
  assert.equal(previewed.current, p.data.advanced.qa.options[0].fbImgs[1]);
  p.tapQaFbImg(event({ index: 0, img: 1 })); p.onPickerSheetSelect(event({ value: 'replace', label: '更换' }));
  assert.equal(p.data.advanced.qa.options[0].fbImgs[1], 'https://img.example/fb4.jpg', '更换');
  p.removeQaFbImg(event({ index: 0, img: 0 }));
  assert.deepEqual(p.data.advanced.qa.options[0].fbImgs, ['https://img.example/fb4.jpg', 'https://img.example/fb3.jpg']);
  const saved = p.prepareFormData(), reopened = page(); reopened.fillFormWithTemplateData(saved);
  assert.deepEqual(reopened.data.advanced.qa.options[0].fbImgs, ['https://img.example/fb4.jpg', 'https://img.example/fb3.jpg']);
  assert.deepEqual(reopened.data.advanced.qa.options[1].effects, qaLegacy().qa.options[1].effects, '记住这个选择的效果保留');
  const tooMany = clone(reopened.data.advanced); tooMany.qa.options[0].fbImgs = ['a', 'b', 'c', 'd'];
  assert.match(cfg.validate(tooMany, {}), /最多 3 张/);
  delete app.chooseImage;
});

test('玩法设置直接在玩法卡内（不再有玩法配置浮层）；选项问答分区：题目 / 作答规则 / 选项；判断开关与反馈图片位置', () => {
  const wxml = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /wx:if="\{\{gameConfigOpen\}\}"/, '玩法配置不再是浮层');
  const card = wxml.slice(wxml.indexOf('id="game-cfg"'), wxml.indexOf('<!-- R5：互动工具'));
  assert.match(card, /gameSection === 'qa'/, '选项问答设置在玩法卡内');
  for (const text of ['题目', '作答规则', '选项']) assert.match(card, new RegExp('<view class="cg-qa-sec[^"]*">' + text + '</view>'));
  assert.match(card, /<cy-switch checked="\{\{advanced\.qa\.judge !== false\}\}" bindchange="toggleQaJudge" \/>/);
  assert.match(card, /bindtap="addQaFbImg"/); assert.match(card, /catchtap="removeQaFbImg"/); assert.match(card, /bindtap="tapQaFbImg"/);
  assert.match(card, /toggleOptionMemo/, '“记住这个选择”保留');
  assert.doesNotMatch(card, /拍照没有对错|采用到主题时绑定实际数值或物资|这些分段是本站玩法结果后的反馈/, '设置里不放说明文字');
});

test('多选正确答案与服务端一致：就地提示、不替作者改答案或模式；关判断跳过；切回恢复原设置', () => {
  const raw = qaLegacy(); raw.qa.options.push({ id: 'o3', label: '1949 年', fb: '', correct: false });
  const p = openQa(raw);
  assert.equal(p.data.qaAnswerError, '');
  p.pickQaAnswerMode(event({}, 1));
  assert.equal(p.data.advanced.qa.multi, true, '模式按作者选择切换');
  assert.deepEqual(p.data.advanced.qa.options.map(o => !!o.correct), [false, true, false], '不自动改答案');
  assert.equal(p.data.qaAnswerError, '多选问答至少要指定两个正确答案');
  p.pickCorrectQaOption(event({ index: 0 })); p.pickCorrectQaOption(event({ index: 2 }));
  assert.equal(p.data.qaAnswerError, '多选问答不能把全部选项都设为正确答案');
  p.toggleQaJudge(event({}, false));
  assert.equal(p.data.qaAnswerError, '', '不判断对错时跳过正确答案要求');
  p.toggleQaJudge(event({}, true));
  assert.equal(p.data.advanced.qa.multi, true); assert.deepEqual(p.data.advanced.qa.options.map(o => !!o.correct), [true, true, true]);
  assert.equal(p.data.qaAnswerError, '多选问答不能把全部选项都设为正确答案');
  p.pickCorrectQaOption(event({ index: 0 }));
  assert.equal(p.data.qaAnswerError, '');
  const saved = JSON.parse(p.prepareFormData().advancedConfigJson);
  assert.deepEqual(saved.qa.options.map(o => !!o.correct), [false, true, true]);
  const wxml = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{advanced.qa.judge !== false && qaAnswerError\}\}" title="\{\{qaAnswerError\}\}"/);
});
