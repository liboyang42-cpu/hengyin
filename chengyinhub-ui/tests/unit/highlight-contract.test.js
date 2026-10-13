const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('代码生成预览使用 Highlight.js 11 的对象参数 API', () => {
  const file = path.resolve(__dirname, '../../src/views/tool/gen/index.vue');
  const source = fs.readFileSync(file, 'utf8');

  assert.match(source, /hljs\.highlight\(code \|\| "", \{ language, ignoreIllegals: true \}\)/);
  assert.doesNotMatch(source, /hljs\.highlight\(language, code \|\| "", true\)/);
  assert.match(source, /highlight\.js\/styles\/github\.css/);
  assert.doesNotMatch(source, /github-gist\.css/);
});
