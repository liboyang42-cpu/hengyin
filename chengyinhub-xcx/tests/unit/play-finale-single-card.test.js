// g20:最后一站不再叠六层 —— 先放尾声(openFinish 先走 _loadLifeEnding),再一张通关卡。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8');

test('最后一站:不弹解锁徽章卡、不弹名次勋章屏、不放勋章庆祝', () => {
  assert.match(js, /if \(newBadges\.length && !completedNow\) \{/);
  assert.match(js, /if \(hasMedalArt && medalRank && completedNow\) \{/);
  assert.match(js, /if \(!completedNow && \(newBadges\.length \|\| \(hasMedalArt/);
});

test('通关过场就是那一张卡:开了过场就不再垫勋章页', () => {
  assert.match(js, /if \(!this\._openFinale\(isShopDay\)\) this\._openMedalScreen\(\);/);
  const finale = js.slice(js.indexOf('  _openFinale(isShopDay) {'), js.indexOf('  closeFinale()'));
  assert.match(finale, /return false;/);
  assert.match(finale, /return true;/);
});

test('尾声在完成页之前:openFinish 先读结局', () => {
  const open = js.slice(js.indexOf('  openFinish() {'), js.indexOf('    this.loadEnding();', js.indexOf('  openFinish() {')));
  assert.match(open, /this\._loadLifeEnding\(\(\) => this\.openFinish\(\)\);/);
});
