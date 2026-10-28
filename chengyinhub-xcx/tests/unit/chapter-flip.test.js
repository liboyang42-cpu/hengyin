// 2026-10-28 章节翻转过场:画布几何、方块取样、过场数据(只写真读数)。
const assert = require('node:assert/strict')
const { test } = require('node:test')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.join(__dirname, '..', '..')
const E = require(path.join(ROOT, 'pages/play/components/chapter-flip/engine.js'))
const { buildGateFlip, buildFinale, faceLabel } = require(path.join(ROOT, 'pages/play/utils/chapter-flip-data.js'))
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('三棱锥:转到 i*120 度时第 i 面正对观者,背面不画', () => {
  for (const i of [0, 1, 2]) {
    assert.equal(E.faceRel(i, i * 120), 0)
    assert.equal(E.facing(E.faceQuad(i, i * 120, 195, 281, 1)), true, `第 ${i} 面正对时要画`)
  }
  assert.equal(E.facing(E.faceQuad(0, 180, 195, 281, 1)), false, '转到背后的面不画')
  const q = E.faceQuad(0, 0, 195, 281, 1)
  assert.ok(q[0][1] < q[1][1] && Math.abs(q[1][1] - q[2][1]) < 0.01, '顶点在上,底边水平')
  assert.ok(Math.abs((q[0][0] - 195)) < 0.01, '正对时顶点在中线上')
})

test('三棱锥:仿射矩阵把面图三个角正好贴到屏幕三角上', () => {
  const q = E.faceQuad(1, 150, 200, 300, 1.1)
  const [a, b, c, d, e, f] = E.affine(360, 378, q)
  const map = (u, v) => [a * u + c * v + e, b * u + d * v + f]
  ;[[180, 0, q[0]], [0, 378, q[1]], [360, 378, q[2]]].forEach(([u, v, p]) => {
    const m = map(u, v)
    assert.ok(Math.abs(m[0] - p[0]) < 1e-6 && Math.abs(m[1] - p[1]) < 1e-6)
  })
})

test('方块取样:只取遮罩不透明的格心', () => {
  const w = 20, h = 10, mask = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) for (let x = 0; x < 10; x++) mask[(y * w + x) * 4 + 3] = 255
  const pts = E.gridPoints(mask, w, h, 5, 100, 50)
  assert.deepEqual(pts.map(p => [p.x, p.y]), [[100, 50], [105, 50], [100, 55], [105, 55]])
})

test('面标签取章节名「·」前那段', () => {
  assert.equal(faceLabel('01', '提篮桥 · 五个地点'), '01 · 提篮桥')
  assert.equal(faceLabel('02', ''), '02')
})

const cards = [
  { id: 1, title: '提篮桥 · 五个地点', cover: 'https://x/a.jpg,https://x/b.jpg', mileage: '1.2km', timeText: '40 分钟', nodeCount: 2 },
  { id: 2, title: '延伸 · 四个小任务', cover: '', nodeCount: 0 },
]
const nodes = [
  { nodeId: 11, chapterId: 1, done: true, imgUrl: 'https://x/p1.jpg' },
  { nodeId: 12, chapterId: 1, done: false },
  { nodeId: 21, chapterId: 2, done: false, imgUrl: 'https://x/p2.jpg,https://x/p3.jpg' },
]

test('章节开场:上一章玩过才先出「上一章完成」,成绩只写真读数', () => {
  const g = buildGateFlip({ cards, chapterId: 2, nodes, topicCover: 'https://x/t.jpg', topicName: '海上方舟', desc: '舟山路一带。' })
  assert.equal(g.endInfo.number, '01')
  assert.equal(g.endInfo.kicker, '第 1 章 · 完成')
  assert.deepEqual(g.endInfo.stats, [{ v: '1/2', k: '坐标' }], '没有解谜题就不出解谜分格,不拿 0 凑')
  assert.equal(g.info.kicker, '第 2 章 / 共 2 章')
  assert.deepEqual(g.info.stats, [{ v: '1', k: '坐标' }], '没配里程/时长的格子不出')
  assert.equal(g.faces.length, 3)
  assert.equal(g.faces[0].cover, 'https://x/a.jpg', '第一面 = 上一章封面(取第一张)')
  assert.equal(g.faces[0].style, 'riso')
  assert.equal(g.faces[1].style, 'engrave')
  assert.deepEqual(g.faces[1].photos, ['https://x/p2.jpg'])
  assert.equal(g.faces[2].style, 'gold')

  const first = buildGateFlip({ cards, chapterId: 1, nodes, desc: '' })
  assert.equal(first.endInfo, null, '第一章没有上一章完成页')
  assert.deepEqual(first.info.stats.map(s => s.k), ['坐标', '距离', '预计'])
  assert.equal(first.info.stats[1].v, '1.2 km')

  const untouched = buildGateFlip({ cards, chapterId: 2, nodes: nodes.map(n => Object.assign({}, n, { done: false })) })
  assert.equal(untouched.endInfo, null, '上一章一站没做(例如直接跳章)不放完成页')
})

test('通关:三面落在金色面,合计不造 0', () => {
  const f = buildFinale({ cards, nodes, topicCover: '', topicName: '海上方舟', doneCount: 9, elapsed: '1:07:24', score: 2680, puzzle: { score: 70, count: 1 } })
  assert.equal(f.faces[2].style, 'gold')
  assert.equal(f.faces[2].cover, '', '主题和最后一章都没封面时面图走渐变底')
  assert.deepEqual(f.info.stats, [{ v: '9', k: '坐标' }, { v: '1:07:24', k: '用时' }, { v: '2,680', k: '探索值' }])
  const noXp = buildFinale({ cards, nodes, topicName: 'x', doneCount: 0, elapsed: '', score: 0, puzzle: { score: 70, count: 1 } })
  assert.deepEqual(noXp.info.stats, [{ v: '70', k: '解谜分' }], '探索值拿不到就退到解谜分;都没有的格子不出')
})

test('过场组件:通关期间不出字,按钮最后才出;盖在通关勋章页之上', () => {
  const js = read('pages/play/components/chapter-flip/index.js')
  const wxml = read('pages/play/components/chapter-flip/index.wxml')
  const wxss = read('pages/play/components/chapter-flip/index.wxss')
  assert.match(js, /this\.setData\(\{ quiet: true \}\)/, '触发通关后文字全部隐去')
  assert.match(js, /setData\(\{ quiet: false, cta: true \}\)/, '标志拼好后才出主题名与按钮')
  assert.match(js, /LOGO_SRC = '\/images\/cy_logo_mark\.png'/, '方块拼成城瘾标志')
  assert.match(js, /silver: true, cell: 5/, '标志用银色细方块(业主:缝要看得出)')
  assert.match(wxml, /aria-label="保存通关卡"/)
  assert.match(wxss, /\.cf--finale \{ z-index: 320; \}/)
  const page = read('pages/play/index.wxml')
  assert.match(page, /<cy-chapter-flip wx:if="\{\{showFinish && finale\.show\}\}" mode="finale"/)
})
