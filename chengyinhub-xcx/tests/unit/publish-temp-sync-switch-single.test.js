const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const wxml = fs.readFileSync(path.resolve(__dirname, '../../pages/publish/temp/index.wxml'), 'utf8');
function assertExplicitMarketSubmission(text) {
  assert.doesNotMatch(text, /bindchange="switch1Change"|checked="{{\s*formData\.isSync/);
  assert.match(text, /先保存为我的玩法/);
  assert.doesNotMatch(text, /class="cg-sq-sub"/);
}
test('编辑页保留保存入口，清除说明小字且不提供自动同步开关', () => assertExplicitMarketSubmission(wxml));
test('重新引入自动同步开关会被拒绝', () => {
  assert.throws(() => assertExplicitMarketSubmission(wxml + '<cy-switch checked="{{formData.isSync}}" bindchange="switch1Change" />'));
});
