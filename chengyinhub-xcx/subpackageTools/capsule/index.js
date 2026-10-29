const { page, storage, uuid } = require('../cloud-ten-runtime');
const { createCapsuleClient } = require('../utils/features/capsule-client');
const modal = require('../../utils/modal');
const cyToast = require('../../utils/toast.js');
const DAY = 86400000;
// 上海日期(开启日按上海时间);卡片只露日期与倒数天数,开启前不碰正文
function ymd(ms) { const d = new Date(Number(ms) + 8 * 3600000); return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0') + '-' + String(d.getUTCDate()).padStart(2, '0'); }
function decorate(item) { if (!item) return item; const out = Object.assign({}, item); if (item.createdAtMs) out.createdText = ymd(item.createdAtMs); if (item.unlockAtMs) { out.unlockText = ymd(item.unlockAtMs); out.daysLeft = Math.max(0, Math.ceil((Number(item.unlockAtMs) - Date.now()) / DAY)); } return out; }
function unlockOptions() { const now = Date.now(); return [['1 个月后', 30], ['半年后', 182], ['1 年后', 365]].map(([label, days]) => { const date = ymd(now + days * DAY); return { label, date, short: date.slice(5) }; }); }
function listPatch(value) { return { items: value.items.map(decorate), hasMore: value.hasMore, cursor: value.nextBeforeId }; }
Page(page({
  data: {"body": "", "unlockDate": "", "photoUrl": "", "pendingCreate": false, "composing": false, "unlockCustom": false, "unlockOptions": [], "minDate": ""},
  client: createCapsuleClient,
  disposeOnHide: true,
  show(query) { const options = unlockOptions(); this.setData({ pendingCreate: !!storage.get('cloud-ui:capsule:' + getApp().getUserID()), unlockOptions: options, minDate: ymd(Date.now() + DAY), unlockDate: this.data.unlockDate || options[2].date }); this.refresh(); },
  methods: {
    choosePhoto() { const owner = this._identity; const sequence = this._sequence; getApp().chooseImage(urls => { if (this._active && owner === require('../cloud-ten-runtime').identity() && sequence === this._sequence) this.setData({ photoUrl: urls[0] || '' }); }, 1, { skipCrop: true }); },
    clearPhoto() { this.setData({ photoUrl: '' }); },
    refresh() { return this.run(() => this.client.list(), listPatch); },
    next() { const cursor = this._cursor; return this.run(() => this.client.list(cursor), listPatch); },
    create() { if (this.data.pendingCreate) return this.recover(); const client = this.client; const owner = this._identity; return this.run(() => { const key = 'cloud-ui:capsule:' + getApp().getUserID(); const requestId = storage.get(key) || uuid(); storage.set(key, requestId); if (storage.get(key) !== requestId) throw new Error('操作失败，请重试'); this.setData({ pendingCreate: true }); return client.create({ requestId, body: this.data.body, photoUrl: this.data.photoUrl, unlockDate: this.data.unlockDate }).catch(error => { if (this.client !== client || this._identity !== owner || owner !== require('../cloud-ten-runtime').identity()) throw error; if (error.code === 'REJECTED' && !client.pendingRequestId()) { storage.set(key, null); this.setData({ pendingCreate: false }); } else this.setData({ body: '', photoUrl: '' }); throw error; }); }, value => { storage.set('cloud-ui:capsule:' + getApp().getUserID(), null); setTimeout(() => this.refresh(), 0); return { result: null, body: '', photoUrl: '', pendingCreate: false, composing: false }; }, true); },
    recover() { const requestId = storage.get('cloud-ui:capsule:' + getApp().getUserID()); return this.run(() => this.client.receipt(requestId), value => { storage.set('cloud-ui:capsule:' + getApp().getUserID(), null); return { result: value, body: '', photoUrl: '', pendingCreate: false }; }); },
    retryCreate() { return this.run(() => this.client.retry(), value => { storage.set('cloud-ui:capsule:' + getApp().getUserID(), null); return { result: value, body: '', photoUrl: '', pendingCreate: false }; }, true); },
    detail(event) { const id = event.currentTarget.dataset.id; return this.run(() => this.client.detail(id), value => ({ result: decorate(value) })); },
    toggleCompose() { if (this.data.busy) return; const options = unlockOptions(); this.setData({ composing: !this.data.composing, unlockOptions: options, unlockDate: this.data.unlockDate || options[2].date }); },
    pickUnlock(event) { if (!this.data.busy) this.setData({ unlockDate: event.currentTarget.dataset.date, unlockCustom: false }); },
    pickCustom(event) { if (!this.data.busy) this.setData({ unlockDate: event.detail.value, unlockCustom: true }); },
    lockedTip() { cyToast('尚未到开启时间'); },
    closeDetail() { this.setData({ result: null }); this.refresh(); },
    askRemove() { const owner = this._identity; modal.show({ title: '删除这枚胶囊？', content: '删除后无法恢复。', confirmText: '删除', danger: true, success: res => { if (res.confirm && owner === this._identity) this.remove(); } }); },
    remove() { const id = this.data.result && this.data.result.id; return this.run(() => this.client.remove(id), value => ({ result: value }), true); }
  }
}));
