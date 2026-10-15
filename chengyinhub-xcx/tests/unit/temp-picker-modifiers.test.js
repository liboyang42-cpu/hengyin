const test = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog');
const config = require('../../pages/publish/utils/publish/advanced-game-config');

test('R5选择器仅基础玩法，附加规则三项可编辑且多人只读', () => {
  global.getApp = () => ({ globalData: {} });
  global.wx = { getStorageSync() {}, showToast() {} };
  let page;
  global.Page = value => { page = value; };
  require('../../pages/publish/temp/index');
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = patch => {
    for (const [path, value] of Object.entries(patch)) {
      const parts = path.split('.'); let target = page.data;
      while (parts.length > 1) target = target[parts.shift()];
      target[parts[0]] = value;
    }
  };
  page._setFormState = page.setData;
  const items = catalog.PICKER_GROUPS.flatMap(group => group.items);
  assert.equal(new Set(items.map(item => item.key)).size, 23);
  assert.equal(catalog.ALL.length, 33);
  page.data.advanced = catalog.applyToConfig(config.defaultConfig(), 'qaText');
  page.data.gameKey = 'qaText'; page.data.timerAvailable = true;
  page.data.formData.validationMethod = 1;
  for (const item of catalog.MODIFIERS) {
    page.pickGame({ currentTarget: { dataset: { key: item.key } } });
    assert.equal(page.data.advanced[item.key].enabled, !item.disabled);
    assert.equal(page.data.gameKey, 'qaText');
    assert.equal(page.data.formData.validationMethod, 1);
  }
  const next = catalog.applyToConfig(page.data.advanced, 'sort');
  assert.equal(next.qa.enabled, false);
  assert.equal(next.sort.enabled, true);
  assert.equal(next.multiplayer.enabled, false);
  page.onAdvancedToggle({ currentTarget: { dataset: { key: 'timer' } }, detail: { value: false } });
  assert.equal(page.data.advanced.timer.enabled, false);
  assert.equal(page.data.advanced.leaderboard.enabled, true);
  page.data.timerAvailable = false;
  page.pickGame({ currentTarget: { dataset: { key: 'timer' } } });
  assert.equal(page.data.advanced.timer.enabled, false);
  for (const key of ['', 'album']) {
    page.data.gameKey = key;
    page.data.advanced.leaderboard.enabled = false;
    page.pickGame({ currentTarget: { dataset: { key: 'leaderboard' } } });
    assert.equal(page.data.advanced.leaderboard.enabled, false);
  }
});
