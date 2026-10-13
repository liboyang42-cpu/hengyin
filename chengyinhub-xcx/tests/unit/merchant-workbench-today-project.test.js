// T2 · 商家工作台三修的回归闸:
//   B1 假百分比不许回来(源码级)
//   B2 「今日项目」必须按真实日期判定(行为级)
//   B7 死页 pages/merchant/publish 不许复活(磁盘 + 引用双查)
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const { execFileSync } = require('node:child_process')

const ROOT = path.resolve(__dirname, '../..')
const { pickTodayProject, localToday } = require('../../utils/merchant-workbench.js')

const TODAY = '2026-08-05'

// ---------- B2:今日项目 ----------

test('B2: 今天落在 [startDate, endDate] 内的那条才是今日项目', () => {
  const list = [
    { id: 1, startDate: '2026-09-01', endDate: '2026-09-03' }, // 未来 —— 旧实现会挑中它(列表第一条)
    { id: 2, startDate: '2026-08-04', endDate: '2026-08-06' }, // 跨天档期,今天在里面
  ]
  assert.equal(pickTodayProject(list, TODAY).id, 2)
})

test('B2: 单日项目(无 endDate)只在当天命中', () => {
  const list = [{ id: 7, startDate: TODAY }]
  assert.equal(pickTodayProject(list, TODAY).id, 7)
  assert.equal(pickTodayProject(list, '2026-08-06'), null)
})

test('B2: 今天没有项目就返回 null,不许退化成列表第一条', () => {
  const list = [
    { id: 1, startDate: '2026-08-01', endDate: '2026-08-02' }, // 已结束
    { id: 2, startDate: '2026-08-10', endDate: '2026-08-11' }, // 还没开始
  ]
  assert.equal(pickTodayProject(list, TODAY), null)
})

test('B2: 档期未知(无 startDate)不算今日 —— 宁可空态也不能编一条出来', () => {
  assert.equal(pickTodayProject([{ id: 1, startDate: null, endDate: null }], TODAY), null)
  assert.equal(pickTodayProject([{ id: 1 }], TODAY), null)
  assert.equal(pickTodayProject([], TODAY), null)
  assert.equal(pickTodayProject(null, TODAY), null)
})

test('B2: 同日多条取最早开始的那条', () => {
  const list = [
    { id: 1, startDate: '2026-08-05', endDate: '2026-08-05' },
    { id: 2, startDate: '2026-08-03', endDate: '2026-08-07' },
  ]
  assert.equal(pickTodayProject(list, TODAY).id, 2)
})

test('B2: 带时间戳后缀的日期照样按日历日比,不被时区推走', () => {
  const list = [{ id: 3, startDate: '2026-08-05 00:00:00', endDate: '2026-08-05 23:59:59' }]
  assert.equal(pickTodayProject(list, TODAY).id, 3)
})

test('B2: localToday 统一取上海日历日，凌晨/深夜与补零不随设备时区变化', () => {
  assert.equal(localToday(new Date('2026-08-05T00:30:00+08:00')), '2026-08-05')
  assert.equal(localToday(new Date('2026-08-05T23:30:00+08:00')), '2026-08-05')
  assert.equal(localToday(new Date('2026-09-09T12:00:00+08:00')), '2026-09-09')
  assert.equal(localToday(new Date('invalid')), '', '无效瞬时仍失败关闭，不编造日历日')
})

test('B2: 同一跨日瞬时在 LA/UTC/上海都以上海今天判状态、结束与今日项目', () => {
  const modulePath = path.join(ROOT, 'utils/merchant-workbench.js')
  const script = `
    const RealDate = Date;
    const instant = '2026-08-04T16:30:00Z';
    // 只冻结本测试子进程的真实时钟，不改生产 helper 或进程外的时区。
    global.Date = class extends RealDate {
      constructor(...args) { super(...(args.length ? args : [instant])); }
      static now() { return new RealDate(instant).getTime(); }
    };
    const { localToday, statusLabelOf, isEndedProject, pickTodayProject,
      buildProjectCards } = require(${JSON.stringify(modulePath)});
    const list = [
      { id: 0 },
      { id: 1, startDate: '2026-08-04' },
      { id: 2, status: 1, startDate: '2026-08-05' },
      { id: 3, status: 1, startDate: '2026-08-06' },
      { id: 4, startDate: '2026-08-05', endDate: '2026-08-04' },
    ];
    const now = new Date();
    const pad = n => String(n).padStart(2, '0');
    const deviceDay = now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    console.log(JSON.stringify({
      deviceDay,
      explicitInstant: localToday(new Date(instant)),
      defaultToday: localToday(),
      statuses: list.map(p => statusLabelOf(p.startDate, p.endDate)),
      endedIds: list.filter(p => isEndedProject(p.startDate, p.endDate)).map(p => p.id),
      activeIds: list.filter(p => !isEndedProject(p.startDate, p.endDate)).map(p => p.id),
      todayProjectId: pickTodayProject(list).id,
      explicitTodayProjectId: pickTodayProject(list, '2026-08-04').id,
      explicitStatus: statusLabelOf('2026-08-05', null, '2026-08-04'),
      explicitEnded: isEndedProject('2026-08-04', null, '2026-08-04'),
      unknownStatus: statusLabelOf(null, null),
      unknownEnded: isEndedProject(null, null),
      noHit: pickTodayProject(list, '2026-08-07'),
      cardStatuses: buildProjectCards({ joinList: list, todoByProject: [] }).map(p => p.statusLabel),
    }));
  `
  for (const tz of ['America/Los_Angeles', 'UTC', 'Asia/Shanghai']) {
    const observed = JSON.parse(execFileSync(process.execPath, ['-e', script], {
      env: { ...process.env, TZ: tz }, encoding: 'utf8',
    }))
    assert.deepEqual(observed, {
      deviceDay: tz === 'Asia/Shanghai' ? '2026-08-05' : '2026-08-04',
      explicitInstant: '2026-08-05',
      defaultToday: '2026-08-05',
      statuses: ['', '已结束', '进行中', '未开始', '进行中'],
      endedIds: [1],
      activeIds: [0, 2, 3, 4],
      todayProjectId: 2,
      explicitTodayProjectId: 1,
      explicitStatus: '未开始',
      explicitEnded: false,
      unknownStatus: '',
      unknownEnded: false,
      noHit: null,
      cardStatuses: ['', '已结束', '承接中', '承接中', ''],
    }, tz + ' 不得把手机日历当业务日历；显式 today 与未知/坏档期边界仍保留')
  }
})

test('B2: endDate 早于 startDate 的脏数据退化成单日,不让项目静默消失', () => {
  const list = [{ id: 9, startDate: TODAY, endDate: '2026-07-01' }]
  assert.equal(pickTodayProject(list, TODAY).id, 9)
})

test('B2: 页面不许退回「列表第一条」', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.js'), 'utf8')
  assert.doesNotMatch(src, /todayProject:\s*formatted\[0\]/, '不许退回「列表第一条」')
  // 今日项目必须从全量 list 挑 —— 从 slice(0,10) 里挑会让第 11 条之后的今日场次凭空消失
  assert.doesNotMatch(src, /pickTodayProject\(\s*formatted\s*\)/, '不能从截断后的列表里挑今日项目')
})

test('B2: 空态文案报的是真实总数,不是被截到 10 条的 joinList', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.wxml'), 'utf8')
  // 2026-09-16:总数仍然取全量 list,但只数未结束的(已结束不上工作台,也不占「全部 N」)。
  // 2026-09-21:总数拆成「旧承接 + 只在章节里的项目」两段累加,所以不再是一条 joinTotal: 表达式。
  // 2026-09-30:先从全量 list 确认真正 status=1 的承接关系,再筛未结束;未确认申请不能占项目数。
  //   分母仍不得换成 slice(0,10) 之后的列表。
  assert.match(js, /const confirmedRows = list\.filter\(\(item\) => Number\(item\.status\) === 1\)/,
    '只有已确认承接关系可进入项目分母,不能把未确认或未知关系算入')
  assert.match(js, /const activeRows = confirmedRows\.filter\(\(item\) => !isEndedProject\(item\.startDate, item\.endDate, today\)\)/,
    '未结束项目必须从全量已确认承接关系上筛')
  assert.match(js, /_legacyJoinTotal = activeRows\.length/,
    '旧承接那段总数必须取全量 activeRows 的长度')
  assert.match(js, /joinTotal:\s*\(this\._legacyJoinTotal \|\| 0\) \+ chapterOnly/,
    'joinTotal 必须是旧承接总数 + 只在章节里的项目数')
  assert.doesNotMatch(js, /_legacyJoinTotal\s*=\s*(formatted|that\.data\.projectList|list\.slice)/,
    '不许拿铺出去的截断列表当总数')
  assert.match(wxml, /\{\{joinTotal \+ hostProjectTotal\}\}/, '项目总数必须用 joinTotal/hostProjectTotal')
  assert.doesNotMatch(wxml, /joinList\.length/, '不许拿被截断的 joinList 报数')
  assert.doesNotMatch(wxml, /projectCards\.length\}\} ›/, '不许拿只铺了几条的卡片列表报总数')
})

// ---------- B1:假百分比 ----------

test('B1: 工作台不许再出现按状态编造的售出/购买百分比', () => {
  const js = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.js'), 'utf8')
  const wxml = fs.readFileSync(path.join(ROOT, 'pages/merchant/index/index.wxml'), 'utf8')
  // 只查赋值/引用形态,注释里那句「曾经这么干过」的说明不算命中
  assert.doesNotMatch(js, /boughtPct\s*[:=]/, 'boughtPct 不许再被计算或写进 data')
  assert.doesNotMatch(js, /soldPct\s*[:=]/, 'soldPct 不许再被计算或写进 data')
  assert.doesNotMatch(wxml, /boughtPct|soldPct/, 'wxml 不许渲染这两个假百分比')
})

// ---------- B7:死页 ----------

test('B7: pages/merchant/publish 已整目录删除', () => {
  assert.equal(fs.existsSync(path.join(ROOT, 'pages/merchant/publish')), false)
})

test('B7: 全仓不许再引用 pages/merchant/publish', () => {
  const SKIP = new Set(['node_modules', '.git', 'miniprogram_npm', 'dist'])
  const SELF = path.join(ROOT, 'tests/unit/merchant-workbench-today-project.test.js')
  const hits = []
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (SKIP.has(entry.name)) continue
      const full = path.join(dir, entry.name)
      if (entry.isDirectory()) { walk(full); continue }
      if (!/\.(js|json|wxml|wxss|wxs)$/.test(entry.name)) continue
      if (full === SELF) continue
      if (fs.readFileSync(full, 'utf8').includes('merchant/publish')) hits.push(path.relative(ROOT, full))
    }
  }
  walk(ROOT)
  assert.deepEqual(hits, [], `死页仍被引用:${hits.join(', ')}`)
})
