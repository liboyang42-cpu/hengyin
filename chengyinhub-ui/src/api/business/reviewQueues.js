import request from '@/utils/request'

// Review-queue routes of the shared ReviewStateMachine (ExternalGameReviewController / WorkshopProfessionalReviewController).
// A refusal answers HTTP status == code with { code, msg, errorCode }; keep it as data so the page shows the server's reason
// instead of the generic interceptor toast.
const raw = { validateStatus: () => true, rawBusinessError: true }

// ---- external games (/business/external-game) ----

export function listExternalGameReviews(query) {
  return request({ url: '/business/external-game/review/list', method: 'get', params: query, ...raw })
}

export function getExternalGameReview(submissionId) {
  return request({ url: '/business/external-game/review/' + encodeURIComponent(submissionId), method: 'get', ...raw })
}

export function downloadExternalGamePackage(submissionId) {
  return request({ url: '/business/external-game/review/' + encodeURIComponent(submissionId) + '/package', method: 'get', responseType: 'blob' })
}

export function approveExternalGame(submissionId, note) {
  return request({ url: '/business/external-game/review/' + encodeURIComponent(submissionId) + '/approve', method: 'post', data: note ? { note } : {}, ...raw })
}

export function rejectExternalGame(submissionId, reason) {
  return request({ url: '/business/external-game/review/' + encodeURIComponent(submissionId) + '/reject', method: 'post', data: { reason }, ...raw })
}

export function escalateExternalGame(submissionId, reason) {
  return request({ url: '/business/external-game/review/' + encodeURIComponent(submissionId) + '/escalate', method: 'post', data: { reason }, ...raw })
}

export function sweepExternalGameTimeouts() {
  return request({ url: '/business/external-game/review/sweep-timeouts', method: 'post', ...raw })
}

export function listExternalGameReports(query) {
  return request({ url: '/business/external-game/reports', method: 'get', params: query, ...raw })
}

// ---- workshop professional materializations (/business/workshop-professional) ----

export function listWorkshopReviews() {
  return request({ url: '/business/workshop-professional/review/list', method: 'get', ...raw })
}

export function approveWorkshop(id, note) {
  return request({ url: '/business/workshop-professional/review/' + id + '/approve', method: 'post', data: note ? { note } : {}, ...raw })
}

export function rejectWorkshop(id, reason) {
  return request({ url: '/business/workshop-professional/review/' + id + '/reject', method: 'post', data: { reason }, ...raw })
}

export function escalateWorkshop(id, reason) {
  return request({ url: '/business/workshop-professional/review/' + id + '/escalate', method: 'post', data: { reason }, ...raw })
}

// ---- merchant offer-withdrawal receipts (/business/offer-withdrawal, read-only) ----

export function listOfferWithdrawals(query) {
  return request({ url: '/business/offer-withdrawal/list', method: 'get', params: query, ...raw })
}

// ---- IM webhook deliveries / DLQ (/business/im-webhook) ----

export function listWebhookDeliveries(query) {
  return request({ url: '/business/im-webhook/deliveries', method: 'get', params: query, ...raw })
}

export function redriveWebhookDelivery(deliveryId) {
  return request({ url: '/business/im-webhook/deliveries/' + encodeURIComponent(deliveryId) + '/redrive', method: 'post', ...raw })
}

// ---- External game monetization / split / traffic settlements (/business/external-game/monetization) ----

export function listExternalGameMonetization(query) {
  return request({ url: '/business/external-game/monetization/list', method: 'get', params: query, ...raw })
}

export function reviewExternalGameMonetization(gameId, data) {
  return request({ url: '/business/external-game/monetization/' + encodeURIComponent(gameId) + '/review', method: 'post', data, ...raw })
}

export function updateExternalGameMonetizationConfig(gameId, data) {
  return request({ url: '/business/external-game/monetization/' + encodeURIComponent(gameId) + '/config', method: 'put', data, ...raw })
}

export function listExternalGameSettlements(query) {
  return request({ url: '/business/external-game/monetization/settlements', method: 'get', params: query, ...raw })
}

export function triggerExternalGameSettlement(data) {
  return request({ url: '/business/external-game/monetization/settle', method: 'post', data, ...raw })
}
