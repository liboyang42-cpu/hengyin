/**
 * R9-26 / 官方 v3 回归: 枚举展示必须走业务名 + 自有键, 且控制状态未知时不得伪装成正常。
 *
 * 1. 控制状态只认后端 NORMAL/PAUSED; 未知/null 一律「状态待确认」且不出现暂停/恢复动作。
 * 2. statusText/policyText/broadcastStatusText 等全部展示 helper 复用 enumText,
 *    'constructor'/'__proto__' 不得命中 Object 原型渲染 native function。
 * 3. ROAM_SESSION_AREA(P1, 服务端禁止发布) 只做只读中文展示, 不进编辑器可选 missionTypes。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const source = fs.readFileSync(path.join(uiRoot, 'src/views/business/officialevent/index.vue'), 'utf8')

function loadOfficial() {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s+\{([^}]*)\}\s+from\s+['"][^'"]+['"];?\s*$/gm, (_, names) => {
      const declarations = names.split(',').map(x => x.trim()).filter(Boolean).map(x => {
        const [original, alias] = x.split(/\s+as\s+/)
        return `${alias || original}: ${alias || original}`
      })
      return `const { ${declarations.join(', ')} } = __deps`
    })
    .replace(/^import\s+(\w+)\s+from\s+['"][^'"]+['"];?\s*$/gm, 'const $1 = __deps.$1')
    .replace(/export\s+default\s*/, 'return ')
  const callable = new Proxy({}, { get: () => (...args) => Promise.resolve({ rows: [], total: 0, data: {} }) })
  return new Function('__deps', script)(callable)
}

function methodVm(component) {
  const vm = {}
  for (const [name, fn] of Object.entries(component.methods || {})) vm[name] = fn.bind(vm)
  const data = component.data ? component.data.call(vm) : {}
  return Object.assign(vm, data)
}

const official = loadOfficial()
const vm = methodVm(official)

test('官方 v3 控制状态未知/null 一律「状态待确认」, 合法 NORMAL/PAUSED 保留', () => {
  assert.equal(vm.controlStateText('NORMAL'), '运行正常')
  assert.equal(vm.controlStateText('PAUSED'), '已暂停')
  assert.equal(vm.controlStateText('UNKNOWN'), '状态待确认')
  assert.equal(vm.controlStateText(null), '状态待确认')
  assert.equal(vm.controlStateText(undefined), '状态待确认')
  assert.equal(vm.controlStateText('constructor'), '状态待确认', '不得命中原型')

  assert.equal(vm.controlStateTone('NORMAL'), 'success')
  assert.equal(vm.controlStateTone('PAUSED'), 'danger')
  assert.equal(vm.controlStateTone('UNKNOWN'), 'warning')
  assert.equal(vm.controlStateClass('UNKNOWN'), 'state-unknown')
  assert.equal(vm.controlStateClass('NORMAL'), 'state-normal')
  assert.equal(vm.controlStateClass('PAUSED'), 'state-paused')
})

test('官方 v3 控制状态未知时模板不渲染正常态, 也不发暂停/恢复动作', () => {
  assert.match(source, /controlStateText\(activity360\.controlState\)/)
  assert.match(source, /controlStateTone\(activity360\.controlState\)/)
  assert.match(source, /v-if="activity360\.controlState === 'PAUSED'"[\s\S]*?恢复活动/)
  assert.match(source, /v-else-if="activity360\.controlState === 'NORMAL'"[\s\S]*?暂停活动/)
  assert.match(source, /v-else class="control-unknown">控制状态待确认，已停用暂停\/恢复/)
  assert.doesNotMatch(source, /controlState === 'PAUSED' \? 'danger' : 'success'/, '不得再用二元三元把未知当正常')
  assert.doesNotMatch(source, /controlState === 'PAUSED' \? '已暂停' : '运行正常'/, '不得再用二元三元把未知当正常')
  assert.doesNotMatch(source, /controlState === 'PAUSED' \? 'state-paused' : 'state-normal'/, '列表不得再用二元三元')
})

test('官方 v3 全部展示 helper 走 enumText, 不被 Object 原型污染', () => {
  const hostile = ['constructor', '__proto__', 'hasOwnProperty', 'toString', 'valueOf']
  for (const key of hostile) {
    assert.equal(vm.statusText(key), '未知', `statusText(${key})`)
    assert.equal(vm.policyText(key), '未设置', `policyText(${key})`)
    assert.equal(vm.broadcastStatusText(key), '状态未知', `broadcastStatusText(${key})`)
    assert.equal(vm.controlStateText(key), '状态待确认', `controlStateText(${key})`)
    assert.equal(vm.partyRoleText(key), '未记录职责', `partyRoleText(${key})`)
    assert.equal(vm.partyTypeText(key), '未记录主体', `partyTypeText(${key})`)
    assert.equal(vm.partyStatusText(key), '状态待确认', `partyStatusText(${key})`)
    assert.equal(vm.missionTypeText(key), '未记录任务类型', `missionTypeText(${key})`)
  }
  for (const value of [vm.statusText('constructor'), vm.policyText('constructor'), vm.broadcastStatusText('constructor')]) {
    assert.doesNotMatch(String(value), /native|function|\[object/, '不得渲染原生函数')
  }
})

test('官方 360 参与/发奖/暂停通知/绑定类型全部走业务名, 不裸露原始枚举', () => {
  assert.equal(vm.participationStateText('ACTIVE'), '参与有效')
  assert.equal(vm.participationStateText('LEFT'), '已退出')
  assert.equal(vm.participationStateText('REFUNDED'), '已退款')
  assert.equal(vm.participationStateText('BANNED'), '已封禁')
  assert.equal(vm.participationStateText('constructor'), '状态待确认')
  assert.equal(vm.participationStateText(null), '状态待确认')

  assert.equal(vm.rewardStatusText('NOT_ELIGIBLE'), '未达资格')
  assert.equal(vm.rewardStatusText('ELIGIBLE'), '已具备资格')
  assert.equal(vm.rewardStatusText('PENDING_ISSUE'), '待发放')
  assert.equal(vm.rewardStatusText('ISSUED'), '已发放')
  assert.equal(vm.rewardStatusText('CLAIMED'), '已领取')
  assert.equal(vm.rewardStatusText('FAILED'), '发放失败')
  assert.equal(vm.rewardStatusText('MANUAL_REVIEW'), '人工复核中')
  assert.equal(vm.rewardStatusText(null), '未生成发放记录')
  assert.equal(vm.rewardStatusText('constructor'), '未生成发放记录')

  assert.equal(vm.pauseNotificationText('PENDING'), '待发送')
  assert.equal(vm.pauseNotificationText(null), '未接入')
  assert.equal(vm.pauseNotificationText('UNKNOWN'), '状态待确认')
  assert.equal(vm.pauseNotificationText('constructor'), '状态待确认')

  assert.equal(vm.bindingTypeText('THEME'), '主题')
  assert.equal(vm.bindingTypeText('ROAM_POI'), '漫游据点')
  assert.equal(vm.bindingTypeText('constructor'), '未记录绑定类型')
})

test('官方 v3 ROAM_SESSION_AREA 只读展示, 不进编辑器可选合同', () => {
  assert.equal(vm.missionTypes.find(item => item.value === 'ROAM_SESSION_AREA'), undefined, 'P1 类型不得出现在编辑器可选 missionTypes')
  assert.equal(vm.missionTypeText('ROAM_SESSION_AREA'), '漫游区域覆盖', '历史值仍要有只读中文展示')
  assert.equal(vm.missionTypeText('THEME_VERIFIED_FINISH'), '主题完成')
  assert.match(source, /<el-option\s+v-for="item in missionTypes"/, '编辑器沿用受支持的 missionTypes')
})

test('官方 v3 预览不再裸露 missionType, 已知枚举仍映射业务名', () => {
  assert.doesNotMatch(source, /mission\.shortDescription \|\| mission\.missionType/, '预览不得直接回显原始枚举')
  assert.match(source, /mission\.shortDescription \|\| missionTypeText\(mission\.missionType\)/)
  assert.equal(vm.partyRoleText('FULFILLMENT_MERCHANT'), '商家履约')
  assert.equal(vm.partyTypeText('OFFICIAL'), '官方')
  assert.equal(vm.coverageTargetText({ requiredRole: 'FULFILLMENT_CLUB', targetType: 'CLUB_SESSION', targetId: 3 }), '俱乐部履约 · 俱乐部场次 #3')
})

test('官方 v3 organizerSummary 不再直接显示 OFFICIAL', () => {
  const summaryVm = Object.assign(methodVm(official), {
    draft: { parties: [{ partyType: 'OFFICIAL', role: 'ORGANIZER', status: 'ACCEPTED', partyId: 1 }] }
  })
  const text = official.computed.organizerSummary.call(summaryVm)
  assert.equal(text, '官方 #1 已接受')
  assert.doesNotMatch(text, /OFFICIAL/)
})
