const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = relative => fs.readFileSync(path.join(ROOT, relative), 'utf8')

test('商家入驻明确展示四步计数、分段进度、大标题与纵向字段', () => {
  const wxml = read('pages/merchant/apply/index.wxml')
  const wxss = read('pages/merchant/apply/index.wxss')
  assert.match(wxml, /\{\{step\}\} \/ 4/)
  assert.match(wxml, /stepHeadlines\[step - 1\]/)
  assert.match(wxml, /class="frow frow--field"/)
  assert.match(wxss, /grid-template-columns:\s*repeat\(4, 1fr\)/)
  assert.match(wxss, /\.frow--field\s*\{[^}]*flex-direction:\s*column/)
  assert.doesNotMatch(wxml, /class="wizard-secondary"/)
})

test('搜索结果只把主题提升为海报卡，活动详情结构不在本轮重做', () => {
  const wxml = read('pages/search2/result/index.wxml')
  assert.match(wxml, /item\.type === 'topic'/)
  assert.match(wxml, /class="topic-result-card"/)
  assert.match(wxml, /class="result-card"/)
  assert.match(wxml, /\{\{item\.detail\}\}/)

  const official = read('pages/activity/official-detail/index.wxml')
  assert.match(official, /class="od-page"/)
  assert.doesNotMatch(official, /topic-result-card/)
})

test('预置协作对象入口向页内邀约弹窗透传真实目标，邀请页仍兼容头像与说明', () => {
  const club = read('pages/club/detail/index.js')
  const merchant = read('subpackageMember/components/cy/profile/index.js')
  const invite = read('pages/coop/invite/index.js')
  const clubView = read('pages/club/detail/index.wxml')
  const merchantView = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(club, /coopInviteShow: true/)
  assert.match(clubView, /target-id="\{\{clubId\}\}"/)
  const binding = (view, name, data) => {
    const sheet = view.match(/<cy-coop-invite-sheet\b[^>]*>/)[0]
    const expression = sheet.match(new RegExp(name + '="\\{\\{(.*?)\\}\\}"'))[1]
    return require('node:vm').runInNewContext(expression, data)
  }
  assert.equal(binding(clubView, 'target-name', { club: null }), '')
  assert.equal(binding(clubView, 'target-name', { club: { name: '真实俱乐部' } }), '真实俱乐部')
  assert.equal(binding(clubView, 'topic-name', { topicName: undefined }), '')
  assert.equal(binding(clubView, 'operation-scope', { coopInviteScope: undefined }), '')
  assert.equal(binding(clubView, 'operation-scope', { coopInviteScope: 'CLUB' }), 'CLUB')
  assert.match(merchant, /coopInviteShow: true/)
  assert.match(merchantView, /target-id="\{\{userId\}\}"/)
  assert.equal(binding(merchantView, 'target-name', { subjectMerchant: null, userInfo: { nickname: '当前资料名' }, nickname: '旧顶层名' }), '当前资料名')
  assert.equal(binding(merchantView, 'target-name', { subjectMerchant: { name: '真实门店' }, userInfo: { nickname: '当前资料名' } }), '真实门店')
  assert.equal(binding(merchantView, 'target-name', { subjectMerchant: null, userInfo: null }), '')
  assert.equal(binding(merchantView, 'topic-name', { topicName: undefined }), '')
  assert.equal(binding(merchantView, 'operation-scope', { operationScope: undefined }), '')
  assert.equal(binding(merchantView, 'operation-scope', { operationScope: 'MERCHANT' }), 'MERCHANT')
  assert.match(invite, /logo:\s*routeText\(options\.toLogo\)/)
  assert.match(invite, /meta:\s*routeText\(options\.toMeta\)/)
})
