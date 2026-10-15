// 举报队伍:选原因 → POST /api/team/report {teamId, reason} → 回执(taskId = 审核任务号)。
// 后端:reason 可空、最长 200;进统一审核台,审核期间该队伍不出现在地图上(见 ApiPlayTeamController#report)。
const app = getApp()
const { isBizOk } = require('../../../utils/response-shape.js')

const REASONS = ['广告或营销骚扰', '色情低俗或违法内容', '诈骗或虚假信息', '辱骂或人身攻击', '其他问题']

Component({
  properties: {
    show: { type: Boolean, value: false },
    teamId: { type: String, value: '' },
  },
  data: {
    reasons: REASONS,
    stage: 'pick', // pick | busy | ok | fail
    taskId: '',
    failedText: '',
  },
  observers: {
    show(visible) { if (visible) this.setData({ stage: 'pick', taskId: '', failedText: '' }) },
  },
  methods: {
    onSelect(e) {
      if (this._busy) return
      const index = Number(e.detail && e.detail.index)
      const reason = REASONS[index]
      const teamId = Number(this.properties.teamId)
      if (!reason || !Number.isSafeInteger(teamId) || teamId <= 0) return
      this._busy = true
      this.setData({ stage: 'busy' })
      app.sendRequest({
        url: '/api/team/report', method: 'POST', hideLoading: true, silentError: true,
        data: JSON.stringify({ teamId, reason }),
        header: { 'Content-Type': 'application/json' },
        success(res) {
          if (!isBizOk(res)) {
            this._busy = false
            this.setData({ stage: 'fail', failedText: (res && res.msg) || '举报没有提交成功，请稍后再试' })
            return
          }
          const raw = res.data && res.data.taskId
          this._busy = false
          this.setData({ stage: 'ok', taskId: raw == null ? '' : String(raw) })
        },
        fail: () => {
          this._busy = false
          this.setData({ stage: 'fail', failedText: '网络不太稳定，举报没有提交成功，可以再试一次' })
        },
        successStatusAbnormal: () => {
          this._busy = false
          this.setData({ stage: 'fail', failedText: '网络不太稳定，举报没有提交成功，可以再试一次' })
        },
      })
    },
    onPickCancel() { if (!this._busy) this.triggerEvent('close') },
    onResultClose() { if (!this._busy) this.triggerEvent('close') },
    onRetry() { this.setData({ stage: 'pick', failedText: '' }) },
  },
})
