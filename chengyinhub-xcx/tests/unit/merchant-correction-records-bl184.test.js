'use strict'
// BL-184:我的商家纠错列表/详情 + 文字补证。结构照「我的举报」;字段与状态只取 MerchantContributionVO。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const view = require('../../subpackageMember/correction-common/view.js')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')
const readRepo = (p) => fs.readFileSync(path.join(REPO, p), 'utf8')
const CONTROLLER = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiCommunityTrustController.java'
const SERVICE = 'chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/CommunityTrustServiceImpl.java'
const VO = 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/vo/MerchantContributionVO.java'
const DTO = 'chengyinhub-system/src/main/java/com/chengyinhub/business/domain/dto/MerchantContributionSupplementRequest.java'

test('合同:三个接口与补证 DTO 字段对照 Java 源码', () => {
  const ctl = readRepo(CONTROLLER)
  assert.match(ctl, /@GetMapping\("\/contributions\/mine"\)/)
  assert.match(ctl, /@GetMapping\("\/contributions\/\{contributionId\}"\)/)
  assert.match(ctl, /@PostMapping\("\/contributions\/supplement"\)/)
  const dto = readRepo(DTO)
  const body = view.buildSupplement({ id: '7', version: 2 }, '地址在 3 号门', 'mcs-7-abc-xyz')
  for (const key of Object.keys(body)) assert.match(dto, new RegExp('private \\w+(<\\w+>)? ' + key + ';'), key)
  assert.deepEqual(body, { contributionId: 7, expectedVersion: 2, description: '地址在 3 号门', requestId: 'mcs-7-abc-xyz' })
  assert.equal(body.evidenceAssetIds, undefined, '本批后端拒绝附件')
  const vo = readRepo(VO)
  for (const f of ['id', 'contributionType', 'issueType', 'description', 'reviewStatus', 'reviewReason',
    'canSupplement', 'reviewedAt', 'mergedAt', 'version', 'createTime', 'rewardStatus', 'rewardPoints']) {
    assert.match(vo, new RegExp('private \\w+(<[\\w, ]+>)? ' + f + '\\b'), f)
  }
  const svc = readRepo(SERVICE)
  assert.match(svc, /STATUS_NEEDS_EVIDENCE\.equals\(row\.getReviewStatus\(\)\)\s*\|\| MerchantContribution\.STATUS_REJECTED/)
  const statuses = svc.match(/"reviewStatuses", Arrays\.asList\(([^)]*)\)/)[1].match(/[A-Z_]+/g).sort()
  assert.deepEqual(Object.keys(view.STATUS).sort(), statuses)
  const rewards = svc.match(/"rewardStatuses", Arrays\.asList\(([^)]*)\)/)[1].match(/[A-Z_]+/g).sort()
  assert.deepEqual(Object.keys(view.REWARD_TEXT).sort(), rewards)
})

test('映射:状态/标题/补证条件只取 VO 真值,未知状态不编造', () => {
  const row = view.normalizeContribution({ id: 9, contributionType: 'ISSUE_REPORT', issueType: 'ADDRESS',
    reviewStatus: 'NEEDS_EVIDENCE', canSupplement: true, version: 1, description: '搬了', reviewReason: '请写新地址',
    createTime: '2026-10-16T02:00:00+00:00', rewardStatus: 'PENDING_REVIEW' }, 480)
  assert.equal(row.id, '9')
  assert.equal(row.title, '地址不对')
  assert.equal(row.statusText, '需补充说明')
  assert.equal(row.canSupplement, true)
  assert.equal(row.decided, false)
  assert.equal(row.createText, '2026-10-16 10:00')
  assert.equal(row.rewardText, '奖励在审核通过后结算')
  assert.equal(view.normalizeContribution({ id: 9, reviewStatus: 'PENDING', canSupplement: true, version: 1 }).canSupplement, false)
  assert.equal(view.normalizeContribution({ id: 9, reviewStatus: 'REJECTED', canSupplement: false, version: 1 }).canSupplement, false)
  assert.equal(view.normalizeContribution({ id: 9, reviewStatus: 'REJECTED', canSupplement: true }).canSupplement, false, '无版本号不可补证')
  const odd = view.normalizeContribution({ id: 3, reviewStatus: 'WEIRD', contributionType: 'INFO_UPDATE' })
  assert.equal(odd.statusText, '状态待同步')
  assert.equal(odd.title, '商家信息更新')
  assert.equal(view.normalizeContribution({ id: 3, reviewStatus: 'CREDITED_X', rewardStatus: 'CREDITED', rewardPoints: 5 }).rewardText, '奖励已到账：5 积分')
  assert.equal(view.normalizeContribution({ id: 0, reviewStatus: 'PENDING' }), null)
  assert.equal(view.normalizeList([{ id: 1, reviewStatus: 'PENDING' }, {}]), null, '有坏行整份当不可信')
  const rows = view.normalizeList([{ id: 1, reviewStatus: 'PENDING' }, { id: 2, reviewStatus: 'MERGED' }])
  assert.deepEqual(view.filterRows(rows, 'active').map((r) => r.id), ['1'])
  assert.deepEqual(view.filterRows(rows, 'decided').map((r) => r.id), ['2'])
  assert.equal(view.validateSupplement('  ').ok, false)
  assert.equal(view.validateSupplement('x'.repeat(2001)).ok, false)
  const key = view.makeRequestId(7, 1700000000000, 'abc')
  assert.ok(key.length >= 8 && key.length <= 96)
  assert.equal(view.supplementConfirmed(1, { version: 2, canSupplement: false }), true)
  assert.equal(view.supplementConfirmed(1, { version: 1, canSupplement: true }), false)
  assert.equal(view.supplementConfirmed(1, null), false)
})

function loadPage(rel, requests) {
  let def
  global.getApp = () => ({ globalData: {}, sendRequest: (o) => requests.push(o) })
  global.Page = (d) => { def = d }
  global.wx = { stopPullDownRefresh() {}, navigateTo() {}, showToast() {} }
  const file = path.join(ROOT, rel)
  delete require.cache[require.resolve(file)]
  require(file)
  const p = Object.assign({}, def, { data: JSON.parse(JSON.stringify(def.data)) })
  p.setData = (patch, cb) => { Object.assign(p.data, patch); if (cb) cb() }
  return p
}

test('列表页:读 /contributions/mine,失败给失败态,空列表给空态', () => {
  const requests = []
  const p = loadPage('subpackageMember/correction-mine/index.js', requests)
  p.onLoad()
  const r = requests.shift()
  assert.equal(r.url, '/api/community-trust/contributions/mine')
  assert.equal(r.method, 'GET')
  r.fail()
  assert.equal(p.data.state, 'error')
  p.retry()
  requests.shift().success({ code: 200, data: [] })
  assert.equal(p.data.state, 'empty')
})

test('详情页:补证成功后回读确认;网络未知用同一幂等键重试;403 给无权限', () => {
  const requests = []
  const p = loadPage('subpackageMember/correction-detail/index.js', requests)
  p.onLoad({ contributionId: '7' })
  const first = requests.shift()
  assert.equal(first.url, '/api/community-trust/contributions/7')
  first.success({ code: 200, data: { id: 7, reviewStatus: 'NEEDS_EVIDENCE', canSupplement: true, version: 1 } })
  assert.equal(p.data.state, 'ready')
  p.openSupplement()
  p.onSupplementInput({ detail: { value: '新地址在 3 号门' } })
  p.submitSupplement()
  const post = requests.shift()
  assert.equal(post.url, '/api/community-trust/contributions/supplement')
  assert.equal(post.method, 'POST')
  const body = JSON.parse(post.data)
  assert.equal(body.expectedVersion, 1)
  p.submitSupplement()
  assert.equal(requests.length, 0, '在途不重复提交')
  post.fail()
  requests.shift().fail() // 回读也失败 → 结果未知
  assert.equal(p.data.sheet, 'unknown')
  p.submitSupplement()
  const retry = requests.shift()
  assert.equal(JSON.parse(retry.data).requestId, body.requestId)
  retry.success({ code: 200, data: { id: 7 } })
  requests.shift().success({ code: 200, data: { id: 7, reviewStatus: 'PENDING', canSupplement: false, version: 2 } })
  assert.equal(p.data.sheet, 'ok')
  assert.equal(p.data.item.statusText, '待审核')
  assert.equal(p.data.item.canSupplement, false)

  const q = loadPage('subpackageMember/correction-detail/index.js', requests)
  q.onLoad({ contributionId: '8' })
  requests.shift().success({ code: 403, msg: '无权访问' })
  assert.equal(q.data.state, 'no-permission')
})

test('详情页:业务拒绝原话展示,不给成功', () => {
  const requests = []
  const p = loadPage('subpackageMember/correction-detail/index.js', requests)
  p.onLoad({ contributionId: '7' })
  requests.shift().success({ code: 200, data: { id: 7, reviewStatus: 'REJECTED', canSupplement: true, version: 3 } })
  p.openSupplement()
  p.onSupplementInput({ detail: { value: '补充' } })
  p.submitSupplement()
  requests.shift().success({ code: 409, msg: '贡献状态已变化，请刷新' })
  assert.equal(p.data.sheet, 'fail')
  assert.equal(p.data.failText, '贡献状态已变化，请刷新')
})

test('入口:纠错半屏成功态只多一个次按钮「查看我的纠错」;新页沿用 cy-nav-bar + cy-page-title', () => {
  const wxml = read('components/cy/merchant-correction-sheet/index.wxml')
  assert.match(wxml, /kind="success"[\s\S]*?secondary-text="查看我的纠错"[^>]*bind:secondary="openMine"/)
  assert.equal((wxml.match(/查看我的纠错/g) || []).length, 1)
  assert.match(read('components/cy/merchant-correction-sheet/index.js'), /\/subpackageMember\/correction-mine\/index/)
  for (const page of ['correction-mine', 'correction-detail']) {
    const w = read('subpackageMember/' + page + '/index.wxml')
    assert.match(w, /<cy-nav-bar plain custom-back bind:back="goBack" \/>/)
    assert.match(w, /<cy-page-title title="[^"]+" safe-top="\{\{false\}\}" \/>/)
  }
})

test('BL-184: 通过/已更新用绿色对勾，不用「已结束」的 × 样式；未通过保持中性', () => {
  assert.equal(view.STATUS.APPROVED.tone, 'green')
  assert.equal(view.STATUS.MERGED.tone, 'green')
  assert.equal(view.STATUS.REJECTED.tone, 'mono')
})
