/**
 * Figma 220:761 Roadmap 与 220:1121 Infinity 结构试点的模拟器截图与读回。
 * 只证明渲染和前端映射；服务端状态权威性由独立接口合同负责。
 */
const fs = require('fs')
const path = require('path')

const projectRoot = path.resolve(__dirname, '..')
const outputFile = path.join(projectRoot, 'docs/screenshots/play-roadmap-figma-2026-08-23.png')
const storyOutputFile = path.join(projectRoot, 'docs/screenshots/play-infinity-story-figma-2026-08-23.png')
process.env.UI_AUDIT_OUT = process.env.UI_AUDIT_OUT || path.join('/tmp', 'chengyin-play-roadmap-figma-0823')

const { frameDefect, launchFixed, wait } = require('./_infra')

const fixture = {
  loading: false,
  screen: 'intro',
  emptyTip: '',
  emptyKind: '',
  privacyGateShow: false,
  introTab: 'start',
  mode: 1,
  modeLabel: '城市定向',
  reducedMotion: true,
  chapter: { idxLabel: '第一章', name: '顺着旧街走到黄昏' },
  nextNode: { nodeId: 1002 },
  nodes: [
    { nodeId: 1001, num: 1, name: '街角绿地补给点', gameTitle: '城市颜色采样', done: true },
    { nodeId: 1002, num: 2, name: '梧桐下的橱窗', gameTitle: '寻找门牌线索', done: false },
    { nodeId: 1003, num: 3, name: '隐蔽展墙', description: '找到被海报覆盖的墙', done: false },
    { nodeId: 1004, num: 4, name: '黄昏观景口', gameTitle: '完成黄昏选择题', done: false, locked: true },
  ],
}

const storyFixture = {
  loading: false,
  screen: '',
  allDone: false,
  emptyTip: '',
  emptyKind: '',
  privacyGateShow: false,
  mode: 1,
  reducedMotion: true,
  chapterCount: 1,
  doneCount: 1,
  gameCount: 4,
  chapterFull: false,
  chapter: {
    idxLabel: '第一章',
    name: '顺着旧街走到黄昏',
    cover: '/images/route_city_cover.png',
    atmosphereClass: 'atmosphere--default',
    blocks: [
      { type: 'text', content: '从第一块路牌开始，城市把线索留在脚步之间。' },
      { type: 'text', content: '向下滚动，读到当前已经解锁的故事。' },
      { type: 'node', nodeId: 1002 },
      { type: 'text', content: '这一段必须等第二站完成后才出现。' },
    ],
  },
  nodes: [
    { nodeId: 1001, done: true, story: '你在街角记录下第一种颜色。' },
    { nodeId: 1002, done: false, story: '' },
  ],
}

function readRoadmap(miniProgram) {
  return miniProgram.evaluate(function readRoadmapState() {
    var pages = getCurrentPages()
    var page = pages[pages.length - 1]
    var component = page && page.selectComponent('#playRoadmap')
    return component ? component.data.model : null
  })
}

async function captureScreenshot(miniProgram, file) {
  try {
    await miniProgram.screenshot({ path: file })
  } catch (_) {
    const base64 = await miniProgram.screenshot()
    if (!base64) throw new Error('截图桥未返回图像数据')
    fs.writeFileSync(file, Buffer.from(base64, 'base64'))
  }
}

;(async () => {
  fs.mkdirSync(path.dirname(outputFile), { recursive: true })
  fs.mkdirSync(process.env.UI_AUDIT_OUT, { recursive: true })
  console.log('ROADMAP_SHOT_STAGE launch')
  const miniProgram = await launchFixed()
  const consoleErrors = []
  miniProgram.on('console', (message) => {
    if (message && message.type === 'error') consoleErrors.push(String((message.args || [])[0] || 'console error'))
  })
  miniProgram.on('exception', (error) => consoleErrors.push(`exception: ${String(error && error.message || error)}`))

  try {
    console.log('ROADMAP_SHOT_STAGE home')
    await miniProgram.reLaunch('/pages/index/index')
    await wait(800)
    console.log('ROADMAP_SHOT_STAGE play')
    const page = await miniProgram.reLaunch('/pages/play/index?mock=city')
    await page.waitFor(3000)
    if (!page || page.path !== 'pages/play/index') {
      throw new Error(`玩法页未打开：${page && page.path}；console=${consoleErrors.slice(0, 3).join(' | ') || 'none'}`)
    }
    await page.waitFor(2500)
    console.log('ROADMAP_SHOT_STAGE fixture')
    await page.setData(fixture)
    await wait(800)

    const rendered = await readRoadmap(miniProgram)
    if (!rendered || rendered.items.length !== 4) throw new Error('Roadmap 未按四节点 fixture 渲染')
    if (rendered.items.filter((item) => item.active).length !== 1) throw new Error('Roadmap 下一站高亮不是唯一值')

    await page.setData({ nextNode: { nodeId: 9999 } })
    await wait(100)
    const negative = await readRoadmap(miniProgram)
    if (negative.items.some((item) => item.active)) throw new Error('负控失败：未知 nextNode 仍伪造高亮')

    console.log('ROADMAP_SHOT_STAGE story-fixture')
    await page.setData(storyFixture)
    await page.callMethod('openChapterFull')
    await wait(400)
    if (!await page.data('chapterFull')) throw new Error('Infinity 结构试点未打开章节滚动态')
    const storyParas = await page.data('chapterParas')
    if (!Array.isArray(storyParas) || storyParas.length !== 3) {
      throw new Error('Infinity 结构试点未保持 blocks 顺序或未完成节点截断')
    }
    if (await page.$('.pbar')) throw new Error('Infinity 结构试点仍露出旧运行底栏')
    await page.callMethod('closeChapterFull')

    await page.setData(fixture)
    await page.setData({ nextNode: fixture.nextNode })
    await wait(400)
    console.log('ROADMAP_SHOT_STAGE screenshot')
    await captureScreenshot(miniProgram, outputFile)
    const defect = frameDefect(outputFile, { width: 624, height: 1352 })
    if (defect) throw new Error(`截图帧不合格：${defect}`)

    await page.setData(storyFixture)
    await page.callMethod('openChapterFull')
    await wait(400)
    console.log('ROADMAP_SHOT_STAGE story-screenshot')
    await captureScreenshot(miniProgram, storyOutputFile)
    const storyDefect = frameDefect(storyOutputFile, { width: 624, height: 1352 })
    if (storyDefect) throw new Error(`Infinity 截图帧不合格：${storyDefect}`)
    if (consoleErrors.length) throw new Error(`控制台错误：${consoleErrors.slice(0, 3).join(' | ')}`)

    console.log(`ROADMAP_SHOT_PASS ${outputFile}`)
    console.log(`INFINITY_SHOT_PASS ${storyOutputFile}`)
  } finally {
    miniProgram.disconnect()
  }
})().catch((error) => {
  console.error('ROADMAP_SHOT_FAIL', error && error.stack ? error.stack : error)
  process.exit(1)
})
