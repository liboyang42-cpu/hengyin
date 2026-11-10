'use strict'

const app = getApp()
const { memberId, failureStatus, retryLogin, waitForInitialSession, recoverReadSession } = require('../../../utils/session/login-reload.js')
const { chinaParts } = require('../../../utils/datetime.js')

function success(response) {
  return response && (response.code === 200 || response.code === '200')
}

function rowsOf(response) {
  return success(response) && response.data && Array.isArray(response.data.rows)
    ? response.data.rows
    : null
}

function finiteNumber(value) {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function nonNegativeInteger(value) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
    ? value
    : null
}

function idKey(value) {
  const raw = value === null || value === undefined ? '' : String(value).trim()
  if (!/^\d+$/.test(raw)) return ''
  const normalized = raw.replace(/^0+(?=\d)/, '')
  return normalized === '0' ? '' : normalized
}

function pointText(value) {
  const number = finiteNumber(value)
  if (number === null) return ''
  return Number.isInteger(number) ? String(number) : String(Math.round(number * 100) / 100)
}

function dateParts(value) {
  return value ? chinaParts(value) : null
}

function monthLabel(value) {
  const parts = dateParts(value)
  return parts ? parts.year + '年' + parts.month + '月' : '其他邀请'
}

function shortTime(value) {
  const parts = dateParts(value)
  if (!parts) return ''
  const hour = String(parts.hours).padStart(2, '0')
  const minute = String(parts.minutes).padStart(2, '0')
  return parts.month + '月' + parts.day + '日 ' + hour + ':' + minute
}

function request(options, onFailure) {
  return new Promise((resolve) => {
    app.sendRequest(Object.assign({}, options, {
      hideLoading: true,
      silentError: true,
      success: (response) => resolve(response || {}),
      fail: (res, statusCode) => { const response = Object.assign({}, res || {}, { code: failureStatus(res, statusCode) }); if (onFailure) onFailure(response); resolve(response) },
      successStatusAbnormal: (res, statusCode) => resolve(Object.assign({}, res || {}, { code: failureStatus(res, statusCode) })),
    }))
  })
}

function rewardMap(rows) {
  const out = {}
  ;(rows || []).forEach((row) => {
    if (!row || Number(row.eventType) !== 5) return
    const eventId = idKey(row.eventId)
    const points = finiteNumber(row.changePoints)
    if (!eventId || !(points > 0)) return
    const key = eventId
    if (!out[key]) out[key] = { points: 0, createTime: row.createTime || '' }
    out[key].points += points
    if (!out[key].createTime && row.createTime) out[key].createTime = row.createTime
  })
  return out
}

function buildGroups(records, rewards, rewardReady) {
  const groups = []
  const byLabel = {}
  ;(records || []).forEach((record) => {
    const recordKey = record && idKey(record.id)
    if (!recordKey) return
    const reward = rewards[recordKey] || null
    const sourceTime = record.createTime || ''
    const label = monthLabel(sourceTime)
    let group = byLabel[label]
    if (!group) {
      group = { label, items: [], rewardTotal: 0, rewardText: '' }
      byLabel[label] = group
      groups.push(group)
    }
    const nickname = typeof record.nickname === 'string' && record.nickname.trim()
      ? record.nickname.trim()
      : '—'
    const recordTime = shortTime(record.createTime)
    const earned = !!reward
    const rewardTime = earned ? shortTime(reward.createTime) : ''
    const rewardValue = earned ? pointText(reward.points) : ''
    if (earned) group.rewardTotal += reward.points
    group.items.push({
      id: record.id,
      nickname,
      avatar: record.avatar || '',
      timeText: recordTime || '邀请时间暂未记录',
      statusText: earned
        ? '首购奖励已到账' + (!recordTime && rewardTime ? ' · ' + rewardTime : '')
        : (rewardReady ? '已加入 · 首购待完成' : '已加入 · 奖励发放中'),
      rewardText: earned ? '+' + rewardValue + ' 积分' : (rewardReady ? '待解锁' : '发放中'),
    })
  })
  groups.forEach((group) => {
    group.rewardText = group.rewardTotal > 0 ? '+' + pointText(group.rewardTotal) + ' 积分' : group.items.length + ' 人'
    delete group.rewardTotal
  })
  return groups
}

/* r3-visual 分享赚积分(refs4/share-earn-credits-frames):只用积分流水里的真实行。
   latest = 最新一笔邀请首购奖励(eventType 5,流水按 id 倒序,第一条就是最新);
   balance = 流水第一条的 afterPoints(账户当前积分)。最新一条正好是这笔邀请奖励时,
   余额胶囊从它的 currentPoints 数到 afterPoints;否则静止显示。缺字段就不出。 */
function earnFacts(pointRows, records) {
  const rows = Array.isArray(pointRows) ? pointRows : []
  const head = rows[0] || null
  const after = head ? finiteNumber(head.afterPoints) : null
  const balance = after === null ? null : { to: after, from: after }
  if (balance && head && Number(head.eventType) === 5) {
    const before = finiteNumber(head.currentPoints)
    if (before !== null && before <= after) balance.from = before
  }
  const row = rows.find(r => r && Number(r.eventType) === 5 && finiteNumber(r.changePoints) > 0 && idKey(r.eventId)) || null
  let latest = null
  if (row) {
    const who = (records || []).find(r => r && idKey(r.id) === idKey(row.eventId)) || null
    latest = {
      points: pointText(row.changePoints),
      nickname: who && typeof who.nickname === 'string' && who.nickname.trim() ? who.nickname.trim() : '',
      avatar: (who && who.avatar) || '',
      timeText: shortTime(row.createTime),
    }
  }
  return { balance, latest }
}

/* 数字滚动:约 14 步走完,最后一步一定落在真实值上;减少动态效果直接落值 */
function countUp(ctx, key, from, to, ms, reduced) {
  clearTimeout(ctx['_cu_' + key])
  if (reduced || from === to || typeof setTimeout !== 'function') { ctx.setData({ [key]: pointText(to) }); return }
  const steps = 14
  let i = 0
  const tick = () => {
    if (ctx._detached) return
    i += 1
    const t = i / steps
    const eased = 1 - Math.pow(1 - t, 3)
    ctx.setData({ [key]: i >= steps ? pointText(to) : String(Math.round(from + (to - from) * eased)) })
    if (i < steps) ctx['_cu_' + key] = setTimeout(tick, ms / steps)
  }
  ctx.setData({ [key]: pointText(from) })
  ctx['_cu_' + key] = setTimeout(tick, ms / steps)
}

// V1-P59(IqRophy):汇总卡里叠放的被邀请人头像,只用后端返回的真实记录,最多 5 个
const STACK_MAX = 5
const pageMotion = require('../../../utils/page-motion.js')
const { readReducedMotion } = require('../../../utils/motion-preference.js')
function buildStack(records) {
  return (records || []).slice(0, STACK_MAX).map((record) => ({
    id: record.id,
    avatar: record.avatar || '',
    nickname: typeof record.nickname === 'string' ? record.nickname.trim() : '',
  }))
}

Component({
  properties: {
    theme: { type: String, value: 'player' },
  },
  data: {
    loading: true,
    authRequired: false,
    accessDenied: false,
    loadErr: false,
    loadMoreError: false,
    empty: false,
    total: 0,
    hasMore: false,
    groups: [],
    rewardReady: true,
    earnedTotalText: '0',
    earnedCount: 0,
    stack: [],
    // r3-visual 分享赚积分:余额胶囊(balanceText,读不到为空串不出)、最新到账卡(latest,0 隐 1 扫 2 金额 3 到位)
    balanceText: '',
    latest: null,
    earnStage: 0,
    // utils/page-motion.js:1 = 先隐着渲染,2 = 依次浮上来,0 = 静止
    pmIn: 0,
  },
  lifetimes: {
    attached() { this.load(true) },
    detached() { this._detached = true; this._loadSeq = (this._loadSeq || 0) + 1; clearTimeout(this._pmInTimer); clearTimeout(this._cu_balanceText); clearTimeout(this._cu_earnedTotalText); (this._earnT || []).forEach(clearTimeout) },
  },
  pageLifetimes: {
    show() { this._hidden = false; if (this._readSessionChanged || this._readOwner !== undefined && this._readOwner !== memberId(app)) { this._readSessionChanged = false; this.load(true) } },
    hide() { this._hidden = true },
  },
  methods: {
    load(reset) {
      if (this._detached) return
      const owner = memberId(app)
      if (owner !== this._readOwner) { this._readOwner = owner; this._loadSeq = (this._loadSeq || 0) + 1; this._loading = false; this._records = []; this._rewards = {}; this.setData({ groups: [], stack: [], total: 0, hasMore: false }); reset = true }
      if (!owner) { waitForInitialSession(this, app, () => this.load(true)); this.setData({ authRequired: true, accessDenied: false, loadErr: true, loading: false }); return }
      if (this._loading) return
      const pageNo = reset ? 1 : (this._pageNo || 1) + 1
      const seq = (this._loadSeq || 0) + 1
      this._loadSeq = seq
      this._loading = true
      if (reset) {
        this._records = []
        this._rewards = {}
        this._rewardReady = true
      }
      this.setData({
        loading: true,
        authRequired: false, accessDenied: false,
        loadErr: false,
        loadMoreError: false,
        empty: false,
      })

      const recover = res => seq === this._loadSeq && !this._detached && recoverReadSession(this, app, res,
        function (anonymous) { this._loadSeq++; this._loading = false; this._readOwner = undefined; this._records = []; this._rewards = {}; this.setData({ groups: [], total: 0, loading: false, loadErr: true, authRequired: anonymous, accessDenied: false }); },
        () => this.load(true))
      const inviteRequest = request({
        url: '/api/user/invite_list',
        method: 'POST',
        data: { pageNum: pageNo, pageSize: app.getPageSize() },
      }, recover)
      const rewardsRequest = reset
        ? request({ url: '/api/user/points/list', method: 'POST', data: { pageNum: 1, pageSize: 200 } }, recover)
        : Promise.resolve(null)

      Promise.all([inviteRequest, rewardsRequest]).then(([inviteResponse, rewardsResponse]) => {
        if (this._detached || seq !== this._loadSeq) return
        if (recover(inviteResponse) || recover(rewardsResponse) || owner !== memberId(app)) return
        const code = [inviteResponse, rewardsResponse].map(res => failureStatus(res)).find(value => value === 401 || value === 403)
        if (code) { this._loading = false; this._records = []; this._rewards = {}; this.setData({ groups: [], loading: false, loadErr: true, authRequired: code === 401, accessDenied: code === 403 }); return }
        this._loading = false
        const rows = rowsOf(inviteResponse)
        const responseTotal = rows && inviteResponse.data
          ? nonNegativeInteger(inviteResponse.data.total)
          : null
        if (!rows || responseTotal === null) {
          const hasRows = (this._records || []).length > 0
          this.setData({
            loading: false,
            loadErr: !hasRows,
            loadMoreError: hasRows,
            empty: false,
          })
          return
        }

        const merged = reset ? [] : (this._records || []).slice()
        const seen = {}
        merged.forEach((record) => {
          const key = record && idKey(record.id)
          if (key) seen[key] = true
        })
        rows.forEach((record) => {
          const key = record && idKey(record.id)
          if (!key || seen[key]) return
          seen[key] = true
          merged.push(record)
        })
        if (responseTotal < merged.length) {
          const hasRows = (this._records || []).length > 0
          this.setData({
            loading: false,
            loadErr: !hasRows,
            loadMoreError: hasRows,
            empty: false,
          })
          return
        }
        if (reset) {
          const pointRows = rowsOf(rewardsResponse)
          const pointTotal = rewardsResponse && rewardsResponse.data
            ? nonNegativeInteger(rewardsResponse.data.total)
            : null
          this._rewardReady = !!pointRows && pointTotal !== null && pointTotal === pointRows.length
          this._rewards = pointRows ? rewardMap(pointRows) : {}
          this._pointRows = pointRows || []
        }
        this._records = merged
        this._pageNo = pageNo

        const total = responseTotal
        let earnedTotal = 0
        Object.keys(this._rewards || {}).forEach((key) => { earnedTotal += this._rewards[key].points })
        // 首次拿到记录时汇总卡与记录行依次浮上来;刷新、翻页不重播
        if (merged.length) pageMotion.enter(this, 'pmIn', { once: true })
        this.setData({
          loading: false,
          loadErr: false,
          loadMoreError: false,
          empty: merged.length === 0,
          total,
          hasMore: total > merged.length,
          groups: buildGroups(merged, this._rewards || {}, this._rewardReady),
          rewardReady: this._rewardReady,
          earnedTotalText: (this._earnPlayed || !reset || !this._rewardReady) ? (pointText(earnedTotal) || '0') : '0', // r3:首屏由 playEarn 从 0 数上去
          earnedCount: Object.keys(this._rewards || {}).length,
          stack: buildStack(merged),
        })
        if (reset) this.playEarn(merged, earnedTotal)
      })
    },
    /* 首屏一次:积分大数字从 0 数到真实累计;最新到账卡 扫描 → 金额浮现 → 上滑到位 → 勾;余额胶囊跟着数上去 */
    playEarn(records, earnedTotal) {
      if (this._earnPlayed || !this._rewardReady) return
      this._earnPlayed = true
      const reduced = readReducedMotion()
      const { balance, latest } = earnFacts(this._pointRows, records)
      this.setData({ latest, earnStage: latest && !reduced ? 1 : 3 })
      countUp(this, 'earnedTotalText', 0, earnedTotal, 900, reduced)
      if (balance) countUp(this, 'balanceText', balance.from, balance.to, 900, reduced || balance.from === balance.to)
      if (!latest || reduced) return
      this._earnT = [
        setTimeout(() => !this._detached && this.setData({ earnStage: 2 }), 520),
        setTimeout(() => !this._detached && this.setData({ earnStage: 3 }), 1000),
      ]
    },
    retry() { if (this.data.authRequired) retryLogin(this, app, () => this.load(true)); else this.load(true) },
    loadMore() {
      if (this.data.hasMore && !this.data.loading) this.load(false)
    },
  },
})

if (typeof module !== 'undefined') module.exports = { earnFacts, countUp }
