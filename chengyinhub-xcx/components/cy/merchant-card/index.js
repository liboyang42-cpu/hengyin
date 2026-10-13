
Component({
  properties: {
    variant: { type: String, value: 'list' },
    size: { type: String, value: 'md' },
    status: { type: String, value: 'open' },
    partner: { type: Boolean, value: false },
    cover: { type: String, value: '' },
    logo: { type: String, value: '' },
    categoryName: { type: String, value: '' },
    address: { type: String, value: '' },
    name: { type: String, value: '' },
    cityRole: { type: String, value: '' },
    desc: { type: String, value: '' },
    statusText: { type: String, value: '' },
    distance: { type: String, value: '' },
    district: { type: String, value: '' },
    verified: { type: null, value: null },
    tags: { type: null, value: [] },
    serviceTag: { type: String, value: '' },
    serviceText: { type: String, value: '' },
    actionText: { type: String, value: '去看看' },
    interactive: { type: Boolean, value: false },

    capacity: { type: null, value: null },
    availableTime: { type: String, value: '' },
    chargeType: { type: null, value: null },
    suitTypes: { type: String, value: '' },
    coopOpen: { type: null, value: null },
  },
  data: { _tags: [], _suit: '', _coverFailed: false, _logoFailed: false },
  observers: {
    'tags, variant, chargeType, capacity': function (tags, variant, chargeType, capacity) {
      let values = [];
      if (variant === 'venue') {
        if (chargeType === 0 || chargeType === '0') values.push('场地免费');
        if (chargeType === 1 || chargeType === '1') values.push('场地收费');
        if (Number(capacity) > 0) values.push('可承接 ' + capacity + ' 人');
      } else {
        if (typeof tags === 'string') {
          try { tags = JSON.parse(tags); } catch (_) { tags = tags.split(/[,;、，；]+/); }
        }
        values = Array.isArray(tags) ? tags.filter(t => typeof t === 'string').map(t => t.trim()).filter(Boolean) : [];
      }
      this.setData({ _tags: Array.from(new Set(values)) });
    },
    suitTypes(value) { this.setData({ _suit: String(value || '').split(/[,;、，；\/]+/).map(s => s.trim()).filter(Boolean).join(' · ') }); },
    cover() { this.setData({ _coverFailed: false }); },
    logo() { this.setData({ _logoFailed: false }); },
  },
  methods: {
    onTap() { const s = this.data.status; if (s !== 'disabled' && s !== 'loading') this.triggerEvent('tap'); },
    onService() { if (this.data.status !== 'disabled' && this.data.status !== 'loading') this.triggerEvent('service'); },
    onCoverError() { this.setData({ _coverFailed: true }); },
    onLogoError() { this.setData({ _logoFailed: true }); },
  },
});
