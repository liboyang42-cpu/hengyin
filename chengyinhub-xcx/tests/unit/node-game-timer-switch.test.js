// R5：限时统一在附加规则中编辑；切玩法/打开旧稿保留 timer 与 variants。
const assert = require('node:assert/strict');
const test = require('node:test');
const cfg = require('../../pages/publish/utils/publish/advanced-game-config.js');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog.js');
function source() {
  const s = catalog.applyToConfig(cfg.defaultConfig(), 'sort'); s.timer.enabled = true;
  const choice = cfg.relaxChoices(s).find(row => row.key === 'timer.durationSeconds');
  s.variants = [{ when: choice.when, relax: choice.relax, unknown: [1] }]; return s;
}
for (const key of ['check', 'match', 'coin', 'd20', 'album']) test('切换 ' + key + ' 保留原限时和放宽，选择器 availability 不删旧数据', () => {
  const before = source(), next = catalog.applyToConfig(before, key);
  assert.deepEqual(next.timer, before.timer); assert.deepEqual(next.variants, before.variants);
});
test('dropStaleTimer 是兼容只读入口，不改配置或引用', () => {
  const before = source(), json = JSON.stringify(before); assert.equal(catalog.dropStaleTimer(before, 'album'), before); assert.equal(JSON.stringify(before), json);
});
test('R5 A 已接通结果类型可使用唯一的限时附加项', () => {
  for (const key of ['check', 'd20', 'coin', 'dice', 'blindTaste', 'typeIn', 'countdown']) assert.equal(catalog.supportsTimer(key), true, key);
});
