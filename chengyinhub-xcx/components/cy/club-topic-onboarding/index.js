// cy-club-topic-onboarding · J3-A/J3-B「承接商家」(T1 半屏弹窗),一个组件两种 variant。
// mode='node'    → J3-A 城市定向按节点招商(Figma 155:118):按点位逐个招,一个节点只能一家承接。
// mode='chapter' → J3-B 自由探索按章节招商(Figma 158:118):按章节挂条款,商家选章节不选点位,
//                  通过后商家自己在章节下建点位、再走点位审核(那一步在别处,不在本弹窗)。
// 两种稿的表头(居中标题、无抓手、header 分隔线)与 T1 现成组件 cy-scene-sheet 的默认头
// (标题左对齐 + ✕ 右)不一致 —— 按须知"复用现成组件"优先,不新起一套头部 chrome,
// 已在 PR 里说明这处与稿子的出入。
//
// 纯展示组件:数据由宿主通过属性传入,不自己发请求。2026-11-02 起宿主是俱乐部主题页
// (pages/club/topic-detail),数据取 /api/club/recruit/overview(扩了章节招募条件、申请数、玩法名),
// 动作 triggerEvent 由宿主接到现成的招商页(pages/topic/merchantinfo)。
//
// 原先设想的聚合接口形状(未建,现由 recruit/overview 承担):
//   node    → { doneCount, totalCount, pendingCount, sections: [{ chapterName, stationCount,
//               wonCount, nodes: [{ id, seq, name, sub, state: 'won'|'pending'|'open',
//               merchantName, statusLabel }] }] }
//   chapter → { chapters: [{ id, name, statusLabel: '招募中'|'未开放', recruiting: boolean,
//               category, capacityText, tags: [string], approvedCount, capLimit,
//               thresholdText, pendingCount }] }
Component({
  properties: {
    show: { type: Boolean, value: false },
    topicId: { type: String, value: '' },
    mode: { type: String, value: 'node' }, // 'node' | 'chapter'
    loadState: { type: String, value: 'loading' }, // loading | ready | empty | error | denied(403,不给重试)
    loadErrorText: { type: String, value: '' },
    doneCount: { type: Number, value: 0 },
    totalCount: { type: Number, value: 0 },
    pendingCount: { type: Number, value: 0 },
    progressPct: { type: Number, value: 0 },
    sections: { type: Array, value: [] }, // mode='node'
    chapters: { type: Array, value: [] }, // mode='chapter'
    // 2026-11-02(ROUND3B):宿主按服务端权限给。false 时招商/开放/申请这些动作一个都不画,不留死按钮。
    canAct: { type: Boolean, value: false },
  },
  // 2026-11-02(ROUND3B,用户「不要说明文字」):稿上顶部与底部的解释句(按节点 / 按章节两段、底部节点审核提示、
  // 未开放章节的「开放后…」)不再渲染,只留数据行。
  data: {},
  methods: {
    // ===== mode='node' =====
    onInviteNode(e) {
      this.triggerEvent('invite', { topicId: this.data.topicId, nodeId: e.currentTarget.dataset.id });
    },
    onCopyPhone(e) {
      this.triggerEvent('copyphone', { phone: e.currentTarget.dataset.phone });
    },
    onViewPool() {
      this.triggerEvent('viewpool', { topicId: this.data.topicId });
    },

    // ===== mode='chapter' =====
    onViewApplications(e) {
      this.triggerEvent('viewapplications', { topicId: this.data.topicId, chapterId: e.currentTarget.dataset.id });
    },
    onOpenRecruiting(e) {
      this.triggerEvent('openrecruiting', { topicId: this.data.topicId, chapterId: e.currentTarget.dataset.id });
    },
    onEditConditions(e) {
      this.triggerEvent('editconditions', { topicId: this.data.topicId, chapterId: e.currentTarget.dataset.id });
    },

    onRetry() { this.triggerEvent('retry', { topicId: this.data.topicId, mode: this.data.mode }); },
    onClose() { this.triggerEvent('close'); },
  },
});
