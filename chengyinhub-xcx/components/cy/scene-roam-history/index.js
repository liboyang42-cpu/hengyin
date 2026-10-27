'use strict'

const { currentPlayerRoamMemory } = require('../../../utils/roam-player-memory.js'); const pageMotion = require('../../../utils/page-motion.js')
const { validRecovery } = require('../../../utils/roam-recovery.js')
const { readNonNegative } = require('../../../utils/roam-history-metrics.js')
const { createReader, merge } = require('../../../utils/roam-passport-read.js')
const WEEK = ['日', '一', '二', '三', '四', '五', '六']

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
    summary: { trips: 0, km: '0.0', shopsText: '0' },
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
      list.forEach((session) => {
        if (session.distanceValue === null) kmKnown = false
        else km += session.distanceValue
        if (session.shopsValue === null) shopsKnown = false
        else shops += session.shopsValue
      })
      pageMotion.enter(this, 'pmList'); this.setData({
        list,
        state: 'ready',
        inProgress: this._inProgress === true,
        summary: { trips: list.length, km: kmKnown ? km.toFixed(1) : '—', shopsText: shopsKnown ? String(shops) : '—' },
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
          cover: typeof firstPhoto === 'string' ? firstPhoto : ((firstPhoto && firstPhoto.path) || ''),
          stamps: photos.length,
          medals: [session && session.medal, session && session.shopMedalName].filter(Boolean),
          fromCode: code,
          toCode: code,
          distanceValue,
          // UI-04:本机记录没取到显示 0;服务端事实(serverFact)的 null 显示「—」不伪造 0
          distanceText: distanceValue === null ? (session && session.serverFact ? '—' : '0') : String(distanceValue),
          shopsValue,
          shopsText: shopsValue === null ? '—' : String(shopsValue),
          time: (session && typeof session.time === 'string' && session.time.trim()) || this._formatDuration(session && session.durSec) || '—',
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
    _formatDuration(seconds) {
      const value = nonNegativeNumber(seconds)
      if (value === null) return ''
      return `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`
    },
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
