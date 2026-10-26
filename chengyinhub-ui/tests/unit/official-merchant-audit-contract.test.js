const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8')
const apiSource = fs.readFileSync(path.resolve(__dirname, '../../src/api/business/auditWorkbench.js'), 'utf8')

test('新增商家节点按选中任务提交版本和整数状态，媒体审批保持独立入口与权限', async () => {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ')
  const sent = []
  let failing = false
  const send = kind => payload => {
    sent.push([kind, { ...payload }])
    return failing ? Promise.reject(Error('审核失败')) : Promise.resolve()
  }
  const component = new Function('reviewAuditTask', 'reviewMediaAsset', script)(send('audit'), send('media'))
  let refreshed = 0
  let successes = 0
  const vm = { ...component.data(), ...component.methods, getList() { refreshed++ },
    $modal: { msgSuccess() { successes++ } } }
  for (const bizType of ['merchant_node', 'merchant_node_template', 'MEDIA_ASSET']) {
    for (const status of [1, 2]) {
      vm.detailTask = { bizType: bizType === 'MEDIA_ASSET' ? 'merchant_node' : 'MEDIA_ASSET' }
      vm.detailOpen = true
      vm.openReview({ id: 17, version: 5, bizType }, status)
      vm.reviewForm.reasonText = '已核对证据'
      await vm.submitReview()
      assert.deepEqual(sent.at(-1), [bizType === 'MEDIA_ASSET' ? 'media' : 'audit',
        { taskId: 17, expectedVersion: 5, status, reasonText: '已核对证据' }])
      assert.equal(vm.reviewOpen, false)
      assert.equal(vm.detailOpen, false)
    }
  }
  assert.equal(refreshed, 6)
  assert.equal(successes, 6)
  failing = true
  for (const bizType of ['merchant_node', 'MEDIA_ASSET']) {
    vm.detailOpen = true
    vm.openReview({ id: 17, version: 5, bizType }, 2)
    await assert.rejects(vm.submitReview(), /审核失败/)
    assert.equal(vm.reviewOpen, true)
    assert.equal(vm.detailOpen, true)
  }
  assert.equal(refreshed, 6, '失败不刷新成成功')
  assert.equal(successes, 6, '失败不提示审核成功')
  assert.match(source, /scope\.row\.bizType === 'MEDIA_ASSET' \? \['business:mediaReview:approve'\] : \['business:audit:review'\]/)
  assert.match(source, /scope\.row\.bizType === 'MEDIA_ASSET' \? \['business:mediaReview:reject'\] : \['business:audit:review'\]/)
  assert.match(apiSource, /data\.status === 1 \? '\/business\/mediaReview\/approve' : '\/business\/mediaReview\/reject'/)
})

test('审核工作台可筛选商家城市节点和节点模板', () => {
  assert.match(source, /\{ label: '商家城市节点', value: 'merchant_node' \}/)
  assert.match(source, /\{ label: '商家节点模板', value: 'merchant_node_template' \}/)
})

test('新增商家节点类型仍复用审核快照与通过驳回动作', () => {
  assert.match(source, /snapshotJson/)
  assert.match(source, /openReview\(detailTask, 1\)/)
  assert.match(source, /openReview\(detailTask, 2\)/)
  assert.match(source, /const review = this\.reviewBizType === 'MEDIA_ASSET' \? reviewMediaAsset : reviewAuditTask/)
  assert.match(source, /const payload = this\.reviewPayload\(\)/)
  assert.match(source, /return review\(payload\)/)
})
