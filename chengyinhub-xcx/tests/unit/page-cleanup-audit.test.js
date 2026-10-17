'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { execFileSync } = require('node:child_process');

test('page cleanup audit covers the manifest and retains unresolved external and cross-page contracts', () => {
  const root = path.resolve(__dirname, '../..');
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const pages = manifest.pages.concat((manifest.subPackages || manifest.subpackages || []).flatMap(group => group.pages.map(page => group.root + '/' + page)));
  const audit = JSON.parse(execFileSync(process.execPath, [path.join(root, 'tests/tools/audit-page-cleanup.js')], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }));
  assert.deepEqual(audit.rows.map(row => row.page), pages);
  const report = fs.readFileSync(path.join(root, '../docs/design/miniprogram-page-cleanup-1009.md'), 'utf8').split('## 完整页面清单')[1];
  const documented = [...report.matchAll(/^\| `([^`]+)` \| (?:是|否) \| .* \| (KEEP|UNSURE|DELETE) \|$/gm)];
  assert.deepEqual(documented.map(row => row[1]), pages);
  assert.deepEqual(documented.map(row => row[2]), audit.rows.map(row => row.verdict));
  assert.equal(audit.counts.DELETE, 0);
  assert.deepEqual(audit.orphans, []);
  for (const tab of manifest.tabBar.list) assert.equal(audit.rows.find(row => row.page === tab.pagePath).verdict, 'KEEP');
  assert.equal(audit.rows.find(row => row.page === 'pages/play/circle/index').verdict, 'KEEP');
  const unresolved = audit.rows.filter(row => row.verdict === 'UNSURE');
  assert.equal(unresolved.length, 4);
  for (const row of unresolved) assert.ok(row.tests.length > 1);
  assert.ok(audit.externalSources.some(row => row.text.includes('wx.subscribe.page')));
});
