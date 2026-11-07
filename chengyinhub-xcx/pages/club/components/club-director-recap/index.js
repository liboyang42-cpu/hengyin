/**
 * cy-club-director-recap · D9 活动复盘 · 已结束整页态。
 * Figma s7SEFaoJ3GQUIxJhdqcFUb 289:523。纯展示,数据全部由宿主活动详情页从 recap 映射传入。
 * 版式(C-11 第三轮):三块大数 → 完成情况行 → 榜单;节点完成有数据才接在最后。
 * 「榜单对外可见性」行沿用收编行列表的可点行为,tap 时把行 id 回抛给宿主(宿主决定是否二次确认)。
 */
Component({
  properties: {
    title: { type: String, value: '活动复盘' },
    metrics: { type: Array, value: [] }, // 三块大数 [{ label, valueText, unit }]
    completionTitle: { type: String, value: '完成情况' },
    completionRows: { type: Array, value: [] },
    leaderboardTitle: { type: String, value: '榜单' },
    leaderboardRows: { type: Array, value: [] },
    stationTitle: { type: String, value: '节点完成' },
    stationRows: { type: Array, value: [] },
    caption: { type: String, value: '' },
  },
  methods: {
    onLeaderboardRowTap(e) {
      this.triggerEvent('leaderboardrowtap', e.detail)
    },
  },
})
