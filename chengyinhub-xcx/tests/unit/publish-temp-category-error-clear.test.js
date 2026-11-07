const test = require('node:test');
const assert = require('node:assert/strict');

// 发布模板页:提交时漏选类别会出现「请选择至少一个玩法类别」红字。
// 用户随后选了类别,红字必须当场消失,不能等到下一次提交才刷新。
global.getApp = () => ({ globalData: {}, getUserID: () => 9, getAuthorization: () => '', sendRequest() {} });
global.wx = { ...require('../helpers/memory-storage.js')(), showToast() {}, getBackgroundAudioManager: () => ({ stop() {} }) };
let definition;
global.Page = value => { definition = value; };
require('../../pages/publish/temp/index');

function page() {
  const p = { ...definition, data: JSON.parse(JSON.stringify(definition.data)) };
  p.setData = function (patch, cb) {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.'); let at = this.data;
      for (const part of parts.slice(0, -1)) at = at[part];
      at[parts.at(-1)] = value;
    }
    if (cb) cb();
  };
  return p;
}

test('选中类别清掉类别必选红字', () => {
  const p = page();
  p.setData({ 'errors.categoryIds': '请选择至少一个玩法类别', 'errors.title': '请填写玩法标题' });
  p.updateCategorySelection([3], [{ id: 3, categoryName: '城市探索' }]);
  assert.equal(p.data.errors.categoryIds || '', '');
  assert.equal(p.data.errors.title, '请填写玩法标题', '别的字段红字不受影响');
  assert.deepEqual(p.data.selectedCategoryIds, [3]);
});

test('清空选择时不凭空清掉红字', () => {
  const p = page();
  p.setData({ 'errors.categoryIds': '请选择至少一个玩法类别' });
  p.updateCategorySelection([], []);
  assert.equal(p.data.errors.categoryIds, '请选择至少一个玩法类别');
});
