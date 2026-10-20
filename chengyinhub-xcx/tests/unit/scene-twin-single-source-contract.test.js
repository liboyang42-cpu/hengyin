'use strict'

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const { SCENES } = require(path.join(ROOT, 'utils/scene-registry.js'))
const { KNOWN_SCENE_TWINS } = require('../helpers/scene-twin-allowlist.js')
const S = require('../../scripts/lib/xcx-scan.js')

function read(relativePath) {
  return fs.readFileSync(path.join(ROOT, relativePath), 'utf8')
}

function componentWxmlPath(ownerWxmlPath, componentRef) {
  if (typeof componentRef !== 'string' || componentRef.includes('://')) return null
  const withoutLeadingSlash = componentRef.replace(/^\//, '')
  const relative = componentRef.startsWith('/')
    ? withoutLeadingSlash
    : path.posix.join(path.posix.dirname(ownerWxmlPath), withoutLeadingSlash)
  return relative.endsWith('.wxml') ? relative : `${relative}.wxml`
}

function usedComponentTags(source) {
  const withoutComments = source.replace(/<!--[\s\S]*?-->/g, '')
  return new Set(Array.from(withoutComments.matchAll(/<([a-z][\w-]*)\b/g), (match) => match[1]))
}

function usingComponents(wxmlPath) {
  const jsonPath = wxmlPath.replace(/\.wxml$/, '.json')
  const json = JSON.parse(read(jsonPath))
  return {
    ...(JSON.parse(read('app.json')).usingComponents || {}),
    ...(json.usingComponents || {}),
  }
}

function sceneComponentWxmlPath(sceneId) {
  return ['components/cy', 'subpackageMember/components/cy']
    .map((root) => `${root}/scene-${sceneId}/index.wxml`)
    .find((candidate) => fs.existsSync(path.join(ROOT, candidate)))
}

function routeRendersScene(sceneId, route, readSource = read) {
  const targetWxml = sceneComponentWxmlPath(sceneId)
  const queue = [`${route.replace(/^\//, '')}.wxml`]
  const visited = new Set()

  while (queue.length) {
    const currentWxml = queue.shift()
    if (visited.has(currentWxml)) continue
    visited.add(currentWxml)

    const tags = usedComponentTags(readSource(currentWxml))
    const components = usingComponents(currentWxml)
    for (const tag of tags) {
      const childWxml = componentWxmlPath(currentWxml, components[tag])
      if (!childWxml) continue
      if (childWxml === targetWxml) return true
      if (fs.existsSync(path.join(ROOT, childWxml))) queue.push(childWxml)
    }
  }
  return false
}

function twinSceneIds() {
  return Object.entries(SCENES)
    .filter(([sceneId, scene]) => scene.route
      && sceneComponentWxmlPath(sceneId))
    .map(([sceneId]) => sceneId)
    .sort()
}

// 「委托了组件」只是必要条件,不是充分条件 ——
// 页面完全可以既挂着 <cy-scene-x /> 又自绘一整套正文(甚至藏在某个 wx:if 分支里),
// 那正是本门禁要抓的分叉,而只查组件是否出现会对它恒绿。
//
// 所以再要一条「页面自己没有实现内容」的硬证据。判据取两个结构信号,不取行数/字节这类阈值:
//   · 页面 wxml 零正文 wx:for —— 唯一例外是下面严格证明0/1项的身份重挂组件壳
//   · 页面 js 零业务请求     —— 自己取数就是在自己实现正文
// 这两条在全站实测能把两类干净分开(2026-08-08 基线 b8790ca58):
//   9 个已收口的壳:wx:for 全 0、业务请求全 0
//   12 个双份实现:除 share-invite 外全部至少踩中一项
// share-invite 页面本身只有 541 字节、也没委托组件,由白名单登记,不靠本判据。
// 身份切换以key重挂同一真实组件不是自绘列表；只接受当前可证明0/1项的完整外壳。
function isSingleProfileMountShell(route, wxml, js) {
  const source = S.maskWxmlComments(wxml)
  const mounts = source.match(/<cy-profile\s+wx:for="\{\{profileMounts\}\}"\s+wx:key="id"\s+id="profile"\s+viewer="self"\s*\/>/g) || []
  if (mounts.length !== 1 || (source.match(/<cy-profile\b/g) || []).length !== 1) return false
  const pageWxml = `${route.replace(/^\//, '')}.wxml`
  if (componentWxmlPath(pageWxml, usingComponents(pageWxml)['cy-profile'])
      !== 'subpackageMember/components/cy/profile/index.wxml') return false
  const shell = source.replace(mounts[0], '')
  if (/wx:for\s*=/.test(shell)) return false
  const allowedTags = new Set(['page-meta', 'view', 'cy-toast', 'cy-loading-mask', 'cy-modal-host', 'cy-empty', 'tabBar', 'cy-privacy-gate'])
  const text = shell.split('')
  for (const tag of S.scanTags(shell)) {
    if (!allowedTags.has(tag.name)) return false
    text.fill(' ', tag.start, tag.end + 1)
  }
  if (text.join('').trim()) return false // 普通正文即使没有循环也不能伪装成委托壳。
  const code = S.maskJavaScriptComments(js)
  const compact = code.replace(/\s+/g, '')
  const writes = S.extractSetDataFields(code)
  return (code.match(/\bprofileMounts\b/g) || []).length === 3
    && (code.match(/\bprofileMounts\s*:\s*\[\s*\]/g) || []).length === 1
    && compact.includes('this.setData({profileMounts:owner&&token?[{id:this._profileEpoch}]:[]});')
    && writes.unscannable.length === 0
    && writes.fields.filter(field => field.name === 'profileMounts').length === 1
}

function pageImplementsContentItself(route, readSource = read) {
  const base = route.replace(/^\//, '')
  let wxml = ''
  let js = ''
  try { wxml = readSource(`${base}.wxml`) } catch (e) { wxml = '' }
  try { js = readSource(`${base}.js`) } catch (e) { js = '' }
  const rendersList = /wx:for\s*=/.test(wxml) && !isSingleProfileMountShell(route, wxml, js)
  const fetchesBusinessData = /url:\s*['"]\/api\//.test(js)
  return rendersList || fetchesBusinessData
}

function unresolvedTwinSceneIds(readSource = read) {
  return twinSceneIds()
    .filter((sceneId) => {
      const route = SCENES[sceneId].route
      const delegates = routeRendersScene(sceneId, route, readSource)
      // 收口 = 委托了组件 **且** 自己不实现内容。缺哪一条都算分叉。
      return !delegates || pageImplementsContentItself(route, readSource)
    })
    .sort()
}

function assertTwinGate(readSource = read) {
  const unresolved = unresolvedTwinSceneIds(readSource)
  const allowed = Object.keys(KNOWN_SCENE_TWINS).sort()
  const unregistered = unresolved.filter((sceneId) => !allowed.includes(sceneId))
  const stale = allowed.filter((sceneId) => !unresolved.includes(sceneId))

  assert.deepEqual(
    unresolved,
    allowed,
    [
      '页面与同名场景组件出现了未登记分叉，或白名单已经过期。',
      `未登记分叉: ${unregistered.join(', ') || '无'}`,
      `应删除的白名单项: ${stale.join(', ') || '无'}`,
    ].join('\n'),
  )
}

test('注册表里的孪生内容必须收成组件单一真源，或显式登记为待收口债务', () => {
  assertTwinGate()
})

test('迁移到 Member 分包的同名场景仍在孪生分母，既有券码债务不因搬目录消失', () => {
  const ids = twinSceneIds()
  for (const sceneId of ['member-order-detail', 'member-order-history', 'play-activity-detail', 'roam-poi-detail', 'qr-coupon']) {
    assert.equal(sceneComponentWxmlPath(sceneId), `subpackageMember/components/cy/scene-${sceneId}/index.wxml`)
    assert.ok(ids.includes(sceneId), `${sceneId} 搬目录后不得漏扫`)
  }
  assert.ok(unresolvedTwinSceneIds().includes('qr-coupon'), '优惠券页的既有双份实现仍须由原白名单约束')
})

test('负控:迁移后的活动薄壳移除真实组件委托，孪生门禁仍必须判红', () => {
  const sceneId = 'play-activity-detail'
  const route = SCENES[sceneId].route
  const pageWxml = `${route.replace(/^\//, '')}.wxml`
  const source = read(pageWxml)
  assert.equal(routeRendersScene(sceneId, route), true, '迁移后仍必须读取真实组件委托')
  const mutated = source.replace(
    /<cy-scene-play-activity-detail\b[^>]*\/>/,
    '<!-- <cy-scene-play-activity-detail /> --><view>活动页不再委托组件</view>',
  )
  assert.notEqual(mutated, source, '变异必须摘掉真实活动组件，而不是错过锚点')
  const readMutated = (relativePath) => relativePath === pageWxml ? mutated : read(relativePath)
  assert.equal(routeRendersScene(sceneId, route, readMutated), false, '注释中的旧标签不能充当真实委托')
  assert.throws(() => assertTwinGate(readMutated), /未登记分叉: play-activity-detail/)
})

test('已知双份白名单的每一项都写明现状原因和退出意图', () => {
  for (const [sceneId, debt] of Object.entries(KNOWN_SCENE_TWINS)) {
    assert.equal(typeof debt.reason, 'string', `${sceneId} 缺少 reason`)
    assert.ok(debt.reason.trim().length >= 12, `${sceneId} 的 reason 过于含糊`)
    assert.equal(typeof debt.exitIntent, 'string', `${sceneId} 缺少 exitIntent`)
    assert.match(debt.exitIntent, /删除此项/, `${sceneId} 必须说明何时删除白名单项`)
  }
})

test('负控:merchant-profit 路由页一旦退回自绘，孪生门禁必须判红', () => {
  const sceneId = 'merchant-profit'
  const route = SCENES[sceneId].route
  const pageWxml = `${route.replace(/^\//, '')}.wxml`
  const source = read(pageWxml)

  assert.equal(routeRendersScene(sceneId, route), true, '负控基线失效:merchant-profit 已不是组件薄壳')
  const mutated = source.replace(
    /<cy-scene-merchant-profit\b[^>]*\/>/,
    '<view class="profit-list">页面重新自绘分润内容</view>',
  )
  assert.notEqual(mutated, source, '负控变异锚点失效:页面里找不到 merchant-profit 场景组件')

  const readMutated = (relativePath) => relativePath === pageWxml ? mutated : read(relativePath)
  assert.equal(routeRendersScene(sceneId, route, readMutated), false, '扫描器必须看见组件委托已被移除')
  assert.throws(() => assertTwinGate(readMutated), /未登记分叉: merchant-profit/)
})

// ★ 这条才是真正的回潮形态,也是初版判据抓不到的那个:
//   页面**不删组件标签**,只是又长出一套自绘正文。
//   只查「组件是否出现」的实现对它恒绿 —— 恒绿的门禁比没有门禁更坏,因为它给人已设防的错觉。
test('负控:页面保留组件标签但自绘一套正文(双份回潮),孪生门禁必须判红', () => {
  const sceneId = 'merchant-profit'
  const route = SCENES[sceneId].route
  const pageWxml = `${route.replace(/^\//, '')}.wxml`
  const source = read(pageWxml)

  assert.equal(routeRendersScene(sceneId, route), true, '负控基线失效:merchant-profit 已不是组件薄壳')
  // 组件标签原样留着,只在旁边补一段自绘列表 —— 这正是分叉的真实长相
  const mutated = source.replace(
    /(<cy-scene-merchant-profit\b[^>]*\/>)/,
    '$1\n  <view class="profit-list"><view wx:for="{{rows}}" wx:key="id">{{item.amount}}</view></view>',
  )
  assert.notEqual(mutated, source, '负控变异锚点失效:页面里找不到 merchant-profit 场景组件')

  const readMutated = (relativePath) => relativePath === pageWxml ? mutated : read(relativePath)
  // 委托仍在 —— 初版判据到这里就放绿了
  assert.equal(routeRendersScene(sceneId, route, readMutated), true, '本负控要的就是「委托还在」这个前提')
  // 但页面自己开始渲列表 ⇒ 必须判红
  assert.equal(pageImplementsContentItself(route, readMutated), true, '自绘正文必须被识别出来')
  assert.throws(() => assertTwinGate(readMutated), /未登记分叉: merchant-profit/)
})

// 另一半:页面自己去取业务数据,同样算自绘正文(不是所有分叉都会用 wx:for)。
test('负控:页面自己发业务请求(不经组件)也必须判红', () => {
  const sceneId = 'merchant-profit'
  const route = SCENES[sceneId].route
  const pageJs = `${route.replace(/^\//, '')}.js`
  const source = read(pageJs)
  const mutated = `${source}\n// 变异:页面自己取数\nconst _probe = { url: '/api/coop/finance' }\n`
  assert.notEqual(mutated, source, '负控变异未生效')

  const readMutated = (relativePath) => relativePath === pageJs ? mutated : read(relativePath)
  assert.equal(pageImplementsContentItself(route, readMutated), true, '页面自取数必须被识别出来')
  assert.throws(() => assertTwinGate(readMutated), /未登记分叉: merchant-profit/)
})


test('single-instance identity mount delegates profile and share scene without drawing page content', () => {
  const route = SCENES['share-invite'].route
  assert.equal(routeRendersScene('share-invite', route), true)
  assert.equal(pageImplementsContentItself(route), false)
  assertTwinGate()
})

test('single-instance delegation must reject real body, API and unbounded mount mutations', () => {
  const route = SCENES['share-invite'].route
  const base = route.replace(/^\//, '')
  const js = read(`${base}.js`), wxml = read(`${base}.wxml`)
  const mount = '<cy-profile wx:for="{{profileMounts}}" wx:key="id" id="profile" viewer="self" />'
  assert.ok(wxml.includes(mount))
  const variants = [
    [`${base}.wxml`, wxml + '\n<view wx:for="{{rows}}">{{item.name}}</view>'],
    [`${base}.wxml`, wxml + '\n<view>页面自绘了第二套正文</view>'],
    [`${base}.wxml`, wxml + '\n<image src="{{privatePicture}}" />'],
    [`${base}.js`, js + "\nconst request = { url: '/api/coop/finance' }"],
    [`${base}.js`, js.replace('[{ id: this._profileEpoch }]', '[{ id: this._profileEpoch }, { id: 99 }]')],
    [`${base}.js`, js.replace('[{ id: this._profileEpoch }]', 'rows')],
    [`${base}.js`, js + "\nthis.setData({ profileMounts: rows })"],
    [`${base}.wxml`, wxml.replace(mount, `<!-- ${mount} -->`)],
    [`${base}.wxml`, wxml.replace(mount, `${mount}\n${mount}`)],
    [`${base}.wxml`, wxml.replace(mount, mount.replace('cy-profile ', 'view '))],
  ]
  // WXML允许属性名与等号间空白；非法mount与空白属性同时出现仍必须拒绝。
  const spaced = wxml.replace('wx:for="{{profileMounts}}"', 'wx:for = "{{profileMounts}}"')
  const unbounded = js.replace('[{ id: this._profileEpoch }]', 'rows')
  assert.notEqual(spaced, wxml); assert.notEqual(unbounded, js)
  const bothMutated = file => file === `${base}.wxml` ? spaced : file === `${base}.js` ? unbounded : read(file)
  assert.equal(pageImplementsContentItself(route, bothMutated), true)
  assert.throws(() => assertTwinGate(bothMutated), /未登记分叉: share-invite/)
  for (const [file, mutated] of variants) {
    assert.notEqual(mutated, file.endsWith('.js') ? js : wxml, '变异必须实际修改源')
    const readMutated = name => name === file ? mutated : read(name)
    assert.throws(() => assertTwinGate(readMutated), /未登记分叉: share-invite/)
  }
})
