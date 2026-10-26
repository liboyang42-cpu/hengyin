'use strict'
const { exactSessionId } = require('./roam-recovery.js')
function teamId(value) {
  const exact = exactSessionId(value)
  return exact ? (typeof value === 'number' ? value : exact) : null
}
function identity(app) {
  const authorization = typeof app.getAuthorization === 'function' ? app.getAuthorization() : (app.globalData || {}).authorization
  return JSON.stringify([String(app.getUserID() || ''), authorization || ''])
}
function capture(host, app, selectedTeam) {
  const owner = identity(app), epoch = host._teamScopeEpoch || 0
  const selected = selectedTeam === undefined ? undefined : teamId(selectedTeam)
  return () => !host._hidden && !host._unloaded && epoch === (host._teamScopeEpoch || 0) && owner === identity(app)
    && (selected === undefined || String(teamId(host.data.current && host.data.current.teamId)) === String(selected))
}
module.exports = { teamId, identity, capture }
