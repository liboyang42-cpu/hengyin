'use strict'
/* l-after(2026-11-10)游戏主题库的数据口(业主批准 proto/game ps-library):
 * 列表读现有的公开目录 POST /api/public-catalog/list(真实接口,城市定向 / 自由探索按 product_type 1 / 2 筛),
 * 卡片字段复用 pages/topic/utils/public-catalog.cardView。
 * 「¥0 可玩」只在服务端给了通行证覆盖事实时出(行上的 passCovered === true,由 l-pass 的通行证接口补);
 * 没有这个事实就不画价签,不把未知写成 0。换接口时只改这一个文件。 */
const catalog = require('../../pages/topic/utils/public-catalog.js')

const KINDS = [
  { key: 'all', label: '全部', productType: '' },
  { key: 'city', label: '城市定向', productType: '1' },
  { key: 'free', label: '自由探索', productType: '2' }
]
const PAGE_SIZE = 20

function kindByKey(key) { return KINDS.find((k) => k.key === key) || KINDS[0] }

function query(kindKey, pageNum) {
  const k = kindByKey(kindKey)
  const selected = k.productType ? { product_type: k.productType } : {}
  return catalog.buildQuery(selected, { pageNum: pageNum > 0 ? pageNum : 1, pageSize: PAGE_SIZE })
}

// 通行证覆盖:只认服务端明确的 true;其余(缺字段 / false / 字符串)都不出价签
function coveredByPass(row) {
  return !!row && typeof row === 'object' && row.passCovered === true
}

function minutesText(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  return '约 ' + Math.max(1, Math.round(n / 60)) + ' 分钟'
}

/* 一行一张俱乐部式卡(components/cy/club-card 结构):封面、顶行(城标 + ¥0 可玩 + 城市)、主题名、一行数据、标签片 */
function libraryCard(row) {
  const c = catalog.cardView(row)
  if (!c) return null
  const stops = Number(row.locationCount)
  const data = [c.kind, stops > 0 ? stops + ' 站' : '', minutesText(row.totalTime)].filter(Boolean).join(' · ')
  return {
    id: c.id,
    title: c.title,
    cover: c.cover,
    city: c.cityName,
    kind: c.kind,
    data,
    free: coveredByPass(row),
    tags: [c.kind, c.cityName].filter(Boolean).concat(c.tags.filter((t) => t !== c.kind)).slice(0, 3)
  }
}

function libraryList(rows) {
  return (Array.isArray(rows) ? rows : []).map(libraryCard).filter(Boolean)
}

module.exports = { KINDS, PAGE_SIZE, kindByKey, query, coveredByPass, libraryCard, libraryList, readPage: catalog.readPage }
