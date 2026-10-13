/**
 * R9-10 回归: 官方活动日期控件(value-format="timestamp")在真实载入/提交边界做 ISO↔epoch 转换。
 *
 * OfficialEvent 的 Date 字段无 @JsonFormat → 接口回 ISO 带偏移；而控件解析是
 * new Date(Number(text))，字符串得到 Invalid → 空白。修复: openDraft 载入转 epoch，
 * payload 提交前再归一，未改动也不丢、回读保持同一瞬间。后端本就收 epoch(控件一直这么发)。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const VIEW = path.join(uiRoot, 'src/views/business/officialevent/index.vue')

function runUnder(TZ) {
  const script = `
    const fs = require('fs');
    const src = fs.readFileSync(${JSON.stringify(VIEW)}, 'utf8');
    // harness 剥离 import 时必须注入真实 helper(复用生产同一入口), 不能用 stub 改写行为
    const ruoyi = fs.readFileSync(${JSON.stringify(path.join(uiRoot, 'src/utils/ruoyi.js'))}, 'utf8').replace(/^export function /gm, 'function ');
    const { toTimestamp } = new Function(ruoyi + '\\nreturn { toTimestamp }')();
    const script = src.match(/<script>([\\s\\S]*?)<\\/script>/)[1]
      .replace(/^import\\s+\\{([^}]*)\\}\\s+from\\s+['"][^'"]+['"];?\\s*$/gm, (_, names) =>
        'const { ' + names.split(',').map(x => x.trim()).filter(Boolean).map(x => {
          const b = x.split(/\\s+as\\s+/); return (b[1] || b[0]) + ': ' + (b[1] || b[0]);
        }).join(', ') + ' } = __deps')
      .replace(/^import\\s+(\\w+)\\s+from\\s+['"][^'"]+['"];?\\s*$/gm, 'const $1 = __deps.$1')
      .replace(/export\\s+default\\s*/, 'return ');
    const DRAFT = {
      event: {
        signupStart: '2026-09-13T02:27:00.000+08:00',
        activityStart: '2026-09-13T04:00:00.000+08:00',
        activityEnd: '2026-09-13T06:00:00.000-07:00',
        settleTime: '2026-09-14',
        rewardJson: '{}'
      },
      bindings: [],
      missions: [{ _key: 'm', availableStart: '2026-09-13T02:27:00.000Z', availableEnd: null }],
      parties: [{ _key: 'p', windowStart: '2026-09-13T02:27:00.000+08:00', windowEnd: '' }],
      fulfillmentRequirements: [{ _key: 'r', windowStart: 1789237620000, windowEnd: 'not-a-date' }]
    };
    const deps = new Proxy({ getOfficialEventV2Draft: () => Promise.resolve({ data: DRAFT }), toTimestamp }, {
      get: (target, name) => {
        if (name in target) return target[name];
        return (...args) => Promise.resolve({ rows: [], total: 0, data: {} });
      }
    });
    const component = new Function('__deps', script)(deps);
    const vm = {};
    for (const [name, fn] of Object.entries(component.methods || {})) vm[name] = fn.bind(vm);
    Object.assign(vm, component.data.call(vm));
    vm.$modal = { msgSuccess() {}, msgWarning() {}, msgError() {} };
    vm.openDraft(1);
    setTimeout(() => {
      const payload = vm.payload();
      process.stdout.write(JSON.stringify({
        loaded: {
          signupStart: vm.draft.event.signupStart,
          activityEnd: vm.draft.event.activityEnd,
          settleTime: vm.draft.event.settleTime,
          missionStart: vm.draft.missions[0].availableStart,
          missionEnd: vm.draft.missions[0].availableEnd,
          partyStart: vm.draft.parties[0].windowStart,
          partyEnd: vm.draft.parties[0].windowEnd,
          reqStart: vm.draft.fulfillmentRequirements[0].windowStart,
          reqEnd: vm.draft.fulfillmentRequirements[0].windowEnd
        },
        payload: {
          signupStart: payload.event.signupStart,
          activityEnd: payload.event.activityEnd,
          settleTime: payload.event.settleTime,
          missionStart: payload.missions[0].availableStart,
          partyStart: payload.parties[0].windowStart,
          reqStart: payload.fulfillmentRequirements[0].windowStart
        }
      }));
    }, 0);
  `
  return JSON.parse(execFileSync(process.execPath, ['-e', script], {
    env: Object.assign({}, process.env, { TZ }),
    encoding: 'utf8'
  }))
}

const ZONES = ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']

test('R9-10 官方日期输入三档时区: 载入转 epoch 保持同一瞬间, 提交/无改保存不丢', () => {
  for (const TZ of ZONES) {
    const r = runUnder(TZ)
    assert.equal(r.loaded.signupStart, Date.parse('2026-09-13T02:27:00.000+08:00'), `${TZ}: +08:00 载入`)
    assert.equal(r.loaded.activityEnd, Date.parse('2026-09-13T06:00:00.000-07:00'), `${TZ}: -07:00 载入`)
    assert.equal(r.loaded.settleTime, Date.parse('2026-09-14T00:00:00+08:00'), `${TZ}: date-only 视为中国零点`)
    assert.equal(r.loaded.missionStart, Date.parse('2026-09-13T02:27:00.000Z'), `${TZ}: Z 载入`)
    assert.equal(r.loaded.missionEnd, null, `${TZ}: null 保持 null`)
    assert.equal(r.loaded.partyStart, Date.parse('2026-09-13T02:27:00.000+08:00'), `${TZ}: party window`)
    assert.equal(r.loaded.partyEnd, null, `${TZ}: 空串归 null`)
    assert.equal(r.loaded.reqStart, 1789237620000, `${TZ}: epoch 原样保留`)
    assert.equal(r.loaded.reqEnd, null, `${TZ}: 非法值归 null`)

    // 提交边界保持同一瞬间(未改动不丢)
    assert.equal(r.payload.signupStart, r.loaded.signupStart, `${TZ}: 提交保持 +08:00 瞬时`)
    assert.equal(r.payload.activityEnd, r.loaded.activityEnd, `${TZ}: 提交保持 -07:00 瞬时`)
    assert.equal(r.payload.settleTime, r.loaded.settleTime, `${TZ}: 提交保持 date-only 瞬时`)
    assert.equal(r.payload.missionStart, r.loaded.missionStart, `${TZ}: 提交保持 mission`)
    assert.equal(r.payload.reqStart, r.loaded.reqStart, `${TZ}: 提交保持 epoch`)
  }
})
