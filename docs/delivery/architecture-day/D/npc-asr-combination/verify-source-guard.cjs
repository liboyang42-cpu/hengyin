const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const [originalTest, reviewedTest, actualRoot] = process.argv.slice(2);
assert.ok(originalTest && reviewedTest && actualRoot, 'originalTest reviewedTest actualCombinedSourceRoot required');
const targets = new Set([
  '门店分身对话:归属校验必须在进模型之前',
  '门店分身对话:会话键取负,不与漫游会话撞 active-slot',
  '喂给模型的现场事实里没有答案类字段',
  '店铺分身对话默认关,且不是靠 sys_config 缺省值蒙对的',
  'ASR 没接入时在进模型之前就拒绝,不扣额度不留假记录',
  '语音片段不落盘:只有识别出的文字进对话记录',
]);
function run(file) {
  const executed = [], failures = [];
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    __dirname: '/readonly/chengyinhub-xcx/tests/unit',
    require(name) {
      if (name === 'node:test') return { test(name, body) {
        if (!targets.has(name)) return;
        executed.push(name);try { body(); } catch (error) { failures.push({ name, code: error.code, message: error.message }); }
      } };
      if (name === 'node:assert/strict') return assert;
      if (name === 'node:path') return path;
      if (name === 'node:fs') return { readFileSync(name) {
        if (/\/pages\/play\/index\.(js|wxml|wxss)$/.test(name)) return ''; // Eager snapshots unused by these 6 backend cases.
        assert.ok(name.startsWith('/readonly/'));
        return fs.readFileSync(path.join(actualRoot, name.slice('/readonly/'.length)), 'utf8');
      } };
      throw new Error('unexpected dependency');
    },
  }, { filename: file });
  assert.equal(executed.length, targets.size, 'all six existing backend source-contract cases must execute');
  return failures;
}
const before = run(originalTest);
assert.deepEqual(before.map(x => x.name).sort(), [
  'ASR 没接入时在进模型之前就拒绝,不扣额度不留假记录',
  '门店分身对话:归属校验必须在进模型之前',
].sort(), 'exact old-variable/whitespace negative controls');
assert.ok(before.every(x => x.code === 'ERR_ASSERTION'));
assert.deepEqual(run(reviewedTest), []);
console.log('PASS: 6 existing backend source-contract cases on actual def951 sources; old scanner 4pass/2fail, reviewed scanner 6pass. No Java/DB/full suite execution.');
