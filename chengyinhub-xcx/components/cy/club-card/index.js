// 玩家看兴趣与入会权益，合作方看带队能力；不改变账号身份或权限。
Component({
  properties: {
    club: { type: Object, value: null },
    loading: { type: Boolean, value: false },
    interactive: { type: Boolean, value: false },
    audience: { type: String, value: 'player' },
    cta: { type: String, value: '' },
  },
  data: { _data: '', _tags: [], _joined: false, _coverFailed: false, _logoFailed: false },
  observers: {
    'club, audience': function (club, audience) {
      const c = club || {};
      const data = [];
      if (c.memberCount != null) data.push(c.memberCount + ' 位成员');
      if (c.topicCount != null) data.push(c.topicCount + ' 个近期团');
      const joined = c.isJoined === true || c.isJoined === 1;
      const owner = c.isOwner === true || c.isOwner === 1;
      let tags = [];
      if (audience === 'merchant') {
        if (Number(c.canDesignRoute) === 1) tags.push('能设计路线');
        if (Number(c.canNpc) === 1) tags.push('能出 NPC');
        if (Number(c.canMerchantCoop) === 1) tags.push('能对接商家');
        if (Number(c.hasGuideCert) === 1) tags.push('导游资质');
      } else {
        tags = splitList(c.clubType).concat(splitList(c.keywords), splitList(c.tags));
        if (club && !owner && !joined) tags.push('免费加入');
        if (Number(c.prioritySignupEnabled) === 1) tags.push('成员优先报名');
      }
      this.setData({ _data: data.join(' · '), _tags: Array.from(new Set(tags)), _joined: joined, _coverFailed: false, _logoFailed: false });
    },
  },
  methods: {
    onTap() { if (!this.data.loading && this.data.club) this.triggerEvent('tap'); },
    onCoverError() { this.setData({ _coverFailed: true }); },
    onLogoError() { this.setData({ _logoFailed: true }); },
  },
});
function splitList(value) {
  if (!value) return [];
  if (typeof value === 'string') {
    const s = value.trim();
    if (s[0] === '[') { try { value = JSON.parse(s); } catch (_) { return []; } }
    else value = s.split(/[,;、，；]+/);
  }
  return Array.isArray(value) ? value.filter(v => typeof v === 'string').map(v => v.trim()).filter(Boolean) : [];
}
