'use strict'
// BL-122 第十批(BL-116..121)新接的接口合同:路径/方法/入参/返回,逐条对照 Java 源码。
// 消费方只有 #164(BL-120 另存为模板);BL-116 已实现(#160),BL-117/118/119/121 BLOCKED(#161/#162/#163/#165)无新调用。
// 页面处理器单测随 BL-120 交付:myproject-save-as-template.test.js。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const REPO = path.resolve(ROOT, '..')
const API = 'chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/'
const readRepo = (file) => fs.readFileSync(path.join(REPO, file), 'utf8')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function block(src, marker, len) {
  const at = src.indexOf(marker)
  assert.ok(at >= 0, '应有 ' + marker)
  return src.slice(at, at + (len || 1600))
}

test('BL-120 POST /api/template/topic-template/save-as:表单参数 id+scope,本人+审核通过,回包无 topicId', () => {
  const src = readRepo(API + 'ApiTopicTemplateController.java')
  assert.ok(src.includes('@RequestMapping("/api/template/topic-template")'))
  const body = block(src, '@PostMapping("/save-as")')
  // 不是 @RequestBody:小程序 data 平铺 id/scope 即可
  assert.match(body, /public AjaxResult saveAs\(String id, String scope\)/)
  assert.match(body, /MerchantPermission\.PROJECT_MANAGE/)
  assert.match(body, /只能把自己的主题另存为模板/)
  assert.match(body, /topic\.getStatus\(\) != 1/)
  assert.match(body, /saveAsTemplate\(tid, memberId, 1\)/)
  assert.match(body, /success\("已另存为主题模板"\)/)
  const end = body.indexOf('\n    }\n')
  assert.doesNotMatch(body.slice(0, end), /topicId/, '回包没有 topicId,前端不得依赖')
})

test('BL-120 save-as 是置位 is_template(不复制),前端文案不得写成复制一份', () => {
  const xml = readRepo('chengyinhub-system/src/main/resources/mapper/business/CmsTopicMapper.xml')
  assert.match(xml, /<update id="updateTopicIsTemplate">\s*update cms_topic set is_template = #\{flag\}\s*where id = #\{id\} and member_id = #\{memberId\}/)
  const js = read('subpackageA/pages/myproject/index.js')
  const wxml = read('subpackageA/pages/myproject/index.wxml')
  for (const s of [js, wxml]) assert.doesNotMatch(s, /另存[^\n]*(复制一份|原主题不受影响)/)
})

test('BL-120 前端调用与合同一致:POST + 只发 id/scope + 不读回包 data', () => {
  const js = read('subpackageA/pages/myproject/index.js')
  const call = block(js, "url: '/api/template/topic-template/save-as'", 700)
  assert.match(call, /method: 'POST'/)
  assert.match(call, /data: \{ id: id, scope: this\.data\.operationScope \}/)
  assert.doesNotMatch(call, /res\.data/)
  // 回调不在 method shorthand 里用 this
  assert.doesNotMatch(call.slice(call.indexOf('success('), call.indexOf('complete: release')), /\bthis\./)
})

test('BL-120 可另存前置条件与后端 status==1 对齐:只放 project/my 里审核通过后的派生态', () => {
  const ctl = readRepo(API + 'ApiProjectController.java')
  const lc = block(ctl, 'private String lifecycleState(int status', 500)
  assert.match(lc, /if \(isDraft\) return "draft";\s*if \(status == 2\) return "rejected";\s*if \(status == 0\) return "pending";/)
  const js = read('subpackageA/pages/myproject/index.js')
  assert.match(js, /const canSaveAsTemplate = p\.bizType === 'topic'\s*&& \(online \|\| p\.state === 'offline' \|\| p\.state === 'completed'\);/)
})
