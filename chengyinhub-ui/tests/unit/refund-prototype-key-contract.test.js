/**
 * 退款模块 v4 回归: 枚举展示 helper 不得被 Object 原型键污染。
 *
 * 'constructor'/'__proto__'/'toString' 等若走普通对象下标，会命中 Object.prototype
 * 并把 native function 渲染到资金台。全部改为 hasOwnProperty 自有键判断。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

const ruoyiSource = read('src/utils/ruoyi.js')
const realToTimestamp = new Function(ruoyiSource.replace(/^export function /gm, 'function ') + '\nreturn toTimestamp;')()

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8')
}

function loadRefundIndex() {
  const script = read('src/views/business/finance/refund/index.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listRefund, addRefund, reviewRefund \} from '@\/api\/business\/refund'$/m, 'const { listRefund, addRefund, reviewRefund } = {};')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = {};')
    .replace(/^import (ActivityCancelRefundCases|ManualPayoutQueue|Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

function loadManualPayoutQueue() {
  const script = read('src/views/business/finance/refund/ManualPayoutQueue.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]*\} from '@\/api\/business\/refund'$/m, 'const {} = {};')
    .replace(/^import \{ toTimestamp \} from '@\/utils\/ruoyi'$/m, '')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function('toTimestamp', script.replace(/export\s+default\s*/, 'return '))(realToTimestamp)
}

function loadActivityCancelCases() {
  const script = read('src/views/business/finance/refund/ActivityCancelRefundCases.vue').match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s*\{[\s\S]*?\}\s*from\s*'@\/api\/business\/refund';?/m, 'const {} = {};')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};')
  return new Function(script.replace(/export\s+default\s*/, 'return '))()
}

const HOSTILE = ['constructor', '__proto__', 'hasOwnProperty', 'toString', 'valueOf']
function assertSafeText(value, label) {
  assert.equal(typeof value, 'string', `${label} 必须是字符串, 不能是原生函数`)
  assert.doesNotMatch(value, /native|function|\[object/, `${label} 不得出现原生函数`)
}

test('v4 退款主台 sourceLabel/reviewLabel/reviewTone 原型键 fail-closed', () => {
  const m = loadRefundIndex().methods
  for (const key of HOSTILE) {
    assertSafeText(m.sourceLabel(key), `sourceLabel(${key})`)
    assert.equal(m.reviewLabel(key), '未知状态', `reviewLabel(${key})`)
    assert.equal(m.reviewTone(key), 'neutral', `reviewTone(${key})`)
  }
  assert.equal(m.sourceLabel('registration'), '报名订单')
  assert.equal(m.reviewLabel(0), '待审核')
  assert.equal(m.reviewLabel(1), '已通过')
  assert.equal(m.reviewLabel(2), '已驳回')
  assert.equal(m.reviewTone(2), 'danger')
  assert.equal(m.sourceLabel('coupon'), 'coupon', '未知来源保留原文但不可是函数')
  assert.equal(m.sourceLabel(null), '未知来源')
})

test('v4 人工兜底队列 methodLabel/payoutLabel/payoutTone 原型键 fail-closed', () => {
  const m = loadManualPayoutQueue().methods
  for (const key of HOSTILE) {
    assertSafeText(m.methodLabel(key), `methodLabel(${key})`)
    assert.equal(m.payoutLabel(key), '状态已变化', `payoutLabel(${key})`)
    assert.equal(m.payoutTone(key), 'neutral', `payoutTone(${key})`)
  }
  assert.equal(m.methodLabel('offline_transfer'), '线下转账')
  assert.equal(m.payoutLabel(2), '待人工处理')
  assert.equal(m.payoutLabel(3), '待二次复核')
  assert.equal(m.methodLabel(null), '—')
})

test('v4 活动取消退款案件 statusTone 原型键 fail-closed', () => {
  const m = loadActivityCancelCases().methods
  for (const key of HOSTILE) {
    assert.equal(m.statusTone(key), 'neutral', `statusTone(${key})`)
  }
  assert.equal(m.statusTone('MANUAL_REFUND_REQUIRED'), 'danger')
  assert.equal(m.statusTone('NO_REFUND'), 'neutral')
})

test('v4 三个退款文件不再用裸对象下标渲染展示文案', () => {
  for (const rel of [
    'src/views/business/finance/refund/index.vue',
    'src/views/business/finance/refund/ManualPayoutQueue.vue',
    'src/views/business/finance/refund/ActivityCancelRefundCases.vue'
  ]) {
    const source = read(rel)
    assert.match(source, /hasOwnProperty\.call/, `${rel} 必须用自有键判断`)
  }
  assert.doesNotMatch(read('src/views/business/finance/refund/index.vue'), /\{ registration: '报名订单' \}\[value\]/)
  assert.doesNotMatch(read('src/views/business/finance/refund/ManualPayoutQueue.vue'), /METHOD_LABELS\[method\]/)
  assert.doesNotMatch(read('src/views/business/finance/refund/ActivityCancelRefundCases.vue'), /\}\)\(\[status\]/)
})
