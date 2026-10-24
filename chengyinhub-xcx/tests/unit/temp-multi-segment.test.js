const test = require('node:test');
const assert = require('node:assert/strict');
const catalog = require('../../pages/publish/utils/publish/node-game-catalog');
const config = require('../../pages/publish/utils/publish/advanced-game-config');
const preview = require('../../pages/publish/utils/publish/advanced-game-preview');

function loadPage() {
  global.getApp = () => ({ globalData: {} });
  global.wx = { getStorageSync() {}, showToast() {} };
  let page;
  global.Page = value => { page = value; };
  delete require.cache[require.resolve('../../pages/publish/temp/index')];
  require('../../pages/publish/temp/index');
  page.data = JSON.parse(JSON.stringify(page.data));
  page.setData = (patch, cb) => {
    for (const [path, value] of Object.entries(patch)) {
      const parts = path.split('.'); let target = page.data;
      while (parts.length > 1) target = target[parts.shift()];
      target[parts[0]] = value;
    }
    if (cb) cb.call(page);
  };
  page._setFormState = (patch, cb) => { page.setData(patch); page._syncSegments(); if (cb) cb.call(page); };
  return page;
}

test('多段题目：加段、换主玩法保留、存取后仍在、预览逐段走到、可移除', () => {
  const page = loadPage();
  page.data.advanced = catalog.applyToConfig(config.defaultConfig(), 'qaPick');
  page.data.gameKey = 'qaPick'; page.data.gameSection = 'qa';
  page._syncSegments();
  assert.deepEqual(page.data.segmentAddOptions.map(row => row.key), ['sort', 'match', 'classify']);
  page.addSegment({ detail: { value: 0 } });
  assert.equal(page.data.advanced.sort.enabled, true);
  assert.equal(page.data.advanced.qa.enabled, true, '加段不得关掉主玩法');
  assert.equal(page.data.segmentOn.sort, true);
  page.addSegment({ detail: { value: page.data.segmentAddOptions.findIndex(row => row.key === 'match') } });
  assert.equal(page.data.segmentOn.match, true);

  const a = page.data.advanced;
  a.qa.title = '哪条路通往避难所'; a.qa.options[0].label = '北线'; a.qa.options[1].label = '南线';
  a.sort.prompt = '按顺序'; a.sort.items.forEach((it, i) => { it.label = '步骤' + (i + 1); });
  a.match.prompt = '连线'; a.match.left.forEach((it, i) => { it.label = '左' + i; }); a.match.right.forEach((it, i) => { it.label = '右' + i; });
  // 存取：两段以上都还在
  const back = config.parse(config.serialize(page.data.advanced)).value;
  assert.equal(back.qa.enabled && back.sort.enabled && back.match.enabled, true);
  const kit = preview.buildPreviewKit(back);
  assert.ok(kit && Array.isArray(kit.queue) && kit.queue.length === 2, '预览必须挂上后续两段');

  // 换主玩法为排序：连线这段保留，原主玩法问答让位
  page.pickGame({ currentTarget: { dataset: { key: 'sort' } } });
  assert.equal(page.data.gameSection, 'sort');
  assert.equal(page.data.advanced.match.enabled, true);
  assert.equal(page.data.advanced.sort.enabled, true);

  page.removeSegment({ currentTarget: { dataset: { section: 'match' } } });
  assert.equal(page.data.advanced.match.enabled, false);
  assert.equal(page.data.segmentOn.match, false);
  // 主玩法自身不能被「移除」
  page.removeSegment({ currentTarget: { dataset: { section: 'sort' } } });
  assert.equal(page.data.advanced.sort.enabled, true);
});

test('主玩法不是客观题时不提供加段', () => {
  const page = loadPage();
  page.data.advanced = catalog.applyToConfig(config.defaultConfig(), 'album');
  page.data.gameSection = 'album';
  page._syncSegments();
  assert.deepEqual(page.data.segmentAddOptions, []);
});
