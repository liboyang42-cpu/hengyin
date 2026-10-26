'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const tick = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
const receipt = req => req.success({ code: 200, data: { state: 'APPLIED', requestId: JSON.parse(req.data).requestId } })
const acknowledge = h => h.page.onTeamCommandAction({ currentTarget: { dataset: { teamId: TEAM, action: 'acknowledge' } } })
const TEAM = '9223372036854775807', MEMBER = '9007199254740993'
function mount(name) {
  const file = path.resolve(__dirname, '../../subpackageRoam/' + name + '/index.js')
  let def, owner = '7', auth = 'session-1'
  const requests = [], modals = [], toasts = [], values = {}; let random = 0
  const app = { getUserID: () => owner, getAuthorization: () => auth, globalData: {}, sendRequest: r => requests.push(r) }
  const realRequire = createRequire(file)
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { getApp: () => app, Page: d => { def = d },
    require: p => /utils\/modal(?:\.js)?$/.test(p) ? { show: r => modals.push(r) }
      : /utils\/toast\.js$/.test(p) ? msg => toasts.push(msg) : realRequire(p),
    wx: { getStorageInfoSync: () => ({ keys: Object.keys(values) }), getStorageSync: k => values[k],
      setStorageSync: (k,v) => { values[k] = v }, removeStorageSync: k => { delete values[k] },
      getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(++random).buffer }), getLocation() {} }, setTimeout, clearTimeout })
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data, p) } })
  page.data.current = { teamId: TEAM }; page.data.applicants = [{ memberId: MEMBER }, { memberId: '9007199254740992' }]
  page.refresh = page.loadMyTeams = () => {}; page._patchCurrent = p => Object.assign(page.data.current, p); page._showFail = r => toasts.push(r)
  return { page, requests, modals, toasts, switchOwner() { owner = '8'; auth = 'session-2' }, rotate() { auth = 'session-2' } }
}
test('nearby apply/withdraw/handle preserve exact Long IDs and distinct applicants', async () => {
  const h = mount('nearby'); h.page.apply(); h.modals[0].success({ confirm: true, content: 'hello' }); await tick()
  assert.equal(JSON.parse(h.requests[0].data).targetId, TEAM)
  receipt(h.requests[0]); acknowledge(h)
  h.page.withdraw(TEAM); await tick(); assert.equal(JSON.parse(h.requests[1].data).targetId, TEAM); receipt(h.requests[1]); acknowledge(h)
  h.page.onHandle({ currentTarget: { dataset: { id: MEMBER, ok: true } } })
  await tick()
  assert.equal(JSON.parse(h.requests[2].data).memberId, MEMBER)
  receipt(h.requests[2])
  assert.deepEqual(Array.from(h.page.data.applicants, r => r.memberId), [MEMBER, '9007199254740992'], '回执不能替代当前名单')
  h.page.loadApplicants()
  h.requests.at(-1).success({ code: 200, data: [{ memberId: '9007199254740992' }] })
  assert.deepEqual(Array.from(h.page.data.applicants, r => r.memberId), ['9007199254740992'])
})
for (const change of ['switchOwner', 'rotate', 'team', 'unload']) {
  test('nearby delayed confirmation and callback reject stale scope: ' + change, async () => {
    const mutate = h => change === 'team' ? h.page.data.current = { teamId: '99' }
      : change === 'unload' ? h.page.onUnload() : h[change]()
    const h = mount('nearby'); h.page.apply(); mutate(h); h.modals[0].success({ confirm: true }); await tick()
    assert.equal(h.requests.length, 0)
    const g = mount('nearby'); g.page.apply(); g.modals[0].success({ confirm: true }); await tick(); mutate(g)
    receipt(g.requests[0])
    assert.equal(g.page.data.current.viewerStatus, undefined)
    assert.equal(g.toasts.length, 0)
  })
}
test('unsafe numeric IDs and invalid members never send a mutation', () => {
  const h = mount('nearby'); h.page.withdraw(Number(TEAM)); assert.equal(h.requests.length, 0)
  h.page.onHandle({ currentTarget: { dataset: { id: Number(MEMBER), ok: true } } }); assert.equal(h.requests.length, 0)
})
test('my teams withdrawal keeps exact ID and ignores stale modal/response', async () => {
  for (const staleAt of ['none', 'modal', 'response']) {
    const h = mount('my-teams'); let reloads = 0; h.page.reload = () => reloads++
    h.page._rows = { wait: [{ teamId: TEAM, action: { act: 'withdraw' } }] }
    h.page.onRowAction({ currentTarget: { dataset: { group: 'wait', index: 0 } } })
    if (staleAt === 'modal') h.switchOwner()
    h.modals[0].success({ confirm: true }); await tick()
    if (staleAt === 'modal') { assert.equal(h.requests.length, 0); continue }
    assert.equal(JSON.parse(h.requests[0].data).targetId, TEAM)
    if (staleAt === 'response') h.switchOwner()
    receipt(h.requests[0])
    assert.equal(reloads, staleAt === 'none' ? 1 : 0)
  }
})
test('my teams old list cannot render after account changes', () => {
  const h = mount('my-teams'); h.page._seq = 0; h.page.reload(); h.switchOwner()
  for (const r of h.requests) r.success({ code: 200, data: [{ id: TEAM, teamId: TEAM, applyStatus: 'PENDING' }] })
  assert.equal(h.page.data.groups.length, 0)
})
