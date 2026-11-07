// 第九轮 m1-9:地址开地图走 utils/open-address.js,点了才载入;是否给点只看坐标是否成立(与它同一判据)
function openAddress() { return require('../../../utils/open-address.js') }
function hasGeo(lat, lng) { const a = Number(lat), b = Number(lng); return lat !== '' && lng !== '' && lat != null && lng != null && isFinite(a) && isFinite(b) && !(a === 0 && b === 0) && Math.abs(a) <= 90 && Math.abs(b) <= 180; }
Component({
  properties: {
    // 第九轮 m1-9:有坐标时地址一点开地图;nav 为真时再给一个「导航」胶囊
    latitude: { type: null, value: null },
    longitude: { type: null, value: null },
    nav: { type: Boolean, value: false },
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
  data: { _tags: [], _suit: '', _coverFailed: false, _logoFailed: false, _geo: false },
  observers: {
    'latitude, longitude': function (latitude, longitude) { this.setData({ _geo: hasGeo(latitude, longitude) }); },
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
    onAddress() {
      const d = this.data;
      openAddress().openAddress({ latitude: d.latitude, longitude: d.longitude, name: d.name, address: d.address });
    },
    onCoverError() { this.setData({ _coverFailed: true }); },
    onLogoError() { this.setData({ _logoFailed: true }); },
  },
});
