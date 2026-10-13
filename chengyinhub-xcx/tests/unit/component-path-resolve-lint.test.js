'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { execFileSync } = require('node:child_process')
const { lint } = require('../../scripts/component-path-resolve-lint.js')

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'cy-component-path-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const write = (rel, source) => {
    const target = path.join(root, rel)
    fs.mkdirSync(path.dirname(target), { recursive: true })
    fs.writeFileSync(target, source)
  }
  const json = (rel, config) => write(rel, JSON.stringify(config))
  const component = (base, config = { component: true }) => {
    json(base + '.json', config)
    write(base + '.js', 'Component({})')
    write(base + '.wxml', '<view />')
  }
  json('app.json', { pages: ['pages/p/index'], subPackages: [
    { root: 'subpackageA', pages: [] }, { root: 'subpackageB', pages: [] },
  ] })
  component('components/loading/index')
  component('subpackageA/components/inner/index')
  component('subpackageB/components/inner/index')
  const host = (extra = {}, rel = 'pages/p/index.json') => json(rel, {
    usingComponents: { 'cy-inner': '/subpackageA/components/inner/index' },
    componentPlaceholder: { 'cy-inner': 'view' },
    ...extra,
  })
  return { root, write, json, component, host }
}

const clean = (result) => {
  assert.deepEqual(result.bad, [])
  assert.deepEqual(result.cross, [])
  assert.deepEqual(result.crossRefs, [])
  assert.deepEqual(result.crossPkg, [])
  assert.deepEqual(result.missingRefs, [])
}

test('主包同 tag 的 view 占位允许真实已注册分包组件异步加载', (t) => {
  const f = fixture(t)
  f.host()
  const result = lint(f.root)
  clean(result)
  assert.equal(result.asyncComponents.length, 1)
})

test('相对路径目标与宿主注册的真实主包自定义占位可解析', (t) => {
  const f = fixture(t)
  f.host({
    usingComponents: { 'cy-inner': '../../subpackageA/components/inner/index', 'cy-loading': '../../components/loading/index' },
    componentPlaceholder: { 'cy-inner': 'cy-loading' },
  })
  const result = lint(f.root)
  clean(result)
  assert.equal(result.asyncComponents.length, 1)
})

test('另一分包引用真实组件必须同样走合法占位，分包引主包仍可同步', (t) => {
  const f = fixture(t)
  f.host({}, 'subpackageB/pages/p/index.json')
  f.json('subpackageA/pages/p/index.json', { usingComponents: { 'cy-loading': '/components/loading/index' } })
  clean(lint(f.root))
  f.host({ componentPlaceholder: {} }, 'subpackageB/pages/p/index.json')
  assert.equal(lint(f.root).cross.length, 1, '缺占位不能因来自另一分包而放行')
})

test('负控:删除占位或改错 carrier tag 必须红，不能拿另一 tag 的 view 冒充', (t) => {
  const f = fixture(t)
  for (const placeholders of [{}, { 'wrong-tag': 'view' }]) {
    f.host({ componentPlaceholder: placeholders })
    assert.equal(lint(f.root).cross.length, 1)
  }
  assert.equal(lint(f.root).bad.length, 1, '未登记的占位 carrier tag 也必须显式报错')
})

test('负控:占位不能掩盖目标文件不存在', (t) => {
  const f = fixture(t)
  f.host({ usingComponents: { 'cy-inner': '/subpackageA/components/missing/index' } })
  const result = lint(f.root)
  assert.equal(result.bad.length, 1)
  assert.equal(result.asyncComponents.length, 0)
})

test('负控:磁盘存在但目标包没在 app.json 注册仍不能凭 view 放行', (t) => {
  const f = fixture(t)
  f.component('subpackageUnregistered/components/inner/index')
  f.host({ usingComponents: { 'cy-inner': '/subpackageUnregistered/components/inner/index' } })
  assert.equal(lint(f.root).cross.length, 1)
})

test('负控:目标只有 JSON 或 component 非 true 均不是真实异步组件', (t) => {
  const f = fixture(t)
  f.host()
  f.json('subpackageA/components/inner/index.json', { component: false })
  assert.equal(lint(f.root).cross.length, 1)
  f.json('subpackageA/components/inner/index.json', { component: true })
  fs.unlinkSync(path.join(f.root, 'subpackageA/components/inner/index.wxml'))
  assert.equal(lint(f.root).cross.length, 1)
})

test('负控:自定义占位 tag 未注册或目标文件不存在均必须红', (t) => {
  const f = fixture(t)
  f.host({ componentPlaceholder: { 'cy-inner': 'cy-loading' } })
  assert.equal(lint(f.root).cross.length, 1)
  f.host({ usingComponents: { 'cy-inner': '/subpackageA/components/inner/index', 'cy-loading': '/components/missing/index' },
    componentPlaceholder: { 'cy-inner': 'cy-loading' } })
  const result = lint(f.root)
  assert.equal(result.cross.length, 1)
  assert.equal(result.bad.length, 1)
})

test('负控:占位自身是假组件或来自分包均不能用于主包恢复', (t) => {
  const f = fixture(t)
  f.host({ usingComponents: { 'cy-inner': '/subpackageA/components/inner/index', 'cy-loading': '/components/loading/index' },
    componentPlaceholder: { 'cy-inner': 'cy-loading' } })
  f.json('components/loading/index.json', { component: false })
  assert.equal(lint(f.root).cross.length, 1)
  f.host({ usingComponents: { 'cy-inner': '/subpackageA/components/inner/index', 'cy-loading': '/subpackageB/components/inner/index' },
    componentPlaceholder: { 'cy-inner': 'cy-loading', 'cy-loading': 'view' } })
  assert.equal(lint(f.root).cross.length, 1, '占位组件自己异步声明也不能冒充立即可用的主包组件')
})

test('负控:合法异步组件配置不豁免图片、WXSS、同步 require 或分包之间资源', (t) => {
  const f = fixture(t)
  f.host()
  f.write('subpackageA/images/a.png', 'png')
  f.write('subpackageB/images/b.png', 'png')
  f.write('subpackageA/helper.js', 'module.exports = {}')
  f.write('pages/p/index.wxml', '<image src="/subpackageA/images/a.png" />')
  f.write('pages/p/index.wxss', '.a{background:url("/subpackageA/images/a.png")}')
  f.write('pages/p/index.js', "const helper = require('/subpackageA/helper.js')")
  f.write('subpackageA/pages/p/index.wxml', '<image src="/subpackageB/images/b.png" />')
  const result = lint(f.root)
  assert.deepEqual(result.cross, [], '组件异步配置本身合法')
  assert.equal(result.crossRefs.length, 3, '资源和同步模块仍各自被拦截')
  assert.equal(result.crossPkg.length, 1, '另一分包的图片仍被拦截')
})

test('CLI 原全部搬家、资源、模块与注释负控及新增异步正反控仍通过自证', () => {
  const output = execFileSync(process.execPath, [path.resolve(__dirname, '../../scripts/component-path-resolve-lint.js'), '--selftest'], { encoding: 'utf8' })
  assert.match(output, /自证通过/)
})
