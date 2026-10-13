const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = relative => fs.readFileSync(path.join(ROOT, relative), 'utf8')

test('邀约对象名在未加载时为空字符串，商家名优先且可回退真实昵称', () => {
  const vm = require('node:vm')
  const name = (file, data) => vm.runInNewContext(read(file).match(/target-name="\{\{([^}]+)\}\}"/)[1], data)
  const profile = 'subpackageMember/components/cy/profile/index.wxml'
  const club = 'pages/club/detail/index.wxml'
  assert.equal(name(profile, { subjectMerchant: null, userInfo: {} }), '')
  assert.equal(name(profile, { subjectMerchant: null, userInfo: { nickname: '会员昵称' } }), '会员昵称')
  assert.equal(name(profile, { subjectMerchant: { name: '店铺名称' }, userInfo: { nickname: '会员昵称' } }), '店铺名称')
  assert.equal(name(profile, { subjectMerchant: { name: '' }, userInfo: { nickname: null } }), '')
  assert.equal(name(club, { club: null }), '')
  assert.equal(name(club, { club: { name: '俱乐部名称' } }), '俱乐部名称')
})

test('邀约主题名和操作范围在首次加载为空字符串，加载后原样透传', () => {
  const vm = require('node:vm')
  for (const [file, scopeKey] of [
    ['pages/club/detail/index.wxml', 'coopInviteScope'],
    ['subpackageMember/components/cy/profile/index.wxml', 'operationScope'],
  ]) {
    for (const [attribute, key, value] of [
      ['topic-name', 'topicName', '城市探索'],
      ['operation-scope', scopeKey, 'MERCHANT'],
    ]) {
      const expression = read(file).match(new RegExp(attribute + '=\"\\{\\{([^}]+)\\}\\}\"'))[1]
      for (const empty of [null, undefined, '']) {
        assert.equal(vm.runInNewContext(expression, { [key]: empty }), '', file + ':' + attribute)
      }
      assert.equal(vm.runInNewContext(expression, { [key]: value }), value, file + ':' + attribute)
    }
  }
})

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
  assert.match(clubView, /target-name="\{\{\(club && club\.name\) \|\| ''\}\}"/)
  assert.match(merchant, /coopInviteShow: true/)
  assert.match(merchantView, /target-id="\{\{userId\}\}"/)
  assert.match(merchantView, /target-name="\{\{\(subjectMerchant && subjectMerchant\.name\) \|\| userInfo\.nickname \|\| ''\}\}"/)
  assert.match(invite, /logo:\s*routeText\(options\.toLogo\)/)
  assert.match(invite, /meta:\s*routeText\(options\.toMeta\)/)
})
