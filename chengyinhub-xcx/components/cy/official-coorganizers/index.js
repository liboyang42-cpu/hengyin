// cy-official-coorganizers · CL6 联合主办展示(只读)。
// mode=row:活动首屏一行「头像/首字 + 名称 + 角色」,只放已接受的主办/协办;
// mode=section:完整列表,含履约/支持等其他角色;待确认/已拒绝只在 viewerIsManager 时出现。
// 视图模型全在 utils/official-parties.js(有单测);本组件只做 parties → vm 的投影,不发请求。
// 「邀请协办方」已排期活动暂无后端接口(PR #21 合同申请),showInviteEntry 只画一个不可点的「待接口」占位。
const { buildOfficialPartiesView } = require('../../../utils/official-parties.js');

Component({
  properties: {
    parties: { type: Array, value: [] },          // OfficialEventParty 原样数组
    names: { type: Object, value: null },         // 可选 { 'TYPE:partyId': '名称' | { name, avatar } }
    viewerIsManager: { type: Boolean, value: false },
    mode: { type: String, value: 'section' },     // row | section
    onCover: { type: Boolean, value: false },     // row 叠在封面暗角上时用反白字
    heroLimit: { type: Number, value: 3 },
    title: { type: String, value: '联合主办' },
    showInviteEntry: { type: Boolean, value: false },
    // bare:不画自带标题与卡片底,由宿主页用它现有的分段/标题样式包住(2026-10-15 晚用户决定:新功能按现有 UI 做)
    bare: { type: Boolean, value: false },
  },
  data: { vm: { hero: [], heroMore: 0, list: [], hostCount: 0, hasPending: false } },
  observers: {
    'parties, names, viewerIsManager, heroLimit'(parties, names, viewerIsManager, heroLimit) {
      this.setData({ vm: buildOfficialPartiesView(parties, { names, viewerIsManager, heroLimit }) });
    },
  },
});
