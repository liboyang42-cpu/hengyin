const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const SRC = path.resolve(__dirname, '../../src');
const pluginPath = path.resolve(SRC, 'plugins/download.js');

test('download 插件不再暴露命中 /common/download?fileName 的 name() 入口', () => {
  const source = fs.readFileSync(pluginPath, 'utf8');
  assert.doesNotMatch(source, /["'][^"']*\/common\/download\?fileName/,
    'download.js 不得再拼接 fileName 下载 URL');
  assert.doesNotMatch(source, /^\s*name\s*\(/m,
    'download.js 不得再定义 name() 方法');
  assert.match(source, /\bzip\s*\(/, 'zip() 必须保留');
});

test('全仓无任何 $download.name / download.name 调用点', () => {
  const offenders = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        if (entry.name === 'node_modules') continue;
        walk(full);
      } else if (/\.(vue|js)$/.test(entry.name)) {
        const text = fs.readFileSync(full, 'utf8');
        if (/(\$download|download)\s*\.\s*name\s*\(/.test(text)) {
          offenders.push(path.relative(SRC, full));
        }
      }
    }
  };
  walk(SRC);
  assert.deepEqual(offenders, [], `发现残留的 download.name() 调用: ${offenders.join(', ')}`);
});
