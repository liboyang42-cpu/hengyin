const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.wxml'), 'utf8');
const js = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/fabu/index.js'), 'utf8');

// 只看「章节设置」这一张弹层:页面上还有别的 .tit / 别的弹层,不限定会串台。
const sheetStart = wxml.indexOf('pop-chapter-settings');
const sheetEnd = wxml.indexOf('2026-09-04 拍板:音频', sheetStart);
assert.ok(sheetStart >= 0, '章节设置弹层还在');
assert.ok(sheetEnd > sheetStart, '弹层结束标记找不到，切片会串台');
const sheet = wxml.slice(sheetStart, sheetEnd);

function assertNoEntryCaption(text) {
  assert.doesNotMatch(text.replace(/<!--[\s\S]*?-->/g, ''), /chapter-story-entry-hint|块间插入点/);
}

test('章节表单不显示故事流入口说明小字', () => assertNoEntryCaption(sheet));

test('名称的必填星号与选填剧情分别显示', () => {
  assert.match(sheet, /章节名称<text class="g-req"[^>]*>\*<\/text>/);
  assert.match(sheet, /class="chapter-story-field__req">选填<\/text>/);
  assert.match(sheet, /\{\{popChapterAction==0\?'添加章节':'编辑章节'\}\}/);
});

test('这句话描述的行为与 JS 一致:完成新增才会自动进入故事流', () => {
  assert.match(js, /const cityStoryFlow = Number\(this\.data\.formData\.productType\) === 1 \|\| newChapter\.opening === true;/);
  assert.ok(js.includes('updatedChapters.length - 1 } } });'), '完成新增后确实 openStoryEditor');
  const editBranchAt = js.indexOf('} else {\n      // 编辑现有章节的逻辑保持不变');
  assert.ok(editBranchAt > 0, '编辑既有章节的分支没被挪走');
  assert.ok(js.slice(editBranchAt, js.indexOf("    this.updateAllStatistics();", editBranchAt)).indexOf('openStoryEditor') < 0,
    '编辑既有章节不会自动进故事流，所以这句话在这里就是错的');
});

test('负控:重新加入入口说明小字会被拒绝', () => {
  assert.throws(() => assertNoEntryCaption(sheet + '<view class="chapter-story-entry-hint">点完成后进入故事流</view>'));
});
