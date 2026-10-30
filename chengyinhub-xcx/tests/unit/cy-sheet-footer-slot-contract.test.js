'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execSync } = require('node:child_process');

// cy-sheet 只有带 footer 属性时才渲染 slot="footer"。实走复核:「给这杯咖啡起个名字」的「取名」按钮
// 放在 footer 槽里却没开 footer,整屏没有提交按钮,这一站永远完成不了;同样漏开的还有慢任务 / 计步 / 时间窗。
test('cy-sheet 里放了 slot="footer" 的,开标签必须带 footer', () => {
  const root = path.resolve(__dirname, '../..');
  const files = execSync('git ls-files "*.wxml"', { cwd: root }).toString().trim().split('\n');
  const broken = [];
  for (const rel of files) {
    const src = fs.readFileSync(path.join(root, rel), 'utf8');
    const open = /<cy-sheet\b([\s\S]*?)>/g;
    let m;
    while ((m = open.exec(src))) {
      const end = src.indexOf('</cy-sheet>', m.index);
      // 玩法组件把半屏内容拆进 body.wxml 再 include(内嵌档复用同一份):footer 槽在被 include 的那份里,也要算进来
      const body = src.slice(m.index + m[0].length, end < 0 ? undefined : end).replace(/<cy-sheet[\s\S]*?<\/cy-sheet>/g, '')
        .replace(/<include\s+src="([^"]+)"\s*\/>/g, (all, inc) => {
          const file = path.join(root, path.dirname(rel), inc);
          return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : all;
        });
      if (/slot="footer"/.test(body) && !/(^|\s)footer(\s|=|\/?$)/.test(m[1])) {
        broken.push(rel + ':' + src.slice(0, m.index).split('\n').length);
      }
    }
  }
  assert.deepEqual(broken, []);
});
