/**
 * R9-10 回归: 后台 parseTime 必须能消费带时区偏移的 ISO, 且按中国墙钟展示。
 *
 * 后端两种形态并存: 带 @JsonFormat 的裸串(BaseEntity.createTime 等)与无注解的
 * ISO 带偏移串(AuditTask.submittedAt / MerchantCustomerProfile.lastInteractionTime /
 * MerchantCrmTimelineItemVO.occurredAt / OfficialEvent.*)。旧 parseTime 无条件
 * replace(/-/g,'/'), 负偏移恒成 0-0-0, 正偏移按浏览器本地墙钟漂移。
 *
 * 用子进程分别以 UTC / America/Los_Angeles / Asia/Shanghai 运行真实 parseTime。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const uiRoot = path.resolve(__dirname, '../..')
const ROUTE_FILE = path.join(uiRoot, 'src/utils/ruoyi.js')

function parseTimeUnder(tz) {
  const script = `
    const fs = require('fs');
    const src = fs.readFileSync(${JSON.stringify(ROUTE_FILE)}, 'utf8');
    const parseTime = new Function(src.replace(/^export function /gm, 'function ') + '\\nreturn parseTime')();
    const fmt = '{y}-{m}-{d} {h}:{i}';
    process.stdout.write(JSON.stringify({
      plus8: parseTime('2026-09-13T02:27:00.000+08:00', fmt),
      minus7: parseTime('2026-09-13T02:27:00.000-07:00', fmt),
      zulu: parseTime('2026-09-13T02:27:00.000Z', fmt),
      bare: parseTime('2026-09-13 02:27:00', fmt),
      dateOnly: parseTime('2026-09-13', fmt),
      invalid: parseTime('not-a-date', fmt),
      zero: parseTime(0, fmt),
      empty: parseTime('', fmt)
    }));
  `
  const out = execFileSync(process.execPath, ['-e', script], {
    env: Object.assign({}, process.env, { TZ: tz }),
    encoding: 'utf8'
  })
  return JSON.parse(out)
}

const ZONES = ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']

test('R9-10 后台 parseTime 三档时区: ISO 偏移按中国墙钟, 非法/空/0 分清', () => {
  for (const tz of ZONES) {
    const r = parseTimeUnder(tz)
    assert.equal(r.plus8, '2026-09-13 02:27', `${tz}: +08:00 应为中国墙钟`)
    assert.equal(r.minus7, '2026-09-13 17:27', `${tz}: -07:00 应为中国墙钟 17:27`)
    assert.equal(r.zulu, '2026-09-13 10:27', `${tz}: Z 瞬时按中国墙钟 10:27`)
    assert.equal(r.bare, '2026-09-13 02:27', `${tz}: 裸中国时间原样`)
    assert.equal(r.dateOnly, '2026-09-13 00:00', `${tz}: date-only 视为中国零点`)
    assert.equal(r.invalid, null, `${tz}: 非法值回退 null, 不得 0-0-0`)
    assert.equal(r.zero, null, `${tz}: 0 视为无值`)
    assert.equal(r.empty, null, `${tz}: 空串视为无值`)
  }
})
