const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { formatDurationMinutes } = require('../../utils/template-display.js');

const WXML = fs.readFileSync(
  path.join(__dirname, '../../pages/template/index.wxml'),
  'utf8'
);

test('模板时长展示只补一次分钟单位', () => {
  assert.equal(formatDurationMinutes(35), '35分钟');
  assert.equal(formatDurationMinutes('35'), '35分钟');
  assert.equal(formatDurationMinutes('35分钟'), '35分钟');
  assert.equal(formatDurationMinutes('35 分钟'), '35分钟');
});

test('模板货架时长：主题 totalTime 是秒，按秒换算；玩法卡不直接渲染原始 duration', () => {
  // 2026-10-28:模板页货架移进 pages/templatedetail/shelf。旧页把主题的秒数交给
  // formatDurationMinutes,上架后会显示「14400分钟」—— 新货架与预览统一按秒换算(fmtSec)。
  const shelf = fs.readFileSync(path.join(__dirname, '../../pages/templatedetail/shelf/index.js'), 'utf8');
  const preview = fs.readFileSync(path.join(__dirname, '../../pages/templatedetail/topic-preview/index.js'), 'utf8');
  assert.match(shelf, /const dur = fmtSec\(item\.totalTime\);/, '主题摘要按秒换算');
  assert.match(preview, /fmtSec\(info\.totalTime\)/, '预览按秒换算');
  const wxml = fs.readFileSync(path.join(__dirname, '../../pages/templatedetail/shelf/index.wxml'), 'utf8');
  assert.doesNotMatch(wxml, /item\.duration\s*}}/, '不许绕过归一化直接渲染 item.duration');
  assert.doesNotMatch(WXML, /item\.duration\s*}}/, '模板页外壳也不渲染原始时长');
});
