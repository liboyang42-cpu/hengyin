// 原生 chrome 换肤是页面级职责,分润组件化后这条仍盯页面壳。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const PAGES = [
  'pages/merchant/marketing/index.js',
  'pages/merchant/relation/index.js',
  'pages/merchant/citynode/index.js',
  'pages/coop/finance/index.js',
  'pages/coop/withdraw/index.js',
]
const read = relativePath => fs.readFileSync(path.join(ROOT, relativePath), 'utf8')

function assertLifecycleRestores(source, pagePath, lifecycle, options = {}) {
  const old = { getApp: global.getApp, wx: global.wx }
  const app = { globalData: {}, getUserRole: () => options.role,
    sendRequest() { throw new Error('theme lifecycle must not send a request') } }
  const navigation = [], backgrounds = []
  const order = []
  let continuationEpoch = 0
  global.getApp = () => app
  global.wx = {
    setNavigationBarColor: options => { order.push('restore'); navigation.push(options) },
    setBackgroundColor: options => backgrounds.push(options),
  }
  try {
    const requirePage = createRequire(path.join(ROOT, pagePath))
    let definition
    vm.runInNewContext(source, {
      getApp: global.getApp, wx: global.wx, require: requirePage,
      Page: config => { definition = config }, clearTimeout() {},
    }, { filename: pagePath })
    assert.equal(typeof definition[lifecycle], 'function', `${pagePath} must define ${lifecycle}`)
    // Exercise the actual page hook and shared theme helper, including hooks that invalidate async callbacks.
    requirePage('../../../utils/merchant-theme.js').merchantPageShow()
    navigation.length = backgrounds.length = 0
    order.length = 0
    const instance = { _requestEpoch: 0 }
    Object.defineProperty(instance, '_loginContinuationEpoch', {
      get() { return continuationEpoch },
      set(value) { continuationEpoch = value; order.push('invalidate') },
    })
    definition[lifecycle].call(instance)
    if (options.requireInvalidation) {
      assert.equal(continuationEpoch, 1, 'hide must invalidate the original login continuation')
      assert.deepEqual(order, ['invalidate', 'restore'], 'invalidate before restoring the player chrome')
    }
    assert.equal(app.globalData.theme, 'dark', `${pagePath} must restore player dark on ${lifecycle}`)
    assert.deepEqual(navigation, [{ frontColor: '#ffffff', backgroundColor: '#020104' }])
    assert.deepEqual(backgrounds, [{
      backgroundColor: '#020104', backgroundColorTop: '#020104', backgroundColorBottom: '#020104',
    }])
  } finally {
    if (old.getApp === undefined) delete global.getApp; else global.getApp = old.getApp
    if (old.wx === undefined) delete global.wx; else global.wx = old.wx
  }
}

function assertMerchantThemeLifecycle(source, pagePath) {
  assert.match(source, /require\(['"]\.\.\/\.\.\/\.\.\/utils\/merchant-theme\.js['"]\)/,
    `${pagePath} must use the shared merchant theme helper`)
  assert.match(source, /onShow\(\)\s*\{[\s\S]*?merchantTheme\.merchantPageShow\(\);/,
    `${pagePath} must switch the native chrome to merchant light on show`)
  assertLifecycleRestores(source, pagePath, 'onHide')
  assert.match(source, /onUnload\(\)\s*\{[\s\S]*?merchantTheme\.merchantPageRestore\(\);[\s\S]*?\}/,
    `${pagePath} must restore player dark chrome on unload`)
  assertLifecycleRestores(source, pagePath, 'onUnload')
}

test('merchant top-level and discover pages keep native chrome aligned with their light surface', () => {
  PAGES.forEach(pagePath => assertMerchantThemeLifecycle(read(pagePath), pagePath))
})

test('negative control: removing a lifecycle restore is rejected', () => {
  const pagePath = PAGES[0]
  const source = read(pagePath)
  const broken = source.replace(
    'onHide() { merchantTheme.merchantPageRestore(); },',
    'onHide() {},'
  )
  assert.notEqual(broken, source, 'negative control must mutate the source')
  assert.throws(() => assertMerchantThemeLifecycle(broken, pagePath), assert.AssertionError)
})

test('negative control: an unreachable citynode restore cannot satisfy the lifecycle contract', () => {
  const pagePath = 'pages/merchant/citynode/index.js'
  const source = read(pagePath)
  const broken = source.replace('merchantTheme.merchantPageRestore();', 'if (false) merchantTheme.merchantPageRestore();')
  assert.notEqual(broken, source, 'negative control must mutate the real citynode hide hook')
  assert.throws(() => assertMerchantThemeLifecycle(broken, pagePath), assert.AssertionError)
})

// 2026-10-29:广场列表页(pages/square/list)已删,「广场恒为玩家深色域」用例与负控随页撤走。
