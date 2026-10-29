'use strict'
const { createRecovery } = require('../../utils/team-event-command.js')
const { exactId, reader, sameReader } = require('../../utils/user-character.js')
const FINAL = ['APPLIED', 'REJECTED', 'CANCELLED']
function dispose(host) {
  if (host._teamRecoveries) host._teamRecoveries.dispose()
  host._teamRecoveries = null
  host.setData({ teamCommandRows: [], teamCommandError: '', busy: false })
}
function mount(host, app, platform) {
  dispose(host)
  const actor = reader(app), instances = new Map(), rows = new Map()
  let stopped = false, scanError = false
  const current = () => !stopped && !host._hidden && !host._unloaded && sameReader(app, actor)
  function render() {
    if (!current()) return
    const visible = Array.from(rows.values()).filter(v => v.state || v.busy)
    host.setData({ teamCommandRows: visible, busy: visible.some(v => v.busy) })
  }
  function ensure(value) {
    const targetId = exactId(value)
    if (!current() || scanError || !actor.id || !actor.token || !targetId) return null
    if (!instances.has(targetId)) {
      const instance = createRecovery({ app, platform, domain: 'TEAM', targetId,
        changed(value) {
          if (!current()) return
          const input = value.input || {}
          rows.set(targetId, { targetId, state: value.state, requestId: value.requestId,
            label: value.label, message: value.message, busy: value.busy,
            terminal: FINAL.includes(value.state),
            detail: input.action === 'apply' ? String(input.message || '')
              : input.action === 'handle' ? (input.approved ? '同意成员 ' : '拒绝成员 ') + String(input.memberId || '') : '' })
          render()
        },
        terminal(receipt, command) { if (current()) host.afterTeamCommandTerminal(receipt, command) },
      })
      instances.set(targetId, instance)
    }
    return instances.get(targetId)
  }
  const manager = {
    current,
    start(target, action, input) {
      const instance = ensure(target)
      if (!instance) {
        if (current() && !scanError) host.setData({ teamCommandError: '当前账号或队伍信息不可用，请重新进入后重试' })
        return Promise.resolve()
      }
      return instance.start(action, input)
    },
    act(target, action) {
      if (!current() || !['query', 'retry', 'cancel', 'acknowledge'].includes(action)) return
      const instance = instances.get(exactId(target))
      if (instance) instance[action]()
    },
    dispose() { stopped = true; instances.forEach(instance => instance.dispose()) },
  }
  host._teamRecoveries = manager
  if (!actor.id || !actor.token) return manager
  try {
    const info = platform.getStorageInfoSync()
    if (!info || !Array.isArray(info.keys)) throw Error('invalid storage inventory')
    const prefix = 'cy-team-event-command-v1:' + actor.id + ':TEAM:'
    info.keys.forEach(key => {
      if (typeof key !== 'string' || !key.startsWith(prefix)) return
      const target = key.slice(prefix.length)
      if (exactId(target) === target) ensure(target)
    })
  } catch (_) {
    scanError = true
    if (current()) host.setData({ teamCommandError: '未能读取待确认的队伍操作，请重试；暂不提交新操作' })
  }
  return manager
}
function manager(host, app, platform) {
  return host._teamRecoveries && host._teamRecoveries.current() ? host._teamRecoveries : mount(host, app, platform)
}
module.exports = { mount, manager, dispose }
