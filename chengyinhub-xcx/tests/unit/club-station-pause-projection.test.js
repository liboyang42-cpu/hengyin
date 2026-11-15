const test = require('node:test')
const assert = require('node:assert/strict')
const { normalizeRouteState, applyRouteStateToNodes, pauseNotice, resolveNextRouteNode } = require('../../pages/play/utils/play-route-state')

const pause = { reasonCode: 'ONSITE', reason: '设备检修', resumeEta: '2026-11-16 06:00:00' }
const state = paused => ({ routeMode: 'BRANCH_GRAPH', sessionId: 109, status: 'ACTIVE', version: 0,
  currentNodeId: 1, recommendedNodeId: 1,
  nodeStates: { 1: paused ? 'DISCOVERED_LOCKED' : 'PLAYABLE', 2: 'PLAYABLE', 3: 'COMPLETED', 4: 'HIDDEN' },
  lockReasons: paused ? { 1: '设备检修，预计恢复：2026-11-16 06:00:00' } : {}, nodePauses: paused ? { 1: pause } : {} })
const nodes = [1, 2, 3, 4].map(nodeId => ({ nodeId, name: 'TEST ' + nodeId, done: nodeId === 3 }))

test('same run/version ACTIVE -> PAUSED -> ACTIVE replaces ephemeral pause and lock data', () => {
  const active = applyRouteStateToNodes(nodes, state(false))
  const stopped = applyRouteStateToNodes(active, state(true))
  assert.equal(stopped[0].locked, true)
  assert.equal(stopped[0].playable, false)
  assert.equal(stopped[0].paused, true)
  assert.deepEqual(stopped[0].pause, pause)
  assert.match(stopped[0].lockReason, /设备检修/)
  assert.equal(stopped[0].lockReason, '设备检修，预计恢复：2026-11-16 06:00:00')
  assert.equal((stopped[0].lockReason.match(/预计恢复/g) || []).length, 1)
  assert.equal(resolveNextRouteNode(stopped, state(true)), null)
  const resumedState = state(false)
  delete resumedState.nodePauses
  delete resumedState.lockReasons
  const resumed = applyRouteStateToNodes(stopped, resumedState)
  assert.equal(resumed[0].paused, false)
  assert.equal(resumed[0].pause, null)
  assert.equal(resumed[0].locked, false)
  assert.equal(resumed[0].lockReason, '')
  assert.equal(resumed[0].playable, true)
  assert.deepEqual(resumed.slice(1), active.slice(1))
  assert.equal(resumed[0].done, active[0].done)
  const otherLock = { ...resumedState, nodeStates: { ...resumedState.nodeStates, 1: 'DISCOVERED_LOCKED' } }
  const lockedAgain = applyRouteStateToNodes(stopped, otherLock)[0]
  assert.equal(lockedAgain.paused, false)
  assert.equal(lockedAgain.pause, null)
  assert.equal(lockedAgain.lockReason, '完成当前路线后解锁')
  assert.equal(normalizeRouteState(state(true)).sessionId, '109')
  assert.equal(normalizeRouteState(state(true)).version, 0)
})

test('normalizer keeps only optional plain pause fields and handles old/invalid payloads', () => {
  assert.deepEqual(normalizeRouteState(null).nodePauses, {})
  assert.deepEqual(normalizeRouteState({ nodePauses: [] }).nodePauses, {})
  assert.deepEqual(normalizeRouteState({ nodePauses: { 1: { ...pause, privateTask: 'not projected' }, 2: null, 3: [], nope: pause } }).nodePauses, { 1: pause })
  assert.deepEqual(normalizeRouteState({ nodePauses: { 1: { reason: 7, resumeEta: {} } } }).nodePauses[1], { reasonCode: '', reason: '', resumeEta: '' })
  assert.equal(pauseNotice(null, '本站暂不可用'), '本站暂不可用')
})

test('one paused station does not block another, erase completed history, or expose hidden nodes', () => {
  const raw = state(true)
  raw.nodePauses[3] = pause
  raw.nodePauses[4] = pause
  const rows = applyRouteStateToNodes(nodes, raw)
  assert.deepEqual(rows.map(n => n.nodeId), [1, 2, 3])
  assert.equal(rows[1].playable, true)
  assert.notEqual(rows[1].paused, true)
  assert.equal(rows[2].done, true)
  assert.equal(rows[2].locked, false)
  assert.notEqual(rows[2].paused, true)
  assert.equal(rows[2].pause, undefined)
  assert.equal(raw.status, 'ACTIVE')
  assert.deepEqual(nodes.map(n => n.done), [false, false, true, false])
})

test('linear/self-play rows and unrelated prerequisite locks retain their existing behavior', () => {
  const linear = [{ nodeId: 1, locked: true, lockReason: '先完成上一站' }]
  assert.deepEqual(applyRouteStateToNodes(linear, { routeMode: 'LINEAR' }), linear)
  const prerequisite = state(false)
  prerequisite.nodeStates[1] = 'DISCOVERED_LOCKED'
  prerequisite.lockReasons = { 1: '先完成上一站' }
  const row = applyRouteStateToNodes(nodes, prerequisite)[0]
  assert.equal(row.lockReason, '先完成上一站')
  assert.notEqual(row.paused, true)
})

test('a shared branch without the new station metadata keeps its existing pause flags', () => {
  const shared = [{ nodeId: 1, paused: true, runtimeStatus: 'PAUSED', lockReason: '原共享局状态' }]
  const raw = state(false)
  raw.nodeStates[1] = 'DISCOVERED_LOCKED'
  delete raw.nodePauses
  const projected = applyRouteStateToNodes(shared, raw)[0]
  assert.equal(projected.paused, true)
  assert.equal(projected.runtimeStatus, 'PAUSED')
  assert.equal(projected.lockReason, '原共享局状态')
  assert.equal(raw.status, 'ACTIVE')
})

test('real page maps normal nodes and refreshes the same version, showing the authoritative arrangement once', () => {
  const path = require.resolve('../../pages/play/index.js')
  const notices = []
  let definition
  global.getApp = () => ({ sendRequest() { throw new Error('No business API in this unit test') } })
  global.Page = value => { definition = value }
  global.getCurrentPages = () => []
  global.wx = { nextTick: fn => fn(), showToast: options => notices.push(options.title), vibrateShort() {} }
  delete require.cache[path]
  require(path)
  assert.ok(definition)
  const page = Object.assign({}, definition)
  page.data = { ...definition.data, mode: 1 }
  const raw = { nodeId: 1, name: 'TEST', done: false, locked: true, paused: true, pause,
    lockReason: '设备检修，预计恢复：2026-11-16 06:00:00' }
  const mapped = page.normNode(raw, 0, 1)
  assert.equal(mapped.paused, true)
  assert.deepEqual(mapped.pause, pause)
  assert.equal(mapped.lockReason, raw.lockReason)
  assert.equal(page._lockedTip(mapped), true)
  assert.deepEqual(notices, [raw.lockReason])
  assert.ok(!notices[0].includes('改去其他节点'))

  page.data.nodes = nodes.map((node, index) => page.normNode(node, index, 1))
  page._routeNodeCatalog = page.data.nodes.slice()
  page._routeState = normalizeRouteState(state(false))
  page._versionedRouteState = page._routeState
  page.setData = (patch, callback) => { Object.assign(page.data, patch); if (callback) callback() }
  page.rebuild = () => {}
  page.refreshThemeBackpack = () => {}
  assert.equal(page._applyRouteState(state(true)), true)
  assert.equal(page.data.nodes[0].paused, true)
  assert.equal(page.data.nodes[0].locked, true)
  assert.equal(page.data.nodes[0].lockReason, raw.lockReason)
  const resumed = state(false)
  delete resumed.nodePauses
  delete resumed.lockReasons
  assert.equal(page._applyRouteState(resumed), true)
  assert.equal(page._routeState.sessionId, '109')
  assert.equal(page._routeState.version, 0)
  assert.equal(page.data.nodes[0].paused, false)
  assert.equal(page.data.nodes[0].pause, null)
  assert.equal(page.data.nodes[0].locked, false)
  assert.equal(page.data.nodes[0].lockReason, '')
  assert.equal(page.data.nodes[1].playable, true)
  assert.equal(page.data.nodes[2].done, true)
})

function linearPage(generic = false, qualification = false) {
  const requests = []
  let definition
  global.getApp = () => ({ sendRequest: options => requests.push(options) })
  global.Page = value => { definition = value }
  global.getCurrentPages = () => []
  global.wx = { nextTick: fn => fn(), showToast() {}, vibrateShort() {} }
  const path = require.resolve('../../pages/play/index.js')
  delete require.cache[path]
  require(path)
  const page = Object.assign({}, definition)
  page.data = { ...definition.data, mode: 1, activityId: 9917146, topicId: 9917143,
    themeBag: generic ? { sessionId: 109, runtimeScope: 'PLAYER' } : {} }
  page.setData = patch => Object.assign(page.data, patch)
  page.rebuild = page.refreshThemeBackpack = () => {}
  page.data.nodes = nodes.map((row, index) => page.normNode({ ...row,
    ...(row.nodeId === 1 && qualification ? { locked: true, lockReason: '资格待确认' } : {}) }, index, 1))
  page._routeNodeCatalog = page.data.nodes.slice()
  page._routeState = normalizeRouteState({ ...state(false), routeMode: 'LINEAR' })
  page._versionedRouteState = page._routeState
  return { page, requests }
}

for (const generic of [false, true]) {
  test('real ' + (generic ? 'generic' : 'ordinary') + ' LINEAR refresh keeps only station pause and restores qualification locks', () => {
    for (const qualification of [false, true]) {
      const { page } = linearPage(generic, qualification)
      const initial = { ...state(false), routeMode: 'LINEAR' }
      assert.equal(page._applyRouteState(initial), true)
      assert.equal(page._applyRouteState({ ...state(true), routeMode: 'LINEAR' }), true)
      assert.equal(page.data.nodes[0].paused, true)
      const resume = { ...initial }
      delete resume.nodePauses
      delete resume.lockReasons
      assert.equal(page._applyRouteState(resume), true)
      assert.equal(page.data.nodes[0].paused, false)
      assert.equal(page.data.nodes[0].pause, null)
      assert.equal(page.data.nodes[0].locked, qualification)
      assert.equal(page.data.nodes[0].lockReason, qualification ? '资格待确认' : '')
      assert.equal(page.data.nodes[2].done, true)
      assert.equal(page._routeState.sessionId, '109')
      assert.equal(page._routeState.version, 0)
    }
  })

  test('real ' + (generic ? 'generic' : 'ordinary') + ' LINEAR async reads ignore old pause responses and clear synchronization locks', async () => {
    const { page, requests } = linearPage(generic)
    const paused = page.refreshRouteState()
    requests[0].success({ code: 200, data: { ...state(true), routeMode: 'LINEAR' } })
    await paused
    assert.equal(page.data.nodes[0].paused, true)
    const older = page.refreshRouteState()
    const latest = page.refreshRouteState()
    const active = { ...state(false), routeMode: 'LINEAR' }
    delete active.nodePauses
    delete active.lockReasons
    requests[2].success({ code: 200, data: active })
    await latest
    requests[1].success({ code: 200, data: { ...state(true), routeMode: 'LINEAR' } })
    assert.equal(await older, false)
    assert.equal(page.data.nodes[0].paused, false)
    assert.equal(page.data.nodes[0].locked, false)
    assert.equal(page.data.nodes[0].lockReason, '')
    assert.equal(page._routeState.version, 0)
  })
}

test('LINEAR station metadata leaves completed, hidden, other nodes and legacy TEAM flags untouched', () => {
  const raw = { ...state(true), routeMode: 'LINEAR' }
  const control = [{ nodeId: 1, done: false }, { nodeId: 2, done: false },
    { nodeId: 3, done: true }, { nodeId: 4, done: false, routeNodeState: 'HIDDEN' },
    { nodeId: 5, paused: true, runtimeStatus: 'PAUSED', locked: true, lockReason: '原TEAM状态' }]
  raw.nodePauses[3] = pause
  raw.nodePauses[4] = pause
  const projected = applyRouteStateToNodes(control, raw)
  assert.equal(projected[0].paused, true)
  for (let i = 1; i < control.length; i++) assert.equal(projected[i], control[i])
  assert.equal(control[0].paused, undefined)
  assert.equal(raw.status, 'ACTIVE')
  assert.equal(raw.version, 0)
})

for (const [mode, generic] of [['LINEAR', false], ['LINEAR', true], ['BRANCH_GRAPH', false]]) {
  test('real nodes loader ' + mode + (generic ? ' generic' : '') + ' clears pause and ignores older nodes response by existing load epoch', async () => {
    const { page, requests } = linearPage(generic)
    page._routeState = normalizeRouteState({ ...state(false), routeMode: mode })
    page._versionedRouteState = page._routeState
    // Omit UI/audio/persistence effects; keep the actual loader, epoch and node/route projections.
    for (const key of ['_applyStoryVars', '_applyLook', '_probeCasebook', '_discardCompletedHistory']) page[key] = () => {}
    page._currentCompletedHistory = () => null
    page.buildChapter = () => ({})
    page.buildChapterCards = () => []
    const payload = paused => ({ topicId: 9917143, topicName: 'TEST', mode: 1, productType: 1,
      registered: true, playable: true, runKey: '109', chapters: [],
      nodes: nodes.map(node => ({ ...node, ...(node.nodeId === 1 && paused ? {
        locked: true, paused: true, pause, lockReason: '设备检修，预计恢复：2026-11-16 06:00:00' } : {}) })),
      routeState: { ...state(paused), routeMode: mode } })
    page.loadData(false)
    requests[0].success({ code: 200, data: payload(true) })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(page._loadCommitted, true)
    assert.equal(page.data.nodes[0].paused, true)
    assert.match(page.data.nodes[0].lockReason, /预计恢复/)
    page.loadData(false)
    page.loadData(false)
    const active = payload(false)
    delete active.routeState.nodePauses
    delete active.routeState.lockReasons
    requests[2].success({ code: 200, data: active })
    await new Promise(resolve => setImmediate(resolve))
    requests[1].success({ code: 200, data: payload(true) })
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(page.data.nodes[0].paused, false)
    assert.equal(page.data.nodes[0].pause, null)
    assert.equal(page.data.nodes[0].locked, false)
    assert.equal(page.data.nodes[0].lockReason, '')
    assert.equal(page.data.nodes.find(node => node.nodeId === 3).done, true)
    assert.equal(page._routeState.sessionId, '109')
    assert.equal(page._routeState.version, 0)
  })
}
