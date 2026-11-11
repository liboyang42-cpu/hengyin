// T-37 户外游戏紧急求助:游玩工具菜单里一行「紧急求助」→ cy-option-sheet 动作菜单:拨 110 / 拨 120 / 复制我的位置。
// 只在玩家点了之后取一次位置(前台、按需);已有本局定位就直接用,不另起持续定位。
// 拨号走 wx.makePhoneCall(系统拨号盘,玩家再确认一次);位置复制成一段文字,可贴给任何人。
const cyToast = require('../../../utils/toast.js');
const SOS_ITEMS = ['拨打 110', '拨打 120', '复制我的位置'];
const SOS_NUMBERS = ['110', '120'];

function coord(value) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.round(n * 1e6) / 1e6 : null;
}

/** 位置文字:中文坐标 + 高德标点链接(gcj02,与 wx.getLocation 同坐标系) */
function locationText(loc) {
  const lat = coord(loc && loc.latitude);
  const lng = coord(loc && loc.longitude);
  if (lat === null || lng === null || (lat === 0 && lng === 0)) return '';
  return '我需要帮助，我的位置：纬度 ' + lat + '，经度 ' + lng
    + ' https://uri.amap.com/marker?position=' + lng + ',' + lat;
}

function methods(wxApi, toastFn) {
  const api = () => wxApi || wx;
  const toast = (text) => (toastFn || cyToast)(text);
  const api_ = {
    openSos() {
      if (this.data.sessionToolsOpen && typeof this.toggleSessionTools === 'function') this.toggleSessionTools();
      this.setData({ sosOpen: true });
    },
    closeSos() { this.setData({ sosOpen: false }); },
    onSosSelect(e) {
      const index = Number(e && e.detail && e.detail.index);
      this.setData({ sosOpen: false });
      if (index === 0 || index === 1) this.sosCall(SOS_NUMBERS[index]);
      else if (index === 2) this.sosShareLocation();
    },
    sosCall(number) {
      if (SOS_NUMBERS.indexOf(number) < 0) return;
      api().makePhoneCall({ phoneNumber: number, fail() {} });
    },
    sosShareLocation() {
      const copy = (loc) => {
        const text = locationText(loc);
        if (!text) { toast('没有取到位置'); return; }
        api().setClipboardData({ data: text, fail() {} });
      };
      if (this._loc && locationText(this._loc)) { copy(this._loc); return; }
      api().getLocation({
        type: 'gcj02',
        success: copy,
        fail: () => toast('没有取到位置'),
      });
    },
  };
  return api_;
}

const data = { sosOpen: false, sosItems: SOS_ITEMS };

module.exports = { methods, data, locationText, SOS_ITEMS, SOS_NUMBERS };
