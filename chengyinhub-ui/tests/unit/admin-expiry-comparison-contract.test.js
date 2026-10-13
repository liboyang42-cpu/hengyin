/**
 * R9-10 回归: 后台"到期比较"必须用偏移感知的绝对时刻, 不能用 replace(/-/g,'/')。
 *
 * - 人工兜底队列 leaseExpired 用 payoutLeaseExpiresAt(后端 Date → ISO 带偏移) 与 Date.now() 比大小。
 *   旧码 new Date(str.replace(/-/g,'/')) 遇负偏移 → Invalid → NaN<now=false → 过期租约永远不露接管入口。
 * - 章节承接档 expired 用 validEnd(@JsonFormat 裸串) 比较; 旧码按浏览器本地时区解析, 非中国浏览器会偏。
 * 这里三档时区驱动真实 toTimestamp + 真实组件方法, 并用"同一时刻的多种记法"做负控。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const { execFileSync } = require('node:child_process')
const path = require('node:path')

const uiRoot = path.resolve(__dirname, '../..')

function runUnder(TZ) {
  const script = `
    const fs = require('fs');
    const path = require('path');
    const uiRoot = ${JSON.stringify(uiRoot)};
    const ruoyi = fs.readFileSync(path.join(uiRoot, 'src/utils/ruoyi.js'), 'utf8');
    const toTimestamp = new Function(ruoyi.replace(/^export function /gm, 'function ') + '\\nreturn toTimestamp;')();
    function loadView(rel, apiStrip) {
      const source = fs.readFileSync(path.join(uiRoot, rel), 'utf8');
      let script = source.match(/<script>([\\s\\S]*?)<\\/script>/)[1];
      script = script
        .replace(/^import \\{[^}]*\\} from '@\\/api\\/business\\/refund'$/m, 'const {} = {};')
        .replace(/^import \\{ offerQueue, adjustOffer, deactivateOffer \\} from "@\\/api\\/business\\/chapterOffer";$/m, 'const { offerQueue, adjustOffer, deactivateOffer } = {};')
        .replace(/^import \\{ toTimestamp \\} from '@\\/utils\\/ruoyi'$/m, '')
        .replace(/^import (Cy\\w+) from .+$/gm, 'const $1 = {};');
      return new Function('toTimestamp', script.replace(/export\\s+default\\s*/, 'return '))(toTimestamp);
    }
    const manual = loadView('src/views/business/finance/refund/ManualPayoutQueue.vue');
    const leaseExpired = manual.methods.leaseExpired.bind({});
    const chapter = loadView('src/views/business/chapteroffer/index.vue');
    const expired = chapter.methods.expired.bind({});
    const past = ['2020-01-01 00:00:00', '2019-12-31T16:00:00.000Z', '2020-01-01T00:00:00.000+08:00', '2019-12-31T09:00:00.000-07:00'];
    const future = ['2999-01-01 00:00:00', '2998-12-31T16:00:00.000Z', '2999-01-01T00:00:00.000+08:00', '2998-12-31T09:00:00.000-07:00'];
    process.stdout.write(JSON.stringify({
      minus7: toTimestamp('2026-09-13T02:27:00.000-07:00'),
      plus8: toTimestamp('2026-09-13T02:27:00.000+08:00'),
      bare: toTimestamp('2026-09-13 02:27:00'),
      dateOnly: toTimestamp('2026-09-13'),
      invalidNaN: Number.isNaN(toTimestamp('not-a-date')),
      epoch: toTimestamp(1789237620000),
      pastLease: leaseExpired({ payoutLeaseOwner: 1, payoutLeaseExpiresAt: '2020-01-01T00:00:00.000-07:00' }),
      futureLease: leaseExpired({ payoutLeaseOwner: 1, payoutLeaseExpiresAt: '2999-01-01T00:00:00.000-07:00' }),
      noOwner: leaseExpired({ payoutLeaseExpiresAt: '2020-01-01T00:00:00.000-07:00' }),
      pastExpired: past.map(v => expired({ validEnd: v })),
      futureExpired: future.map(v => expired({ validEnd: v })),
      emptyExpired: expired({ validEnd: null })
    }));
  `
  return JSON.parse(execFileSync(process.execPath, ['-e', script], {
    env: Object.assign({}, process.env, { TZ }),
    encoding: 'utf8'
  }))
}

const ZONES = ['UTC', 'America/Los_Angeles', 'Asia/Shanghai']

test('R9-10 到期比较三档时区: toTimestamp 偏移感知, leaseExpired 负偏移也判过期', () => {
  for (const TZ of ZONES) {
    const r = runUnder(TZ)
    assert.equal(r.minus7, Date.parse('2026-09-13T02:27:00.000-07:00'), `${TZ}: -07:00`)
    assert.equal(r.plus8, Date.parse('2026-09-13T02:27:00.000+08:00'), `${TZ}: +08:00`)
    assert.equal(r.bare, Date.parse('2026-09-13T02:27:00+08:00'), `${TZ}: 裸串按中国时间`)
    assert.equal(r.dateOnly, Date.parse('2026-09-13T00:00:00+08:00'), `${TZ}: date-only 中国零点`)
    assert.equal(r.invalidNaN, true, `${TZ}: 非法值 NaN`)
    assert.equal(r.epoch, 1789237620000, `${TZ}: epoch 原样`)
    assert.equal(r.pastLease, true, `${TZ}: 已过期租约必须判过期`)
    assert.equal(r.futureLease, false, `${TZ}: 未过期租约不判过期`)
    assert.equal(r.noOwner, false, `${TZ}: 无人持有不判过期`)
  }
})

test('R9-10 chapteroffer 同一时刻四种记法到期结果一致(三时区负控)', () => {
  for (const TZ of ZONES) {
    const r = runUnder(TZ)
    assert.deepEqual(r.pastExpired, [true, true, true, true], `${TZ}: 同一过去时刻四种记法都要判过期`)
    assert.deepEqual(r.futureExpired, [false, false, false, false], `${TZ}: 同一未来时刻四种记法都不判过期`)
    assert.equal(r.emptyExpired, false, `${TZ}: 空 validEnd 不判过期`)
  }
})
