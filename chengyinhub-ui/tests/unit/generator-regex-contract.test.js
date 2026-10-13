const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

test('表单代码生成器不执行配置中的正则表达式字符串', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/utils/generator/js.js'), 'utf8');

  assert.doesNotMatch(source, /eval\s*\(/);
  assert.match(source, /toRegExpLiteral/);
  assert.match(source, /new RegExp/);
});
