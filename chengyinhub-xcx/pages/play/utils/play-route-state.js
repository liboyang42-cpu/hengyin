const { id } = require('../../../utils/orient-score-view.js')
const ROUTE_MODE_LINEAR = 'LINEAR'
const ROUTE_MODE_BRANCH = 'BRANCH_GRAPH'
const NODE_STATES = new Set(['HIDDEN', 'DISCOVERED_LOCKED', 'PLAYABLE', 'COMPLETED'])

function isRecord(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function asRecord(value) {
  if (isRecord(value)) return value
  if (typeof value !== 'string' || !value.trim()) return {}
  try {
    const parsed = JSON.parse(value)
    return isRecord(parsed) ? parsed : {}
  } catch (error) {
    return {}
  }
}

function normalizeRouteState(raw) {
  const source = asRecord(raw)
  const routeMode = String(source.routeMode || '').toUpperCase() === ROUTE_MODE_BRANCH
    ? ROUTE_MODE_BRANCH
    : ROUTE_MODE_LINEAR
  const rawStates = asRecord(source.nodeStates)
  const nodeStates = {}
  Object.keys(rawStates).forEach((nodeId) => {
    const state = String(rawStates[nodeId] || '').toUpperCase()
    if (NODE_STATES.has(state)) nodeStates[String(nodeId)] = state
  })
  return {
    routeMode,
    sessionId: id(source.sessionId) || '',
    status: source.status == null ? '' : String(source.status).toUpperCase(),
    currentNodeId: source.currentNodeId == null ? null : source.currentNodeId,
    recommendedNodeId: source.recommendedNodeId == null ? null : source.recommendedNodeId,
    version: Number.isFinite(Number(source.version)) ? Number(source.version) : null,
    nodeStates,
    opening: isRecord(source.opening) ? source.opening : null,
    // 思维阁:服务端只下发玩家已经得到的念头。这里是白名单 —— 漏登记就等于服务端没下发。
    // 畸形项直接丢,免得菜单里渲染出一行空白。
    thoughts: Array.isArray(source.thoughts) ? source.thoughts.filter(item => isRecord(item)
      && typeof item.name === 'string' && item.name
      && Number.isFinite(Number(item.progress)) && Number.isFinite(Number(item.need || 0))) : [],
    // 这局配了几个伏笔(不论得没得到):>0 才在开局请求微信运动授权。只是个数,不露名字。
    thoughtCount: Number.isInteger(Number(source.thoughtCount)) && Number(source.thoughtCount) > 0 ? Number(source.thoughtCount) : 0,
    // 精力 / 幸运当前值(推进与动作回执顶层带;只读 state() 不带时为 null)
    hp: Number.isFinite(Number(source.hp)) && source.hp !== null && source.hp !== '' ? Number(source.hp) : null,
    luck: Number.isFinite(Number(source.luck)) && source.luck !== null && source.luck !== '' ? Number(source.luck) : null,
    attributes: Array.isArray(source.attributes) ? source.attributes.filter(item => isRecord(item)
      && typeof item.label === 'string' && typeof item.value === 'number' && Number.isFinite(item.value)) : [],
    decisionLog: Array.isArray(source.decisionLog) ? source.decisionLog.filter(isRecord) : [],
    lockReasons: asRecord(source.lockReasons),
  }
}

function sameNodeId(left, right) {
  return left != null && right != null && String(left) === String(right)
}

function asTimestamp(value) {
  const numeric = Number(value)
  if (Number.isFinite(numeric) && numeric > 0) return numeric
  const parsed = Date.parse(String(value || ''))
  return Number.isFinite(parsed) ? parsed : 0
}

function nodeRouteState(routeState, nodeId) {
  if (!routeState || routeState.routeMode !== ROUTE_MODE_BRANCH) return ''
  return routeState.nodeStates[String(nodeId)] || 'HIDDEN'
}

function decisionFacts(routeState) {
  const order = {}
  const completedAt = {}
  ;(routeState && routeState.decisionLog || []).forEach((entry, index) => {
    const at = asTimestamp(entry.at != null ? entry.at : entry.completedAt)
    const completedId = entry.completedNodeId != null ? entry.completedNodeId
      : (entry.nodeId != null ? entry.nodeId : entry.fromNodeId)
    if (completedId != null) {
      const key = String(completedId)
      if (order[key] == null) order[key] = index * 2
      if (at > 0 && completedAt[key] == null) completedAt[key] = at
    }
    if (entry.toNodeId != null) {
      const key = String(entry.toNodeId)
      if (order[key] == null) order[key] = index * 2 + 1
    }
  })
  return { order, completedAt }
}

function applyRouteStateToNodes(nodes, routeStateInput) {
  const routeState = normalizeRouteState(routeStateInput)
  const rows = Array.isArray(nodes) ? nodes : []
  if (routeState.routeMode !== ROUTE_MODE_BRANCH) return rows.slice()
  const facts = decisionFacts(routeState)
  return rows.reduce((visible, node) => {
    if (!node || node.nodeId == null) return visible
    const state = nodeRouteState(routeState, node.nodeId)
    if (state === 'HIDDEN') return visible
    const key = String(node.nodeId)
    const completedAt = facts.completedAt[key]
    visible.push(Object.assign({}, node, {
      routeNodeState: state,
      routeDecisionOrder: facts.order[key] == null ? Number.MAX_SAFE_INTEGER : facts.order[key],
      locked: state === 'DISCOVERED_LOCKED',
      playable: state === 'PLAYABLE',
      done: state === 'COMPLETED',
      doneAt: state === 'COMPLETED'
        ? (completedAt || asTimestamp(node.completedAt) || asTimestamp(node.doneAt) || 0)
        : (Number(node.doneAt) || 0),
      lockReason: state === 'DISCOVERED_LOCKED'
        ? (routeState.lockReasons[key] || node.lockReason || '完成当前路线后解锁')
        : '',
    }))
    return visible
  }, [])
}

function findNode(nodes, nodeId) {
  return (nodes || []).find((node) => node && sameNodeId(node.nodeId, nodeId)) || null
}

function resolveNextRouteNode(nodes, routeStateInput) {
  const routeState = normalizeRouteState(routeStateInput)
  const rows = Array.isArray(nodes) ? nodes : []
  if (routeState.routeMode !== ROUTE_MODE_BRANCH) {
    return rows.filter((node) => node && !node.done)
      .slice()
      .sort((left, right) => (Number(left.sortId) || 0) - (Number(right.sortId) || 0))[0] || null
  }
  const candidateIds = [routeState.recommendedNodeId, routeState.currentNodeId]
  for (let i = 0; i < candidateIds.length; i++) {
    const node = findNode(rows, candidateIds[i])
    if (node && !node.done && node.routeNodeState !== 'COMPLETED'
        && nodeRouteState(routeState, node.nodeId) === 'PLAYABLE') return node
  }
  return null
}

function orderedJourneyNodes(nodes, routeStateInput) {
  const routeState = normalizeRouteState(routeStateInput)
  const rows = Array.isArray(nodes) ? nodes.slice() : []
  if (routeState.routeMode !== ROUTE_MODE_BRANCH) {
    return rows.sort((left, right) => (Number(left.sortId) || 0) - (Number(right.sortId) || 0))
  }
  const rank = (node) => {
    if (node.done || node.routeNodeState === 'COMPLETED') return 0
    if (sameNodeId(node.nodeId, routeState.currentNodeId)
        || sameNodeId(node.nodeId, routeState.recommendedNodeId)) return 1
    return 2
  }
  return rows.sort((left, right) => {
    const group = rank(left) - rank(right)
    if (group) return group
    const leftDecision = Number.isFinite(Number(left.routeDecisionOrder)) ? Number(left.routeDecisionOrder) : Number.MAX_SAFE_INTEGER
    const rightDecision = Number.isFinite(Number(right.routeDecisionOrder)) ? Number(right.routeDecisionOrder) : Number.MAX_SAFE_INTEGER
    const decision = leftDecision - rightDecision
    if (decision) return decision
    const completed = (Number(left.doneAt) || Number.MAX_SAFE_INTEGER)
      - (Number(right.doneAt) || Number.MAX_SAFE_INTEGER)
    if (completed) return completed
    return (Number(left.sortId) || 0) - (Number(right.sortId) || 0)
  })
}

function buildVisibleRoutePath(nodes, routeStateInput) {
  const routeState = normalizeRouteState(routeStateInput)
  if (routeState.routeMode !== ROUTE_MODE_BRANCH) return Array.isArray(nodes) ? nodes.slice() : []
  const ordered = orderedJourneyNodes(nodes, routeState)
  const path = ordered.filter((node) => node && (node.done || node.routeNodeState === 'COMPLETED'))
  const next = resolveNextRouteNode(ordered, routeState)
  if (next && !path.some((node) => sameNodeId(node.nodeId, next.nodeId))) path.push(next)
  return path
}

function defaultActionId() {
  return 'route-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12)
}

function createRouteActionStore(idFactory, options) {
  const makeId = typeof idFactory === 'function' ? idFactory : defaultActionId
  const opts = options || {}
  const storage = opts.storage
  const storageKey = storage && opts.scope ? 'cy_play_intents_v1:' + JSON.stringify(opts.scope) : ''
  const pending = Object.create(null)
  const keyOf = (kind, nodeId) => String(kind || 'complete') + ':' + String(nodeId == null ? '' : nodeId)
  const copy = value => JSON.parse(JSON.stringify(value))
  if (storageKey) {
    const saved = storage.getStorageSync(storageKey)
    if (saved && typeof saved === 'object') Object.keys(saved).forEach(key => {
      const row = saved[key]
      if (row && typeof row.routeActionId === 'string' && /^[A-Za-z0-9:_-]{1,80}$/.test(row.routeActionId)) pending[key] = { index: copy(row), body: null }
    })
  }
  const persist = () => {
    if (!storageKey) return
    const index = {}
    Object.keys(pending).forEach(key => { index[key] = pending[key].index })
    try { storage.setStorageSync(storageKey, index) } catch (error) { throw new Error('未能保存待确认命令，请释放存储空间后重试') }
  }
  const identity = (kind, nodeId, state) => ({
    routeActionId: String(makeId()), expectedRouteVersion: normalizeRouteState(state).version,
    operation: String(kind), nodeId, expectedSessionId: normalizeRouteState(state).sessionId || '',
  })
  return {
    context(kind, nodeId, routeStateInput) {
      const key = keyOf(kind, nodeId)
      if (!pending[key]) { pending[key] = { index: identity(kind, nodeId, routeStateInput), body: null }; persist() }
      return {
        routeActionId: pending[key].index.routeActionId,
        expectedRouteVersion: pending[key].index.expectedRouteVersion,
        routeSessionId: pending[key].index.routeSessionId || pending[key].index.expectedSessionId || '0',
      }
    },
    payload(kind, nodeId, routeStateInput, input) {
      const key = keyOf(kind, nodeId)
      if (!pending[key]) {
        const index = identity(kind, nodeId, routeStateInput)
        // 本地只存可回读的索引；原答案、扫码内容、坐标、照片地址留在进程内。
        ;['activityId', 'topicId', 'commandId', 'expectedChapterId', 'targetChapterId'].forEach(field => {
          if (input[field] != null) index[field] = input[field]
        })
        const body = Object.assign(copy(input), { routeActionId: index.routeActionId, expectedRouteVersion: index.expectedRouteVersion })
        pending[key] = { index, body }
      }
      persist()
      const entry = pending[key]
      if (!entry.body) return Object.assign(copy(entry.index), { receiptOnly: true })
      const result = copy(entry.body)
      Object.defineProperty(result, '_bindRouteContext', { value: fields => {
        Object.assign(entry.body, fields)
        ;['routeSessionId', 'runtimeInstance', 'authorityEpoch', 'expectedRouteVersion'].forEach(field => {
          if (fields[field] != null) entry.index[field] = fields[field]
        })
        persist()
        Object.assign(result, fields)
      } })
      Object.defineProperty(result, '_markIntentUnknown', { value: () => {
        entry.body.receiptOnly = true
        result.receiptOnly = true
        persist()
      } })
      return result
    },
    resolve(kind, nodeId) {
      delete pending[keyOf(kind, nodeId)]
      persist()
    },
    clear() {
      // 持久意图仍可在同作用域再次读取，只有明确回执才能 resolve。
      if (storageKey) return
      Object.keys(pending).forEach((key) => delete pending[key])
    },
  }
}

const scopedStores = Object.create(null)
function scopedRouteActionStore(scope, storage) {
  const key = JSON.stringify(scope)
  if (!scopedStores[key]) scopedStores[key] = createRouteActionStore(null, { scope, storage })
  return scopedStores[key]
}

function isRouteConflict(response) {
  const source = isRecord(response) ? response : {}
  const code = String(source.upstreamCode != null && source.upstreamCode !== '' ? source.upstreamCode : (source.code || '')).toUpperCase()
  if (code === '409' || code === 'ROUTE_VERSION_CONFLICT' || code === 'STALE_ROUTE_VERSION') return true
  const message = String(source.msg || '')
  return /(?:路线|route).*(?:冲突|已更新|过期)|(?:版本|version).*(?:冲突|已更新|过期)/i.test(message)
}

module.exports = {
  ROUTE_MODE_LINEAR,
  ROUTE_MODE_BRANCH,
  normalizeRouteState,
  applyRouteStateToNodes,
  resolveNextRouteNode,
  orderedJourneyNodes,
  buildVisibleRoutePath,
  createRouteActionStore,
  scopedRouteActionStore,
  isRouteConflict,
}
