'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm')
const { createRequire } = require('node:module')
const TEAM = '9223372036854775807', MEMBER = '9007199254740993'
const tick = async () => { for (let i = 0; i < 6; i++) await Promise.resolve() }
function mount(name, values = {}) {
  let def, owner = '7', token = 'auth-7', random = 0
  const file = path.resolve(__dirname, '../../subpackageRoam/' + name + '/index.js')
  const requests = [], modals = [], notices = []
  const app = { globalData: {}, getUserID: () => owner, getAuthorization: () => token, sendRequest: r => requests.push(r) }
  const platform = { getStorageInfoSync: () => ({ keys: Object.keys(values) }), getStorageSync: k => values[k],
    setStorageSync: (k,v) => { values[k] = JSON.parse(JSON.stringify(v)) }, removeStorageSync: k => { delete values[k] },
    getRandomValues: o => o.success({ randomValues: new Uint8Array(16).fill(++random).buffer }) }
  const real = createRequire(file)
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Page: d => { def = d }, getApp: () => app, wx: platform, setTimeout, clearTimeout,
    require: p => /utils\/modal(?:\.js)?$/.test(p) ? { show: o => modals.push(o) } : /utils\/toast\.js$/.test(p) ? t => notices.push(t) : real(p) })
  const page = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)), setData(p) { Object.assign(this.data,p) } })
  const actual = { reload: page.reload, loadOpen: page.loadOpen, loadApplicants: page.loadApplicants };
  let reads = 0
  page.refresh = page.loadMyTeams = page.reload = page.loadApplicants = () => { reads++ }
  page._showFail = r => notices.push(r)
  page.data.current = { teamId: TEAM, viewerStatus: 'LEADER' }
  page.data.applicants = [{ memberId: MEMBER }]
  page._rows = { wait: [{ teamId: TEAM, action: { act: 'withdraw' } }] }
  return { page, platform, requests, modals, values, notices, actual, get reads() { return reads },
    switchOwner() { owner = '8'; token = 'auth-8' },
    async start() {
      if (name === 'nearby') page.apply()
      else page.onRowAction({ currentTarget: { dataset: { group: 'wait', index: 0 } } })
      modals.at(-1).success({ confirm: true, content: '原留言' }); await tick()
    },
    action(action) { page.onTeamCommandAction({ currentTarget: { dataset: { teamId: TEAM, action } } }) },
    receipt(index,state, extra = {}) { const requestId = JSON.parse(requests[index].data).requestId; requests[index].success({ code: 200, data: { requestId, state, ...extra } }) },
  }
}
for (const name of ['nearby','my-teams']) {
  // 第十一轮 g2(j4 F1):原断言「终态回执卡留到点确认并归档」编码的是旧决定 —— 现在终态是确定结果,
  // 共享层静默归档,面板只留结果未知的原请求。
  test(name + ': UNKNOWN freezes input, survives missing rows/reopen, retries same request and settles terminal silently', async () => {
    const h = mount(name); await h.start()
    assert.equal(h.requests.length, 1); assert.equal(h.requests[0].url, '/api/team/command')
    const original = h.requests[0].data, command = JSON.parse(original)
    assert.equal(command.targetId, TEAM)
    assert.equal(command.action, name === 'nearby' ? 'apply' : 'withdraw')
    h.requests[0].fail(); assert.equal(h.page.data.teamCommandRows[0].state, 'UNKNOWN')
    const before = JSON.stringify(h.values)
    if (name === 'nearby') h.page.withdraw(TEAM)
    else await h.start()
    await tick(); assert.equal(h.requests.length, 1); assert.equal(JSON.stringify(h.values), before)
    h.page.data.current = null; h.page._rows = {}; h.page.data.groups = []; h.page.onHide()
    assert.equal(h.page.data.teamCommandRows.length, 0)
    const cold = mount(name, h.values); cold.page.data.current = null; cold.page._rows = {}; cold.page.onShow()
    assert.equal(cold.page.data.teamCommandRows[0].targetId, TEAM)
    cold.action('query'); assert.equal(cold.requests[0].url, '/api/team/command/query')
    cold.receipt(0,'UNKNOWN'); assert.equal(cold.page.data.teamCommandRows[0].state,'UNKNOWN')
    cold.action('retry'); assert.equal(cold.requests[1].data,original)
    cold.receipt(1,'APPLIED'); assert.ok(cold.reads > 0)
    assert.equal(Object.keys(cold.values).length,0,'APPLIED 回执已归档'); assert.equal(cold.page.data.teamCommandRows.length,0,'不留回执卡')
  })
  test(name + ': hidden/account-changed callback cannot publish a result; return restores original slot', async () => {
    const h = mount(name); await h.start(); const saved = JSON.stringify(h.values)
    h.page.onHide(); h.receipt(0,'APPLIED'); assert.equal(JSON.stringify(h.values),saved)
    h.switchOwner(); h.page.onShow(); assert.equal(h.page.data.teamCommandRows.length,0)
    assert.equal(JSON.stringify(h.values),saved)
    const old = mount(name,h.values); old.page.onShow(); assert.equal(old.page.data.teamCommandRows[0].state,'UNKNOWN')
  })
  test(name + ': storage enumeration failure blocks fresh mutation and exposes recovery retry', async () => {
    const h = mount(name); h.platform.getStorageInfoSync = () => { throw Error('unreadable') }
    await h.start(); assert.equal(h.requests.length,0); assert.ok(h.page.data.teamCommandError)
    h.platform.getStorageInfoSync = () => ({ keys: [] }); h.page.reloadTeamCommands()
    await h.start(); assert.equal(h.requests.length,1)
  })
}
test('nearby handle preserves exact member/boolean and cancel uses frozen full command', async () => {
  const h = mount('nearby'); h.page.onHandle({ currentTarget: { dataset: { id: MEMBER, ok: false } } })
  h.modals[0].success({ confirm:true }); await tick()
  const command=JSON.parse(h.requests[0].data); assert.equal(command.memberId,MEMBER); assert.equal(command.approved,false)
  h.requests[0].successStatusAbnormal(); h.action('cancel'); assert.equal(h.requests[1].data,h.requests[0].data)
  assert.equal(h.requests[1].url,'/api/team/command/cancel'); h.receipt(1,'CANCELLED')
  assert.equal(h.page.data.applicants.length,1,'cancel receipt cannot claim current applicant membership')
  assert.equal(h.page.data.teamCommandRows.length,0,'终止成功后不留回执卡')
  assert.equal(Object.keys(h.values).length,0)
})
test('nearby: REJECTED shows the backend reason in the fail sheet and leaves no card', async () => {
  const h=mount('nearby');await h.start();h.receipt(0,'REJECTED',{errorCode:'APPLY_BLOCKED',message:'暂时无法加入该队伍'})
  assert.equal(h.page.data.teamCommandRows.length,0);assert.equal(Object.keys(h.values).length,0)
  const sheet=h.notices.find(n=>n&&n.title);assert.ok(sheet);assert.equal(sheet.title,'暂时不能申请这支队伍')
  const g=mount('nearby');await g.start();g.receipt(0,'REJECTED',{message:'队伍已满或已停止招募'})
  assert.equal(g.notices.find(n=>n&&n.title).why,'队伍已满或已停止招募','无 errorCode 时显示后端理由')
})
test('my-teams: REJECTED puts the backend reason under the card that asked', async () => {
  const h=mount('my-teams');await h.start();h.receipt(0,'REJECTED',{message:'申请状态已变化，请刷新后重试'})
  assert.equal(JSON.stringify(h.page.data.commandReject),JSON.stringify({teamId:TEAM,message:'申请状态已变化，请刷新后重试'}))
  assert.equal(h.page.data.teamCommandRows.length,0)
  const wxml=fs.readFileSync(path.resolve(__dirname,'../../subpackageRoam/my-teams/index.wxml'),'utf8')
  assert.match(wxml,/commandReject\.teamId == item\.teamId/)
})
test('wrong request id, HTTP errors and legacy success never settle a pending intention', async () => {
  for (const response of [{code:200,data:{state:'APPLIED',requestId:'f'.repeat(32)}},{code:200,data:{}},{code:403},{code:500}]) {
    const h=mount('nearby');await h.start();h.requests[0].success(response)
    assert.equal(h.page.data.teamCommandRows[0].state,'UNKNOWN'); assert.equal(Object.keys(h.values).length,1)
    assert.ok(h.requests.every(r=>r.url.startsWith('/api/team/command')))
  }
})
test('a terminal receipt stored by a killed page is archived on reopen without replaying a mutation', async () => {
  const h=mount('nearby');await h.start()
  const key=Object.keys(h.values)[0], saved=h.values[key]
  saved.result={requestId:saved.command.requestId,state:'REJECTED',errorCode:'TEAM_FULL',message:'已满'}
  h.page.onUnload();const cold=mount('nearby',{[key]:saved});cold.page.onShow()
  assert.equal(cold.page.data.teamCommandRows.length,0);assert.equal(cold.requests.length,0)
  assert.equal(Object.keys(cold.values).length,0)
  cold.action('retry');assert.equal(cold.requests.length,0)
})
test('recovery panel exists independently of list rows on both pages and offers all receipt operations', () => {
  const panel=fs.readFileSync(path.resolve(__dirname,'../../subpackageRoam/utils/team-recovery-panel.wxml'),'utf8')
  for(const action of ['query','retry','cancel']) assert.match(panel,new RegExp('data-action="'+action+'"'))
  // j4 F1:不露队伍号、请求号,不再有「确认并归档结果」
  assert.doesNotMatch(panel,/requestId|targetId\}\}<\/text>|队伍 \{\{|data-action="acknowledge"/)
  for(const name of ['nearby','my-teams']) assert.match(fs.readFileSync(path.resolve(__dirname,'../../subpackageRoam/'+name+'/index.wxml'),'utf8'),/include src="\.\.\/utils\/team-recovery-panel.wxml"/)
})

for (const name of ['nearby','my-teams']) test(name + ': confirmation from before hide remains invalid after return', async () => {
  const h = mount(name); h.page.onShow()
  h.page._rows = { wait: [{ teamId: TEAM, action: { act: 'withdraw' } }] }
  if (name === 'nearby') h.page.apply()
  else h.page.onRowAction({ currentTarget: { dataset: { group: 'wait', index: 0 } } })
  h.page.onHide(); h.page.onShow()
  h.modals[0].success({ confirm: true, content: 'stale' }); await tick()
  assert.equal(h.requests.length, 0)
})
test('nearby: applicant response from before hide cannot repopulate the returned page', () => {
  const h = mount('nearby'); h.actual.loadApplicants.call(h.page)
  h.page.onHide(); h.page.onShow()
  h.requests[0].success({ code: 200, data: [{ memberId: 'old-user' }] })
  assert.equal(h.page.data.applicants[0].memberId, MEMBER)
})
test('my-teams: hidden location request cannot hold loading or overwrite the new attempt', () => {
  const h = mount('my-teams'), locations = []
  h.page.onShow()
  h.platform.getLocation = options => locations.push(options)
  h.actual.loadOpen.call(h.page); assert.equal(locations.length, 1)
  h.page.onHide(); h.page.onShow(); h.actual.loadOpen.call(h.page)
  assert.equal(locations.length, 2)
  locations[0].success({ latitude: 31, longitude: 121 })
  assert.equal(h.requests.length, 0)
  locations[1].success({ latitude: 31, longitude: 121 })
  assert.equal(h.requests.length, 1)
})
// 第十一轮 g2,j4 F4:我带的招募中队伍在「我的队伍」卡上写出待处理申请数;读不到时写「申请数没读到」。
test('my-teams: leader card shows the pending application count, or that it could not be read', () => {
  const h = mount('my-teams'); h.page.onShow(); h.page.loadOpen = () => {}
  h.page._render([{ id: TEAM, leaderMemberId: '7', status: 0, joinedCount: 1, maxMembers: 3 }, { id: '55', leaderMemberId: '7', status: 0 }], [], { joined: true, apps: true })
  const reads = h.requests.filter(r => r.url === '/api/team/applications')
  assert.equal(reads.length, 2)
  assert.equal(JSON.parse(reads[0].data).teamId, TEAM)
  reads[0].success({ code: 200, data: [{ memberId: '1' }, { memberId: '2' }] })
  reads[1].fail({})
  assert.equal(h.page.data.pending[TEAM].text, '2 条入队申请')
  assert.equal(h.page.data.pending['55'].error, true)
  const wxml = fs.readFileSync(path.resolve(__dirname, '../../subpackageRoam/my-teams/index.wxml'), 'utf8')
  assert.match(wxml, /pending\[item\.teamId\]\.text/)
  assert.match(wxml, /申请数没读到/)
})
