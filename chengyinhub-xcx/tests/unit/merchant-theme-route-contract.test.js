const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')
const {
  THEMELESS_REDIRECT_SHELLS,
  classifyPageDomain,
  findThemeErrors,
  hasPageEntityMarkup,
  pageRoutes,
  topLevelThemeClasses,
} = require('../../scripts/ui-theme-unit-lint')

const ROOT = path.resolve(__dirname, '../..')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function assertMerchantThemeRoot(source, route) {
  const themes = topLevelThemeClasses(source)
  assert.ok(themes.has('theme-merchant') || themes.has('theme-topic-editor'),
    `${route}:页面顶层必须含 theme-merchant 或 theme-topic-editor`)
  assert.equal(themes.has('theme-dark'), false, `${route}:商家页禁止声明 theme-dark`)
}

test('商家工作域从 app.json 自动发现且页面顶层显式挂商家主题', () => {
  const app = JSON.parse(read('app.json'))
  const merchantRoutes = pageRoutes(app).filter((route) => classifyPageDomain(route) === 'merchant')
  assert.ok(merchantRoutes.length > 0, '必须自动发现至少一个商家域页面')
  merchantRoutes.forEach((route) => {
    const source = read(`${route}.wxml`)
    if (THEMELESS_REDIRECT_SHELLS.has(route)) {
      assert.equal(hasPageEntityMarkup(source), false, `${route}:重定向壳不得伪造主题根节点`)
      return
    }
    assertMerchantThemeRoot(source, route)
  })
  assert.deepEqual(
    merchantRoutes.filter((route) => THEMELESS_REDIRECT_SHELLS.has(route)),
    ['pages/publish/topicadd/topicadd'],
    '无实体主题根的商家域重定向壳必须保持显式、唯一',
  )
})

test('负控:topicadd 重定向壳重新渲染实体节点必须判红', () => {
  const broken = '<view class="theme-topic-editor"></view>'
  assert.equal(hasPageEntityMarkup(broken), true, '重定向壳长回实体 UI 后必须离开无主题豁免')
})

test('负控:从任一商家页面根摘掉 theme-merchant 会被拒绝', () => {
  const app = JSON.parse(read('app.json'))
  const route = pageRoutes(app).find((candidate) => classifyPageDomain(candidate) === 'merchant'
    && topLevelThemeClasses(read(`${candidate}.wxml`)).has('theme-merchant'))
  assert.ok(route, '必须自动发现至少一个 theme-merchant 页面供负控使用')
  const source = read(`${route}.wxml`)
  const broken = source.replace(/\s*theme-merchant\b/, '')
  assert.notEqual(broken, source, '负控必须实际摘掉主题 class')
  assert.throws(() => assertMerchantThemeRoot(broken, route), /页面顶层必须含/)
})

test('我的项目按进入身份二选一:商家浅色走类作用域桥接,玩家和俱乐部保持深色', () => {
  const wxss = read('subpackageA/pages/myproject/index.wxss')
  assert.match(wxss, /^@import\s+['"]\.\.\/\.\.\/\.\.\/style\/merchant-light-scope\.wxss['"];/)
  assert.equal(classifyPageDomain('subpackageA/pages/myproject/index'), 'shared')
  const themes = topLevelThemeClasses(read('subpackageA/pages/myproject/index.wxml'))
  assert.ok(themes.has('theme-merchant') && themes.has('theme-dark'))
})


test('club pricing is shared and declares both themes with scoped light tokens', () => {
  const route = 'pages/topic/pricing/index'
  assert.equal(classifyPageDomain(route), 'shared')
  const themes = topLevelThemeClasses(read(route + '.wxml'))
  assert.ok(themes.has('theme-merchant') && themes.has('theme-dark'))
  const styles = read(route + '.wxss')
  assert.match(styles, /@import ["'].*merchant-light-scope\.wxss["']/)
  assert.doesNotMatch(styles, /@import ["'].*merchant-light\.wxss["']/)
})

test('搜索地图是共享页:双主题通过,缺任一主题仍被真实门禁拒绝', (t) => {
  const route = 'pages/searchmap/index'
  assert.equal(classifyPageDomain(route), 'shared')
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'searchmap-theme-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  fs.mkdirSync(path.join(root, 'pages/searchmap'), { recursive: true })
  fs.mkdirSync(path.join(root, 'pages/publish/topicadd'), { recursive: true })
  fs.writeFileSync(path.join(root, 'app.json'), JSON.stringify({ pages: [route, 'pages/publish/topicadd/topicadd'] }))
  fs.writeFileSync(path.join(root, 'pages/publish/topicadd/topicadd.wxml'), '<!-- redirect shell -->')
  const file = path.join(root, route + '.wxml')
  fs.writeFileSync(file, read(route + '.wxml'))
  assert.deepEqual(findThemeErrors(root), [], '真实双主题页面必须通过')
  for (const theme of ['theme-dark', 'theme-merchant']) {
    fs.writeFileSync(file, `<view class="${theme}"></view>`)
    assert.deepEqual(findThemeErrors(root).map(({ domain, message }) => ({ domain, message })), [
      { domain: 'shared', message: '共享页必须显式声明 theme-dark 与 theme-merchant' },
    ], `只有 ${theme} 必须判红`)
  }
})
