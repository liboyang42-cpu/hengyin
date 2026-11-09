// §20.1 公开目录 + UI-P3 主题合集落地:匿名只读 POST /api/public-catalog/{city/list,list,filters,info}。
// 只展示服务端给出的事实:没有就不画(不补 0、不猜城市名、不回退旧价、不造评分/人数/到货提醒)。
// 筛选只发合法枚举;交付形态、玩法、团体/学校选项全部来自 /filters,空集合就不出对应筛选。

const PRODUCT_TYPE_LABELS = { 1: '经典定向', 2: '自由探索' }
const DELIVERY_LABELS = { SELF_PLAY: '自玩', GUIDED_SESSION: '带队场次', CLUB_HOSTED: '俱乐部场次' }
const LOCALE_LABELS = { 'zh-CN': '中文', en: 'English', 'en-US': 'English', ja: '日本語', ko: '한국어' }
const CITY_CODE = /^\d{6}$/

function isRecord(v) { return !!v && typeof v === 'object' && !Array.isArray(v) }
function text(v) { return typeof v === 'string' && v.trim() ? v.trim() : '' }
function num(v) {
  if (v == null || v === '' || typeof v === 'boolean') return NaN
  const n = Number(v)
  return Number.isFinite(n) ? n : NaN
}

// 时长:cms_topic.total_time 为秒(与主题详情 formatTimeToChinese 同口径)
function durationText(seconds) {
  const s = num(seconds)
  if (!(s > 0)) return ''
  const minutes = Math.round(s / 60)
  if (minutes < 60) return minutes + ' 分钟'
  const h = Math.floor(minutes / 60), m = minutes % 60
  return m ? (h + ' 小时 ' + m + ' 分钟') : (h + ' 小时')
}

function priceAmount(v) {
  const n = num(v)
  if (!(n >= 0)) return ''
  return String(Math.round(n * 100) / 100)
}

// 购买事实:免费自玩与在售分开;UNKNOWN 价不回退、不当 0;未在售不给价
function saleText(row) {
  if (row.freeSelfPlay === true) return '免费自玩'
  if (row.saleState === 'ON_SALE') {
    if (row.priceState !== 'KNOWN') return '价格待确认'
    const amount = priceAmount(row.priceFrom)
    if (!amount) return '价格待确认'
    return (row.priceUnit === 'SELF_PLAY_PASS' ? '自玩 ' : '') + '¥' + amount + ' 起'
  }
  if (row.saleState === 'SALE_ENDED') return '已停售'
  if (row.saleState === 'NOT_ON_SALE') return '未开售'
  return ''
}

function ageText(row) {
  const a = num(row.ageMin), b = num(row.ageMax)
  if (a >= 0 && b >= 0) return a + '–' + b + ' 岁'
  if (a >= 0) return a + ' 岁以上'
  if (b >= 0) return b + ' 岁以下'
  return ''
}

function groupText(row) {
  if (row.groupEligible !== true) return ''
  const a = num(row.groupMinPeople), b = num(row.groupMaxPeople)
  if (a > 0 && b > 0) return '团体 ' + a + '–' + b + ' 人'
  return '可接团体'
}

// 语言:只在实际返回语言与请求语言不同(缺译回退)时标出实际语言
function localeTag(row) {
  const req = text(row.requestedLocale), act = text(row.actualLocale)
  if (!act || !req || act === req) return ''
  return LOCALE_LABELS[act] || act
}

function cardView(row) {
  if (!isRecord(row) || row.id == null || !text(row.name)) return null
  const stops = num(row.locationCount)
  const meta = [text(row.cityName), durationText(row.totalTime), stops > 0 ? (stops + ' 站') : ''].filter(Boolean)
  const sale = saleText(row)
  const tags = [
    sale,
    groupText(row),
    row.schoolEligible === true ? '可接学校' : '',
    ageText(row),
    localeTag(row)
  ].filter(Boolean)
  return {
    id: String(row.id),
    title: text(row.name),
    subtitle: text(row.subtitle),
    summary: text(row.summary),
    cover: text(row.imgUrl),
    kind: PRODUCT_TYPE_LABELS[row.productType] || '',
    meta: meta.join(' · '),
    sale,
    tags,
    cityCode: CITY_CODE.test(String(row.cityCode || '')) ? String(row.cityCode) : '',
    cityName: text(row.cityName),
    accessibilityNote: text(row.accessibilityNote),
    deliveryModes: (Array.isArray(row.deliveryModes) ? row.deliveryModes : []).map((m) => DELIVERY_LABELS[m]).filter(Boolean)
  }
}

function cardList(rows) {
  return (Array.isArray(rows) ? rows : []).map(cardView).filter(Boolean)
}

// 城市:只列服务端聚合出的真实城市;无编码或无名的行不进选择器(不猜名)
function cityOptions(rows) {
  return (Array.isArray(rows) ? rows : [])
    .filter((r) => isRecord(r) && CITY_CODE.test(String(r.cityCode || '')) && text(r.cityName))
    .map((r) => ({ key: String(r.cityCode), label: text(r.cityName), count: num(r.topicCount) > 0 ? num(r.topicCount) : null }))
}

// 筛选 chip:玩法 / 交付形态 / 团体 / 学校,全部来自 /filters 的真实取值
function filterChips(options) {
  const o = isRecord(options) ? options : {}
  const chips = []
  ;(Array.isArray(o.productTypes) ? o.productTypes : []).forEach((t) => {
    if (PRODUCT_TYPE_LABELS[t]) chips.push({ key: 'product_type:' + t, field: 'product_type', value: String(t), label: PRODUCT_TYPE_LABELS[t] })
  })
  ;(Array.isArray(o.deliveryModes) ? o.deliveryModes : []).forEach((m) => {
    if (DELIVERY_LABELS[m]) chips.push({ key: 'delivery_mode:' + m, field: 'delivery_mode', value: m, label: DELIVERY_LABELS[m] })
  })
  if (o.groupEligibleAvailable === true) chips.push({ key: 'group_eligible:1', field: 'group_eligible', value: '1', label: '可接团体' })
  if (o.schoolEligibleAvailable === true) chips.push({ key: 'school_eligible:1', field: 'school_eligible', value: '1', label: '可接学校' })
  return chips
}

// 同字段单选(再点取消);只拼合法字段
const FIELDS = ['city_code', 'product_type', 'delivery_mode', 'group_eligible', 'school_eligible']
function toggleFilter(selected, chip) {
  const next = Object.assign({}, selected || {})
  if (!chip || FIELDS.indexOf(chip.field) < 0) return next
  if (next[chip.field] === chip.value) delete next[chip.field]
  else next[chip.field] = chip.value
  return next
}

function buildQuery(selected, page) {
  const q = { locale: 'zh-CN', sort: 'NEWEST' }
  FIELDS.forEach((f) => { if (selected && selected[f] != null && selected[f] !== '') q[f] = String(selected[f]) })
  if (q.city_code && !CITY_CODE.test(q.city_code)) delete q.city_code
  const p = page || {}
  q.pageNum = p.pageNum > 0 ? p.pageNum : 1
  q.pageSize = p.pageSize > 0 ? p.pageSize : 10
  return q
}

// 分享 / 直达:只带当前合法筛选,落地页无需先经过首页
function sharePath(route, selected) {
  const q = buildQuery(selected)
  const parts = FIELDS.filter((f) => q[f] != null).map((f) => f + '=' + encodeURIComponent(q[f]))
  return '/' + route + (parts.length ? '?' + parts.join('&') : '')
}

function selectedFromOptions(options) {
  const o = options || {}
  const s = {}
  FIELDS.forEach((f) => { if (o[f] != null && o[f] !== '') s[f] = decodeURIComponent(String(o[f])) })
  if (s.city_code && !CITY_CODE.test(s.city_code)) delete s.city_code
  return s
}

// 列表读回:{code,data:{rows,total}}
function readPage(response) {
  if (!response || response.code != 200 || !isRecord(response.data) || !Array.isArray(response.data.rows)) return null
  const total = num(response.data.total)
  return { rows: response.data.rows, total: total >= 0 ? total : null }
}

// 落地详情:章节名 + 首章前几站钩子;不含答案/正文
function detailView(data) {
  const card = cardView(data)
  if (!card) return null
  const chapters = (Array.isArray(data.chapterNames) ? data.chapterNames : []).map(text).filter(Boolean)
  const preview = isRecord(data.preview) ? data.preview : {}
  const stops = (Array.isArray(preview.stops) ? preview.stops : [])
    .filter((s) => isRecord(s) && text(s.name))
    .map((s, i) => ({ key: String(s.order != null ? s.order : i), order: num(s.order) > 0 ? num(s.order) : i + 1, name: text(s.name), hook: text(s.hookTeaser) }))
  return Object.assign(card, {
    chapters,
    previewTitle: text(preview.firstChapterName),
    stops,
    canonicalPath: text(data.canonicalPath)
  })
}

// 详情读回状态:404 = 已下架或未公开(与「不存在」同口径,不区分);其它非 200 = 读取失败
function detailState(response) {
  if (response && response.code == 200 && isRecord(response.data)) return 'ok'
  if (response && Number(response.code) === 404) return 'gone'
  return 'error'
}

module.exports = {
  PRODUCT_TYPE_LABELS, DELIVERY_LABELS,
  durationText, saleText, cardView, cardList, cityOptions, filterChips, toggleFilter,
  buildQuery, sharePath, selectedFromOptions, readPage, detailView, detailState
}
