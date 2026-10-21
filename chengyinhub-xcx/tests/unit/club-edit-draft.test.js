'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const drafts = require('../../utils/club-edit-draft.js')
const { decodeDisplayText } = require('../../utils/display-text.js')
const COMPONENT = path.resolve(__dirname, '../../components/cy/scene-club-edit/index.js')
// PublicClubVO.from/getters: nullable Java String/Integer fields are explicit null;
 // detail adds Boolean viewer flags. It exposes no updateTime.
const CLUB = { id: 9, memberId: 1, name: '原俱乐部', logo: null, cover: null,
  description: '介绍', clubTypes: null, activityPrefs: null, clubType: null,
  city: '上海', keywords: null, style: null, leaderName: null, level: null, tags: null,
  memberCount: 1, topicCount: 0, prioritySignupEnabled: null, memberDiscountPrice: null,
  memberReservedQuota: null, isJoined: true, isOwner: true, joinPolicy: null,
  joinPolicySupported: true, memberPostAllowed: 1, merchantUndertakeOpen: 1,
  myJoinStatus: null, myJoinReviewReason: '', viewerIsAdmin: false,
  viewerIdentityUnavailable: false, pendingJoinRequestCount: 0, nonOwnerMemberCount: 0 }
function mount(options = {}) {
  let member = '1', authorization = 'session-1', spec
  const values = options.values || {}, requests = [], events = [], notices = [], timers = [], media = []
  const flags = { read: false, write: false, remove: false }
  const wx = {
    getStorageSync(key) { if (flags.read) throw Error('read unavailable'); return values[key] },
    setStorageSync(key, value) { if (flags.write) throw Error('full'); values[key] = value },
    removeStorageSync(key) { if (flags.remove) throw Error('remove unavailable'); delete values[key] },
  }
  const app = {
    getUserID: () => member, getAuthorization: () => authorization,
    sendRequest: r => requests.push(r),
    chooseImage(callback, count, config) { media.push({ callback, count, config }) },
  }
  const toast = Object.assign(message => notices.push(message), { success: message => notices.push(message) })
  vm.runInNewContext(fs.readFileSync(COMPONENT, 'utf8'), {
    wx, getApp: () => app, Component: value => { spec = value },
    setTimeout: callback => { timers.push(callback) },
    require(name) {
      if (name.endsWith('/club-edit-draft.js')) return drafts
      if (name.endsWith('/display-text.js')) return { decodeDisplayText }
      if (name.endsWith('/toast.js')) return toast
      throw Error('Unexpected dependency: ' + name)
    },
  })
  const c = Object.assign({}, spec.methods, {
    data: Object.assign(JSON.parse(JSON.stringify(spec.data)), { clubId: options.clubId || '9' }),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this) },
    triggerEvent(name, detail) { events.push({ name, detail }) },
  })
  function change(id, session) { member = id; authorization = session || 'session-' + id }
  const h = { c, wx, app, values, requests, events, notices, timers, media, flags, spec, change,
    ready(club = CLUB) { requests.shift().success({ code: 200, data: club }) },
    attach() { spec.lifetimes.attached.call(c) },
    detach() { if (spec.lifetimes.detached) spec.lifetimes.detached.call(c) },
    switchClub(id) { c.data.clubId = id; if (spec.properties.clubId.observer) spec.properties.clubId.observer.call(c) },
  }
  h.attach()
  return h
}
function edit(h, field = 'name', value = '我的修改') {
  h.c.onInput({ currentTarget: { dataset: { field } }, detail: { value } })
}
function preflight(h, latest = CLUB) {
  h.c.save()
  const read = h.requests.shift()
  assert.equal(read.url, '/api/club/detail')
  read.success({ code: 200, data: latest })
  return h.requests.shift()
}
test('草稿key按账号和club隔离，匿名及不精确ID不落盘', () => {
  assert.notEqual(drafts.key(1, 9), drafts.key(2, 9))
  assert.notEqual(drafts.key(1, 9), drafts.key(1, 10))
  for (const id of ['', 0, null, undefined, {}, [], true, Number.MAX_SAFE_INTEGER + 1]) assert.equal(drafts.key(id, 9), '')
  const h = mount(); h.ready(); h.change('')
  edit(h)
  assert.deepEqual(h.values, {})
})
test('坏档和读取失败既不删除也不覆盖，包含false、0、畸形base', () => {
  const h = mount(), key = drafts.key(1, 9), baseline = drafts.base(CLUB)
  for (const bad of [false, 0, [], 'bad', { broken: true },
    { schemaVersion: 1, base: 'not-json', form: drafts.form(CLUB) }]) {
    h.values[key] = bad
    assert.equal(drafts.read(h.wx, key).state, 'corrupt')
    assert.equal(drafts.write(h.wx, key, baseline, CLUB), false)
    assert.equal(drafts.remove(h.wx, key), false)
    assert.equal(h.values[key], bad)
  }
  h.flags.read = true
  assert.equal(drafts.read(h.wx, key).state, 'error')
  assert.equal(drafts.write(h.wx, key, baseline, CLUB), false)
  assert.equal(drafts.remove(h.wx, key), false)
})
test('写入和清除需匹配原记录，另一编辑者的新稿不能覆盖或删除', () => {
  const h = mount(), key = drafts.key(1, 9), baseline = drafts.base(CLUB)
  assert.equal(drafts.write(h.wx, key, baseline, CLUB, ''), true)
  const expected = JSON.stringify(drafts.read(h.wx, key).record)
  assert.equal(drafts.write(h.wx, key, baseline, { ...CLUB, name: '另一个编辑' }, expected), true)
  assert.equal(drafts.write(h.wx, key, baseline, CLUB, expected), false)
  assert.equal(drafts.remove(h.wx, key, expected), false)
  assert.equal(drafts.read(h.wx, key).record.form.name, '另一个编辑')
})
test('真实attached/输入/detached/重新attached：先提示，明确恢复才回填', () => {
  const h = mount(); h.ready(); edit(h)
  assert.equal(drafts.read(h.wx, drafts.key(1, 9)).record.form.name, '我的修改')
  h.detach()
  const reopened = mount({ values: h.values }); reopened.ready()
  assert.equal(reopened.c.data.name, CLUB.name)
  assert.equal(reopened.c.data.draftAvailable, true)
  edit(reopened, 'name', '不应覆盖')
  assert.equal(reopened.c.data.name, CLUB.name)
  reopened.c.restoreDraft()
  assert.equal(reopened.c.data.name, '我的修改')
  assert.equal(reopened.c.data.draftAvailable, false)
  assert.equal(reopened.events.some(e => e.name === 'dirtychange' && e.detail.dirty), true)
})
test('主动放弃只清原账号原club的有效草稿并重新读取', () => {
  const h = mount(); h.ready(); edit(h)
  const otherKey = drafts.key(2, 9)
  h.values[otherKey] = { keep: true }
  const reopened = mount({ values: h.values }); reopened.ready()
  reopened.c.discardDraft()
  assert.equal(reopened.values[drafts.key(1, 9)], undefined)
  assert.deepEqual(reopened.values[otherKey], { keep: true })
  assert.equal(reopened.requests.shift().url, '/api/club/detail')
})
test('重开遇到服务器变化展示差异，旧稿不能恢复或直接提交', () => {
  const h = mount(); h.ready(); edit(h)
  const reopened = mount({ values: h.values })
  reopened.ready({ ...CLUB, name: '当前新资料' })
  assert.equal(reopened.c.data.draftBlocked, true)
  assert.match(reopened.c.data.draftComparison, /我的修改.*当前新资料/)
  reopened.c.restoreDraft(); reopened.c.save()
  assert.equal(reopened.c.data.name, '当前新资料')
  assert.equal(reopened.requests.length, 0)
})
test('保存前再次读差异，预读不一致不发送update', () => {
  const h = mount(); h.ready(); edit(h)
  const write = preflight(h, { ...CLUB, name: '他人更新' })
  assert.equal(write, undefined)
  assert.equal(h.c.data.draftBlocked, true)
  assert.match(h.c.data.draftComparison, /我的修改.*他人更新/)
  h.c.save(); assert.equal(h.requests.length, 0)
})
test('坏档/读取失败在真实组件中阻止覆盖，原记录保留', () => {
  for (const readFailure of [false, true]) {
    const h = mount(); const bad = { damaged: true }
    h.values[drafts.key(1, 9)] = bad
    h.flags.read = readFailure; h.ready()
    assert.equal(h.c.data.draftBlocked, true)
    edit(h); h.c.restoreDraft(); h.c.discardDraft(); h.c.save()
    assert.equal(h.values[drafts.key(1, 9)], bad)
    assert.equal(h.requests.length, 0)
  }
})
test('存储失败明确提示勿退出，不能假称已保存草稿；恢复存储可重试', () => {
  const h = mount(); h.ready(); h.flags.write = true; edit(h)
  assert.match(h.c.data.draftNotice, /未能保留.*勿退出/)
  assert.equal(h.values[drafts.key(1, 9)], undefined)
  h.flags.write = false; edit(h, 'name', '再试')
  assert.match(h.c.data.draftNotice, /已保留/)
})
test('核对失败和保存失败均保留草稿，明确成功只清原对象', () => {
  const h = mount(); h.ready(); edit(h)
  h.values[drafts.key(2, 9)] = { keep: true }; h.values[drafts.key(1, 10)] = { keep: true }
  h.c.save(); h.requests.shift().fail()
  assert.equal(h.c.data.saving, false)
  assert.equal(drafts.read(h.wx, drafts.key(1, 9)).state, 'ready')
  preflight(h).success({ code: 500, msg: '暂时不可用' })
  assert.equal(drafts.read(h.wx, drafts.key(1, 9)).state, 'ready')
  const write = preflight(h)
  assert.equal(write.url, '/api/club/update-mine')
  assert.equal(JSON.parse(write.data).id, '9')
  assert.equal(JSON.parse(write.data).expectedVersion, undefined)
  write.success({ code: 200 })
  assert.equal(h.values[drafts.key(1, 9)], undefined)
  assert.deepEqual(h.values[drafts.key(2, 9)], { keep: true })
  assert.deepEqual(h.values[drafts.key(1, 10)], { keep: true })
})
for (const change of ['account', 'club', 'detach', 'ABA']) {
  test('核对/保存迟到回包不清其他草稿：' + change, () => {
    for (const phase of ['preflight', 'post']) {
      const h = mount(); h.ready(); edit(h)
      h.values[drafts.key(2, 9)] = { keep: true }; h.values[drafts.key(1, 10)] = { keep: true }
      h.c.save(); const read = h.requests.shift()
      let pending = read
      if (phase === 'post') { read.success({ code: 200, data: CLUB }); pending = h.requests.shift() }
      if (change === 'account') h.change('2')
      if (change === 'club') h.switchClub('10')
      if (change === 'detach') h.detach()
      if (change === 'ABA') { h.change('2'); h.change('1', 'new-session-1') }
      const count = h.requests.length
      pending.success(phase === 'post' ? { code: 200 } : { code: 200, data: CLUB })
      assert.equal(h.requests.length, count)
      assert.equal(drafts.read(h.wx, drafts.key(1, 9)).state, 'ready')
      assert.deepEqual(h.values[drafts.key(2, 9)], { keep: true })
      assert.deepEqual(h.values[drafts.key(1, 10)], { keep: true })
      assert.equal(h.events.some(e => e.name === 'saved' || e.name === 'back'), false)
    }
  })
}
test('读取回包与媒体回调在切对象或关闭后失效，裁剪配置保持', () => {
  const h = mount(); const initial = h.requests.shift()
  h.switchClub('10'); initial.success({ code: 200, data: CLUB })
  assert.notEqual(h.c.data.loadState, 'ready')
  const live = mount(); live.ready(); live.c.pickLogo(); live.c.pickCover()
  assert.equal(live.media[0].config.cropScale, '1:1')
  assert.equal(live.media[1].config.cropScale, '16:9')
  live.detach(); live.media[0].callback(['new-logo']); live.media[1].callback(['new-cover'])
  assert.equal(live.c.data.logo, '')
  assert.equal(live.c.data.cover, '')
  assert.deepEqual(live.values, {})
})
test('成功后延迟关闭仍核验账号，删除失败保留提示和原稿', () => {
  const h = mount(); h.ready(); edit(h); preflight(h).success({ code: 200 })
  h.change('2'); h.timers.forEach(fn => fn())
  assert.equal(h.events.some(e => e.name === 'saved' || e.name === 'back'), false)
  const failed = mount(); failed.ready(); edit(failed); failed.flags.remove = true
  preflight(failed).success({ code: 200 })
  assert.match(failed.c.data.draftNotice, /已保存.*未能清除/)
  assert.equal(drafts.read(failed.wx, drafts.key(1, 9)).state, 'ready')
  assert.equal(failed.timers.length, 0)
})
test('权限仍由服务端校验；管理员不能恢复草稿中的主理人经营配置', () => {
  const h = mount()
  h.ready({ ...CLUB, isOwner: false, viewerIsAdmin: false })
  assert.equal(h.c.data.loadState, 'permission')
  h.c.save(); assert.equal(h.requests.length, 0)
  const admin = mount(); admin.ready({ ...CLUB, isOwner: false, viewerIsAdmin: true })
  assert.equal(admin.c.data.loadState, 'ready')
  edit(admin); const write = preflight(admin, { ...CLUB, isOwner: false, viewerIsAdmin: true })
  assert.equal(write.url, '/api/club/update-mine')
})

test('HTTP未知/网络保存失败留稿，权限变化不提交', () => {
  for (const callback of ['fail', 'successStatusAbnormal']) {
    const h = mount(); h.ready(); edit(h); const write = preflight(h)
    write[callback]()
    assert.equal(h.c.data.saving, false)
    assert.equal(drafts.read(h.wx, drafts.key(1, 9)).state, 'ready')
    assert.equal(h.notices.includes('已保存'), false)
  }
  const h = mount(); h.ready(); edit(h)
  assert.equal(preflight(h, { ...CLUB, isOwner: false, viewerIsAdmin: false }), undefined)
  assert.equal(h.c.data.draftBlocked, true)
})
test('管理员恢复只恢复可编辑展示字段，经营配置保持服务端现值', () => {
  const h = mount(); h.ready()
  h.c.onPrioritySignupChange({ detail: { value: true } })
  const admin = mount({ values: h.values })
  admin.ready({ ...CLUB, isOwner: false, viewerIsAdmin: true })
  assert.equal(admin.c.data.draftAvailable, true)
  admin.c.restoreDraft()
  assert.equal(admin.c.data.prioritySignupEnabled, false)
})
test('已加载后出现坏档或较新本机草稿，保存前不覆盖也不提交', () => {
  const h = mount(); h.ready(); edit(h)
  const key = drafts.key(1, 9), bad = { damaged: true }
  h.values[key] = bad
  h.c.save()
  assert.equal(h.requests.length, 0)
  assert.equal(h.values[key], bad)
  const newer = mount(); newer.ready(); edit(newer)
  const record = drafts.read(newer.wx, key).record
  record.form.name = '另一个窗口的新草稿'
  newer.values[key] = record
  newer.c.save()
  assert.equal(newer.requests.length, 0)
  assert.equal(newer.values[key].form.name, '另一个窗口的新草稿')
})

test('新编辑遇到预读冲突可明确放弃原稿并读取服务器当前资料', () => {
  const h = mount(); h.ready(); edit(h)
  const latest = { ...CLUB, name: '服务器新名' }
  assert.equal(preflight(h, latest), undefined)
  h.c.discardDraft()
  assert.equal(h.values[drafts.key(1, 9)], undefined)
  assert.equal(h.requests.length, 1)
  h.ready(latest)
  assert.equal(h.c.data.name, '服务器新名')
  assert.equal(h.c.data.draftBlocked, false)
})
test('存储失败后的重新读取不得丢弃未保留输入', () => {
  const h = mount(); h.ready(); h.flags.write = true; edit(h)
  h.c.loadClub()
  assert.equal(h.requests.length, 0)
  assert.equal(h.c.data.name, '我的修改')
  assert.match(h.c.data.draftNotice, /尚未保留.*勿退出/)
  h.flags.write = false; edit(h, 'name', '重新保留')
  h.c.loadClub()
  assert.equal(h.requests.length, 1)
})

test('保存成功后的延迟关闭不吞掉期间新输入，新稿仍可落盘', () => {
  const h = mount(); h.ready(); edit(h)
  preflight(h).success({ code: 200 })
  edit(h, 'name', '成功后继续编辑')
  h.timers.forEach(fn => fn())
  assert.equal(h.events.some(e => e.name === 'saved' || e.name === 'back'), false)
  assert.equal(drafts.read(h.wx, drafts.key(1, 9)).record.form.name, '成功后继续编辑')
})

test('成功后立即续编经权威回读迁移基线，二次保存与重开恢复均可达', () => {
  const h = mount(); h.ready(); edit(h, 'name', '第一笔已保存')
  preflight(h).success({ code: 200 })
  edit(h, 'name', '成功后继续编辑')
  const latest = { ...CLUB, name: '第一笔已保存' }
  const readback = h.requests.shift()
  assert.equal(readback.url, '/api/club/detail')
  readback.success({ code: 200, data: latest })
  assert.equal(h.c.data.name, '成功后继续编辑')
  assert.equal(h.c._draftBase, drafts.base(latest))
  const reopened = mount({ values: h.values }); reopened.ready(latest)
  assert.equal(reopened.c.data.draftAvailable, true)
  reopened.c.restoreDraft()
  assert.equal(reopened.c.data.name, '成功后继续编辑')
  assert.equal(preflight(h, latest).url, '/api/club/update-mine')
})
test('回读失败仍保留续编，关闭重开从权威资料迁移，重试可二次保存', () => {
  const h = mount(); h.ready(); edit(h, 'name', '第一笔已保存')
  preflight(h).success({ code: 200 }); edit(h, 'name', '续编')
  h.requests.shift().fail()
  assert.match(h.c.data.saveErrorText, /核对/)
  const latest = { ...CLUB, name: '第一笔已保存' }
  const reopened = mount({ values: JSON.parse(JSON.stringify(h.values)) }); reopened.ready(latest)
  assert.equal(reopened.c.data.draftAvailable, true)
  reopened.c.restoreDraft(); assert.equal(reopened.c.data.name, '续编')
  h.c.save(); h.requests.shift().success({ code: 200, data: latest })
  assert.equal(h.requests.length, 0)
  assert.equal(preflight(h, latest).url, '/api/club/update-mine')
})
for (const change of ['account', 'club', 'detach', 'ABA']) {
  test('成功后回读迟到不迁移其他身份或对象：' + change, () => {
    const h = mount(); h.ready(); edit(h)
    preflight(h).success({ code: 200 }); edit(h, 'name', '续编')
    const readback = h.requests.shift(), before = JSON.stringify(h.values)
    if (change === 'account') h.change('2')
    if (change === 'club') h.switchClub('10')
    if (change === 'detach') h.detach()
    if (change === 'ABA') { h.change('2'); h.change('1', 'new-session') }
    readback.success({ code: 200, data: { ...CLUB, name: '我的修改' } })
    assert.equal(JSON.stringify(h.values), before)
  })
}
test('回读冲突、坏档和迁移存储失败不覆盖原记录', () => {
  for (const mode of ['conflict', 'corrupt', 'write']) {
    const h = mount(); h.ready(); edit(h)
    preflight(h).success({ code: 200 }); edit(h, 'name', '续编')
    const key = drafts.key(1, 9), readback = h.requests.shift()
    if (mode === 'corrupt') h.values[key] = { damaged: true }
    if (mode === 'write') h.flags.write = true
    const before = JSON.stringify(h.values)
    readback.success({ code: 200, data: { ...CLUB, name: mode === 'conflict' ? '其他人资料' : '我的修改' } })
    assert.equal(JSON.stringify(h.values), before)
    assert.equal(h.c.data.name, '续编')
    assert.equal(h.c.data.draftBlocked || !!h.c.data.saveErrorText, true)
  }
})

test('权威回读不需要更新时间，无续编可正常关闭，业务异常可重试', () => {
  const h = mount(); h.ready(); edit(h)
  preflight(h).success({ code: 200 })
  h.requests.shift().success({ code: 200, data: { ...CLUB, name: '我的修改' } })
  h.timers.forEach(fn => fn())
  assert.equal(h.events.some(e => e.name === 'saved'), true)
  for (const res of [{ code: 500 }, { code: 200, data: null }]) {
    const f = mount(); f.ready(); edit(f)
    preflight(f).success({ code: 200 }); edit(f, 'name', '续编')
    f.requests.shift().success(res)
    assert.equal(f.c._draftBase, drafts.base(CLUB))
    assert.equal(drafts.read(f.wx, drafts.key(1, 9)).record.form.name, '续编')
    assert.match(f.c.data.saveErrorText, /核对/)
  }
})
test('待核对草稿重开遇到不匹配权威资料或迁移失败，保留原稿不允许恢复', () => {
  const h = mount(); h.ready(); edit(h)
  preflight(h).success({ code: 200 }); edit(h, 'name', '续编'); h.detach()
  for (const mode of ['conflict', 'write']) {
    const values = JSON.parse(JSON.stringify(h.values)), r = mount({ values })
    if (mode === 'write') r.flags.write = true
    const before = JSON.stringify(values)
    r.ready({ ...CLUB, name: mode === 'conflict' ? '其他修改' : '我的修改' })
    assert.equal(JSON.stringify(values), before)
    assert.equal(r.c.data.draftAvailable, false)
    assert.equal(r.c.data.draftBlocked, true)
  }
})

test('成功后权限变化回读与较新记录负控，不把payload当权威事实', () => {
  for (const mode of ['permission', 'newer']) {
    const h = mount(); h.ready(); edit(h)
    preflight(h).success({ code: 200 }); edit(h, 'name', '续编')
    const read = h.requests.shift(), key = drafts.key(1, 9)
    if (mode === 'newer') h.values[key].form.name = '另一个编辑者'
    const before = JSON.stringify(h.values)
    read.success({ code: 200, data: { ...CLUB, name: '我的修改',
      ...(mode === 'permission' ? { isOwner: false, viewerIsAdmin: true } : {}) } })
    assert.equal(JSON.stringify(h.values), before)
    assert.equal(h.c.data.draftBlocked, true)
    assert.equal(h.c._draftBase, drafts.base(CLUB))
  }
})

test('PublicClubVO缺失或非法可编辑字段不许默认为匹配并迁移', () => {
  const fields = ['name', 'logo', 'cover', 'description', 'activityPrefs', 'clubType',
    'city', 'keywords', 'style', 'prioritySignupEnabled', 'memberReservedQuota',
    'joinPolicy', 'joinPolicySupported', 'isOwner', 'viewerIsAdmin', 'viewerIdentityUnavailable', 'id']
  for (const field of fields) {
    for (const invalid of ['missing', {}, [], true, 'wrong-integer']) {
      const h = mount(); h.ready(); edit(h)
      preflight(h).success({ code: 200 }); edit(h, 'name', '续编')
      const read = h.requests.shift(), latest = { ...CLUB, name: '我的修改' }
      if (invalid === 'missing') delete latest[field]
      else latest[field] = invalid
      // true is valid for Boolean properties; nonempty strings valid for text.
      if (invalid === true && ['isOwner', 'viewerIsAdmin', 'joinPolicySupported'].includes(field)) continue
      if (invalid === 'wrong-integer' && ['name','logo','cover','description','activityPrefs','clubType','city','keywords','style'].includes(field)) continue
      const before = JSON.stringify(h.values)
      read.success({ code: 200, data: latest })
      assert.equal(JSON.stringify(h.values), before, field)
      assert.equal(h.c._draftConfirmed !== null, true, field)
      assert.equal(h.c.data.draftBlocked || !!h.c.data.saveErrorText, true, field)
    }
  }
})
test('PublicClubVO初读与预读不接受缺字段、错club或身份不可确认', () => {
  for (const patch of [{ id: undefined }, { id: 10 }, { logo: undefined },
    { memberReservedQuota: '0' }, { activityPrefs: [] }, { viewerIdentityUnavailable: true }]) {
    const h = mount(); const malformed = { ...CLUB, ...patch }; h.ready(malformed)
    assert.notEqual(h.c.data.loadState, 'ready')
    const live = mount(); live.ready(); edit(live)
    assert.equal(preflight(live, malformed), undefined)
    assert.equal(drafts.read(live.wx, drafts.key(1, 9)).record.form.name, '我的修改')
  }
})

test('DTO显式nullable字段允许按既有表单语义比较，缺字段仍不接受', () => {
  const nullable = { ...CLUB, name: null, city: null, description: null, joinPolicySupported: null }
  assert.equal(drafts.detail(nullable, 9), true)
  const h = mount(); h.ready({ ...CLUB, joinPolicySupported: null })
  edit(h); preflight(h, { ...CLUB, joinPolicySupported: null }).success({ code: 200 })
  edit(h, 'name', '续编')
  h.requests.shift().success({ code: 200, data: { ...CLUB, name: '我的修改', joinPolicySupported: null } })
  assert.equal(h.c._draftConfirmed, null)
  assert.equal(drafts.read(h.wx, drafts.key(1, 9)).record.form.name, '续编')
})
