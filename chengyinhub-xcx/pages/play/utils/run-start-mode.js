// CG-ALL-11 开局方式(业主 2026-11-17 批准;后端 #866 RunStartMode / IShopDayProofService 为准):
//   · /api/play/nodes 下发 altStations([{ id, name, longitude, latitude }],路线替代起点)与 runStartMode(DESK / ALT / null)
//   · 本局第一次 /api/play/arrive 可带 startMode=DESK(桌面完成:不定位开局,整局奖励只发探索值与徽章)
//     或 startMode=ALT + altStationId(在替代站 300 米内开局,半径 / 精度 / 守卫照旧,服务端判)
//   · 只用于城市定向(mode 1)且本局还没开局;自由探索、预览不出
// index.js 的 UI 门禁基线按行号登记,这里放方法,index.js 只在原行上挂钩(不增删行)。
const cyModal = require('../../../utils/modal.js');

const DESK = 'DESK';
const ALT = 'ALT';
const DESK_LABEL = '桌面完成';
const DESK_NOTE = '不用到现场，这一局只得探索值与徽章。';

/** /nodes 的 altStations:只收有 id 与名字的行,坐标原样留给服务端判 */
function altStationsOf(raw) {
  return (Array.isArray(raw) ? raw : [])
    .filter((row) => row && row.id != null && String(row.id) !== '' && typeof row.name === 'string' && row.name.trim())
    .map((row) => ({ id: String(row.id), name: row.name.trim() }));
}

/** 服务端 runStartMode 只认 DESK / ALT,其它一律当正常开局(与 RunStartMode.normalize 同口径) */
function normalizeRunStartMode(raw) {
  const mode = typeof raw === 'string' ? raw.trim().toUpperCase() : '';
  return mode === DESK || mode === ALT ? mode : '';
}

/** 选项表:每个替代站一项,最后是桌面完成 */
function startChoices(altStations) {
  return altStationsOf(altStations).map((s) => ({ startMode: ALT, altStationId: s.id, label: '改在「' + s.name + '」开局' }))
    .concat([{ startMode: DESK, label: DESK_LABEL }]);
}

/** arrive 请求附带的参数:只在选了开局方式时才带(没带 = 原请求与指纹逐字不变) */
function arriveParams(choice) {
  if (!choice || (choice.startMode !== DESK && choice.startMode !== ALT)) return {};
  return choice.startMode === ALT ? { startMode: ALT, altStationId: choice.altStationId } : { startMode: DESK };
}

function methods() {
  // 写成 const 对象再返回:台账 / 处理器门禁只扫模块里 `const X = {` 的键
  const api = {
    /** /nodes 回包:记下替代起点与服务端记的开局方式 */
    _noteRunStart(d) {
      const data = d || {};
      this._altStations = altStationsOf(data.altStations);
      this._runStartMode = normalizeRunStartMode(data.runStartMode);
    },
    /** 能不能给「换个方式开局」:城市定向、本局还没开局、不是预览 */
    _startModesAvailable() {
      return Number(this.data.mode) === 1 && !this.data.isPreview && !this.data.isMock && !this._runStartMode
        && typeof this._startLocationNeeded === 'function' && this._startLocationNeeded();
    },
    /** 这一局玩家选了的开局方式(换局作废);服务端已记 DESK 的局按桌面完成走 */
    _chosenStartMode() {
      if (this._runStartMode === DESK) return { startMode: DESK };
      const choice = this._startChoice;
      if (!choice || choice.sessionKey !== this._sessionKey() || !this._startModesAvailable()) return null;
      return choice;
    },
    _deskStart() {
      const choice = this._chosenStartMode();
      return !!choice && choice.startMode === DESK;
    },
    _startModeParams() { return arriveParams(this._chosenStartMode()); },
    /** 开局被拦(太远 / 没定位):弹「换个方式开局」。返回 true = 已弹 */
    _offerStartModes() {
      if (!this._startModesAvailable()) return false;
      this._startModeChoices = startChoices(this._altStations);
      this.setData({ startModeSheetShow: true, startModeSheetItems: this._startModeChoices.map((c) => c.label) });
      return true;
    },
    onStartModeCancel() { this._startModeChoices = null; this.setData({ startModeSheetShow: false }); },
    onStartModeSelect(e) {
      const picked = (this._startModeChoices || [])[Number(e && e.detail && e.detail.index)];
      this._startModeChoices = null;
      this.setData({ startModeSheetShow: false });
      if (!picked || !this._startModesAvailable()) return;
      const sessionKey = this._sessionKey();
      const go = () => {
        if (sessionKey !== this._sessionKey()) return;
        this._startChoice = Object.assign({ sessionKey }, arriveParams(picked));
        this.onArriveTap();
      };
      if (picked.startMode !== DESK) { go(); return; }
      cyModal.show({ title: DESK_LABEL, content: DESK_NOTE, confirmText: '开始', cancelText: '再想想',
        success: (r) => { if (r && r.confirm) go(); } });
    },
  };
  return api;
}

const data = { startModeSheetShow: false, startModeSheetItems: [] };

module.exports = { DESK, ALT, data, methods, altStationsOf, normalizeRunStartMode, startChoices, arriveParams };
