'use strict';
// f-merchant(M-35 / G-10 收尾,2026-11-11):商家可达页不得落到写死的玩家暗根。
// 规则(业主):玩家页深色、商家页浅色。页面域由 scripts/ui-theme-unit-lint.js 的 DOMAIN_RULES 单一真源划分,
// 本测试补上它管不到的一层:从商家页(pages/merchant/**、pages/coop/**)能跳到的页面,
// 若仍归玩家域(根节点只有 theme-dark),必须是下面写明原因的「刻意保持玩家域」目标,否则红。
// 新增一个商家入口跳到玩家页时:要么把目标页做成共享页(根节点按视角二选一),要么在这里写清原因。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const lint = require(path.join(ROOT, 'scripts/ui-theme-unit-lint.js'));

const PLAYER_BY_DESIGN = {
  'pages/index/index': '切回玩家首页(商家退出商家视角/分享落地),本身就是玩家页',
  'pages/member/index/index': '「我的」宿主页;商家视角由 cy-profile 根节点挂 theme-merchant',
  'pages/userinfo/userinfo': '公开主页跟随被看者身份(cy-profile「观看者 RBAC 不参与被看者主题」),玩家主页就是深色',
  'pages/topic/index/index': '主题域,保持主题自己的强制配色',
  'subpackageRoam/citystamp/index': '城市签仪式页,漫游游戏设计域',
  'subpackageRoam/poi-detail/index': '据点历史链接落点(漫游域),商家主页只做重定向',
  'pages/club/governance/index': '俱乐部治理是主理人(俱乐部域)页面,商家兼主理人时以主理人身份进入',
  'pages/club/settlement/index': '俱乐部分润是俱乐部域页面,商家合作结算只把俱乐部来源转过去',
};

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(dir, entry.name);
    if (entry.isDirectory()) return walk(target);
    return /\.(js|wxml)$/.test(entry.name) ? [target] : [];
  });
}

function merchantSources() {
  return ['pages/merchant', 'pages/coop'].flatMap((dir) => walk(path.join(ROOT, dir)))
    .map((file) => ({ file: path.relative(ROOT, file), source: fs.readFileSync(file, 'utf8') }));
}

function darkTargets(sources, classify) {
  const appJson = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'));
  const routes = new Set(lint.pageRoutes(appJson));
  const found = new Map();
  sources.forEach(({ file, source }) => {
    for (const match of source.matchAll(/['"`]\/?((?:pages|subpackage\w+)\/[\w\-/]+)/g)) {
      let target = match[1].replace(/\/$/, '');
      if (!routes.has(target)) {
        if (routes.has(target + '/index')) target += '/index';
        else continue;
      }
      if (/^pages\/(?:play|roam)\//.test(target)) continue; // 用户自维护的游玩/漫游设计域
      if (classify(target) !== 'player') continue;
      if (!found.has(target)) found.set(target, new Set());
      found.get(target).add(file);
    }
  });
  return found;
}

function unexplained(found) {
  return [...found.keys()].filter((target) => !PLAYER_BY_DESIGN[target]).sort();
}

test('商家页能跳到的玩家域页面都写明了刻意保持深色的原因', () => {
  const found = darkTargets(merchantSources(), lint.classifyPageDomain);
  const missing = unexplained(found);
  assert.deepEqual(missing, [], '这些页面从商家入口进去仍是暗根:\n' + missing.map((t) => t + ' <- ' + [...found.get(t)].join(', ')).join('\n'));
});

test('原因清单不留死条目:每一条都仍是真实的商家可达玩家页', () => {
  const found = darkTargets(merchantSources(), lint.classifyPageDomain);
  const stale = Object.keys(PLAYER_BY_DESIGN).filter((target) => !found.has(target));
  assert.deepEqual(stale, [], '入口已撤或页面已改共享域,请删掉对应原因');
});

test('本轮改成共享页的商家可达页:根节点按视角二选一并引入商家浅色作用域', () => {
  ['pages/search2/index', 'pages/search2/result/index', 'pages/square/detail/index'].forEach((route) => {
    assert.equal(lint.classifyPageDomain(route), 'shared', route + ' 应归共享域');
    const wxml = fs.readFileSync(path.join(ROOT, route + '.wxml'), 'utf8');
    assert.match(wxml, /\{\{merchantView \? 'theme-merchant' : 'theme-dark'\}\}/, route);
    const wxss = fs.readFileSync(path.join(ROOT, route + '.wxss'), 'utf8');
    assert.match(wxss, /merchant-light-scope\.wxss/, route);
    const js = fs.readFileSync(path.join(ROOT, route + '.js'), 'utf8');
    assert.match(js, /isMerchantView\(\)/, route);
    assert.match(js, /merchantTheme\.merchantPageShow\(\)/, route);
    assert.match(js, /merchantTheme\.merchantPageRestore\(\)/, route);
  });
  assert.deepEqual(lint.findThemeErrors(ROOT), [], '页面根主题门禁');
});

test('负控:商家页新加一个跳玩家暗根页的入口必须变红', () => {
  const sources = merchantSources().concat([{ file: 'pages/merchant/fake.js', source: "wx.navigateTo({ url: '/pages/mylike/mylike' })" }]);
  const asPlayer = (route) => (route === 'pages/mylike/mylike' ? 'player' : lint.classifyPageDomain(route));
  assert.deepEqual(unexplained(darkTargets(sources, asPlayer)), ['pages/mylike/mylike']);
  // search2 若退回玩家域,同样变红
  const backToDark = (route) => (route === 'pages/search2/index' ? 'player' : lint.classifyPageDomain(route));
  assert.deepEqual(unexplained(darkTargets(merchantSources(), backToDark)), ['pages/search2/index']);
});
