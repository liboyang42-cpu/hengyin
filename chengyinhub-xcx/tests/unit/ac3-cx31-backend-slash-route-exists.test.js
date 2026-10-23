// LANE AC3 CX-31(路由清单)补测:后端以「/pages/…」「/subpackageX/…」前导斜杠写死的小程序路由必须真实存在。
// 既有 backend-miniprogram-route-exists.test.js 只认不带前导斜杠的 "pages/…" 字面量,
// PR #312 的 CX-31 清单指出它漏掉了 MerchantPrerequisiteServiceImpl / ApiImController / ApiClubController 这几处。
// 唯一允许的例外是已删除的 pages/club/dissolution-blockers:scene-club-edit 在跳转前就地改写
// (行为由 club-dissolution-blockers-inline-contract.test.js 钉住),这里再确认改写入口仍在。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const JAVA_ROOTS = [
  path.join(REPO, 'chengyinhub-system/src/main/java'),
  path.join(REPO, 'chengyinhub-admin/src/main/java'),
]
// 只收「整串就是路由」的字面量:以 " 或 ? 结尾。带扩展名的资源路径(如 /pages/play/audio/…/x.mp3)不是路由。
const SLASH_ROUTE_LITERAL = /"\/((?:pages|subpackage[A-Za-z0-9]*)\/[-A-Za-z0-9_/]+)(?=["?])/g
// 已删除页面 → 由客户端改写承接的出处。新增例外必须写明承接方,不得空口豁免。
const REWRITTEN_BY_CLIENT = {
  'pages/club/dissolution-blockers/index': {
    file: 'components/cy/scene-club-edit/index.js',
    marker: "raw.indexOf('/pages/club/dissolution-blockers/') !== 0",
  },
}

function allRoutes() {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const out = new Set(app.pages)
  for (const sub of app.subPackages || app.subpackages || []) {
    for (const p of sub.pages) out.add(`${sub.root.replace(/\/$/, '')}/${p}`)
  }
  return out
}

function javaFiles() {
  const out = []
  const walk = dir => {
    if (!fs.existsSync(dir)) return
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, e.name)
      if (e.isDirectory()) walk(full)
      else if (e.name.endsWith('.java')) out.push(full)
    }
  }
  JAVA_ROOTS.forEach(walk)
  return out
}

function scan(src, file) {
  const hits = []
  src.split('\n').forEach((line, i) => {
    if (/^\s*(\/\/|\*)/.test(line)) return
    for (const m of line.matchAll(SLASH_ROUTE_LITERAL)) hits.push({ file, line: i + 1, route: m[1] })
  })
  return hits
}

function findBackendSlashRoutes() {
  return javaFiles().flatMap(f => scan(fs.readFileSync(f, 'utf8'), path.relative(REPO, f)))
}

function deadRoutes(hits, routes) {
  return hits.filter(h => !routes.has(h.route) && !REWRITTEN_BY_CLIENT[h.route])
}

test('CX-31 后端前导斜杠的小程序路由都在 app.json 里真实存在(已删页只允许客户端改写承接)', () => {
  assert.deepEqual(deadRoutes(findBackendSlashRoutes(), allRoutes()), [])
})

test('CX-31 扫描确实命中 #312 清单点名的后端路由(不是零命中的橡皮图章)', () => {
  const found = new Set(findBackendSlashRoutes().map(h => h.route))
  for (const route of ['pages/merchant/apply/index', 'pages/merchant/decor/index', 'subpackageB/pages/im/list/index',
    'pages/club/dissolution-blockers/index']) {
    assert.ok(found.has(route), route)
  }
  assert.equal([...found].some(r => r.startsWith('pages/play/audio/')), false, '资源文件路径不应被当成路由')
})

test('CX-31 客户端改写豁免的承接代码仍在,且豁免的页确实已不在 app.json', () => {
  const routes = allRoutes()
  for (const [route, owner] of Object.entries(REWRITTEN_BY_CLIENT)) {
    assert.equal(routes.has(route), false, `${route} 已回到 app.json,应删掉豁免`)
    assert.ok(fs.readFileSync(path.join(ROOT, owner.file), 'utf8').includes(owner.marker), owner.file)
  }
})

test('CX-31 负控:不存在的前导斜杠路由判红,带查询串与资源扩展名的分别按路由/非路由处理', () => {
  const routes = allRoutes()
  const hits = scan([
    'x.put("url", "/pages/merchant/apply/index");',
    'x.put("url", "/subpackageB/pages/im/list/index?tab=1");',
    'x.put("url", "/pages/club/ghost-page/index?id=" + id);',
    'Set.of("/pages/play/audio/merchant-games/rhythm-one.mp3");',
    '// "/pages/commented/out/index"',
  ].join('\n'), 'Fake.java')
  assert.deepEqual(hits.map(h => h.route),
    ['pages/merchant/apply/index', 'subpackageB/pages/im/list/index', 'pages/club/ghost-page/index'])
  assert.deepEqual(deadRoutes(hits, routes).map(h => h.route), ['pages/club/ghost-page/index'])
})
