/**
 * R9-10 v2 回归: 统一时间输入严格合同(后台 parseTime/toTimestamp 三档时区)。
 *
 * 只接受 Date、有限正数、精确 10/13 位数字字符串、带偏移 ISO、严格合法日历串;
 * 10 位秒 ×1000; 0/布尔/数组/对象/非法日历(2026-02-30)失败关闭且不抛错。
 * 绝对时刻统一按中国字段展示。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')
const ROUTE_FILE = path.join(uiRoot, 'src/utils/ruoyi.js')

function runUnder(tz) {
  const script = `
    const fs = require('fs');
    const src = fs.readFileSync(${JSON.stringify(ROUTE_FILE)}, 'utf8').replace(/^export function /gm, 'function ');
    const { parseTime, toTimestamp } = new Function(src + '\\nreturn { parseTime, toTimestamp }')();
    const MS = 1789237620000, SEC = MS / 1000, fmt = '{y}-{m}-{d} {h}:{i}';
    const malformed = [];
    for (const value of [{}, [], false, true, ['1789237620']]) {
      let out, threw = '';
      try { out = parseTime(value, fmt); } catch (e) { threw = e.message; }
      malformed.push({ value: JSON.stringify(value), out: out === null ? null : out, threw });
    }
    process.stdout.write(JSON.stringify({
      millis: parseTime(MS, fmt), seconds: parseTime(SEC, fmt), numericString: parseTime(String(SEC), fmt),
      invalidCalendar: parseTime('2026-02-30 02:27:00', fmt), zero: parseTime(0, fmt), zeroString: parseTime('0', fmt),
      malformed,
      isoFeb30: parseTime('2026-02-30T10:00:00.000+08:00', fmt),
      isoBadTime: parseTime('2026-09-13T25:00:00+08:00', fmt),
      isoNoSec: parseTime('2026-09-13T02:27+08:00', fmt),
      slashBare: parseTime('2026/09/13 02:27:00', fmt),
      fracBare: parseTime('2026-09-13 02:27:00.123', fmt),
      outOfRange: parseTime(1e300, fmt),
      pre1970: parseTime(-86400000, fmt),
      tsMillis: toTimestamp(MS), tsSeconds: toTimestamp(SEC), tsNumericString: toTimestamp(String(SEC)),
      tsInvalidNaN: Number.isNaN(toTimestamp('2026-02-30')), tsZeroNaN: Number.isNaN(toTimestamp(0)), tsZeroStringNaN: Number.isNaN(toTimestamp('0')),
      tsIsoFeb30NaN: Number.isNaN(toTimestamp('2026-02-30T10:00:00.000+08:00')),
      tsOutOfRangeNaN: Number.isNaN(toTimestamp(1e300)),
      tsPre1970: toTimestamp(-86400000)
    }));
  `
  return JSON.parse(execFileSync(process.execPath, ['-e', script], {
    env: Object.assign({}, process.env, { TZ: tz }),
    encoding: 'utf8'
  }))
}

test('R9-10 v3 统一时间输入严格合同(三时区): ISO 同样严格日历/范围, 不缩小合法输入', () => {
  for (const TZ of ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']) {
    const r = runUnder(TZ)
    assert.equal(r.millis, '2026-09-13 02:27', `${TZ}: 13 位毫秒`)
    assert.equal(r.seconds, '2026-09-13 02:27', `${TZ}: 10 位秒 ×1000`)
    assert.equal(r.numericString, '2026-09-13 02:27', `${TZ}: 10 位数字串`)
    assert.equal(r.invalidCalendar, null, `${TZ}: 裸串 2026-02-30 拒绝`)
    assert.equal(r.zero, null, `${TZ}: 数字 0 拒绝`)
    assert.equal(r.zeroString, null, `${TZ}: '0' 拒绝`)
    assert.equal(r.isoFeb30, null, `${TZ}: 带偏移 ISO 的 2026-02-30 也必须拒绝(不滚月)`)
    assert.equal(r.isoBadTime, null, `${TZ}: 带偏移 ISO 的非法时分秒拒绝`)
    assert.equal(r.isoNoSec, '2026-09-13 02:27', `${TZ}: ISO 无秒仍支持`)
    assert.equal(r.slashBare, '2026-09-13 02:27', `${TZ}: / 分隔裸串不缩小支持`)
    assert.equal(r.fracBare, '2026-09-13 02:27', `${TZ}: 裸串毫秒不缩小支持`)
    assert.equal(r.outOfRange, null, `${TZ}: 超 Date 范围有限数字失败关闭, 不产 NaN 文案`)
    assert.equal(r.pre1970, '1969-12-31 08:00', `${TZ}: 1970 前合法历史日期保留`)
    for (const item of r.malformed) {
      assert.equal(item.threw, '', `${TZ}: ${item.value} 不得抛错`)
      assert.equal(item.out, null, `${TZ}: ${item.value} 失败关闭`)
    }
    assert.equal(r.tsMillis, 1789237620000, `${TZ}: toTimestamp 毫秒`)
    assert.equal(r.tsSeconds, 1789237620000, `${TZ}: toTimestamp 秒 ×1000`)
    assert.equal(r.tsNumericString, 1789237620000, `${TZ}: toTimestamp 数字串`)
    assert.equal(r.tsInvalidNaN, true, `${TZ}: toTimestamp 非法日历 NaN`)
    assert.equal(r.tsZeroNaN, true, `${TZ}: toTimestamp 0 NaN`)
    assert.equal(r.tsZeroStringNaN, true, `${TZ}: toTimestamp '0' NaN`)
    assert.equal(r.tsIsoFeb30NaN, true, `${TZ}: toTimestamp ISO Feb30 NaN`)
    assert.equal(r.tsOutOfRangeNaN, true, `${TZ}: toTimestamp 超范围 NaN`)
    assert.equal(r.tsPre1970, -86400000, `${TZ}: toTimestamp 1970 前保留`)
  }
})
