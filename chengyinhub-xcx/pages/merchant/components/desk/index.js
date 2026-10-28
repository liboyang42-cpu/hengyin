const { createVerificationWorkflow } = require('../../../../utils/verification-readback');
const contract = require('./contract');
const { chinaDayStart, chinaDateKey, toTimestamp } = require('../../../../utils/datetime');
const app = getApp();
function sendDeskRequest(endpoint, options) {
  switch (endpoint) {
    case 'stores': return app.sendRequest({ ...options, url: '/api/merchant/desk/stores' });
    case 'anchor': return app.sendRequest({ ...options, url: '/api/merchant/desk/anchor' });
    case 'anchor/save': return app.sendRequest({ ...options, url: '/api/merchant/desk/anchor/save' });
    case 'redeem/resolve': return app.sendRequest({ ...options, url: '/api/merchant/desk/redeem/resolve' });
    case 'redeem/ocr-candidates': return app.sendRequest({ ...options, url: '/api/merchant/desk/redeem/ocr-candidates' });
    case 'products': return app.sendRequest({ ...options, url: '/api/merchant/desk/products' });
    case 'products/detail': return app.sendRequest({ ...options, url: '/api/merchant/desk/products/detail' });
    case 'products/save': return app.sendRequest({ ...options, url: '/api/merchant/desk/products/save' });
    case 'products/delist': return app.sendRequest({ ...options, url: '/api/merchant/desk/products/delist' });
    case 'products/sku/save': return app.sendRequest({ ...options, url: '/api/merchant/desk/products/sku/save' });
    case 'products/sku/slots/page': return app.sendRequest({ ...options, url: '/api/merchant/desk/products/sku/slots/page' });
    case 'f3Reservations': return app.sendRequest({ ...options, url: '/api/free-explore/bundle/merchant/reservations' });
    case 'arrivals/today': return app.sendRequest({ ...options, url: '/api/merchant/desk/arrivals/today' });
    case 'reconciliation': return app.sendRequest({ ...options, url: '/api/merchant/desk/reconciliation' });
    case 'frozen/page': return app.sendRequest({ ...options, url: '/api/merchant/desk/frozen/page' });
    case 'metrics/dictionary': return app.sendRequest({ ...options, url: '/api/merchant/desk/metrics/dictionary' });
    case 'reconciliation/claims': return app.sendRequest({ ...options, url: '/api/merchant/desk/reconciliation/claims' });
    case 'verdicts': return app.sendRequest({ ...options, url: '/api/merchant/desk/verdicts' });
    case 'verdicts/detail': return app.sendRequest({ ...options, url: '/api/merchant/desk/verdicts/detail' });
    case 'verdicts/evidence': return app.sendRequest({ ...options, url: '/api/merchant/desk/verdicts/evidence' });
    case 'receipt': return app.sendRequest({ ...options, url: '/api/merchant/desk/receipt' });
    default: throw Error('未知商家工作台接口');
  }
}
const exactId = contract.id;
const blank = () => ({ products: [], detail: null, slots: [], slotSkuId: '', slotFromDate: '', slotMore: false, arrivals: null, finance: null,
  frozen: [], frozenMore: false, claims: [], reviews: [], review: null, metrics: [], product: null, delistConfirm: false, sku: null, note: '',
  reservationDate: '', reservations: [], stores: [], anchor: null, anchorDraft: null, wifiConfirmed: false, wifiText: '',
  codeText: '', ocrText: '', candidates: [], candidate: null, redeemState: '',
  readStates: {}, readErrors: {}, lastReceipt: null, pending: false, busy: false, requestId: '', notice: '', error: '', route: '', claimPage: 1,
  productsOn: false });
// D2:商品与履约段受 features.merchantDeskProducts 控制,默认关闭(商城暂停)。
const productsOn = () => ((app.globalData && app.globalData.features) || {}).merchantDeskProducts === true;
const rejected = new Set(['F4_DISABLED', 'F4_BAD_REQUEST', 'F4_NOT_OWNER', 'F4_VERSION_CONFLICT',
  'F4_PRODUCT_LIVE_LOCKED', 'F4_REVIEW_CLOSED', 'F4_EVIDENCE_LIMIT', 'F4_STORE_NOT_ACTIVE', 'F4_ANCHOR_TOO_FAR']);
function receipt(value, requestId, action) {
  return value && value.state === 'APPLIED' && value.requestId === requestId
    && (!action || value.action === action) && exactId(value.targetId)
    && contract.targetReceipt(value, requestId);
}
Component({
  properties: { access: { type: Object, value: null }, redemptionReady: Boolean, redemptionResult: Object },
  data: blank(),
  observers: { 'access.**'() { if (this._attached) this.activate(); }, redemptionResult(value) { if (this._attached) this.acceptRedemptionResult(value); } },
  lifetimes: {
    attached() { this._attached = true; this.activate(); },
    detached() { this.clearReads(); this._attached = false; if (this._workflow) this._workflow.destroy(); this._epoch = (this._epoch || 0) + 1; },
  },
  pageLifetimes: { show() { if (this._attached) this.activate(); } },
  methods: {
    identity() {
      const access = this.properties.access;
      const user = exactId(app.getUserID()), merchant = exactId(access && access.merchant && access.merchant.id);
      return access && access.active === true && user && merchant && app.getAuthorization()
        ? user + ':' + merchant : '';
    },
    current(epoch) { return this._attached && epoch === this._epoch && this._owner === this.identity()
      && this._token === app.getAuthorization() && this._accessSignature === JSON.stringify(this.properties.access); },
    activate() {
      const owner = this.identity(), token = app.getAuthorization(), signature = JSON.stringify(this.properties.access);
      if (owner === this._owner && token === this._token && signature === this._accessSignature && this._workflow) return;
      if (this._workflow) this._workflow.destroy();
      this.clearReads();
      this._epoch = (this._epoch || 0) + 1; this._owner = owner; this._token = token; this._accessSignature = signature; this._reads = {}; this._retryReads = {};
      this._frozenCursor = null; this._slotCursor = null; this._inputMode = ''; this._intentId = '';
      this.setData({ products: [], detail: null, slots: [], slotSkuId: '', slotFromDate: '', slotMore: false, arrivals: null, finance: null,
        frozen: [], frozenMore: false, claims: [], reviews: [], review: null, metrics: [], product: null, delistConfirm: false, sku: null, note: '',
        reservationDate: '', reservations: [], stores: [], anchor: null, anchorDraft: null, wifiConfirmed: false, wifiText: '',
        codeText: '', ocrText: '', candidates: [], candidate: null, redeemState: '',
        readStates: {}, readErrors: {}, lastReceipt: null, pending: false, busy: false, requestId: '', notice: '', error: '', route: '', claimPage: 1,
        productsOn: productsOn() });
      if (!owner) { this.setData({ error: '商家身份尚未确认' }); return; }
      this._key = owner + ':merchant-desk-maintenance';
      this._workflow = createVerificationWorkflow({ storage: wx, getOwner: () => this._owner, requestIdentity: true });
      try {
        const pending = this._workflow.pendingRequest(this._key);
        if (pending) this.setData({ pending: true, requestId: pending.requestId || '', notice: '原操作结果待确认，请读取原请求结果' });
      } catch (e) { this.setData({ pending: true, error: '无法读取原操作，请保留现场并检查存储' }); }
    },
    clearReads() {
      Object.values(this._readTimers || {}).forEach(clearTimeout); this._readTimers = {};
    },
    read(endpoint, data, field, decode, preserve = false) {
      const epoch = this._epoch;
      if (!this.current(epoch)) { this.activate(); return; }
      const generation = (this._reads[field] || 0) + 1; this._reads[field] = generation;
      if (this._readTimers[field]) clearTimeout(this._readTimers[field]);
      this._retryReads = this._retryReads || {};
      this._retryReads[field] = () => this.read(endpoint, data, field, decode, preserve);
      const state = (status, message = '') => this.setData({
        readStates: Object.assign({}, this.data.readStates, { [field]: status }),
        readErrors: Object.assign({}, this.data.readErrors, { [field]: message }),
      });
      if (!preserve && !['notice', 'lastReceipt'].includes(field) && Object.prototype.hasOwnProperty.call(blank(), field)) this.setReadField(field, blank()[field]);
      state('LOADING');
      const valid = () => this.current(epoch) && this._reads[field] === generation;
      let settled = false;
      const fail = res => {
        if (!valid() || settled) return; settled = true; clearTimeout(this._readTimers[field]);
        const message = res && res.msg || '读取失败，请重试'; state('ERROR', message);
        this.setData({ error: message });
      };
      this._readTimers[field] = setTimeout(() => fail({ msg: '读取超时，请重试' }), 15000);
      try {
        return sendDeskRequest(endpoint, {
          method: endpoint === 'f3Reservations' ? 'GET' : 'POST', data,
          success: res => {
            if (!valid() || settled) return;
            if (!res || String(res.code) !== '200') { fail(res); return; }
            try {
              const result = decode ? decode(res.data) : res.data;
              if (result === undefined || result === null) throw Error();
              settled = true; clearTimeout(this._readTimers[field]);
              this.setReadField(field, result);
              this.setData({ error: '' });
              state(Array.isArray(result) && !result.length ? 'EMPTY' : 'READY');
            } catch (e) { fail({ msg: '返回结果无法确认，请刷新' }); }
          }, fail, successStatusAbnormal: fail });
      } catch (e) { fail({ msg: '请求未能发出，请重试' }); }
    },
    setReadField(field, value) {
      switch (field) {
        case 'products': this.setData({ products: value }); return;
        case 'detail': this.setData({ detail: value }); return;
        case 'slots': this.setData({ slots: value }); return;
        case 'arrivals': this.setData({ arrivals: value }); return;
        case 'finance': this.setData({ finance: value }); return;
        case 'frozen': this.setData({ frozen: value }); return;
        case 'claims': this.setData({ claims: value }); return;
        case 'reviews': this.setData({ reviews: value }); return;
        case 'review': this.setData({ review: value }); return;
        case 'metrics': this.setData({ metrics: value }); return;
        case 'reservations': this.setData({ reservations: value }); return;
        case 'stores': this.setData({ stores: value }); return;
        case 'anchor': this.setData({ anchor: value }); return;
        case 'candidate': this.setData({ candidate: value }); return;
        case 'candidates': this.setData({ candidates: value }); return;
        case 'notice': this.setData({ notice: value }); return;
        case 'lastReceipt': this.setData({ lastReceipt: value }); return;
        default: throw Error('未知读取目标');
      }
    },
    retryRead(e) {
      if (!this.current(this._epoch)) { this.activate(); return; }
      const field = e.currentTarget.dataset.field;
      if (this._retryReads && this._retryReads[field]) this._retryReads[field]();
    },
    loadStores() { this.read('stores', {}, 'stores', rows => {
      if (!Array.isArray(rows) || rows.some(row => !exactId(row.storeId))) throw Error(); return rows;
    }); },
    openExisting(e) {
      if (!this.current(this._epoch)) { this.activate(); return; }
      const routes = { decor: ['canWriteProfile', '/pages/merchant/decor/index'],
        reviews: ['canReadMarketing', '/pages/merchant/reviews/index?mode=manage'],
        supply: ['canReadMarketing', '/pages/merchant/marketing/index'],
        ledger: ['canReadFinance', '/pages/merchant/ledger/index'] };
      const route = routes[e.currentTarget.dataset.page];
      if (!route || !this.properties.access[route[0]]) return;
      wx.navigateTo({ url: route[1], fail: () => this.setData({ error: '入口打开失败，请重试' }) });
    },
    loadAnchor() { this.read('anchor', {}, 'anchor', row => {
      if (!row || typeof row.configured !== 'boolean' || typeof row.enforced !== 'boolean') throw Error();
      return row;
    }); },
    editAnchor() {
      if (this.data.pending || this.data.busy || !this.data.anchor) return;
      const row = this.data.anchor;
      const store = this.data.stores.find(item => exactId(item.storeId) === exactId(this.properties.access.merchant.id));
      if (!row.configured && !store) { this.setData({ error: '请先读取门店位置' }); return; }
      this.setData({ anchorDraft: { anchorLat: row.configured ? row.anchorLat : store.locationLat,
        anchorLng: row.configured ? row.anchorLng : store.locationLng, radiusM: row.configured ? row.radiusM : 100,
        expectedVersion: row.configured ? row.version : undefined }, wifiText: '', wifiConfirmed: false });
    },
    useStoreAnchor() {
      if (this.data.pending || this.data.busy || !this.data.anchorDraft) return;
      const store = this.data.stores.find(item => exactId(item.storeId) === exactId(this.properties.access.merchant.id));
      if (!store) { this.setData({ error: '请先读取当前门店位置' }); return; }
      this.setData({ anchorDraft: Object.assign({}, this.data.anchorDraft, { anchorLat: store.locationLat, anchorLng: store.locationLng }) });
    },
    anchorInput(e) {
      if (this.data.pending || this.data.busy || !this.data.anchorDraft) return;
      const field = e.currentTarget.dataset.field;
      if (field === 'radiusM') this.setData({ anchorDraft: Object.assign({}, this.data.anchorDraft, { radiusM: e.detail.value }) });
      if (field === 'wifiText') this.setData({ wifiText: e.detail.value });
      if (field === 'wifiConfirmed') this.setData({ wifiConfirmed: e.detail.value === true });
    },
    saveAnchor() {
      const draft = this.data.anchorDraft;
      if (!draft || !this.data.wifiConfirmed) { this.setData({ error: '请确认本次完整WiFi配置，空列表将清除原配置' }); return; }
      if (draft.anchorLat == null || draft.anchorLng == null || !Number.isFinite(Number(draft.anchorLat))
          || !Number.isFinite(Number(draft.anchorLng)) || !/^\d+$/.test(String(draft.radiusM))
          || Number(draft.radiusM) < 30 || Number(draft.radiusM) > 500) {
        this.setData({ error: '门店位置不可用或半径不在30至500米范围' }); return;
      }
      const wifiBssids = this.data.wifiText.trim() ? this.data.wifiText.trim().split(/[\s,;]+/) : [];
      if (wifiBssids.length > 8 || wifiBssids.some(value => !/^[0-9a-f]{2}([:-][0-9a-f]{2}){5}$/i.test(value))) {
        this.setData({ error: '请填写最多8个有效WiFi BSSID' }); return;
      }
      this.write('anchor/save', Object.assign({}, draft, { wifiBssids }), 'ANCHOR_SAVE');
    },
    codeInput(e) {
      if (this.data.redeemState) return;
      const field = e.currentTarget.dataset.field;
      if (!['codeText', 'ocrText'].includes(field)) return;
      this._candidateIntent = (this._candidateIntent || 0) + 1;
      this._reads.candidate = (this._reads.candidate || 0) + 1;
      this._reads.candidates = (this._reads.candidates || 0) + 1;
      if (field === 'codeText') this.setData({ codeText: e.detail.value, candidate: null, candidates: [] });
      else this.setData({ ocrText: e.detail.value, candidate: null, candidates: [] });
    },
    scanCandidate() {
      if (this.data.redeemState || !this.current(this._epoch)) return;
      const epoch = this._epoch, intent = this._candidateIntent = (this._candidateIntent || 0) + 1;
      wx.scanCode({ onlyFromCamera: true, success: result => { if (this.current(epoch) && intent === this._candidateIntent && !this.data.redeemState) this.resolveCandidate(result.result, 'SCAN'); },
        fail: () => { if (this.current(epoch)) this.setData({ error: '未取得扫码结果，可重试或手动输入' }); } });
    },
    manualCandidate() { this.resolveCandidate(this.data.codeText, 'MANUAL'); },
    resolveCandidate(code, mode) {
      if (this.data.redeemState || !code || code.length > 512) return;
      this._candidateIntent = (this._candidateIntent || 0) + 1;
      this._inputMode = mode;
      this.setData({ candidate: null });
      this.read('redeem/resolve', { code, mode }, 'candidate', contract.candidate);
    },
    extractCandidates() {
      if (this.data.redeemState || !this.data.ocrText.trim()) return;
      this._candidateIntent = (this._candidateIntent || 0) + 1;
      this._reads.candidate = (this._reads.candidate || 0) + 1;
      this._inputMode = 'OCR';
      this.setData({ candidate: null, candidates: [] });
      this.read('redeem/ocr-candidates', { text: this.data.ocrText }, 'candidates', row => {
        if (!row || !Array.isArray(row.candidates) || row.candidates.length > 5) throw Error();
        return row.candidates.map((item, index) => {
          if (!item.code || item.code.length > 512 || !contract.candidate(item).accepted) throw Error();
          return { code: item.code, masked: String(item.masked || '候选码'), index };
        });
      });
    },
    chooseCandidate(e) {
      if (this.data.redeemState) return;
      const row = this.data.candidates[Number(e.currentTarget.dataset.index)];
      if (row) this.resolveCandidate(row.code, 'OCR');
    },
    candidateType(e) {
      if (this.data.redeemState || !this.data.candidate || !this.data.candidate.accepted) return;
      const value = e.detail.value;
      if (!['activity', 'topic'].includes(value) || !this.data.candidate.fields.length) return;
      this.setData({ candidate: Object.assign({}, this.data.candidate, { values: Object.assign({}, this.data.candidate.values, { type: value }) }) });
    },
    confirmCandidate() {
      if (!this.current(this._epoch) || !this.properties.redemptionReady || this.data.redeemState) return;
      const row = this.data.candidate;
      if (!row || !row.accepted || row.fields.some(field => !row.values[field.name])) {
        this.setData({ error: '请明确选择票的类型后再确认' }); return;
      }
      this._intentSequence = (this._intentSequence || 0) + 1;
      const intentId = 'desk_' + Date.now().toString(36) + '_' + this._intentSequence;
      this._intentId = intentId;
      this.setData({ redeemState: 'HANDED_OFF' });
      this.triggerEvent('redeemrequest', { intentId, inputMode: this._inputMode, endpoint: row.endpoint,
        payload: Object.assign({}, row.values) });
    },
    acceptRedemptionResult(result) {
      if (!this.current(this._epoch) || !result || result.intentId !== this._intentId || !this._intentId) return;
      if (!['UNKNOWN', 'APPLIED', 'REJECTED'].includes(result.state)) return;
      this.setData({ redeemState: result.state, notice: result.state === 'UNKNOWN' ? '核销结果待确认，请使用原核销请求回读' :
        result.state === 'APPLIED' ? '原核销入口已确认结果' : '原核销入口已拒绝本次请求' });
    },
    nextCandidate() {
      if (!['APPLIED', 'REJECTED'].includes(this.data.redeemState)) return;
      this._intentId = '';
      this.setData({ redeemState: '', candidate: null, candidates: [], codeText: '', ocrText: '' });
    },
    loadProducts() { this.read('products', {}, 'products', rows => {
      if (!Array.isArray(rows) || rows.some(row => !exactId(row.productId))) throw Error();
      return rows.map(row => Object.assign({}, row, { productId: exactId(row.productId) }));
    }); },
    selectProduct(e) {
      if (this.data.pending || this.data.busy) return;
      const id = exactId(e.currentTarget.dataset.id); if (!id) return;
      this._reads.slots = (this._reads.slots || 0) + 1;
      this._slotCursor = null;
      this.setData({ detail: null, product: null, delistConfirm: false, sku: null, slots: [], slotSkuId: '', slotMore: false });
      this.read('products/detail', { productId: id }, 'detail', row => {
        if (!row || exactId(row.productId) !== id || !Array.isArray(row.skus) || row.skus.some(s => !exactId(s.skuId))) throw Error();
        return row;
      });
    },
    newProduct() {
      if (this.data.pending || this.data.busy) return;
      this.setData({ product: { productName: '', price: '', stock: 0, unit: '', albumPics: [] } });
    },
    editProduct() {
      if (this.data.pending || this.data.busy || !this.data.detail || !this.data.detail.editable) return;
      const row = this.data.detail;
      this.setData({ product: { productId: exactId(row.productId), productName: row.productName,
        price: row.price, originalPrice: row.originalPrice, stock: row.stock, unit: row.unit || '', pic: row.pic,
        albumPics: Array.isArray(row.albumPics) ? row.albumPics.slice() : String(row.albumPics || '').split(/[,;]/).filter(Boolean) } });
    },
    productInput(e) {
      if (this.data.pending || this.data.busy || !this.data.product) return;
      const field = e.currentTarget.dataset.field;
      if (!['productName', 'price', 'stock', 'unit'].includes(field)) return;
      this.setData({ product: Object.assign({}, this.data.product, { [field]: e.detail.value }) });
    },
    saveProduct() {
      const product = this.data.product;
      if (!product || !String(product.productName).trim() || !/^\d+$/.test(String(product.price)) || !/^\d+$/.test(String(product.stock))) {
        this.setData({ error: '请填写商品名称、有效售价及库存' }); return;
      }
      this.write('products/save', product, 'PRODUCT_SAVE');
    },
    requestDelist() {
      if (!this.data.pending && !this.data.busy && this.data.detail) this.setData({ delistConfirm: true });
    },
    cancelDelist() { this.setData({ delistConfirm: false }); },
    confirmDelist() {
      if (!this.data.delistConfirm || !this.data.detail) return;
      this.write('products/delist', { productId: exactId(this.data.detail.productId) }, 'PRODUCT_DELIST');
      this.setData({ delistConfirm: false });
    },
    editSku(e) {
      if (this.data.pending || this.data.busy || !this.data.detail || !this.data.detail.editable) return;
      const id = exactId(e.currentTarget.dataset.id);
      const row = this.data.detail.skus.find(s => exactId(s.skuId) === id);
      if (!row) return;
      this.setData({ sku: Object.assign({}, row, { skuId: id, productId: exactId(this.data.detail.productId),
        bookable: row.bookable === 1 || row.bookable === true,
        slotMinutes: row.slotMinutes == null ? 60 : row.slotMinutes,
        capacityPerSlot: row.capacityPerSlot == null ? 0 : row.capacityPerSlot }) });
    },
    newSku() {
      if (this.data.pending || this.data.busy || !this.data.detail || !this.data.detail.editable) return;
      this.setData({ sku: { productId: exactId(this.data.detail.productId), skuName: '', price: '', stock: 0,
        bookable: false, slotMinutes: 60, capacityPerSlot: 0 } });
    },
    skuInput(e) {
      if (this.data.pending || this.data.busy || !this.data.sku) return;
      const field = e.currentTarget.dataset.field;
      if (!['skuName', 'price', 'stock', 'slotMinutes', 'capacityPerSlot', 'bookable'].includes(field)) return;
      this.setData({ sku: Object.assign({}, this.data.sku, { [field]: e.detail.value }) });
    },
    saveSku() {
      const sku = this.data.sku; if (!sku) return;
      if (!sku.skuName || !/^(?:0|[1-9]\d*)(?:\.\d{1,2})?$/.test(String(sku.price))
        || ['stock', 'slotMinutes', 'capacityPerSlot'].some(k => !/^\d+$/.test(String(sku[k])))) {
        this.setData({ error: '请填写规格名称、售价及有效的库存/时段容量' }); return;
      }
      this.write('products/sku/save', Object.assign({}, sku), 'SKU_SAVE');
    },
    loadSlots(e) {
      const id = exactId(e.currentTarget.dataset.id); if (!id) return;
      this._slotCursor = null;
      this.setData({ slots: [], slotSkuId: id, slotFromDate: chinaDateKey(Date.now()), slotMore: false });
      this.readSlotPage(false);
    },
    nextSlots() {
      if (!this.data.slotMore || this.data.readStates.slots === 'LOADING') return;
      this.readSlotPage(true);
    },
    readSlotPage(append) {
      const id = this.data.slotSkuId, fromDate = this.data.slotFromDate;
      const previous = append ? this.data.slots.slice() : [], cursor = append ? this._slotCursor : null;
      const query = Object.assign({ skuId: id, fromDate, days: 14 }, cursor || {});
      this.read('products/sku/slots/page', query, 'slots', page => {
        if (!page || exactId(page.skuId) !== id || page.fromDate !== fromDate || page.days !== 14
          || typeof page.hasMore !== 'boolean' || !Array.isArray(page.rows) || page.rows.length > 100) throw Error();
        const seen = new Set(previous.map(row => exactId(row.slotId)));
        const from = chinaDayStart(fromDate), to = from + 14 * 86400000;
        let lastTime = cursor ? cursor.afterStartAt : from - 1;
        let lastId = cursor ? cursor.afterSlotId : '';
        page.rows.forEach(row => {
          const rowId = exactId(row.slotId), time = toTimestamp(row.slotStart);
          if (!rowId || exactId(row.skuId) !== id || seen.has(rowId) || !Number.isFinite(time)
            || time < from || time >= to || time < lastTime
            || (time === lastTime && (rowId.length < lastId.length || (rowId.length === lastId.length && rowId <= lastId)))) throw Error();
          seen.add(rowId); lastTime = time; lastId = rowId;
        });
        let next = null;
        if (page.hasMore) {
          if (!page.rows.length || !page.next || !Number.isSafeInteger(page.next.afterStartAt)
            || page.next.afterStartAt !== lastTime || exactId(page.next.afterSlotId) !== lastId) throw Error();
          next = { afterStartAt: lastTime, afterSlotId: lastId };
        } else if (page.next != null) throw Error();
        this._slotCursor = next;
        this.setData({ slotMore: page.hasMore });
        return previous.concat(page.rows);
      }, append);
    },
    reservationDateInput(e) {
      this._reads.reservations = (this._reads.reservations || 0) + 1;
      this.setData({ reservationDate: e.detail.value, reservations: [],
        readStates: Object.assign({}, this.data.readStates, { reservations: '' }) });
    },
    loadReservations() {
      if (!this.properties.access || !this.properties.access.canVerify) return;
      const date = this.data.reservationDate || chinaDateKey(Date.now());
      const from = chinaDayStart(date), to = from + 14 * 24 * 3600000;
      if (!Number.isFinite(from)) { this.setData({ error: '预约查询日期无效' }); return; }
      this.setData({ reservationDate: date });
      this.read('f3Reservations', { from, to }, 'reservations', rows => {
        if (!Array.isArray(rows)) throw Error();
        const ids = new Set();
        return rows.map(row => {
          const id = exactId(row.id), start = toTimestamp(row.startAt);
          if (!id || ids.has(id) || !exactId(row.registrationId) || !exactId(row.unitId) || !exactId(row.chapterId)
              || !exactId(row.offerId) || !Number.isFinite(start) || start < from || start >= to
              || !['BOOKED', 'FULFILLED', 'CANCELLED', 'WAIVED', 'LATE_CANCELLED', 'NO_SHOW', 'MERCHANT_EXITED'].includes(row.status)) throw Error();
          ids.add(id);
          // Owner scope is enforced by the existing F3 endpoint. Do not compare ownerMemberId to mms_merchant.id.
          // Whitelist display facts: never retain holder identity, request fingerprint or idempotency keys in component data.
          return { id, registrationId: exactId(row.registrationId), unitId: exactId(row.unitId),
            chapterId: exactId(row.chapterId), offerId: exactId(row.offerId), startAt: row.startAt, status: row.status };
        });
      });
    },
    loadArrivals() { this.read('arrivals/today', {}, 'arrivals', contract.arrivals); },
    loadFinance() {
      this._reads.claims = (this._reads.claims || 0) + 1;
      delete this._retryReads.claims;
      this.setData({ route: '', claimPage: 1, claims: [],
        readStates: Object.assign({}, this.data.readStates, { claims: '' }),
        readErrors: Object.assign({}, this.data.readErrors, { claims: '' }) });
      this.read('reconciliation', {}, 'finance', contract.reconciliation);
      this._frozenCursor = null;
      this.setData({ frozen: [], frozenMore: false });
      this.loadFrozenPage(false);
    },
    nextFrozen() {
      if (this.data.frozenMore && this.data.readStates.frozen !== 'LOADING') this.loadFrozenPage(true);
    },
    loadFrozenPage(append) {
      const previous = append ? this.data.frozen.slice() : [], cursor = append ? this._frozenCursor : null;
      this.read('frozen/page', cursor || {}, 'frozen', page => {
        if (!page || typeof page.hasMore !== 'boolean') throw Error();
        const rows = contract.list(page.rows, 'batchId');
        if (rows.length > 24) throw Error();
        const seen = new Set(previous.map(row => row.batchId));
        let lastPeriod = cursor && cursor.afterPeriodYm, lastId = cursor && cursor.afterBatchId;
        for (const row of rows) {
          if (!/^[0-9]{4}-(0[1-9]|1[0-2])$/.test(row.periodYm || '') || seen.has(row.batchId)
            || lastPeriod && (row.periodYm > lastPeriod || row.periodYm === lastPeriod
              && (row.batchId.length > lastId.length || row.batchId.length === lastId.length && row.batchId >= lastId))) throw Error();
          seen.add(row.batchId); lastPeriod = row.periodYm; lastId = row.batchId;
        }
        let next = null;
        if (page.hasMore) {
          if (!rows.length || !page.next || page.next.afterPeriodYm !== lastPeriod || exactId(page.next.afterBatchId) !== lastId) throw Error();
          next = { afterPeriodYm: lastPeriod, afterBatchId: lastId };
        } else if (page.next != null) throw Error();
        this._frozenCursor = next;
        this.setData({ frozenMore: page.hasMore });
        return previous.concat(rows);
      }, append);
    },
    loadMetrics() { this.read('metrics/dictionary', {}, 'metrics', row => {
      if (!row || !Array.isArray(row.metrics)) throw Error(); return row.metrics;
    }); },
    loadClaims(e) {
      const route = e.currentTarget.dataset.route;
      if (!/^[A-Z_]{2,32}$/.test(route || '')) return;
      this.setData({ route, claimPage: 1, claims: [] }); this.claimPage(1);
    },
    claimPage(page) {
      const route = this.data.route;
      if (!route || !Number.isSafeInteger(page) || page < 1) return;
      this.read('reconciliation/claims', { route, pageNum: page, pageSize: 20 }, 'claims', row => {
        if (!row || row.route !== route || row.pageNum !== page || !Array.isArray(row.rows)) throw Error();
        const rows = contract.list(row.rows, 'claimId');
        if (rows.length > 20 || rows.some(item => item.route !== route)) throw Error();
        this.setData({ claimPage: page }); return rows;
      }, true);
    },
    nextClaims() { if (this.data.readStates.claims !== 'LOADING' && this.data.claims.length === 20) this.claimPage(this.data.claimPage + 1); },
    previousClaims() { if (this.data.readStates.claims !== 'LOADING' && this.data.claimPage > 1) this.claimPage(this.data.claimPage - 1); },
    loadReviews() { this.read('verdicts', {}, 'reviews', rows => contract.list(rows, 'reviewId')); },
    selectReview(e) {
      if (this.data.pending || this.data.busy) return;
      const id = exactId(e.currentTarget.dataset.id); if (!id) return;
      this.setData({ review: null, note: '' });
      this.read('verdicts/detail', { reviewId: id }, 'review', row => {
        if (!row || exactId(row.reviewId) !== id || typeof row.canSubmitEvidence !== 'boolean'
            || !['PENDING', 'CONFIRMED_NORMAL', 'CONFIRMED_ABNORMAL'].includes(row.verdict)) throw Error();
        return Object.assign({}, row, { evidence: contract.list(row.evidence, 'evidenceId') });
      });
    },
    noteInput(e) { if (!this.data.pending && !this.data.busy) this.setData({ note: e.detail.value }); },
    saveEvidence() {
      if (!this.data.review || !this.data.review.canSubmitEvidence || !this.data.note.trim()) return;
      this.write('verdicts/evidence', { reviewId: exactId(this.data.review.reviewId), note: this.data.note, media: [] }, 'REVIEW_EVIDENCE');
    },
    write(endpoint, payload, action) {
      const epoch = this._epoch;
      if (!this.current(epoch)) { this.activate(); return; }
      if (this.data.pending || this.data.busy) return;
      // Only metadata is persisted by the existing workflow; evidence and drafts remain in memory.
      const frozen = JSON.parse(JSON.stringify(payload));
      this.setData({ busy: true, error: '', notice: '' });
      this._workflow.run(this._key, (done, identity) => sendDeskRequest(endpoint, {
        method: 'POST', data: Object.assign({}, frozen, identity),
        success: res => {
          if (!this.current(epoch)) { done({ status: 'unknown' }); return; }
          if (res && String(res.code) === '200' && receipt(res.data, identity.requestId, action)) done({ status: 'success', value: res.data });
          else if (res && rejected.has(res.errorCode)) done({ status: 'failed', response: res });
          else done({ status: 'unknown' });
        }, fail: () => done({ status: 'unknown' }), successStatusAbnormal: () => done({ status: 'unknown' }) }), result => {
        if (!this.current(epoch)) return;
        const unknown = result.status !== 'success' && result.status !== 'failed' && result.status !== 'blocked';
        this.setData({ busy: false, pending: unknown, requestId: unknown ? result.requestId || '' : '',
          notice: unknown ? '结果待确认，请读取原请求结果；请勿修改或重复提交' : result.status === 'success' ? '已收到原操作结果，正在重新读取' : '',
          error: result.status === 'failed' || result.status === 'blocked' ? result.response && result.response.msg || '未提交成功' : '' });
        if (result.status === 'success') this.settleReceipt(result.value);
      });
    },
    recover() {
      if (!this.data.requestId || this.data.busy) return;
      const epoch = this._epoch, requestId = this.data.requestId;
      if (!this.current(epoch)) { this.activate(); return; }
      this.read('receipt', { requestId }, 'notice', row => {
        if (!receipt(row, requestId)) return '原请求仍待确认；未查到结果不能证明没有写入';
        if (!this._workflow.confirmRequest(this._key, requestId)) return '已查到结果，本机待办尚未清理，请再次读回';
        this.setData({ pending: false, requestId: '', sku: null, note: '' }); this.settleReceipt(row);
        return '已确认原操作，正在重新读取结果';
      });
    },
    settleReceipt(row) {
      this.setData({ lastReceipt: row });
      if (['SKU_SAVE', 'REVIEW_EVIDENCE'].includes(row.action) && !row.productId && !row.reviewId) {
        this.read('receipt', { requestId: row.requestId }, 'lastReceipt', detail => {
          if (!receipt(detail, row.requestId, row.action)) throw Error(); this.reloadResult(detail); return detail;
        });
      }
      this.reloadResult(row);
    },
    reloadResult(row) {
      if (row.action === 'REVIEW_EVIDENCE' && (row.reviewId || this.data.review)) this.selectReview({ currentTarget: { dataset: { id: row.reviewId || this.data.review.reviewId } } });
      if (['PRODUCT_SAVE', 'PRODUCT_DELIST'].includes(row.action)) this.selectProduct({ currentTarget: { dataset: { id: row.targetId } } });
      if (row.action === 'SKU_SAVE' && (row.productId || this.data.detail)) this.selectProduct({ currentTarget: { dataset: { id: row.productId || this.data.detail.productId } } });
      if (row.action === 'ANCHOR_SAVE') { this.setData({ anchorDraft: null, wifiText: '', wifiConfirmed: false }); this.loadAnchor(); }
      if (this.properties.access.canManageProjects && this.data.productsOn) this.loadProducts();
      if (this.properties.access.canReadVerifyRecords) this.loadReviews();
    },
  },
});
