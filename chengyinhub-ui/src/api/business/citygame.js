import request from '@/utils/request'

export function listBoards(params) {
  return request({ url: '/business/city-game/boards', method: 'get', params })
}

export function getBoard(boardId) {
  return request({ url: '/business/city-game/boards/' + encodeURIComponent(boardId), method: 'get' })
}

export function previewDice(data) {
  return request({ url: '/business/city-game/boards/dice/preview', method: 'post', data })
}

export function saveBoardDraft(data) {
  return request({ url: '/business/city-game/boards/draft', method: 'post', data })
}

export function updateBoardDraft(boardId, data) {
  return request({ url: '/business/city-game/boards/draft/' + encodeURIComponent(boardId), method: 'put', data })
}

export function publishBoard(boardId) {
  return request({ url: '/business/city-game/boards/' + encodeURIComponent(boardId) + '/publish', method: 'post' })
}

export function freezeBoard(boardId) {
  return request({ url: '/business/city-game/boards/' + encodeURIComponent(boardId) + '/freeze', method: 'post' })
}

export function getBoardAudit(boardId, params) {
  return request({ url: '/business/city-game/boards/' + encodeURIComponent(boardId) + '/audit', method: 'get', params })
}

export function getMerchantOpsQueue(params) {
  return request({ url: '/business/city-game/merchant-ops/queue', method: 'get', params })
}

export function reviewMerchantOp(data) {
  return request({ url: '/business/city-game/merchant-ops/review', method: 'post', data })
}

export function getFairnessReport(params) {
  return request({ url: '/business/city-game/fairness/report', method: 'get', params })
}

export function listTerritories(params) {
  return request({ url: '/business/city-game/territories', method: 'get', params })
}

export function updateTerritoryStatus(territoryId, data) {
  return request({ url: '/business/city-game/territories/' + encodeURIComponent(territoryId) + '/status', method: 'post', data })
}

export function getTerritoryAudit(params) {
  return request({ url: '/business/city-game/territories/audit', method: 'get', params })
}

export function getTerritoryLeaderboard(params) {
  return request({ url: '/business/city-game/territories/leaderboard', method: 'get', params })
}

// CITY-6: City Monopoly operations (config read, overnight batches, season settle, abuse queue).
export function getMonopolyConfig() {
  return request({ url: '/business/city-game/monopoly/config', method: 'get' })
}

export function runMonopolySettle() {
  return request({ url: '/business/city-game/monopoly/settle', method: 'post' })
}

export function runMonopolyNightly() {
  return request({ url: '/business/city-game/monopoly/nightly', method: 'post' })
}

export function settleMonopolySeason(seasonId, params) {
  return request({ url: '/business/city-game/monopoly/season/' + encodeURIComponent(seasonId) + '/settle', method: 'post', params })
}

export function listMonopolyAbuse(params) {
  return request({ url: '/business/city-game/monopoly/abuse', method: 'get', params })
}

export function reviewMonopolyAbuse(signalId, confirm) {
  return request({ url: '/business/city-game/monopoly/abuse/' + encodeURIComponent(signalId) + '/review', method: 'post', data: { confirm: !!confirm } })
}
