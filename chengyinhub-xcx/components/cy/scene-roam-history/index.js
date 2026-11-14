'use strict'

const { currentPlayerRoamMemory } = require('../../../utils/roam-player-memory.js'); const pageMotion = require('../../../utils/page-motion.js')
const { validRecovery } = require('../../../utils/roam-recovery.js')
const { readNonNegative, formatDistance, formatDuration } = require('../../../utils/roam-history-metrics.js')
const { routeGeo } = require('../../../utils/roam-route-geo.js')
const { createReader, merge } = require('../../../utils/roam-passport-read.js')
const WEEK = ['日', '一', '二', '三', '四', '五', '六']

// 2026-10-31 P-7:每条记录就是一张轨迹地图卡。卡内地图区 686×360rpx,轨迹与店铺点一起按 bbox 居中
// (与足迹分享卡同一套投影);没有轨迹(换手机 / 30 天位置已清)只在中间画一个点,不画线。
const MAP_W = 686
const MAP_H = 360
const MAP_PAD = 48
function routeView(track, pois) {
  const pts = (Array.isArray(track) ? track : []).filter((p) => p && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng)))
  if (pts.length < 2) return { segs: [], dots: [], start: null, end: null, single: true }
  const kx = Math.cos(Number(pts[0].lat) * Math.PI / 180)
  const M = (p) => ({ x: Number(p.lng) * kx, y: -Number(p.lat) })
  const tp = pts.map(M)
  const pp = (Array.isArray(pois) ? pois : []).filter((p) => p && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng))).map(M)
  let x0 = 1e9; let y0 = 1e9; let x1 = -1e9; let y1 = -1e9
  tp.concat(pp).forEach((p) => { x0 = Math.min(x0, p.x); y0 = Math.min(y0, p.y); x1 = Math.max(x1, p.x); y1 = Math.max(y1, p.y) })
  const sc = Math.min((MAP_W - MAP_PAD * 2) / Math.max(1e-9, x1 - x0), (MAP_H - MAP_PAD * 2) / Math.max(1e-9, y1 - y0))
  const ox = (MAP_W - (x1 - x0) * sc) / 2 - x0 * sc
  const oy = (MAP_H - (y1 - y0) * sc) / 2 - y0 * sc
  const X = (p) => Math.round(p.x * sc + ox)
  const Y = (p) => Math.round(p.y * sc + oy)
  const segs = []
  for (let i = 1; i < tp.length; i++) {
    const ax = X(tp[i - 1]); const ay = Y(tp[i - 1]); const bx = X(tp[i]); const by = Y(tp[i])
    const len = Math.hypot(bx - ax, by - ay)
    if (len < 2) continue
    segs.push({ i, x: ax, y: ay, len: Math.round(len) + 3, deg: Math.round(Math.atan2(by - ay, bx - ax) * 180 / Math.PI) })
  }
  return { segs, dots: pp.map((p, i) => ({ i, x: X(p), y: Y(p), tone: i % 4 })),
    start: { x: X(tp[0]), y: Y(tp[0]) }, end: { x: X(tp[tp.length - 1]), y: Y(tp[tp.length - 1]) }, single: false }
}

function nonNegativeNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null
}

Component({
  properties: {
    tools: { type: Boolean, value: false }, // 宿主页接了 bind:tool 才显示那年今日/月度回顾/导出
  },
  data: {
    list: [], remoteLoading: false, remoteError: false, hasMore: false, localWarning: false,
    state: 'loading',
    // CU-M-55:本次漫游还没结束。sessions 只在结算时才写(roam 页 _saveSession),
    // 所以「列表空」不等于「没开始过」—— 进行中有本机的 recovery 记录作依据。
    inProgress: false,
    summary: { trips: 0, km: '0.0', shopsText: '0', timeText: '—' },
  },
  lifetimes: {
    attached() { this._load() },
    detached() { if (this._historyReader) this._historyReader.dispose() },
  },
  pageLifetimes: {
    hide() { if (this._historyReader) this._historyReader.dispose() },
    show() { this._load() },
  },
  methods: {
    _loadLocal() {
      this.setData({ state: 'loading' })
      try {
        // readSessionState 自带 ok 位:本地读失败与「真的没有记录」必须分开,
        // 读失败走 error 可重试,不能显示成一段空历史。
        const memory = currentPlayerRoamMemory(getApp(), wx)
        const state = memory.readSessionState()
        const stored = state.sessions
        if (!state.ok || !Array.isArray(stored) || stored.some((item) => !item || typeof item !== 'object' || Array.isArray(item) || this._timestamp(item.ts) <= 0)) {
          this._sessions = []
          this.setData({ state: 'error', list: [] })
          return
        }
        this._sessions = stored
        /* CU-M-55:正在漫游时列表本来就是空的(记录只在结算时写),空态不能再写
           「你还没有开始过城市漫游」。判据用与 roam 页同一份 validRecovery ——
           形状不对/被清掉的记录不算进行中,不能凭空多出一张「正在漫游」卡。 */
        const recovery = memory.readRecoveryState()
        if (!recovery.ok) throw new Error('recovery unavailable')
        this._inProgress = recovery.ok && !!recovery.record && validRecovery(recovery.record)
      } catch (_) {
        this._sessions = []
        this.setData({ state: 'error', list: [] })
        return
      }
      this._sync()
    },
    _load() {
      const previous = this._historyReader && this._historyReader.current() ? this._remoteSessions || [] : []
      if (this._historyReader) this._historyReader.dispose()
      const reader = this._historyReader = createReader(getApp(), wx)
      this._inProgress = false
      this._loadLocal()
      this._localSessions = this._sessions || []
      this._remoteSessions = previous
      this._refreshing = true
      this._nextBefore = null
      const localWarning = this.data.state === 'error'
      this._sessions = merge(this._localSessions, previous)
      if (previous.length) this._sync()
      this.setData({ remoteLoading: true, remoteError: false, hasMore: false, localWarning })
      return this._loadRemotePage(reader)
    },
    async _loadRemotePage(reader) {
      try {
        const page = await reader.page(this._refreshing ? null : this._nextBefore)
        if (!reader.current() || reader !== this._historyReader) return
        this._remoteSessions = this._refreshing ? page.rows : this._remoteSessions.concat(page.rows)
        this._refreshing = false
        this._nextBefore = page.nextBefore
        this._sessions = merge(this._localSessions, this._remoteSessions)
        this._sync()
        this.setData({ remoteLoading: false, remoteError: false, hasMore: !!page.nextBefore })
      } catch (_) {
        if (!reader.current() || reader !== this._historyReader) return
        this.setData({ remoteLoading: false, remoteError: true, state: this._sessions.length ? 'ready' : 'error' })
      }
    },
    loadMore() {
      if (this.data.remoteLoading || !this._nextBefore) return
      this.setData({ remoteLoading: true, remoteError: false })
      return this._loadRemotePage(this._historyReader)
    },
    _sync() {
      const list = this._sorted('new')
      let km = 0; let kmKnown = true
      let shops = 0; let shopsKnown = true
      let secs = 0; let secsKnown = true
      let month = ''
      list.forEach((session) => {
        if (session.distanceValue === null) kmKnown = false
        else km += session.distanceValue
        if (session.shopsValue === null) shopsKnown = false
        else shops += session.shopsValue
        const dur = nonNegativeNumber(session && session.durSec)
        if (dur === null) secsKnown = false
        else secs += dur
        // 按月分组:同一个月的第一张卡上面出月份标题
        session.monthHead = session.monthText !== month ? session.monthText : ''
        month = session.monthText
      })
      pageMotion.enter(this, 'pmList'); this.setData({
        list,
        state: 'ready',
        inProgress: this._inProgress === true,
        // NRC 总成绩:没有数据的项写「—」,不造 0(UI-04)
        summary: { trips: list.length, km: kmKnown ? formatDistance(km) : '—', shopsText: shopsKnown ? String(shops) : '—',
          timeText: secsKnown && list.length ? formatDuration(secs) : '—' },
      })
    },
    _sorted() {
      const list = (this._sessions || []).map((session) => {
        const timestamp = this._timestamp(session && session.ts)
        const date = new Date(timestamp || NaN)
        const validDate = !Number.isNaN(date.getTime())
        const dateText = validDate
          ? `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 周${WEEK[date.getDay()]}`
          : '日期不可用'
        const zone = String((session && session.zone) || '').trim()
        const code = (zone || '漫游').replace(/[的这那片街区]/g, '').slice(0, 2) || '城西'
        const photos = Array.isArray(session && session.photos) ? session.photos : []
        const firstPhoto = photos[0]
        // distance 历史上有两代写法(number 与 "0.2" 字符串);用宽容读法兼容老记录,
        // 但 0 仍是真实零、空/非数字仍是未知。shops 始终按 number 存,保持原合同。
        const distanceValue = readNonNegative(session && session.distance)
        const shopsValue = nonNegativeNumber(session && session.shops)
        return {
          ...session,
          historyKey: session.historyKey || 'local:' + session.ts,
          dateFull: dateText,
          dateShort: validDate
            ? `${date.getMonth() + 1}/${date.getDate()}/${String(date.getFullYear()).slice(2)}`
            : '日期不可用',
          title: (zone && zone !== '这片街区' ? zone : '城市') + '漫游',
          dateCard: validDate ? `${date.getMonth() + 1}月${date.getDate()}日 周${WEEK[date.getDay()]} · ${['凌晨', '上午', '下午', '晚上'][Math.floor(date.getHours() / 6)]}` : '日期不可用',
          monthText: validDate ? `${date.getFullYear()}年${date.getMonth() + 1}月` : '',
          route: routeView(session && session.track, session && session.pois),
          // V3-40:有轨迹坐标就画真实底图,没有才用上面的示意图
          geo: routeGeo(session && session.track, session && session.pois),
          cover: typeof firstPhoto === 'string' ? firstPhoto : ((firstPhoto && firstPhoto.path) || ''),
          stamps: photos.length,
          medals: [session && session.medal, session && session.shopMedalName].filter(Boolean),
          fromCode: code,
          toCode: code,
          distanceValue,
          // UI-04:本机记录没取到显示 0;服务端事实(serverFact)的 null 显示「—」不伪造 0
          distanceText: distanceValue === null ? (session && session.serverFact ? '—' : '0') : formatDistance(distanceValue),
          shopsValue,
          shopsText: shopsValue === null ? '—' : String(shopsValue),
          // 2026-10-31 P-7:用时与总成绩同一写法 h:mm(有 durSec 就按它算,老本机记录才退回存下的展示串)
          time: formatDuration(session && session.durSec, session && session.time) || '—',
          _timestamp: timestamp,
        }
      })
      list.sort((a, b) => b._timestamp - a._timestamp)
      return list
    },
    _timestamp(value) {
      const number = Number(value)
      if (Number.isFinite(number) && number > 0) return number < 100000000000 ? number * 1000 : number
      const parsed = Date.parse(value)
      return Number.isFinite(parsed) ? parsed : 0
    },
    _formatDuration(seconds) { return formatDuration(seconds) },
    openSession(event) {
      this.triggerEvent('open', { id: 'roam-session', params: { ts: event.currentTarget.dataset.sessionId ? 'server:' + event.currentTarget.dataset.sessionId : event.currentTarget.dataset.ts, sessionId: event.currentTarget.dataset.sessionId || '' } })
    },
    closeToRoam() {
      this.triggerEvent('close')
    },
    retry() { this._load() },
    openTool(event) { const key = event.currentTarget.dataset.key; if (key) this.triggerEvent('tool', { key }) },
  },
})
