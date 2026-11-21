// 角色 Cast v1 玩家侧(契约 docs/design/cast-contract-1119.md):视图模型单测 + 故事流结构约束。
// 样例文字全是虚构的(契约 Out of scope:不把私有剧情搬进公开仓)。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const storage = {}
global.wx = global.wx || {}
Object.assign(global.wx, {
  getStorageSync: (k) => storage[k],
  setStorageSync: (k, v) => { storage[k] = v },
  showToast: () => {},
})
const cast = require('../../pages/play/utils/cast-play.js')
const kitCard = require('../../pages/play/utils/story-kit-card.js')

const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

const RAW = {
  enabled: true, playerName: true, playerIdentity: true,
  identities: [{ key: 'i_1', title: '邮差', sub: '来送最后一封信' }],
  cast: [
    { id: 'c_a', name: '林雾', identity: '灯塔看守，十年没下过岛', color: '#3D8BFD', voice: { mode: 'preset', preset: 'warm_female' },
      clues: [{ text: '她怕黑', chapterId: null }, { text: '钥匙在鞋里', chapterId: 12 }], endingLines: { low: '她没再回头。', mid: '', high: '她把灯留给了你。' } },
    { id: 'c_b', name: '老周', identity: '', color: '#BADBAD', voice: { mode: 'none' } },
  ],
}
const cfg = cast.normalizeCast(RAW)

test('castConfig 规范化:没开 / 坏形状 = 关闭;颜色只认六色;没有身份选项就不问身份', () => {
  assert.equal(cast.normalizeCast(null), null)
  assert.equal(cast.normalizeCast({ enabled: false, cast: RAW.cast }), null)
  assert.equal(cast.normalizeCast('not json'), null)
  assert.equal(cast.normalizeCast(JSON.stringify(RAW)).cast.length, 2, 'JSON 字符串也认')
  assert.equal(cfg.cast[1].color, '#8E8E93', '不在色板里的颜色落灰')
  assert.equal(cast.normalizeCast({ enabled: true, playerIdentity: true, identities: [] }).playerIdentity, false)
})

test('C 起名的门:开了角色 + 要起名或选身份 + 本局没档案 + 没走过站', () => {
  assert.equal(cast.needsStart(cfg, null, false), true)
  assert.equal(cast.needsStart(cfg, cast.profileOf({ name: '阿栗' }), false), false, '有档案(中途重进)不再问')
  assert.equal(cast.needsStart(cfg, null, true), false, '走过站 = 中途重进')
  assert.equal(cast.needsStart(null, null, false), false, '没开角色的主题从不拦')
  assert.equal(cast.needsStart(cast.normalizeCast({ enabled: true, cast: RAW.cast }), null, false), false, '不起名也不选身份就不出这一步')
  assert.equal(cast.profileOf({ name: '  ' }), null)
  assert.deepEqual(cast.profileOf({ name: '阿栗', identityKey: 'i_1', identityTitle: '邮差' }), { name: '阿栗', identityKey: 'i_1', identityTitle: '邮差' })
})

test('台词 line → 普通正文段:名字进页边、不拼「某某：」;{玩家}/{身份} 换成本局档案', () => {
  const profile = cast.profileOf({ name: '阿栗', identityTitle: '邮差' })
  const it = cast.lineItem({ key: 'k1', type: 'line', speaker: 'c_a', content: '{玩家}，你这个{身份}来晚了。' }, cfg, profile, { mood: 'm', odd: 0 })
  assert.equal(it.kind, 'text', '仍是正文段(一个 .chfull__para)')
  assert.equal(it.text, '阿栗，你这个邮差来晚了。')
  assert.equal(it.who, '林雾'); assert.equal(it.whoId, 'c_a'); assert.equal(it.lineKey, 'k1')
  assert.ok(!/：/.test(it.text))
  assert.equal(it.voice, true, '预设声音 = 可以点着听')
  const mine = cast.lineItem({ key: 'k2', speaker: 'player', content: '我在。' }, cfg, null)
  assert.equal(mine.who, '你'); assert.equal(mine.whoId, '', '玩家的名字点不开角色卡')
  const gone = cast.lineItem({ key: 'k3', speaker: 'c_deleted', content: '风很大。' }, cfg, null)
  assert.equal(gone.who, '', '角色删了:照常显示,不带名字'); assert.equal(gone.text, '风很大。')
  assert.equal(cast.lineItem({ key: 'k4', speaker: 'c_b', content: '嗯。' }, cfg, null).voice, false, '没配声音不给播放')
  assert.equal(cast.lineItem({ key: 'k5', speaker: 'c_b', content: '嗯。', audioUrl: 'https://x/y.mp3' }, cfg, null).voice, true, '自带录音可以放')
  assert.equal(cast.lineItem({ key: 'k6', speaker: 'c_a', content: '   ' }, cfg, null), null)
  assert.equal(cast.lineItem({ key: 'k7', speaker: 'c_a', content: '没开角色。' }, null, null).who, '', '没开角色:不带名字')
})

test('故事流整理:没开角色原样返回(同一个数组);开了角色首段下沉、变量替换、带名字的段不下沉', () => {
  const items = [{ kind: 'cover' }, { kind: 'text', text: '雾从海面上来。' }]
  assert.equal(cast.castParas(items, null, null), items)
  const out = cast.castParas([{ kind: 'cover' }, { kind: 'text', text: '「{玩家}」', who: '林雾' }, { kind: 'text', text: '雾从海面上来。' }, { kind: 'text', text: '第二段。' }], cfg, null)
  assert.equal(out[1].text, '「你」'); assert.equal(out[1].drop, undefined)
  assert.equal(out[2].drop, '雾'); assert.equal(out[2].rest, '从海面上来。')
  assert.equal(out[3].drop, undefined, '只下沉每章第一个正文段')
})

test('E3 行内选项:最多 3 条、编号 1 起;选后留成玩家的一句,改了信任跟「X会记住这件事」', () => {
  const block = { key: 'b1', type: 'branch', content: '要不要上岛？', options: [
    { text: '上岛', to: 'continue', trust: [{ castId: 'c_a', delta: 2 }] },
    { text: '回去', to: 'end' },
    { text: '等一等', to: 'continue', trust: [{ castId: 'c_gone', delta: 1 }, { castId: 'c_b', delta: 0 }] },
    { text: '第四条不出', to: 'end' }] }
  const it = cast.choiceItem(block, { mood: '' })
  assert.equal(it.kind, 'choice'); assert.equal(it.q, '要不要上岛？')
  assert.deepEqual(it.opts.map((o) => [o.n, o.t]), [[1, '上岛'], [2, '回去'], [3, '等一等']])
  const picked = cast.pickedItem(block, 0, cfg, cast.profileOf({ name: '阿栗' }))
  assert.equal(picked.kind, 'text'); assert.equal(picked.who, '阿栗'); assert.equal(picked.text, '上岛')
  assert.equal(picked.remember, '林雾会记住这件事')
  assert.equal(cast.pickedItem(block, 1, cfg, null).remember, '', '没改信任不出这句')
  assert.equal(cast.pickedItem(block, 2, cfg, null).remember, '', '不存在的角色 / delta 0 不算')
  assert.deepEqual(cast.applyTrust({ c_a: 1 }, cfg, block.options[0]), { c_a: 3 })
  assert.deepEqual(cast.applyTrust({}, cfg, { trust: [{ castId: 'c_a', delta: 9 }] }), { c_a: 2 }, '每条夹在 -2..2(与服务端同口径)')
})

test('信任五格、线索解锁、结局脚注、页脚与时长', () => {
  assert.deepEqual(cast.trustTicks(3).map((t) => t.on), [true, true, true, false, false])
  assert.equal(cast.trustTicks(-2).filter((t) => t.on).length, 0)
  assert.equal(cast.trustTicks(9).filter((t) => t.on).length, 5)
  const clues = cast.clueView(cfg.cast[0], [10])
  assert.deepEqual(clues.open, ['她怕黑']); assert.equal(clues.locked, 1)
  assert.equal(clues.lockedText, '还有 1 条，走到下一站再看')
  assert.deepEqual(cast.clueView(cfg.cast[0], ['12']).open, ['她怕黑', '钥匙在鞋里'], '到过那一章就解锁')
  assert.equal(cast.clueView(Object.assign({}, cfg.cast[1], { lockedCount: 2 }), []).locked, 2, '服务端只给个数时照样显示')
  // 玩家读口(info-to-user)按本局进度裁过:下发的线索都是已解锁的,锁住的只给 lockedCount,不再按本机进度二次上锁
  const redacted = cast.normalizeCast({ enabled: true, cast: [{ id: 'c_r', name: '阿柚', color: '#3D8BFD',
    clues: [{ text: '开局就知道', chapterId: null }, { text: '第一章知道', chapterId: 12 }], lockedCount: 3 }] }).cast[0]
  const rv = cast.clueView(redacted, [])
  assert.deepEqual(rv.open, ['开局就知道', '第一章知道']); assert.equal(rv.locked, 3)
  assert.equal(rv.lockedText, '还有 3 条，走到下一站再看')
  const card = cast.cardView(cfg, 'c_a', { trust: { c_a: 2 }, reached: [], voiceKey: 'k1' })
  assert.equal(card.trustLabel, '林雾对你'); assert.equal(card.ticks.filter((t) => t.on).length, 2); assert.equal(card.voice, true)
  assert.equal(cast.cardView(cfg, 'nobody', {}), null)
  const end = cast.endingView(cfg, { c_a: 4, c_b: 0 }, { endingCount: 3 })
  assert.equal(end.rows.length, 1, '信任为 0 的不出'); assert.equal(end.rows[0].line, '她把灯留给了你。')
  assert.equal(end.foot, '这条线有 3 种结局。这一次，是林雾的结局。')
  assert.equal(cast.endingView(cfg, { c_a: -1 }, {}).rows[0].line, '她没再回头。')
  assert.equal(cast.endingView(cfg, {}, {}), null)
  assert.equal(cast.endingView(cfg, { c_a: 1 }, { endingFor: null }).foot, '', '服务端说了不是谁的结局就不猜')
  assert.equal(cast.leadingCast(cfg, { c_a: 2, c_b: 2 }), '', '平局不归谁')
  assert.equal(cast.footerText('雾港', 2, 5), '雾港 · 第2站 / 共5站')
  assert.equal(cast.footerText('雾港', 9, 5), '雾港 · 第5站 / 共5站')
  assert.equal(cast.fmtDur(8.4), '0:08'); assert.equal(cast.fmtDur(0), '')
  assert.equal(cast.voiceHidden({ code: 503, data: { errorCode: 'CAST_TTS_NOT_CONFIGURED' } }), true)
  assert.equal(cast.voiceHidden({ code: 502, data: { errorCode: 'CAST_TTS_FAILED' } }), false, '合成失败要说,没开通才静默')
})

test('E1 人物表与 E5 路线详情行', () => {
  const bill = cast.playbill(cfg, null, '雾港')
  assert.deepEqual(bill.rows.map((r) => r.name), ['林雾', '老周'])
  assert.deepEqual(bill.player, { name: '你', identity: '' }, '没起名 = 你')
  const row = cast.routeCastRow(cfg)
  assert.equal(row[0].identity, '灯塔看守', '身份取第一小句')
  assert.equal(row[0].face.initial, '林')
  assert.equal(cast.routeCastRow(cast.normalizeCast({ enabled: true, cast: [{ id: 'c', name: '甲', avatarUrl: 'media-asset:3' }] }))[0].face.url, '', '素材引用解不了就用首字')
  assert.deepEqual(cast.routeCastRow(null), [])
  cast.remember(77, RAW)
  assert.equal(cast.cached(77).cfg.cast.length, 2, '详情页读过的配置给游玩页用')
  cast.remember(78, null)
  assert.deepEqual(cast.cached(78), { cfg: null }, '没开角色也缓存(不重复请求)')
})

function host(cfgValue, extra) {
  const page = Object.assign({ data: { chapter: { id: 5, blocks: [] }, nodes: [], cast: cast.data.cast, topicId: 9 }, _routeState: { sessionId: 'r1' }, _cast: cfgValue,
    setData(patch) { Object.keys(patch).forEach((k) => { this.data[k] = patch[k] }) } }, cast.methods(() => Promise.resolve({})), extra || {})
  return page
}

test('故事流块挂钩:没开角色时分支块照旧不出;开了角色在这里截住出行内选项;选过按去向走', () => {
  const block = { key: 'b9', type: 'branch', content: '？', options: [{ text: '走', to: 'continue' }, { text: '停', to: 'end' }] }
  const off = host(null); const items = []
  assert.equal(off._castBlock(block, items, { mood: '', odd: 0 }), '')
  assert.equal(items.length, 0, '没开角色:分支块不产出 item(与今天一致)')
  const lineItems = []
  off._castBlock({ type: 'line', key: 'l', speaker: 'c_a', content: '还是一段正文。' }, lineItems, { mood: '', odd: 0 })
  assert.equal(lineItems.length, 1); assert.equal(lineItems[0].who, '', '没开角色:台词照常显示,没有名字')
  const on = host(cfg); const a = []
  assert.equal(on._castBlock(block, a, { mood: '', odd: 0 }), 'stop', '没选:正文到此为止')
  assert.equal(a[0].kind, 'choice')
  on._castWrite({ choices: { '5:b9': 1 } })
  const b = []
  assert.equal(on._castBlock(block, b, { mood: '', odd: 0 }), 'stop', '去向 end:选完在这里收住')
  assert.equal(b[0].text, '停'); assert.equal(b[0].who, '你')
  on._castWrite({ choices: { '5:b9': 0 } })
  assert.equal(on._castBlock(block, [], { mood: '', odd: 0 }), '', '去向 continue:接着读')
  assert.equal(on._castBlock(block, [], { mood: '', odd: 0, readOnly: true }), '', '只读回看不出选项')
})

test('分支玩法:开了角色在阅读栏里就地选(不弹窗);没开角色照旧弹窗', () => {
  assert.equal(kitCard.playKitState({}, { type: 'branch', present: 'inline' }, true, false, false).pop, true)
  const inline = kitCard.playKitState({}, { type: 'branch', present: 'inline' }, true, false, false, true)
  assert.equal(inline.pop, false); assert.equal(inline.card, false, '直接铺 cy-playkit-branch(编号发丝行)')
})

test('结构约束:一个 item 一个 .chfull__para;不再拼「某某：」;没开角色的节点都挂在 cast.on 下', () => {
  const wxml = read('pages/play/index.wxml')
  assert.equal((wxml.match(/class="chfull__para st-line/g) || []).length, 1)
  const loop = wxml.slice(wxml.indexOf('class="chfull__para st-line'), wxml.indexOf('<view class="chfull__end"'))
  assert.ok(/chfull__vrow/.test(loop) && /chfull__remember/.test(loop) && /chfull__who/.test(loop), '配音行 / 记住这件事 / 页边名字都在同一个段里')
  const js = read('pages/play/index.js')
  assert.ok(!/String\(b\.who\) \+ '：'/.test(js), 'who 不再拼进正文')
  assert.ok(/b\.type === 'line'/.test(js), 'line 块有渲染分支')
  assert.match(wxml, /class="chfull__runfoot" wx:if="\{\{cast\.on && cast\.footer\}\}"/)
  assert.match(wxml, /wx:if="\{\{cast\.on\}\}" bindtap="openCastBill"/, '⋯ 菜单「人物表」只在开了角色时出现')
  assert.match(wxml, /class="castend" wx:if="\{\{cast\.on && cast\.ending\}\}"/)
  const css = read('pages/play/cast-play.wxss')
  assert.ok(!/@keyframes/.test(css.replace(/\/\*[\s\S]*?\*\//g, '')), '只用过渡,不加 @keyframes')
  assert.match(css, /\.chfull__para--who\{ position:relative; \}/, '说话人段与其它正文同一条左边线,不再额外缩进')
  assert.match(css, /\.chfull__who\{[^}]*left:calc\(var\(--cy-space-3\) - var\(--cy-space-8\)\);/, '名字落在阅读栏左页边里')
  assert.match(css, /\.chfull__who\{[^}]*font-size:20rpx; font-weight:400;[^}]*opacity:\.46;/)
})

test('开场门:新局先起名 → 人物表 → 正文;重进 / 没开角色直接放行', () => {
  const page = host(cfg, { data: Object.assign({}, host(cfg).data, { cast: JSON.parse(JSON.stringify(cast.data.cast)), reducedMotion: true, topicName: '雾港' }), _routeState: {} })
  let opened = 0
  assert.equal(page._castGate(() => { opened += 1 }), true)
  assert.equal(page.data['cast.start'].show, true); assert.equal(page.data['cast.start'].step, 'name'); assert.equal(page.data['cast.start'].more, true)
  page.data.cast.start = page.data['cast.start']
  page.data.cast.start.name = ''
  page.submitCastStart()   // 名字 → 身份
  assert.equal(page.data['cast.start.step'], 'identity')
  page.data.cast.start.step = 'identity'; page.data.cast.start.more = false
  page.submitCastStart()   // 空名字也能出发(没有局号:只记本地)
  assert.equal(page.data['cast.bill'].show, true); assert.deepEqual(page.data['cast.bill'].player, { name: '你', identity: '' })
  page.data.cast.bill = page.data['cast.bill']
  page.closeCastBill()
  assert.equal(opened, 1, '人物表「开场」之后才进正文')
  assert.equal(page._castMine().billSeen, true)
  const resumed = host(cfg, { data: Object.assign({}, host(cfg).data, { nodes: [{ done: true }] }), _routeState: { sessionId: 'r2' } })
  assert.equal(resumed._castGate(() => {}), false, '走过站:不问名字,不放人物表')
  const plain = host(null, { _routeState: { sessionId: 'r3' } })
  assert.equal(plain._castGate(() => {}), false, '没开角色的主题不拦')
})

test('没有角色痕迹的主题:游玩页开局不多一次请求;有台词块 / 本局档案才补读主题信息', async () => {
  assert.equal(cast.castHints({ chapters: [{ blocks: [{ type: 'text' }] }], routeState: {} }), false)
  assert.equal(cast.castHints({ chapters: [{ blocks: [{ type: 'line', speaker: 'player' }] }] }), true)
  assert.equal(cast.castHints({ routeState: { playerProfile: { name: '阿栗' } } }), true)
  const calls = []
  const mk = () => Object.assign({ data: { cast: cast.data.cast, topicId: '' }, _routeState: {}, setData(p) { Object.assign(this.data, p) } },
    cast.methods((...args) => { calls.push(args); return Promise.resolve({ code: 200, data: { castConfig: RAW } }) }))
  mk()._applyCast({ topicId: 501, chapters: [{ blocks: [{ type: 'text', content: '雾。' }] }], routeState: {} })
  assert.equal(calls.length, 0, '没有角色的主题请求逐条不变')
  const page = mk()
  page._applyCast({ topicId: 502, chapters: [{ blocks: [{ type: 'line', speaker: 'c_a', content: '来了。' }] }], routeState: {} })
  assert.deepEqual(calls.map((c) => c[0]), ['/api/topic/info-to-user'])
  await page._castReady
  assert.equal(page._castOn(), true); assert.equal(page.data['cast.on'], true)
  mk()._applyCast({ topicId: 502, chapters: [{ blocks: [{ type: 'line' }] }] })
  assert.equal(calls.length, 1, '同一主题读过就用缓存')
  const inline = mk(); inline._applyCast({ topicId: 503, castConfig: null, chapters: [{ blocks: [{ type: 'line' }] }] })
  assert.equal(calls.length, 1, '回包自带 castConfig 就不再查'); assert.equal(inline._castOn(), false)
})
