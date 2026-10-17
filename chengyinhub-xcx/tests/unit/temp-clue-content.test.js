const test = require('node:test');
const assert = require('node:assert/strict');

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
  return page;
}

test('线索内容在剧情里：同名标签段落不进入到达即读的 storyText，其余剧情照常', () => {
  const page = loadPage();
  page.data.moduleList = [{ key: 'story' }];
  page.data.advanced.clues = { 'clue.h_aaaaaaaaaaaa': 'EV04 维护规程' };
  page.data.storyBeats = [
    { id: 1, text: '风暴过后，证据墙前只剩下你们。', tag: '01-03 06:00', imgs: [] },
    { id: 2, text: '先隔离抽热支路，再接回热旁路，最后释放启动供能。', tag: 'EV04 维护规程', imgs: ['https://cdn.example/ev04.jpg'] },
  ];
  const form = page.prepareFormData();
  assert.equal(form.storyText, '风暴过后，证据墙前只剩下你们。', '线索正文不得在到达时泄露');
  const beats = JSON.parse(form.storyJson);
  assert.equal(beats[1].text, '先隔离抽热支路，再接回热旁路，最后释放启动供能。');
  assert.deepEqual(beats[1].imgs, ['https://cdn.example/ev04.jpg'], '线索图片随剧情保存');
});

test('线索工具行显示对应剧情段号；未配时提示未配内容', () => {
  const page = loadPage();
  page.data.storyBeats = [{ id: 1, text: 'x', tag: 'EV04 维护规程', imgs: [] }, { id: 2, text: '', tag: 'EV05 授权链', imgs: [] }, { id: 3, text: '', tag: 'EV04 维护规程', imgs: ['u'] }];
  page._syncClueContent();
  assert.deepEqual(page.data.clueContent, { 'EV04 维护规程': '内容：剧情第1、3段' }, '空段落不算内容');
  const fs = require('node:fs'), path = require('node:path');
  assert.match(fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8'), /clueContent\[clue\.name\] \|\| '未配内容'/);
});
