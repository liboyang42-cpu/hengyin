// 主办方「我发的这条路线」视图 —— 纯展示组件,与 project-join 同构。
const circleTheme = require('../../utils/circle-theme.js');
const projectProgress = require('../../../../utils/project-progress.js');
const FREE_STATUS = ['草稿', '招募中', '待定价', '售票中', '进行中', '结算中', '已关闭', '立项待审'];
Component({
  options: { addGlobalClass: true },
  properties: {
    /* CU-C-68:主办方抽屉「本站运营」资格探不到时的可见说明(页面探、组件只渲染)。 */
    stationOpsErrorText: { type: String, value: '' },
    statusBarHeight: { type: Number, value: 0 },
    navBarHeight:    { type: Number, value: 0 },
    navHidden:       { type: Boolean, value: false }, // 打卡码弹层显示时收起导航(✕为唯一出口)
    loadFailed:      { type: Boolean, value: false },
    rulesOpen:       { type: Boolean, value: false },
    quickActions:    { type: Array, value: [] },
    clubCandidates:  { type: Object, value: { state: '', rows: [] } }, // 走查 v2 R05:主题内可邀约的俱乐部
    regApplies:      { type: Object, value: { state: '', rows: [] } }, // 走查 v2 R06:本主题收到的商家报名
    hostOwnerType:   { type: String, value: 'merchant' }, // club 主办不显示俱乐部协作位
    playerSheet: { type: Object, value: {} },
    playerFilters: { type: Array, value: [] },
    playerActions: { type: Array, value: [] },
    clubActions: { type: Array, value: [] },
    merchantSheet: { type: Object, value: {} },
    moreSheet:     { type: Object, value: { show: false, groups: [] } },
    merchantActions: { type: Array, value: [] },
    clubSheet:       { type: Object, value: { show: false, items: [] } },
    hostTopic:       { type: Object, value: {} },
    hostRecruit:     { type: Object, value: {} },
    hostMerchants:   { type: Array, value: [] },
    hostPlayers:     { type: Object, value: {} },
    ownerChapterApplications: { type: Array, value: [] },
    ownerChapterApplicationsState: { type: String, value: 'idle' },
    pendingMerchantNodes: { type: Array, value: [] },
    pendingMerchantNodesState: { type: String, value: 'idle' },
    // BL-183:圈层供给角色抽屉。开关/提交中/后端错误归页面;勾选只在抽屉里,提交时随事件带回。
    circleConfig:    { type: Object, value: { show: false, submitting: false, errorText: '', errorTitle: '' } },
  },
  observers: {
    'hostTopic, hostRecruit': function (topic, recruit) {
      topic = topic || {};
      let text = '状态待确认';
      if (Number(topic.productType) === 2) {
        const raw = topic.lifecycle;
        const lifecycle = typeof raw === 'number' ? raw
          : (typeof raw === 'string' && /^[0-7]$/.test(raw) ? Number(raw) : -1);
        if (Number.isInteger(lifecycle) && lifecycle >= 0 && lifecycle < FREE_STATUS.length) {
          text = FREE_STATUS[lifecycle];
        }
      } else if (topic.status === 1) {
        text = recruit && recruit.nodeTotal > 0 && recruit.nodeFilled >= recruit.nodeTotal ? '已招满' : '招募中';
      } else if (topic.status === 0) text = '待审核';
      else if (topic.status === 2) text = '审核未通过';
      this.setData({ hostStatusText: text });
    },
    // 抽屉在页面流里,不是遮罩;上面那块 hero 虚化就是「有东西打开了」的唯一提示
    'clubSheet, playerSheet, merchantSheet, circleConfig': function (club, player, merchant, circle) {
      this.setData({
        anyDrawerOpen: !!((club && club.show) || (player && player.show) || (merchant && merchant.show)
          || (circle && circle.show)),
      });
    },
    'circleConfig.show': function (show) {
      if (show && !this._circleConfigOpen) this.resetCircleCandidates();
      this._circleConfigOpen = !!show;
    },
    // 派生量放 observer 而不是让页面再多存两个字段:它们只有这个视图用得上
    'hostRecruit, clubSheet, hostTopic, hostPlayers': function (recruit, sheet, topic, players) {
      const total = (recruit && recruit.nodeTotal) || 0;
      const filled = (recruit && recruit.nodeFilled) || 0;
      const clubs = (sheet && sheet.items) || [];
      this.setData({
        recruitDone: total > 0 && filled >= total,
        clubCount: clubs.length,
        clubAccepted: clubs.filter(function (c) { return c && c.accepted; }).length,
        needExecutorClub: !!topic && Number(topic.productType) === 2 && this.data.hostOwnerType !== 'club'
          && [1, 2].indexOf(Number(topic.lifecycle)) >= 0 && !clubs.some(function (c) { return c && c.accepted; }),
        // 走查 v2 R04/R16:底部进度,只用这几份已有数据派生
        hostProgress: projectProgress.buildHostSteps({
          lifecycle: topic && topic.lifecycle,
          productType: topic && topic.productType,
          nodeTotal: total,
          nodeFilled: filled,
          pendingCount: recruit && recruit.pendingCount,
          clubCount: clubs.filter(function (c) { return c && c.accepted; }).length,
          paidCount: players && players.paidCount,
        }),
      });
    },
  },
  data: {
    recruitDone: false, clubCount: 0, clubAccepted: 0, hostProgress: [], needExecutorClub: false, anyDrawerOpen: false, hostStatusText: '状态待确认',
    circleCandidates: [], circleConfigHint: '',
    circleConfigActions: [{ key: 'submitCircleConfig', label: '保存供给角色' }],
  },
  methods: {
    noop() {},
    // 每次打开都从零开始选:没有读当前已开启候选的接口,预选任何一项都是编造。
    resetCircleCandidates() {
      const theme = circleTheme.themeOf((this.data.hostTopic || {}).circleThemeCode);
      this._circlePicked = [];
      this.setData({
        circleCandidates: theme ? theme.candidates.map((item) => ({
          code: item.code, name: item.name, merchant: item.merchant,
          roleLabel: item.roleLabel, picked: false,
        })) : [],
        circleConfigHint: theme ? '' : '这个主题暂不支持在小程序里选择供给角色',
      });
    },
    toggleCircleCandidate(e) {
      if ((this.data.circleConfig || {}).submitting) return;
      const code = String((e.currentTarget.dataset || {}).code || '');
      if (!code) return;
      const picked = (this._circlePicked || []).slice();
      const at = picked.indexOf(code);
      if (at >= 0) picked.splice(at, 1); else picked.push(code);
      this._circlePicked = picked;
      this.setData({
        circleCandidates: this.data.circleCandidates.map((item) => Object.assign({}, item, {
          picked: picked.indexOf(item.code) >= 0,
        })),
        circleConfigHint: '',
      });
    },
    emitCloseCircleConfig() {
      if ((this.data.circleConfig || {}).submitting) return;
      this.triggerEvent('act', { act: 'closeCircleConfig', dataset: {} });
    },
    emitCircleConfigSubmit() {
      const themeCode = (this.data.hostTopic || {}).circleThemeCode;
      const codes = (this._circlePicked || []).slice();
      const check = circleTheme.validateSelection(themeCode, codes);
      if (!check.ok) {
        this.setData({ circleConfigHint: check.message });
        return;
      }
      this.triggerEvent('act', { act: 'submitCircleConfig', dataset: { candidateCodes: codes } });
    },
    emit(e) {
      const ds = e.currentTarget.dataset || {};
      this.triggerEvent('act', { act: ds.act, dataset: ds });
    },
    emitCloseClub() {
      this.triggerEvent('act', { act: 'closeClubSheet', dataset: {} });
    },
    emitClosePlayer() {
      this.triggerEvent('act', { act: 'closePlayerSheet', dataset: {} });
    },
    emitCloseMore() {
      this.triggerEvent('act', { act: 'closeMoreSheet', dataset: {} });
    },
    emitCloseMerchant() {
      this.triggerEvent('act', { act: 'closeMerchantSheet', dataset: {} });
    },
    // 抽屉外那条黑按钮:key 就是 act 名,页面白名单派发
    emitDrawerAction(e) {
      this.triggerEvent('act', { act: (e.detail || {}).key, dataset: {} });
    },
  },
});
