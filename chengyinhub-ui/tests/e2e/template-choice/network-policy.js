/** Narrow additions for this suite only; audit43 policy and fixture remain unchanged. */
const { classifyRequest: auditRequest, safePathname, ORIGIN } = require('../audit-network-policy')
function classifyRequest(input) {
  const baseline = auditRequest(input)
  if (baseline.kind !== 'block') return baseline
  if (input.redirectedFrom || input.serviceWorker || !safePathname(input.url)) return baseline
  let url
  try { url = new URL(input.url) } catch (_) { return baseline }
  if (url.origin !== ORIGIN || url.username || url.password || url.hash || !['xhr', 'fetch'].includes(input.resourceType)) return baseline
  const endpoint = url.pathname.slice('/prod-api'.length)
  if (!url.pathname.startsWith('/prod-api/')) return baseline
  const read = ['/business/membertemplate/list', '/system/category/list'].includes(endpoint)
    || /^\/business\/membertemplate\/[1-9]\d*$/.test(endpoint)
  if (input.method === 'GET' && read || input.method === 'PUT' && endpoint === '/business/membertemplate') return { kind: 'api', endpoint }
  return baseline
}
module.exports = { classifyRequest }
