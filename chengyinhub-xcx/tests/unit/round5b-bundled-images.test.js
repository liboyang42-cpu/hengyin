'use strict';
// Round5B-8:别家直播截图、像外国军徽的示例徽章、示例推荐图和仅开发用的配图不得再随包发布或被引用。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ROOT = path.resolve(__dirname, '../..');
const RETIRED = [
  'images/index-figma/nearby-card.jpg',
  'subpackageP3/assets/badge-demo.png',
  'images/index-figma/reco-hero.jpg',
  'pages/club/images/d_frametu.jpg',
];

function sources(dir = ROOT, rel = '') {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = rel ? rel + '/' + entry.name : entry.name;
    if (entry.name.startsWith('.') || entry.name === 'node_modules' || file === 'tests/unit/round5b-bundled-images.test.js') return [];
    if (entry.isDirectory()) return sources(path.join(dir, entry.name), file);
    return /\.(js|wxml|wxss|json)$/.test(file) ? [file] : [];
  });
}

test('retired images are deleted and no runtime source references them', () => {
  for (const file of RETIRED) assert.ok(!fs.existsSync(path.join(ROOT, file)), file);
  const offenders = [];
  for (const file of sources()) {
    const text = fs.readFileSync(path.join(ROOT, file), 'utf8');
    for (const retired of RETIRED) if (text.includes(path.basename(retired))) offenders.push(file + ' -> ' + retired);
  }
  assert.deepEqual(offenders, []);
});

test('real POI and badge fallbacks use placeholders that already ship', () => {
  const roam = fs.readFileSync(path.join(ROOT, 'pages/roam/index.js'), 'utf8');
  const badge = fs.readFileSync(path.join(ROOT, 'subpackageP3/pages/badge-3d/index.js'), 'utf8');
  const placeholder = roam.match(/const MOCK_IMG = '([^']+)'/)[1];
  const defaultBadge = badge.match(/const DEFAULT_BADGE_ASSET = '([^']+)'/)[1];
  assert.ok(fs.existsSync(path.join(ROOT, placeholder.slice(1))), placeholder);
  assert.ok(fs.existsSync(path.join(ROOT, defaultBadge.slice(1))), defaultBadge);
});
