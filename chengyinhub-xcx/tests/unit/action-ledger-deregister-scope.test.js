'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { targetsForHandler, deregisterRequestPlan, sourceFilesFor, handlerSlices } = require('../../scripts/uiaudit/build-action-ledger')
const ROOT = path.resolve(__dirname, '../..')
const file = 'components/cy/scene-settings-deregister/index.js'
const targets = handler => targetsForHandler(path.join(ROOT, file), handler)
const urls = handler => targets(handler).apiContracts.map(row => row.url)

test('注销共享16KB owner只跟踪真实委托闭包；局部UI不借用别的入口写入', () => {
  for (const handler of ['goCancellationNotice','closeNoticeSheet','inputCode','toggleConfirmed'])
    assert.deepEqual(urls(handler), [], handler)
  const sms = urls('sendSms')
  assert.ok(sms.includes('/api/sms/send')); assert.ok(sms.includes('/api/user/info'))
  assert.ok(!sms.includes('/api/user/deregister/apply')); assert.ok(!sms.includes('/api/user/deregister/cancel'))
  for (const handler of ['loadStatus','recheck','loginToRead']) {
    const actual = urls(handler)
    assert.ok(actual.includes('/api/user/deregister/status')); assert.ok(actual.includes('/api/user/deregister/precheck'))
    assert.ok(!actual.includes('/api/sms/send')); assert.ok(!actual.includes('/api/user/deregister/apply'))
    assert.ok(!actual.includes('/api/user/deregister/cancel'))
  }
  const cancel = urls('cancel')
  assert.ok(cancel.includes('/api/user/deregister/cancel')); assert.ok(cancel.includes('/api/user/deregister/precheck'))
  assert.ok(!cancel.includes('/api/sms/send')); assert.ok(!cancel.includes('/api/user/deregister/apply'))
  const sources = sourceFilesFor({entry:{js:file,wxml:file.replace('.js','.wxml')}})
  assert.ok(sources.includes('utils/deregister-flow.js'))
  assert.ok(Buffer.byteLength(fs.readFileSync(path.join(ROOT,'utils/deregister-flow.js'),'utf8')) > 16 * 1024)
  assert.match(handlerSlices(path.join(ROOT,file)).get('apply'), /requestId\(\)/)
})

test('注销申请保留原同意→写入→异常原状态查询，并登记互斥结果', () => {
  const actual = targets('apply'), plan = deregisterRequestPlan(file,'apply',actual.apiContracts)
  assert.deepEqual(plan.requiredRequests.map(row=>row.urlTemplate), ['/api/compliance/consents','/api/user/deregister/apply'])
  assert.deepEqual(plan.requiredRequestsByBranch.blocked, [])
  assert.deepEqual(plan.requiredRequestsByBranch['verification-error'].map(row=>row.urlTemplate), ['/api/compliance/consents'])
  assert.deepEqual(plan.requiredRequestsByBranch.timeout.map(row=>row.urlTemplate), ['/api/compliance/consents','/api/user/deregister/apply','/api/user/deregister/status'])
  assert.deepEqual(plan.requiredRequestOutcomes.timeout, ['success','timeout','success'])
  assert.deepEqual(plan.requiredRequestOutcomes['status-error'], ['success','error','error'])
  assert.deepEqual(plan.writeApis, ['/api/compliance/consents','/api/user/deregister/apply'])
  for (const missing of plan.writeApis) assert.throws(()=>deregisterRequestPlan(file,'apply',actual.apiContracts.filter(row=>row.url!==missing)),/缺少真实接口/)
  assert.throws(()=>deregisterRequestPlan(file,'apply',actual.apiContracts.concat({url:'/api/sms/send',method:'POST'})),/未登记请求/)
  assert.equal(deregisterRequestPlan(file,'sendSms',actual.apiContracts), null)
})

test('据点出码重试只写issue，TTL常量不带入核销scanner和其他人的写入', () => {
  const actual=targetsForHandler(path.join(ROOT,'subpackageRoam/citynode-code/index.js'),'onRetry')
  assert.ok(actual.apiContracts.some(row=>row.url==='/api/verify/citynode/issue'&&row.method==='POST'))
  for(const forbidden of ['/api/verify/groupcode/redeem','/api/coupon/verification','/api/registration/scan_dynamic_code','/api/registration/scan_qr_code'])
    assert.ok(!actual.apis.includes(forbidden),forbidden)
  // A callable/unknown imported value does not satisfy the primitive constant proof.
  const directory=fs.mkdtempSync(path.join(ROOT,'scripts/uiaudit/.ttl-scope-'))
  try {
    const fixture=path.join(directory,'index.js')
    fs.writeFileSync(fixture,"const {DYN_TTL_MS}=require('../../../utils/verification-scan.js');Page({onRetry(){DYN_TTL_MS();}});")
    assert.ok(targetsForHandler(fixture,'onRetry').apis.includes('/api/verify/groupcode/redeem'))
  } finally {fs.rmSync(directory,{recursive:true,force:true})}
})
