/* 玩法屏的外壳 · 半屏弹层不许声明「默认内嵌」(2026-09-24)
 *
 * 玩法屏的外壳有三种形状:cy-play-stage(整屏台面)、cy-sheet(半屏弹层)、
 * 独立 view。**三种并存不是错** —— 半屏是本仓 P1-P6 弹层语言里的正当形状;
 * 而 present 的数据模型只有 inline / fullscreen 两个值(PRESENT_VALUES),
 * `fullscreen` 的真正含义是**「不内嵌」**,半屏满足它。
 * ⚠️ 本文件第一版把「声明整屏、实际半屏」当成违规、冻结了 7 处 —— 那是定偏的:
 * 它冻的是一批根本不算违规的东西,而真正的毛病只有一种形状。
 *
 * 唯一不可能的组合:**inline + 半屏**。
 * 内嵌要把玩法放进 `.chfull__para`,而那个段落恒带 transform + filter
 * (值永远是数字、不是 none),非 none 就是 position:fixed 后代的**包含块** ——
 * 半屏弹层贴的是视口,放进去会被圈进那 190rpx 的段落里,跟着一起缩放和模糊。
 * 这正是 2026-09-22 photoCheck 取景层踩过的同一个坑。
 *
 * 2026-09-24 实测只有 `blindTaste` 命中(显式「默认内嵌」+ cy-sheet)。它的组件有 Figma 稿
 * (v5.1 node 46:259,蒙眼猫是「闭眼」规则的唯一图示),所以**撤的是做不到的声明,不是改组件**;
 * 生产 0 个模板在用,撤默认不影响存量。撤完之后这条就是**零基线硬断言**。
 *
 * 2026-10-30 业主裁决「所有玩法都内嵌」:六个半屏玩法改成两档并存(dual)—— 整屏仍是 cy-sheet
 * (`wx:if="{{!inline}}"`),内嵌走原位 `<view class="pk-embed">`,两处 include 同一份 body.wxml。
 * 零基线硬断言照旧:「默认内嵌」的段不许是**只有**半屏的外壳。
 *
 * 判据两端都从现码读:呈现方式读 advanced-game-config.js,外壳读各组件 wxml 的根节点。
 * 两边都会变,所以都不在测试里抄一份。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '..', '..')
const CFG = path.join(ROOT, 'pages', 'publish', 'utils', 'publish', 'advanced-game-config.js')
const KITS = path.join(ROOT, 'pages', 'play', 'components')

/** 从 advanced-game-config.js 读一张呈现方式名单(别在测试里抄,抄的那份一定会烂) */
function presentList(name) {
  const src = fs.readFileSync(CFG, 'utf8')
  const m = src.match(new RegExp(name + '\\s*=\\s*\\[([\\s\\S]*?)\\]'))
  assert.ok(m, `advanced-game-config.js 里找不到 ${name} —— 名单改名了，判据要跟着改`)
  return m[1].split(',').map((s) => s.trim().replace(/^['"]|['"]$/g, '')).filter(Boolean)
}

/** 每个玩法屏的外壳形状:stage(整屏台面) / sheet(半屏弹层) / bare(裸壳) */
function shells() {
  const out = new Map()
  for (const dir of fs.readdirSync(KITS)) {
    if (!dir.startsWith('playkit-')) continue
    const wxml = path.join(KITS, dir, 'index.wxml')
    if (!fs.existsSync(wxml)) continue
    const src = fs.readFileSync(wxml, 'utf8')
    const first = src.split('\n').map((l) => l.trim()).find((l) => l.startsWith('<'))
    const dual = src.includes('<cy-sheet wx:if="{{!inline}}"') && src.includes('class="pk-embed"')
    const kind = first && first.startsWith('<cy-sheet') ? (dual ? 'dual' : 'sheet')
      : (src.includes('cy-play-stage') ? 'stage' : 'bare')
    out.set(dir.replace('playkit-', ''), kind)
  }
  return out
}

/** 段名(驼峰)→ 组件目录名(全小写)。这一层换算错了整条判据就静默恒绿。 */
const dirOf = (section) => section.toLowerCase()

/** 唯一违规形状:声明「默认内嵌」却是半屏弹层。 */
function inlineSheets() {
  const shell = shells()
  return presentList('PRESENT_INLINE_DEFAULT').filter((seg) => shell.get(dirOf(seg)) === 'sheet')
}

/** 两档并存的半屏玩法(整屏 cy-sheet + 内嵌原位)。登记在这里:新玩法屏悄悄变成只有半屏时有人知道。 */
// 2026-10-23 闭眼盲品按主人定稿改套 cy-play-stage(整屏),从半屏名单移出
const DUAL_KITS = ['diyname', 'musiccorner', 'silentorder', 'slowtask', 'steps', 'timewindow']

test('扫描分母正常:两端都读得到(否则下面全是空转)', () => {
  assert.deepEqual(presentList('PRESENT_FULLSCREEN_ONLY'), [], '2026-10-30 起不再有只能全屏的段')
  assert.ok(presentList('PRESENT_INLINE_DEFAULT').length >= 30, '默认内嵌名单读空了')
  assert.ok(shells().size >= 30, `只扫到 ${shells().size} 个玩法屏，选择器多半失配了`)
})

test('★声明「默认内嵌」的段不能是半屏弹层 —— 零基线硬断言', () => {
  const bad = inlineSheets()
  assert.deepEqual(bad, [],
    `这些段声明了「默认内嵌」,外壳却是半屏弹层:${bad.join(', ')}\n` +
    '  内嵌要放进 .chfull__para,那个段落恒带 transform+filter,是 position:fixed 后代的包含块 ——\n' +
    '  半屏弹层贴的是视口，放进去会被圈进 190rpx 的段落里跟着缩放和模糊。\n' +
    '  两条路:把组件改成 cy-play-stage(内嵌走 --pk-stage-pos 注入),或撤掉这个做不到的声明。')
})

test('★没有只有半屏的玩法屏;两档并存的名单只减不增', () => {
  const sheets = [...shells()].filter(([, v]) => v === 'sheet').map(([k]) => k)
  assert.deepEqual(sheets, [], '只有半屏、没有内嵌档的玩法屏进不了故事流:' + sheets.join(', '))
  const duals = [...shells()].filter(([, v]) => v === 'dual').map(([k]) => k).sort()
  assert.deepEqual(duals, [...DUAL_KITS].sort(), '两档并存的集合变了。新玩法屏优先套 cy-play-stage(内嵌走 --pk-stage-pos 注入)。')
})

test('★玩法屏的外壳只能三选一,独立壳限定相册、签到与拍物相机', () => {
  for (const k of new Set([...shells().values()])) {
    assert.ok(['stage', 'sheet', 'dual', 'bare'].includes(k), `冒出了没见过的外壳形状:${k}`)
  }
  const bare = [...shells()].filter(([, v]) => v === 'bare').map(([k]) => k)
  // album 复用既有玻璃相册，没有限时或判定屏；objectcard 是既有独立全屏相机与揭晓流程，不使用通用玩法台面。
  assert.deepEqual(bare.sort(), ['album', 'dailysign', 'objectcard'],
    `独立壳仅允许相册、签到和拍物相机，实际:${bare.join(', ')}\n` +
    '  自己从零搭 = 退出键/限时条/判定屏各写一份，改一处漏一处。')
})

/* ===================== 负控 ===================== */

test('negative control:让一个「默认内嵌」的段变成半屏,必须被抓出来', () => {
  const shell = shells()
  const inline = presentList('PRESENT_INLINE_DEFAULT')
  const victim = inline.find((s) => shell.get(dirOf(s)) === 'stage')
  assert.ok(victim, '「默认内嵌」里一个 stage 段都没有，负控失去锚点')
  const faked = new Map(shell)
  faked.set(dirOf(victim), 'sheet')
  assert.ok(inline.filter((s) => faked.get(dirOf(s)) === 'sheet').includes(victim),
    `把 ${victim} 变成半屏没被判据认出来`)
})

test('negative control:两档并存不算违规;去掉内嵌档就必须被抓出来', () => {
  assert.equal(shells().get('steps'), 'dual')
  assert.deepEqual(inlineSheets(), [], '把两档并存也算成了违规，规则又定偏了')
  const faked = new Map(shells())
  faked.set('steps', 'sheet')
  assert.ok(presentList('PRESENT_INLINE_DEFAULT').filter((s) => faked.get(dirOf(s)) === 'sheet').includes('steps'),
    '去掉 steps 的内嵌档没被判据认出来')
})

test('negative control:段名与目录名的大小写换算必须真的对上', () => {
  // 盲品 2026-10-23 改成整屏,负控换成仍是半屏的驼峰段名 silentOrder
  assert.equal(dirOf('silentOrder'), 'silentorder')
  assert.ok(shells().has(dirOf('silentOrder')), '驼峰段名换算不到组件目录 —— 判据会静默放过所有段')
  assert.equal(shells().get(dirOf('silentOrder')), 'dual', 'silentOrder 的实际外壳读错了')
  assert.equal(shells().get(dirOf('blindTaste')), 'stage', '盲品已经套上 cy-play-stage')
})

test('negative control:名单读不到时必须抛,不能当成「没有违规」', () => {
  assert.throws(() => presentList('PRESENT_NOT_A_REAL_LIST'))
})

test('默认呈现和内嵌限制与实际服务端真源一致', () => {
  const java = fs.readFileSync(path.join(ROOT, '../chengyinhub-system/src/main/java/com/chengyinhub/business/service/support/AdvancedGameConfigValidator.java'), 'utf8')
  const match = java.match(/PRESENT_SECTIONS\s*=\s*setOf\(([\s\S]*?)\);/)
  assert.ok(match, 'PRESENT_SECTIONS')
  const values = [...match[1].matchAll(/"([A-Za-z]+)"/g)].map(item => item[1])
  assert.deepEqual(presentList('PRESENT_INLINE_DEFAULT').sort(), values.sort())
  assert.ok(!/PRESENT_FULLSCREEN_ONLY/.test(java), '服务端又出现了只能全屏的名单')
})
