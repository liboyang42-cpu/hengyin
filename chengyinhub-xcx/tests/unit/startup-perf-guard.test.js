'use strict';
/* startup-perf-guard —— 启动性能护栏(2026-11-21 fix/startup-perf-1121)
 *
 * ① 主包体积棘轮:主包里的静态资源(图片 / 音频等非代码文件)不得超过基线;主包源码总字节
 *    不得超过基线 + 32KB 余量。口径见 scripts/lib/package-graph.js 与 scripts/baselines/package-size.json。
 * ② 已知热路径不得整表 setData:
 *    - 发布编辑器 _storyCommand(每敲一个字都走)只回传当前章 formData.chapters[i],不回传整个章节数组;
 *    - 游玩页带领场 4s 轮询:lead 快照没变就不再 setData lead。
 * ③ 首页 → 主题详情、主题详情 → 报名所在分包的预下载规则存在,且不再从首页预下载用不到的模板详情分包。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const graph = require('../../scripts/lib/package-graph.js');
const baseline = require('../../scripts/baselines/package-size.json');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const CODE = /\.(js|json|wxml|wxss|wxs)$/;

function mainBytes(g) {
  let assets = 0;
  let raw = 0;
  for (const [file, info] of g.files) {
    if (info.owner !== 'MAIN') continue;
    raw += info.size;
    if (!CODE.test(file)) assets += info.size;
  }
  return { assets, raw };
}

test('主包静态资源与源码总字节不超过棘轮基线', () => {
  const g = graph.build();
  const { assets, raw } = mainBytes(g);
  assert.equal(raw, g.raw.MAIN);
  assert.ok(assets <= baseline.main.assetCeilingBytes,
    `主包静态资源 ${assets}B 超过基线 ${baseline.main.assetCeilingBytes}B:非首屏图片/音频请放进使用它的分包或走服务端静态目录`);
  assert.ok(raw <= baseline.main.rawCeilingBytes,
    `主包源码 ${raw}B 超过基线 ${baseline.main.rawCeilingBytes}B:只被某个分包用到的模块/组件请下沉到该分包(node scripts/lib/package-graph.js --movable)`);
  assert.ok(baseline.main.assetCeilingBytes <= baseline.main.assetBytes + 0, '资源棘轮不留余量');
  assert.ok(baseline.main.rawCeilingBytes - baseline.main.rawBytes <= 32 * 1024, '源码棘轮余量不超过 32KB');
});

test('依赖图:主包入口可达、本次下沉的模块只在各自分包被用到', () => {
  const g = graph.build();
  assert.ok(g.reach.get('app.js').has('MAIN'));
  assert.ok(g.reach.get('pages/index/index.js').has('MAIN'));
  const moved = {
    'pages/publish/utils/answer-text.js': 'pages/publish',
    'pages/topic/utils/chapter-count.js': 'pages/topic',
  };
  for (const [file, pkg] of Object.entries(moved)) {
    assert.equal(g.files.get(file).owner, pkg, file);
    assert.deepEqual([...(g.reach.get(file) || [])], [pkg], file + ' 应只被 ' + pkg + ' 走到');
  }
  for (const gone of ['utils/answer-text.js',
    'utils/chapter-count.js', 'behaviors/page-scroll-lock.js', 'pages/play/orient-lifecycle.wxml',
    'pages/play/components/mascot/index.js']) {
    assert.equal(fs.existsSync(path.join(ROOT, gone)), false, gone);
  }
});

function loadFabuPage() {
  let config;
  global.getApp = () => ({ globalData: {}, getUserID: () => 42 });
  global.wx = { getStorageSync: () => '', removeStorageSync: () => {}, getSystemInfoSync: () => ({ windowWidth: 375 }) };
  global.Page = (c) => { config = c; };
  delete require.cache[require.resolve('../../pages/publish/fabu/index.js')];
  require('../../pages/publish/fabu/index.js');
  return config;
}

function storyPage(config) {
  const chapter = (name) => ({ _localId: name, name, nodes: [], blocks: [{ key: name + '-t', type: 'text', content: '开场' }] });
  const page = Object.assign({}, config, {
    data: { formData: { chapters: [chapter('c1'), chapter('c2'), chapter('c3')], imgArr: '' }, pendingMaterials: [], storyEditor: { chapterIndex: 1 } },
    patches: [],
  });
  page.setData = function (patch) {
    this.patches.push(patch);
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let cursor = this.data;
      parts.slice(0, -1).forEach((p) => { if (cursor[p] == null) cursor[p] = {}; cursor = cursor[p]; });
      cursor[parts[parts.length - 1]] = value;
    });
  };
  page.updateAllStatistics = () => {};
  return page;
}

test('发布编辑器 _storyCommand 只回传当前章路径,不整表回传章节数组', () => {
  const page = storyPage(loadFabuPage());
  const others = [page.data.formData.chapters[0], page.data.formData.chapters[2]];
  page._storyCommand({ type: 'splitParagraphs', blockKey: 'c2-t', content: '开场改了', blockKeys: [] });
  const patch = page.patches[page.patches.length - 1];
  assert.equal(Object.prototype.hasOwnProperty.call(patch, 'formData.chapters'), false, '不得整表回传 formData.chapters');
  assert.equal(Object.prototype.hasOwnProperty.call(patch, 'formData'), false);
  assert.ok(patch['formData.chapters[1]'], '应只回传当前章 formData.chapters[1]');
  assert.equal(page.data.formData.chapters[1].blocks[0].content, '开场改了');
  assert.equal(page.data.formData.chapters[0], others[0], '其他章节对象原样保留');
  assert.equal(page.data.formData.chapters[2], others[1]);
});

test('游玩页带领场轮询:lead 快照没变不再 setData lead', () => {
  const { sameLeadSnapshot } = require('../../pages/play/utils/lead-chapter-hold.js');
  const lead = { exists: true, status: 2, members: [{ id: 1, name: 'a' }], skipRequests: [] };
  assert.equal(sameLeadSnapshot(lead, JSON.parse(JSON.stringify(lead))), true);
  assert.equal(sameLeadSnapshot(lead, Object.assign({}, lead, { status: 3 })), false);
  assert.equal(sameLeadSnapshot(null, lead), false);
  const src = read('pages/play/index.js');
  const poll = src.slice(src.indexOf('  loadTeamProgress() {'), src.indexOf('  loadTeamProgress() {') + 6000);
  assert.match(poll, /if \(!sameLeadSnapshot\(prevLead, nextLead\)\) \{\s*that\.setData\(\{ lead: nextLead,/);
  assert.doesNotMatch(poll, /that\.setData\(\{\s*lead: \{/, '轮询不得无条件整份回传 lead');
});

test('预下载:首页拉主题详情分包、主题详情拉报名所在分包,不再从首页拉用不到的模板详情分包', () => {
  const app = JSON.parse(read('app.json'));
  assert.equal(app.lazyCodeLoading, 'requiredComponents');
  const rule = app.preloadRule;
  assert.ok(rule['pages/index/index'].packages.includes('subpackageTopic'));
  assert.equal(rule['pages/index/index'].packages.includes('subpackageTemplateDetail'), false);
  assert.deepEqual(rule['pages/topic/index/index'], { network: 'wifi', packages: ['subpackageMember', 'subpackagePlaykit'] }); // 2026-11-21 play 分包拆分:玩法组件分包一并预下载
  const names = new Set(app.subPackages.map((s) => s.name || s.root));
  for (const [page, r] of Object.entries(rule)) for (const pkg of r.packages) assert.ok(names.has(pkg), page + ' → ' + pkg);
  // 首页真实会去的分包(主题详情 / 报名)
  assert.match(read('pages/index/index.js') + read('pages/index/index.wxml'), /\/pages\/topic\/index\/index/);
  assert.match(read('pages/topic/index/index.js'), /\/subpackageMember\/signup\/index/);
});

test('负控:资源越线 / 无条件 lead 回传都会判红', () => {
  // 资源越线
  const g = graph.build();
  const fake = new Map(g.files);
  fake.set('images/__huge__.png', { size: 200 * 1024, owner: 'MAIN' });
  assert.ok(mainBytes({ files: fake }).assets > baseline.main.assetCeilingBytes);
  // 无条件回传 lead
  assert.match('    that.setData({\n        lead: {', /that\.setData\(\{\s*lead: \{/);
});
