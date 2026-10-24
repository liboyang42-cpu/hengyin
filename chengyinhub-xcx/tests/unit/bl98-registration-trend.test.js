'use strict'

// BL-98 报名按日趋势:纯函数 + 页面接线 + 接口合同(对照 ApiRegistrationController.countByDate / CmsRegistrationMapper.selectCountGroupByDate)。

const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const { presentTrendRows } = require('../../pages/club/topic-detail/registration-trend.js')

test('presentTrendRows 保序映射日期与人数', () => {
  assert.deepEqual(presentTrendRows([
    { participateDate: '2026-10-01', totalCount: 3 },
    { participateDate: '2026-10-03', totalCount: '5' },
  ]), [
    { key: '2026-10-01', dateText: '2026-10-01', countText: '3 人' },
    { key: '2026-10-03', dateText: '2026-10-03', countText: '5 人' },
  ])
  assert.deepEqual(presentTrendRows([]), [])
})

test('presentTrendRows 形状不对返回 null(页面走错误态,不编造)', () => {
  assert.equal(presentTrendRows(null), null)
  assert.equal(presentTrendRows({ rows: [] }), null)
  assert.equal(presentTrendRows([{ participateDate: '', totalCount: 1 }]), null)
  assert.equal(presentTrendRows([{ participateDate: '2026-10-01', totalCount: 'x' }]), null)
  assert.equal(presentTrendRows([null]), null)
})

test('页面:打开报名参与者时加载趋势,form 绑定 owner_type=1 + topicId,失败原样回显', () => {
  const js = read('pages/club/topic-detail/index.js')
  assert.match(js, /this\.loadCustomers\(\);\n\s*this\.loadRegistrationTrend\(\);/)
  const body = js.slice(js.indexOf('loadRegistrationTrend() {'), js.indexOf('// 定向广播的弹层是导演台那套'))
  assert.match(body, /url: '\/api\/registration\/count_by_date', method: 'POST'/)
  assert.match(body, /data: \{ owner_type: 1, owner_id: this\._topicId \}/)
  assert.match(body, /application\/x-www-form-urlencoded/)
  assert.match(body, /res\.msg\.trim\(\)/)
  assert.doesNotMatch(body, /success\(res\) \{[^}]*this\./, '回调里不用 this')
  const wxml = read('pages/club/topic-detail/index.wxml')
  assert.match(wxml, /bind:retry="loadRegistrationTrend"/)
  assert.match(wxml, /wx:for="\{\{trendRows\}\}" wx:key="key"/)
})

test('合同:后端路径/参数/返回字段与前端一致', () => {
  const ctl = fs.readFileSync(path.join(REPO, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiRegistrationController.java'), 'utf8')
  assert.match(ctl, /@PostMapping\("\/count_by_date"\)[\s\S]*?public AjaxResult countByDate\(String owner_type, String owner_id\)/)
  assert.match(ctl, /return registrationQueryApplication.countByDate\(principalMemberId, owner_type, owner_id\)/)
  const application = fs.readFileSync(path.join(REPO, 'chengyinhub-admin/src/main/java/com/chengyinhub/web/service/registration/RegistrationQueryApplication.java'), 'utf8')
  assert.match(application, /无权查看该项目趋势/)
  const xml = fs.readFileSync(path.join(REPO, 'chengyinhub-system/src/main/resources/mapper/business/CmsRegistrationMapper.xml'), 'utf8')
  const sel = xml.slice(xml.indexOf('id="selectCountGroupByDate"'))
  assert.match(sel, /participate_date AS participateDate/)
  assert.match(sel, /AS totalCount/)
})
