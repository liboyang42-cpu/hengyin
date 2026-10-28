const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const { PRESETS, LEGACY_ALIAS, normalizeAtmosphere, atmosphereClass } = require('../../utils/chapter-atmosphere.js')

test('章节配色只有五个受控预设（2026-09-06 换成黑蓝红黄白纯色），未知值回退默认', () => {
  assert.deepEqual(PRESETS.map((item) => item.value), ['DEFAULT', 'BLUE', 'RED', 'YELLOW', 'WHITE'])
  assert.deepEqual(PRESETS.map((item) => item.label), ['黑', '蓝', '红', '黄', '白'])
  assert.equal(normalizeAtmosphere('blue'), 'BLUE')
  assert.equal(normalizeAtmosphere('#ff00aa'), 'DEFAULT')
  assert.equal(atmosphereClass('WHITE'), 'atmosphere--white')
})

// ★ 换取值域时最容易出事的不是新值,是**老数据**:库里存着 NIGHT/ARCHIVE/MOSS/NEON。
//   别名表一旦漏掉某个,那些章节会静默变成默认黑 —— 作者选过的颜色没了,而且零报错。
test('存量四档必须落到最接近的新档，不能整片塌回默认', () => {
  assert.equal(normalizeAtmosphere('NIGHT'), 'BLUE')
  assert.equal(normalizeAtmosphere('ARCHIVE'), 'YELLOW')
  assert.equal(normalizeAtmosphere('NEON'), 'RED')
  // 苔野没有对应色,落回默认是有意的(见 utils/chapter-atmosphere.js 的说明)
  assert.equal(normalizeAtmosphere('MOSS'), 'DEFAULT')
  assert.deepEqual(Object.keys(LEGACY_ALIAS).sort(), ['ARCHIVE', 'MOSS', 'NEON', 'NIGHT'])
})

// 前后端各有一份别名表,不一致会让同一条数据在发布器与玩家端显示成两个颜色。
test('前后端别名表必须逐条一致', () => {
  const java = fs.readFileSync(path.resolve(__dirname,
    '../../../chengyinhub-system/src/main/java/com/chengyinhub/business/util/ChapterAtmospherePreset.java'), 'utf8')
  for (const [legacy, target] of Object.entries(LEGACY_ALIAS)) {
    const expected = target === 'DEFAULT' ? 'DEFAULT' : `"${target}"`
    assert.match(java, new RegExp(`alias\\.put\\("${legacy}",\\s*${expected.replace(/"/g, '"')}\\)`),
      `后端别名表缺 ${legacy} → ${target}`)
  }
  for (const value of PRESETS.map((p) => p.value)) {
    if (value === 'DEFAULT') continue
    assert.match(java, new RegExp(`"${value}"`), `后端取值域缺 ${value}`)
  }
})

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

// 注册真 Page 并执行实际编辑/投影/播放方法；不调用 onLoad 或创建音频设备。
function loadPage(rel, source) {
  const Module = require('node:module')
  const previous = { Page: global.Page, getApp: global.getApp, wx: global.wx }
  let definition
  global.Page = (value) => { definition = value }
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, getUserID: () => 1 })
  global.wx = { getStorageSync: () => '', getSystemInfoSync: () => ({ windowWidth: 375 }),
    getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }) }
  try {
    const absolute = path.join(ROOT, rel)
    const loaded = new Module(absolute, module)
    loaded.filename = absolute
    loaded.paths = Module._nodeModulePaths(path.dirname(absolute))
    loaded._compile(source || read(rel), absolute)
  } finally {
    Object.assign(global, previous)
  }
  assert.ok(definition, rel + ' 未注册 Page')
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)) })
  page.setData = (patch) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.')
      let target = page.data
      while (parts.length > 1) target = target[parts.shift()]
      target[parts[0]] = value
    }
  }
  return page
}

function assertAtmosphereConsumers(playSource, playWxml) {
  const editor = loadPage('pages/publish/fabu/index.js')
  const page = loadPage('pages/play/index.js', playSource)
  const puzzleTag = (playWxml || read('pages/play/index.wxml')).match(/<view\b[^>]*class="gp2 gp2--sheet[^>]*>/)
  assert.ok(puzzleTag, '旧谜题层必须保留实际容器')
  const puzzleClass = puzzleTag[0].match(/class="([^"]*)"/)[1]
  const expectedScenes = { NIGHT: 'hush', ARCHIVE: 'afternoon', NEON: 'grown', WHITE: 'office' }
  for (const [selected, expectedScene] of Object.entries(expectedScenes)) {
    editor.onChapterAtmosphereSelect({ currentTarget: { dataset: { value: selected } } })
    const preset = editor.data.chapterForm.atmospherePreset
    assert.equal(preset, normalizeAtmosphere(selected), '编辑器要写归一后的预设')
    page.data.chapter = page.buildChapter({ chapters: [{ name: '章节', description: '正文', atmospherePreset: preset }] }, [])
    page.data.nodes = []
    page.openChapterFull()
    assert.equal(page.data.chapterFull, true)
    assert.equal(page._sceneTarget, expectedScene, '新故事层开章场景必须读作者预设')
    page._syncStoryScene()
    assert.equal(page._sceneTarget, expectedScene, '滚动后的换景仍消费同一预设')
    const actualClass = puzzleClass.replace(/\{\{([^}]+)\}\}/g,
      (_, expression) => new Function('chapter', 'return (' + expression + ')')(page.data.chapter))
    assert.ok(actualClass.split(/\s+/).includes(atmosphereClass(preset)), '旧谜题层必须实际绑定同一章节氛围')
  }
}

test('编辑器预设经真章节投影，被新故事换景和旧谜题层消费', () => {
  const choice = read('pages/publish/fabu/index.wxml').match(/<view\b[^>]*class="chapter-atmosphere__item[^>]*>/)
  assert.ok(choice, '必须存在真实章节氛围选项')
  assert.match(choice[0], /chapterForm\.atmospherePreset/)
  assert.match(choice[0], /data-value="\{\{item\.value\}\}"/)
  assert.match(choice[0], /bindtap="onChapterAtmosphereSelect"/)
  assertAtmosphereConsumers()
})

test('章节氛围负控：新故事忽略作者预设或旧谜题删绑定都必须判红', () => {
  const source = read('pages/play/index.js')
  const changed = source.replace('chap0.atmosphereClass || chap0.atmospherePreset', "'DEFAULT'")
  assert.notEqual(changed, source, '开章负控锚点失效')
  assert.throws(() => assertAtmosphereConsumers(changed), assert.AssertionError)
  const wxml = read('pages/play/index.wxml')
  const detached = wxml.replace('gp2 gp2--sheet {{chapter.atmosphereClass}}', 'gp2 gp2--sheet')
  assert.notEqual(detached, wxml, '谜题层负控锚点失效')
  assert.throws(() => assertAtmosphereConsumers(undefined, detached), assert.AssertionError)
})
