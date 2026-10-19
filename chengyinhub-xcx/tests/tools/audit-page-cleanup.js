'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const { finiteRoutes } = require('../lint/finite-routes');

const root = path.resolve(__dirname, '../../..');
const files = execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8' }).trim().split('\n');
const texts = files.filter(file => /\.(js|json|wxml|vue|html|java|xml|sql|yml|yaml|properties|ts)$/.test(file)
  && !file.includes('/node_modules/') && !file.endsWith('action-ledger.json')).map(file => {
    const content = fs.readFileSync(path.join(root, file), 'utf8');
    return { file, content, lines: content.split('\n') };
  });
const app = JSON.parse(fs.readFileSync(path.join(root, 'chengyinhub-xcx/app.json'), 'utf8'));
const pages = app.pages.concat((app.subPackages || app.subpackages || []).flatMap(group => group.pages.map(page => group.root + '/' + page)));
const tabs = new Set((app.tabBar.list || []).map(item => item.pagePath));
const candidates = new Set(['pages/play/circle/index', 'pages/publish/topicadd/topicadd', 'pages/club/workbench/index', 'pages/coop/withdraw/index', 'pages/coop/withdraw/records/index']);
const runtimeEntries = new Map();
const jsFiles = texts.filter(source => source.file.startsWith('chengyinhub-xcx/') && source.file.endsWith('.js')
  && !source.file.includes('/tests/') && !source.file.includes('/scripts/')).map(source => path.join(root, source.file));
for (const [file, proof] of finiteRoutes(jsFiles)) {
  const source = fs.readFileSync(file, 'utf8');
  for (const request of proof.requests) {
    if (!request.navigation || request.ambiguous || !request.routes) continue;
    for (const url of request.routes) {
      const page = url.split('?')[0].replace(/^\//, '');
      if (!pages.includes(page) || file.startsWith(path.join(root, 'chengyinhub-xcx', page.slice(0, page.lastIndexOf('/') + 1)))) continue;
      if (!runtimeEntries.has(page)) runtimeEntries.set(page, new Set());
      runtimeEntries.get(page).add(path.relative(root, file) + ':' + source.slice(0, request.call.start).split('\n').length);
    }
  }
}
const rows = pages.map(page => {
  const directory = 'chengyinhub-xcx/' + page.slice(0, page.lastIndexOf('/') + 1);
  const prefix = page.slice(0, page.lastIndexOf('/'));
  const references = [], tests = [], ownShare = [];
  for (const source of texts) {
    if (source.file === 'chengyinhub-xcx/app.json' || source.file.startsWith('docs/') || source.file.startsWith('chengyinhub-xcx/scripts/')) continue;
    const own = source.file.startsWith(directory);
    if (!own && !source.content.includes(page) && !(candidates.has(page) && source.content.includes(prefix))) continue;
    source.lines.forEach((line, index) => {
      const test = source.file.includes('/tests/') || source.file.includes('/src/test/');
      if (own && /onShareAppMessage|onShareTimeline/.test(line)) ownShare.push(source.file + ':' + (index + 1));
      if (!own && (line.includes(page) || candidates.has(page) && line.includes(prefix))) {
        (test ? tests : references).push(source.file + ':' + (index + 1));
      }
    });
  }
  return { page, tabBar: tabs.has(page), runtimeEntries: [...(runtimeEntries.get(page) || [])], references: [...new Set(references)], tests: [...new Set(tests)], ownShare: [...new Set(ownShare)],
    verdict: page === 'pages/play/circle/index' || !candidates.has(page) ? 'KEEP' : 'UNSURE' };
});
const orphans = texts.filter(source => source.file.startsWith('chengyinhub-xcx/') && source.file.endsWith('.js')
  && !source.file.includes('/tests/') && !source.file.includes('/scripts/') && source.lines.some(line => /\bPage\s*\(/.test(line)))
  .map(source => source.file.slice('chengyinhub-xcx/'.length, -3)).filter(page => !pages.includes(page) && fs.existsSync(path.join(root, 'chengyinhub-xcx', page + '.wxml')));
const externalSources = texts.filter(source => !source.file.startsWith('docs/') && !source.file.includes('/tests/') && !source.file.includes('/src/test/'))
  .flatMap(source => source.lines.map((line, index) => ({ file: source.file, line: index + 1, text: line.trim() })))
  .filter(row => /getQRCode|wxacode|subscribe\.page|setMiniProgram|miniProgramPage|setPage\(|onShareAppMessage|onShareTimeline|url:\s*(?:[a-zA-Z_][\w.]*|`[^`]*\$\{)/.test(row.text));
const result = { source: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim(), pages: rows.length,
  counts: { DELETE: 0, KEEP: rows.filter(row => row.verdict === 'KEEP').length, UNSURE: rows.filter(row => row.verdict === 'UNSURE').length },
  orphans, rows, externalSources,
  limitation: 'References are evidence of retention, not complete navigation dataflow. Dynamic URLs, external historical cards and deployed subscription configuration cannot be proved absent by this local scan; no page is approved for deletion.' };
if (process.argv.includes('--table')) {
  console.log('| 页面 | tabBar | 当前源引用（文件:行） | 判定 |');
  console.log('| --- | --- | --- | --- |');
  for (const row of rows) console.log('| `' + row.page + '` | ' + (row.tabBar ? '是' : '否') + ' | '
    + (row.runtimeEntries.length ? '`' + row.runtimeEntries[0] + '`' : row.references.length ? '`' + row.references[0] + '`' : row.ownShare.length ? '旧分享；`' + row.ownShare[0] + '`' : '无直接源引用；兼容契约/外发历史待确认') + ' | ' + row.verdict + ' |');
} else console.log(JSON.stringify(result, null, 2));
