'use strict'

// MPP-7:下一站锁着时,不再只弹一条会消失的 toast,而是在故事流里留一张卡,写清服务端给的原因
// (与 App 的「等待」阶段同一口径)。文案与原 _lockedTip 完全一致,只是换成常驻。纯函数。

const BRANCH_FALLBACK = '前路未知 · 完成当前路线后解锁'
const LINEAR_FALLBACK = '前路未知 · 先完成前置关卡'

/**
 * @param node 被点的站;只有 locked 时才出卡
 * @param nodes 本页节点(找前置站名)
 * @param routeMode 服务端路线模式(BRANCH_GRAPH 用服务端原因)
 * @returns { show, nodeId, name, text } 或 null(不锁 / 前置刚完成)
 */
function lockCard(node, nodes, routeMode) {
  if (!node || !node.locked) return null
  const base = { show: true, nodeId: node.nodeId, name: node.name || '' }
  if (routeMode === 'BRANCH_GRAPH') return Object.assign(base, { text: String(node.lockReason || BRANCH_FALLBACK) })
  const prev = (nodes || []).find((n) => String(n.nodeId) === String(node.unlockAfterNodeId))
  if (prev && prev.done) return null
  return Object.assign(base, { text: prev ? ('先完成「' + prev.name + '」才会解锁') : (node.lockReason ? String(node.lockReason) : LINEAR_FALLBACK) })
}

module.exports = { lockCard, BRANCH_FALLBACK, LINEAR_FALLBACK }
