'use strict';
/* 第十三轮 m1:章节一有完成要求 / 互斥组 / 额外站点,服务端保存时每一站都编成个人任务
   (TopicRoutePublicationCompiler.hasChapterRequirements)。作者端同口径:
   - 这样的主题里每一站按任务站处理(settled),能结算的玩法按既有结果规则路径落规则、参与方式默认定位到达;
   - 已存的站重新打开玩法时补上结果规则并标记要保存;
   - 加互斥选项的当下点名:哪一站用不了(不让加)、哪几站要重新打开存一次;
   - 成绩只在手机上测的玩法(C1 自报)和没有承接商家的预测在这里说人话拒绝。 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const support = require('../../pages/publish/utils/publish/gameplay-support-matrix.js');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
global.getApp = global.getApp || (() => ({ globalData: {} }));
global.wx = global.wx || {};
const runtime = require('../../pages/publish/fabu/theme-runtime.js');
const matrix = require('./fixtures/gameplay-support-matrix.json').data;

const theme = { schemaVersion: 1, chapters: [{ index: 0, eggs: [{ id: 'e', trigger: 'TIME', threshold: 1 }], exclusiveGroups: [[11, 12]] }] };
const NO_BRIDGE = ['estimate', 'pricePair', 'hidden', 'predict', 'random', 'profile', 'note', 'objectCard', 'steps', 'react', 'shake', 'quiet',
  'compass', 'shout', 'countdown', 'stopwatch', 'photoCheck', 'check', 'd20', 'typeIn', 'coin', 'dice', 'branch'];
const PHONE_ONLY = ['react', 'shake', 'quiet', 'compass', 'shout', 'stopwatch'];

test('章节有互斥组时每一站都是任务站;只有途中彩蛋不算', () => {
  const ctx = support.context(theme, { id: 11 });
  assert.equal(ctx.settled, true); assert.equal(ctx.chapterTasks, true);
  assert.equal(support.context(theme, {}).settled, true, '还没存的新站也一样');
  const eggsOnly = { schemaVersion: 1, chapters: [{ index: 0, eggs: [{ id: 'e' }] }] };
  assert.equal(support.context(eggsOnly, { id: 11 }).settled, false);
  assert.equal(support.context({ schemaVersion: 1 }, { id: 11 }).settled, false);
});

test('每种还没有通用结算证明的玩法:在带互斥组的主题里要么带上结果规则能存,要么说清用不了', () => {
  const ctx = support.context(theme, { id: 11 }), shop = Object.assign({}, ctx, { shop: true });
  for (const key of NO_BRIDGE) {
    const game = catalog.findGame(key); assert.ok(game, key);
    const advanced = catalog.applyToConfig(cfg.defaultConfig(), key);
    const reason = support.choice(matrix, key, support.pickAs(advanced, ctx), ctx);
    if (PHONE_ONLY.includes(key)) { assert.equal(reason, '这种玩法的成绩只在手机上测，确认不了玩家真的完成，请换一种玩法', key); continue; }
    if (key === 'predict') {
      assert.equal(reason, '预测要由承接商家给答案，没有承接商家的站点在这里不能用');
      assert.equal(support.choice(matrix, key, support.pickAs(advanced, shop), shop), '');
      continue;
    }
    if (key === 'branch') { assert.match(reason, /确认不了，请换一种玩法/); continue; }
    assert.equal(reason, '', key);
    const settled = support.settleOutcome(matrix, ctx, JSON.parse(JSON.stringify(advanced)), game.section);
    assert.equal(settled.outcomeRules && settled.outcomeRules.version, 1, key + ' 选中即带结果规则');
    assert.equal(support.validate(matrix, { advancedConfigJson: JSON.stringify(settled), validationMethod: 5 }, ctx), '', key + ' 带规则后通过发布前检查');
    assert.notEqual(support.validate(matrix, { advancedConfigJson: JSON.stringify(advanced), validationMethod: 0 }, ctx), '', key + ' 没带规则的旧站要重存');
  }
  // 不带章节规则的状态 / 检定主题保持第十二轮 k7 的口径:手机自报玩法照旧可选
  const k7 = { settled: true, runtime: {} };
  assert.equal(support.choice(matrix, 'react', support.pickAs({ reaction: { enabled: true } }, k7), k7), '');
});

function stations(templates) {
  const host = Object.assign({}, runtime.methods, {
    data: { supportMatrix: matrix, formData: { chapters: [{ nodes: templates.map((t, i) => ({ id: 11 + i, name: t.name, templateId: 100 + i })) }] } },
    _templateDetailsById: Object.fromEntries(templates.map((t, i) => [100 + i, { id: 100 + i, validationMethod: t.method || 0, advancedConfigJson: JSON.stringify(t.config) }])),
  });
  return host._chapterTaskStations(theme);
}

test('加互斥组的当下按站名说清:要重存的列出来,用不了的不让加', () => {
  const coin = catalog.applyToConfig(cfg.defaultConfig(), 'coin'), dice = catalog.applyToConfig(cfg.defaultConfig(), 'dice');
  assert.deepEqual(stations([{ name: '左巷 · 硬币摊', config: coin }, { name: '右巷 · 骰子摊', config: dice }]), { blocked: '', resave: ['左巷 · 硬币摊', '右巷 · 骰子摊'] });
  const ready = Object.assign({}, coin, { outcomeRules: { version: 1, pass: {}, fail: {} } });
  assert.deepEqual(stations([{ name: '左巷', config: ready, method: 5 }]), { blocked: '', resave: [] });
  const shake = catalog.applyToConfig(cfg.defaultConfig(), 'shake');
  assert.equal(stations([{ name: '球场', config: shake }]).blocked, '「球场」：这种玩法的成绩只在手机上测，确认不了玩家真的完成，请换一种玩法');
  const branch = catalog.applyToConfig(cfg.defaultConfig(), 'branch');
  assert.match(stations([{ name: '岔口', config: branch }]).blocked, /^「岔口」：/);
  assert.equal(stations([{ name: '路口', config: cfg.defaultConfig() }]).blocked, '「路口」：这一站的参与方式要选「定位到达」或「扫码」');
  for (const out of [stations([{ name: '球场', config: shake }]), stations([{ name: '岔口', config: branch }])])
    assert.doesNotMatch(out.blocked, /[A-Za-z_]{3,}|节点 \d/, '不露内部名');
});

test('已存的站重新打开玩法:补上结果规则、参与方式默认定位到达,并标记要保存', () => {
  const src = require('node:fs').readFileSync(require('node:path').join(__dirname, '../../pages/publish/temp/index.js'), 'utf8');
  const start = src.indexOf('  _settleLoadedGame: function () {'), end = src.indexOf('  _refreshSupportMatrix: function () {');
  assert.ok(start > 0 && end > start);
  const method = new Function('supportMatrix', 'return {' + src.slice(start, end) + '}')(support)._settleLoadedGame;
  const patches = [];
  const page = { _supportMatrix: matrix, _supportContext: support.context(theme, { id: 11 }),
    data: { gameSection: 'coinFlip', advanced: catalog.applyToConfig(cfg.defaultConfig(), 'coin'), formData: { validationMethod: 0 } },
    _setFormState(patch) { patches.push(patch); Object.assign(this.data, patch); } };
  method.call(page);
  assert.equal(patches.length, 1); assert.equal(patches[0].advanced.outcomeRules.version, 1);
  assert.equal(patches[0]['formData.validationMethod'], 5); assert.equal(page._settledOnLoad, true);
  method.call(page); assert.equal(patches.length, 1, '只补一次');
  const scan = Object.assign({}, page, { _settledOnLoad: false, data: { gameSection: 'coinFlip', advanced: catalog.applyToConfig(cfg.defaultConfig(), 'coin'), formData: { validationMethod: 4 } } });
  patches.length = 0; method.call(scan); assert.equal(patches[0]['formData.validationMethod'], undefined, '已选扫码不改');
  const plain = Object.assign({}, page, { _supportContext: support.context({ schemaVersion: 1 }, { id: 11 }), data: { gameSection: 'coinFlip', advanced: catalog.applyToConfig(cfg.defaultConfig(), 'coin'), formData: {} } });
  patches.length = 0; method.call(plain); assert.equal(patches.length, 0, '没有章节规则的主题不动');
  assert.match(src, /this\.data\.dirty = this\._settledOnLoad === true;/);
});
