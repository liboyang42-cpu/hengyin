import request from '@/utils/request'

// G3 external-games console actions (ExternalGameOpsAdminController, /business/external-game/ops).
// Refusals answer HTTP status == code with { code, msg, errorCode }; keep them as data so the page shows the reason.
const raw = { validateStatus: () => true, rawBusinessError: true }
const base = '/business/external-game/ops'

export function listExternalGameOpsReports(query) {
  return request({ url: base + '/reports', method: 'get', params: query, ...raw })
}

export function resolveExternalGameReport(reportId, data) {
  return request({ url: base + '/reports/' + encodeURIComponent(reportId) + '/resolve', method: 'post', data, ...raw })
}

export function takedownExternalGame(gameId, data) {
  return request({ url: base + '/games/' + encodeURIComponent(gameId) + '/takedown', method: 'post', data, ...raw })
}

export function restoreExternalGame(gameId, data) {
  return request({ url: base + '/games/' + encodeURIComponent(gameId) + '/restore', method: 'post', data, ...raw })
}

export function listExternalGameBindings(query) {
  return request({ url: base + '/bindings', method: 'get', params: query, ...raw })
}

export function bindExternalGameNode(nodeId, data) {
  return request({ url: base + '/nodes/' + encodeURIComponent(nodeId) + '/binding', method: 'put', data, ...raw })
}

export function unbindExternalGameNode(nodeId, data) {
  return request({ url: base + '/nodes/' + encodeURIComponent(nodeId) + '/unbind', method: 'post', data, ...raw })
}

export function listExternalGameEvents(query) {
  return request({ url: base + '/events', method: 'get', params: query, ...raw })
}
