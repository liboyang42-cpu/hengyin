const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');
function setup() {
  let calls = 0, callback, finished = false;
  const app = { chooseImage(cb) { calls++; callback = cb; return { isFinished: () => finished, isAborted: () => false }; } };
  const code = source.slice(source.indexOf('  insertStoryImageAt(e) {'), source.indexOf('  // 故事流里的音频块'));
  const methods = vm.runInNewContext('({' + code + '})', { app, cyToast() {} });
  const page = { ...methods, data: { storyEditor: { chapterIndex: 0 }, formData: { chapters: [{ _localId: 'one' }, { _localId: 'two' }] } },
    setData() {}, _insertStoryMedia() { this.inserted = (this.inserted || 0) + 1; } };
  return { page, calls: () => calls, finish: () => { finished = true; }, done: () => callback(['https://cdn.example/image']) };
}
test('上传未结束只提交一次，取消/失败结束后可重新选择', () => {
  const s = setup(), event = { currentTarget: { dataset: { index: 0 } } };
  s.page.insertStoryImageAt(event); s.page.insertStoryImageAt(event); assert.equal(s.calls(), 1);
  s.finish(); s.page.insertStoryImageAt(event); assert.equal(s.calls(), 2);
  s.done(); assert.equal(s.page.inserted, 1);
});
test('上传期间换章，迟到回调不把图片插进另一章', () => {
  const s = setup(); s.page.insertStoryImageAt({ currentTarget: { dataset: { index: 0 } } });
  s.page.data.storyEditor.chapterIndex = 1; s.done(); assert.equal(s.page.inserted, undefined);
});
