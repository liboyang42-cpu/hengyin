/* R14 旅程检定玩家端(2026-09-18)
 *
 * 入口不在 playKit:PlayEncounterServiceImpl#allowedActions 在节点旅程块 check.enabled
 * 为真且「已到店 && 未锁定」时,把字符串 'check' 追加进 enc.allowedActions;题面在 enc.check。
 * 判定与回执走 /api/play/check/{roll,reroll,settle}(ApiPlayCheckController),服务端幂等。
 *
 * 三条钉死:
 *   ① 没有 allowedActions 里的 'check' 或没有 checkId,就不算有检定(不弹空壳);
 *   ② 先出结果后出文案:roll 回执只有骰子/成败,text/failCostLabel 只在 settled 回执里;
 *   ③ 失败也推进:结算前后都能退出,这一屏不拦节点完成。
 */
const assert = require('node:assert/strict')
const { test, beforeEach } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const PLAY = '../../pages/play/index.js'
const COMPONENT = 'pages/play/components/playkit-journey-check/index.js'

const { pickJourneyCheck, checkReceiptView } = require('../../pages/play/utils/playkit-view.js')

const ENCOUNTER = {
  nodeId: 501,
  allowedActions: ['check', 'qa'],
  check: {
    checkId: 'c-77-1', skill: '观察', tier: 'medium',
    mods: [
      { when: { var: 'tag.rain', op: 'HAS_TAG' }, value: 2, label: '带着伞', held: true },
      { when: { var: 'sys.luck', op: 'GTE', value: 3 }, value: 1, label: '运气不错', held: false },
    ],
    advantage: true, disadvantage: false,
  },
}
const ROLL_RECEIPT = {
  sessionId: '99', nodeId: '501', actionId: 'check:501:c-77-1:roll',
  checkId: 'c-77-1', tier: 'medium', dc: 13,
  mods: [{ label: '带着伞', value: 2, applied: true }],
  advantage: true, disadvantage: false,
  dice: [11], kept: 11, total: 13, success: true, nat: null,
  rerolled: false, settled: false, text: null, failCostLabel: null,
  hp: 8, luck: 2, exhausted: false, stateVersion: 4,
}
const SETTLE_RECEIPT = Object.assign({}, ROLL_RECEIPT, {
  actionId: 'check:501:c-77-1:settle', settled: true, text: '你从伞骨的划痕里读出了方向。', stateVersion: 5,
})

// ===== 纯函数 =====

test('★入口只认 allowedActions 里的 check + checkId;题面只搬公开面', () => {
  const kit = pickJourneyCheck(ENCOUNTER)
  assert.equal(kit.checkId, 'c-77-1')
  assert.equal(kit.skill, '观察')
  assert.equal(kit.tier, 'medium')
  assert.deepEqual(kit.mods, [
    { label: '带着伞', value: 2, held: true },
    { label: '运气不错', value: 1, held: false },
  ])
  assert.equal(kit.advantage, true)
  assert.equal(kit.disadvantage, false)

  // 入口不在 check 段本身:allowedActions 没有它就不算这一拍有检定
  assert.equal(pickJourneyCheck({ check: ENCOUNTER.check, allowedActions: [] }), null)
  assert.equal(pickJourneyCheck({ allowedActions: ['check'] }), null, '没有 checkId 不算检定')
  assert.equal(pickJourneyCheck(null), null)
  // 不下发的字段一个都不许出现在视图里(服务端也不给)
  assert.equal(JSON.stringify(kit).includes('successText'), false)
  assert.equal(JSON.stringify(kit).includes('failCostTag'), false)
})

test('★先结果后文案:roll 回执没有文案,settled 回执才有', () => {
  const before = checkReceiptView(ROLL_RECEIPT)
  assert.equal(before.settled, false)
  assert.equal(before.text, '')
  assert.equal(before.failCostLabel, '')
  assert.deepEqual(before.dice, [11])
  assert.equal(before.total, 13)
  assert.equal(before.dc, 13)
  assert.equal(before.success, true)

  const after = checkReceiptView(SETTLE_RECEIPT)
  assert.equal(after.settled, true)
  assert.equal(after.text, '你从伞骨的划痕里读出了方向。')
  assert.equal(after.mods[0].applied, true)
})

// ===== 组件纯算法 =====

function loadComponent(rel) {
  const abs = path.join(ROOT, rel)
  const prev = global.Component
  const prevBehavior = global.Behavior
  let captured = null
  global.Component = (o) => { captured = o }
  global.Behavior = (config) => config
  try {
    delete require.cache[require.resolve(abs)]
    require(abs)
  } finally {
    global.Component = prev
    global.Behavior = prevBehavior
  }
  assert.ok(captured && captured.methods, rel + ' 必须暴露纯算法出口供单测')
  return captured
}

test('重掷只在「掷过、没结算、没重掷、还有幸运」时可见', () => {
  const can = loadComponent(COMPONENT).methods._canReroll
  assert.equal(can(checkReceiptView(ROLL_RECEIPT)), true, 'roll 完还有 2 点幸运，可以重掷')
  assert.equal(can(null), false, '还没掷就没有重掷')
  assert.equal(can(checkReceiptView(Object.assign({}, ROLL_RECEIPT, { settled: true }))), false)
  assert.equal(can(checkReceiptView(Object.assign({}, ROLL_RECEIPT, { rerolled: true }))), false)
  assert.equal(can(checkReceiptView(Object.assign({}, ROLL_RECEIPT, { luck: 0 }))), false)
})

test('★失败也推进:结算屏的退出入口不受成败影响(wxml 里没有按 success 包住的动作)', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-journey-check/index.wxml'), 'utf8')
  // 结算屏(else 分支)的「继续」必须无条件存在;成败只该换文案颜色,不该锁住出口
  assert.ok(/wx:else bindtap="onClose"/.test(wxml), '结算后必须有可点的「继续」')
  const successGuarded = wxml.split('\n').filter((line) => /bindtap="onClose"/.test(line) && /success/.test(line))
  assert.deepEqual(successGuarded, [], '退出不许挂在 success 条件上 —— 失败也要能推进')
})

// ===== 页面接线 =====

let pageConfig
let requests
let responder = null
let storage = new Map()

const APP = {
  globalData: {},
  getUserID: () => 1,
  sendRequest: (options) => { if (responder) responder(options) },
}
global.getApp = () => APP

global.wx = {
  getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
  getMenuButtonBoundingClientRect: () => ({ top: 20, height: 32 }),
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  showToast: () => {},
  createSelectorQuery: () => ({
    selectAll: () => ({ boundingClientRect: () => {} }),
    select: () => ({ boundingClientRect: () => {}, scrollOffset: () => {} }),
    exec: () => {},
  }),
}
global.Page = config => { pageConfig = config }

function setAtPath(target, rawPath, value) {
  const parts = rawPath.replace(/\[(\d+)\]/g, '$1').split('.')
  let cursor = target
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (cursor[parts[i]] == null) cursor[parts[i]] = {}
    cursor = cursor[parts[i]]
  }
  cursor[parts[parts.length - 1]] = value
}

function makePage() {
  const page = Object.assign({}, pageConfig)
  page.data = JSON.parse(JSON.stringify(pageConfig.data))
  page.data.topicId = '77'
  page._routeState = { sessionId: '99' }
  page.setData = function (patch) {
    Object.keys(patch).forEach(key => setAtPath(page.data, key, patch[key]))
  }
  page.selectComponent = () => null
  return page
}

const flush = () => new Promise(resolve => setImmediate(resolve))
const postData = (options) => (options.data && typeof options.data === 'object') ? options.data : JSON.parse(options.data || '{}')

beforeEach(() => {
  pageConfig = null
  storage = new Map()
  requests = []
  responder = null
  delete require.cache[require.resolve(PLAY)]
  require(PLAY)
})

test('★探测:encounter 把 check 放进 allowedActions 才弹;没到店/已完成/预览都不弹', async () => {
  const page = makePage()
  responder = (options) => {
    requests.push(options)
    options.success({ code: 200, data: ENCOUNTER })
  }

  // 探测由打开节点卡触发(onSheetOpen):回包落地时节点卡仍开着同一节点,该节点未完成
  page.data.nodes = [{ nodeId: 501, done: false }]
  page.data.sheet.show = true
  page.data.sheet.node = page.data.nodes[0]
  page._probeJourneyCheck({ nodeId: 501, done: false })
  await flush()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/play/encounter')
  assert.equal(requests[0].method, 'GET')
  assert.equal(page.data.journeyCheck.show, true)
  assert.equal(page.data.journeyCheck.checkId, 'c-77-1')
  assert.equal(page.data.journeyCheck.skill, '观察')

  const quiet = makePage()
  requests = []
  quiet._probeJourneyCheck({ nodeId: 501, done: true })
  await flush()
  assert.equal(requests.length, 0, '已完成的节点不再探检定')

  const preview = makePage()
  preview.data.isPreview = true
  requests = []
  preview._probeJourneyCheck({ nodeId: 501, done: false })
  await flush()
  assert.equal(requests.length, 0, '预览态不探(预览没有真实会话)')
})

test('未到店时 allowedActions 里没有 check —— 探测到也不弹空壳', async () => {
  const page = makePage()
  responder = (options) => {
    requests.push(options)
    options.success({ code: 200, data: { allowedActions: [], check: ENCOUNTER.check } })
  }
  page._probeJourneyCheck({ nodeId: 501, done: false })
  await flush()
  assert.equal(page.data.journeyCheck.show, false)
})

test('★掷 → 结算:两个请求都打到对的端点、带对的字段;文案只在结算后进视图', async () => {
  const page = makePage()
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501, receipt: null }, pickJourneyCheck(ENCOUNTER))
  const seen = []
  responder = (options) => {
    requests.push(options)
    seen.push(options.url)
    options.success(options.url === '/api/play/check/roll' ? { code: 200, data: ROLL_RECEIPT } : { code: 200, data: SETTLE_RECEIPT })
  }

  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  await flush()
  assert.deepEqual(seen, ['/api/play/check/roll'])
  assert.deepEqual(postData(requests[0]), { topicId: '77', activityId: '0', sessionId: '99', nodeId: '501', checkId: 'c-77-1' })
  assert.equal(page.data.journeyCheck.receipt.settled, false)
  assert.equal(page.data.journeyCheck.receipt.text, '', 'roll 回执不该有文案')

  page.onJourneyCheckAction({ detail: { action: 'settle' } })
  await flush()
  assert.deepEqual(seen, ['/api/play/check/roll', '/api/play/check/settle'])
  assert.equal(page.data.journeyCheck.receipt.settled, true)
  assert.equal(page.data.journeyCheck.receipt.text, '你从伞骨的划痕里读出了方向。')
})

test('★已结算原局以GET读取原动作回执，绝不调用settle作为恢复', async () => {
  const page = makePage()
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501, receipt: null }, pickJourneyCheck(ENCOUNTER))
  const seen = []
  responder = (options) => {
    requests.push(options)
    seen.push(options.url)
    if (options.url === '/api/play/check/roll') options.success({ code: 500, msg: '该检定已结算' })
    else options.success({ code: 200, data: { ...SETTLE_RECEIPT, actionId: ROLL_RECEIPT.actionId } })
  }

  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  await flush()
  assert.equal(page.data.journeyCheck.unknown, true)
  await page.onJourneyCheckAction({ detail: { action: 'recover' } })
  assert.deepEqual(seen, ['/api/play/check/roll', '/api/play/check/receipt'])
  assert.equal(requests[1].method, 'GET')
  assert.equal(requests[1].data.action, 'roll')
  assert.equal(page.data.journeyCheck.receipt.settled, true)
  assert.equal(page.data.journeyCheck.receipt.success, true)
})

test('★失败也推进:结算失败清屏不锁死,退出随时可用', async () => {
  const page = makePage()
  const failed = Object.assign({}, SETTLE_RECEIPT, {
    success: false, text: '泥水灌进鞋里。', failCostLabel: '湿透的鞋', hp: 4,
  })
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501, receipt: null }, pickJourneyCheck(ENCOUNTER))
  responder = (options) => {
    requests.push(options)
    options.success(options.url === '/api/play/check/roll'
      ? { code: 200, data: Object.assign({}, ROLL_RECEIPT, { success: false }) }
      : { code: 200, data: failed })
  }

  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  await flush()
  page.onJourneyCheckAction({ detail: { action: 'settle' } })
  await flush()
  assert.equal(page.data.journeyCheck.receipt.success, false)
  assert.equal(page.data.journeyCheck.receipt.failCostLabel, '湿透的鞋')

  page.closeJourneyCheck()
  assert.equal(page.data.journeyCheck.show, false, '失败结算后照样能关掉这一屏')
  // 关掉检查屏不碰节点/任务屏任何状态:检定不是通关闸
  assert.equal(page.data.sheet.show, false)
})

test('重复点掷骰不会并发出第二个请求(在途锁)', async () => {
  const page = makePage()
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501, receipt: null }, pickJourneyCheck(ENCOUNTER))
  responder = (options) => { requests.push(options); /* 不回包,保持 in-flight */ }
  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  await flush()
  assert.equal(requests.length, 1, '在途时重复点不该再发一次')
})

test('★重掷走 /reroll,回执里 rerolled=true 进视图', async () => {
  const page = makePage()
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501, receipt: checkReceiptView(ROLL_RECEIPT) }, pickJourneyCheck(ENCOUNTER))
  const seen = []
  responder = (options) => {
    requests.push(options)
    seen.push(options.url)
    options.success({ code: 200, data: Object.assign({}, ROLL_RECEIPT, { actionId: 'check:501:c-77-1:reroll', rerolled: true, kept: 18, total: 18 }) })
  }
  page.onJourneyCheckAction({ detail: { action: 'reroll' } })
  await flush()
  assert.deepEqual(seen, ['/api/play/check/reroll'])
  assert.equal(page.data.journeyCheck.receipt.rerolled, true)
  assert.equal(page.data.journeyCheck.receipt.kept, 18)
})


// 9/24 用户裁决的检定内嵌必须经过真实 encounter 入口，不能只改编辑器默认表。
test('检定搬服务端呈现方式，旧响应缺字段仍全屏', () => {
  assert.equal(pickJourneyCheck(ENCOUNTER).present, 'fullscreen')
  assert.equal(pickJourneyCheck({ ...ENCOUNTER, check: { ...ENCOUNTER.check, present: 'inline' } }).present, 'inline')
  assert.equal(pickJourneyCheck({ ...ENCOUNTER, check: { ...ENCOUNTER.check, present: 'fullscreen' } }).present, 'fullscreen')
})

test('真实检定探测回包为内嵌时，关闭节点卡并在当前节点故事流展示一块', async () => {
  const page = makePage()
  page.data.sheet.show = true
  page.data.chapter = { blocks: [{ type: 'text', content: '开场' }, { type: 'node', nodeId: 501 }, { type: 'text', content: '未来剧情' }] }
  page.data.nodes = [{ nodeId: 501, done: false }]
  page.data.sheet.node = page.data.nodes[0]   // 节点卡开着的就是被探测的节点
  page.measureChapter = () => {}
  page.playChapterAudio = () => {}
  responder = options => options.success({ code: 200, data: { ...ENCOUNTER, check: { ...ENCOUNTER.check, present: 'inline' } } })
  page._probeJourneyCheck(page.data.nodes[0])
  await flush()
  assert.equal(page.data.sheet.show, false)
  assert.equal(page.data.journeyCheck.show, true)
  assert.equal(page.data.chapterFull, true)
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text', 'journeyCheck'])
  const beforeText = page.data.chapterParas[0].text
  assert.equal(beforeText, '开场')
  page._storyTop = 177
  page.playChapterAudio = () => { throw new Error('关闭内嵌检定不能重播章节音频') }
  page.openChapterFull = () => { throw new Error('关闭检定不能重铺整章或重置故事位置') }
  page.closeJourneyCheck()
  assert.equal(page.data.chapterParas[0].text, beforeText)
  assert.equal(page._storyTop, 177)
  assert.deepEqual(page.data.chapterParas.map(p => p.i), [0])
  assert.equal(page.data.journeyCheck.show, false)
  assert.deepEqual(page.data.chapterParas.map(p => p.kind), ['text'])
  assert.equal(page.data.nodes[0].done, false, '关闭检定不冒充节点完成')
})

test('内嵌检定不越过前置节点，也不在已完成节点回放', () => {
  const page = makePage()
  page.measureChapter = () => {}
  page.playChapterAudio = () => {}
  page.data.chapter = { blocks: [{ type: 'node', nodeId: 500 }, { type: 'node', nodeId: 501 }] }
  page.data.nodes = [{ nodeId: 500, done: false }, { nodeId: 501, done: false }]
  page.data.journeyCheck = { ...pickJourneyCheck(ENCOUNTER), present: 'inline', show: true, nodeId: 501 }
  page.openChapterFull()
  assert.equal(page.data.chapterParas.some(p => p.kind === 'journeyCheck'), false)
  page.data.nodes.forEach(n => { n.done = true })
  page.openChapterFull()
  assert.equal(page.data.chapterParas.some(p => p.kind === 'journeyCheck'), false)
})

test('内嵌检定有真实 play-stage 壳和同一动作回执，独立全屏入口排除内嵌', () => {
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/play/index.wxml'), 'utf8')
  const shell = fs.readFileSync(path.join(ROOT, 'pages/play/components/playkit-journey-check/index.wxml'), 'utf8')
  assert.match(wxml, /item\.kind === 'journeyCheck'[\s\S]*?class="chfull__kit"[\s\S]*?cy-playkit-journey-check[\s\S]*?bind:action="onJourneyCheckAction"/)
  assert.match(wxml, /cy-playkit-journey-check show="\{\{journeyCheck\.show && journeyCheck\.present !== 'inline'\}\}"/)
  assert.match(shell, /<cy-play-stage/)
  assert.equal(shell.includes('<cy-sheet'), false)
})


test('检定探测失败保持静默，实际检定失败仍交给原错误处理', async () => {
  const page = makePage()
  const visibleErrors = []
  responder = (options) => {
    requests.push(options)
    if (!options.silentError) visibleErrors.push('这一局还没开始')
    options.success({ code: 500, msg: '这一局还没开始' })
  }
  page._probeJourneyCheck({ nodeId: 501, done: false })
  await flush()
  assert.deepEqual(visibleErrors, [], '可选探测不能误报整个活动未开始')
  assert.equal(page.data.journeyCheck.show, false)
  page.data.journeyCheck = Object.assign({ show: true, nodeId: 501 }, pickJourneyCheck(ENCOUNTER))
  page.onJourneyCheckAction({ detail: { action: 'roll' } })
  await flush()
  assert.equal(requests[1].url, '/api/play/check/roll')
  assert.equal(!!requests[1].silentError, false, '真实操作继续显示错误')
})
