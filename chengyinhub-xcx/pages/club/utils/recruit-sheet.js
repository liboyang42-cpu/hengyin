// 2026-11-02(ROUND3B):俱乐部主题页「承接商家」半屏的数据整形。
// 输入是 /api/club/recruit/overview 原样回包,输出喂给现成组件 cy-club-topic-onboarding(稿 J3-A 155:118 / J3-B 158:118)。
// 只整形不推断:电话只有服务端下发了(商家已确认 + 有管理权)才有;章节状态、家数、申请数都照回包。
'use strict'

const TERMS_LABEL = { PERK: '平台预设条款', TRAFFIC: '引流 · 无分成', REVSHARE: '票款分成' }

function maskPhone(raw) {
  const p = String(raw || '').trim()
  return /^1\d{10}$/.test(p) ? p.slice(0, 3) + '****' + p.slice(7) : p
}

function money(v) {
  const n = Number(v)
  if (!isFinite(n) || n <= 0) return ''
  return n % 1 === 0 ? String(n) : n.toFixed(2)
}

/** 城市定向 = 1,自由探索 = 2;存量 NULL 时有站点就按站点版画。 */
function modeOf(d) {
  if (Number(d && d.productType) === 2) return 'chapter'
  return 'node'
}

function nodeView(d) {
  const nodes = Array.isArray(d.nodes) ? d.nodes : []
  const chapters = Array.isArray(d.chapters) ? d.chapters : []
  const chapterName = {}
  chapters.forEach(function (c) { chapterName[String(c.chapterId)] = c.name || '未命名章节' })
  const sections = []
  const byKey = {}
  let done = 0
  let pending = 0
  nodes.forEach(function (n, i) {
    const state = n.state === 'APPROVED' ? 'won' : (n.state === 'PENDING' ? 'pending' : 'open')
    if (state === 'won') done++
    if (state === 'pending') pending++
    const key = n.chapterId == null ? '_' : String(n.chapterId)
    if (!byKey[key]) {
      byKey[key] = { chapterName: chapterName[key] || (key === '_' ? '站点' : '未命名章节'), stationCount: 0, wonCount: 0, nodes: [] }
      sections.push(byKey[key])
    }
    const s = byKey[key]
    s.stationCount++
    if (state === 'won') s.wonCount++
    const phone = state === 'won' && typeof n.phone === 'string' ? n.phone.trim() : ''
    s.nodes.push({
      id: n.nodeId,
      seq: n.index || i + 1,
      name: n.name || '未命名站点',
      sub: n.gameplayName || '玩法待配置',
      state: state,
      merchantName: state === 'open' ? '' : (n.merchantName || '未命名商家'),
      statusLabel: state === 'won' ? '已中标' : (state === 'pending' ? '待审核' : ''),
      phone: phone,
      phoneText: phone ? maskPhone(phone) : '',
    })
  })
  // 稿上分组标题按章节排:回包章节已按 sortId 排好,没挂章节的站点放最后
  sections.sort(function (a, b) {
    const ia = chapters.findIndex(function (c) { return (c.name || '未命名章节') === a.chapterName })
    const ib = chapters.findIndex(function (c) { return (c.name || '未命名章节') === b.chapterName })
    return (ia < 0 ? 1e9 : ia) - (ib < 0 ? 1e9 : ib)
  })
  const total = nodes.length
  return {
    loadState: total ? 'ready' : 'empty',
    doneCount: done, totalCount: total, pendingCount: pending,
    progressPct: total ? Math.round(done * 100 / total) : 0,
    sections: sections,
  }
}

function chapterView(d) {
  const chapters = (Array.isArray(d.chapters) ? d.chapters : []).map(function (c) {
    const recruiting = c.recruiting === true
    const perk = money(c.perkMinValue)
    const terms = TERMS_LABEL[String(c.termsMode || '').toUpperCase()]
    return {
      id: c.chapterId,
      name: c.name || '未命名章节',
      recruiting: recruiting,
      finished: c.finished === true,
      statusLabel: recruiting ? '招募中' : (c.finished === true ? '已结束' : '未开放'),
      category: c.category || '不限品类',
      capacityText: '',
      tags: terms ? [terms] : [],
      approvedCount: Number(c.approvedCount) || 0,
      capLimit: Number(c.maxMerchant) > 0 ? Number(c.maxMerchant) : 0,
      thresholdText: perk ? '权益门槛 ≥ ¥' + perk + ' 体验权益' : '',
      pendingCount: Number(c.pendingCount) || 0,
    }
  })
  return { loadState: chapters.length ? 'ready' : 'empty', chapters: chapters }
}

function shapeRecruitSheet(d) {
  if (!d || typeof d !== 'object') return null
  const mode = modeOf(d)
  return Object.assign({ mode: mode }, mode === 'chapter' ? chapterView(d) : nodeView(d))
}

module.exports = { shapeRecruitSheet, maskPhone }
