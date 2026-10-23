const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/officialevent/index.vue'), 'utf8')

function mount(send, permitted = true) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+\{([^}]*)\}\s+from\s+['"][^'"]+['"];?\s*$/gm,
      (_, names) => 'const { ' + names.split(',').map(name => name.trim()).filter(Boolean).join(', ') + ' } = __deps')
    .replace(/^import\s+(\w+)\s+from\s+['"][^'"]+['"];?\s*$/gm, 'const $1 = __deps.$1')
    .replace(/export\s+default\s*/, 'return ')
  const deps = { broadcastOfficial: send, auth: { hasPermi: () => permitted } }
  const component = new Function('__deps', script)(deps)
  const vm = {}
  for (const [name, fn] of Object.entries(component.methods)) vm[name] = fn.bind(vm)
  Object.assign(vm, component.data.call(vm))
  const successes = [], warnings = []
  vm.$modal = { msgSuccess: msg => successes.push(msg), msgWarning: msg => warnings.push(msg) }
  vm.notifyOpen = true
  vm.notifyEventId = 7
  vm.notify = { audience: ['player'], title: '官方通知', content: '活动说明', channels: ['inapp', 'subscribe'], city: '' }
  return { vm, successes, warnings }
}

test('发布响应只确认提交；UNKNOWN/部分成功不能从响应文案推断全部送达', async () => {
  for (const msg of [undefined, '', '通知已发送,触达 1 人']) {
    let finish
    const sent = []
    const h = mount(data => { sent.push(data); return new Promise(resolve => { finish = resolve }) })
    h.vm.submitNotify()
    assert.equal(sent.length, 1)
    assert.equal(sent[0].eventId, 7)
    assert.equal(sent[0].channels, 'inapp,subscribe')
    assert.equal(h.successes.length, 0, '请求尚未确认前不显示提交成功')
    assert.equal(h.vm.notifyOpen, true)
    finish({ code: 200, data: 71, msg })
    await Promise.resolve()
    assert.deepEqual(h.successes, ['通知已提交'])
    assert.equal(h.vm.notifyOpen, false)
  }
})

test('待发/部分成功/UNKNOWN 的主状态显示未全部确认，非法状态不冒充送达', () => {
  const h = mount(() => Promise.resolve())
  assert.equal(h.vm.broadcastStatusText(1), '投递未全部确认')
  assert.equal(h.vm.broadcastStatusText(2), '投递已确认')
  for (const state of [null, undefined, 'UNKNOWN', 'constructor']) {
    assert.equal(h.vm.broadcastStatusText(state), '状态未知')
  }
})

test('无权限或缺受众仍拒绝发布，结果文案修改不产生额外请求', () => {
  for (const permitted of [false, true]) {
    const sent = []
    const h = mount(data => { sent.push(data); return Promise.resolve() }, permitted)
    if (permitted) h.vm.notify.audience = []
    h.vm.submitNotify()
    assert.equal(sent.length, 0)
    assert.equal(h.successes.length, 0)
    assert.equal(h.warnings.length, 1)
    assert.equal(h.vm.notifyOpen, true)
  }
})
