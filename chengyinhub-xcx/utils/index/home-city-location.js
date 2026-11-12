// 首页城市定位:失败提示文案与手动选择城市候选(定位仅前台按需,手动选择不上传位置)
const CITY_OPTIONS = ['北京市', '上海市', '广州市', '深圳市', '杭州市', '成都市', '重庆市', '武汉市', '西安市', '南京市',
  '苏州市', '天津市', '长沙市', '郑州市', '青岛市', '厦门市', '昆明市', '沈阳市', '济南市', '合肥市'];

const MESSAGES = {
  consent: '定位同意状态获取失败，请稍后重试',
  device: '获取位置失败，请检查手机定位是否开启',
  network: '城市识别请求失败，请检查网络',
  empty: '未识别到所在城市，可手动选择城市',
  generic: '暂时无法定位城市，可继续浏览'
};

function message(kind) { return MESSAGES[kind] || MESSAGES.generic; }

// V-01:记住上次定位/手动选择的城市，自动定位失败时沿用。
const LAST_CITY_KEY = 'home_city_last';
function readLastCity() {
  try { const v = wx.getStorageSync(LAST_CITY_KEY); return typeof v === 'string' ? v.trim() : ''; } catch (e) { return ''; }
}
function saveLastCity(city) {
  try { if (city) wx.setStorageSync(LAST_CITY_KEY, String(city).trim()); } catch (e) { /* 存储失败不影响展示 */ }
}

/**
 * 业主 2026-11-11 D-72：没有历史定位时不再把上海当默认城市。
 * 兜底顺序 = 玩家所在城市（上次成功定位/手动选择的城市）→ 都没有就不给城市，
 * 由首页露出城市选择 + 提示开定位（定位失败的提示沿用既有文案）。
 * 保留 DEFAULT_CITY 这个导出名只为兼容旧引用，值改成空串：调用方必须自己处理「没有城市」的态。
 */
const DEFAULT_CITY = '';
/** 没有历史定位时的兜底城市：只有「玩家自己的城市」，没有就返回空串（不是上海）。 */
function fallbackCity() { return readLastCity(); }

// Round5 #7:点过「暂不定位」就记在本机,之后启动不再自动弹;只有用户点城市才再问。
const DECLINED_KEY = 'home_city_location_declined';
function readLocationDeclined() {
  try { return !!wx.getStorageSync(DECLINED_KEY); } catch (e) { return false; }
}
function saveLocationDeclined(declined) {
  try { if (declined) wx.setStorageSync(DECLINED_KEY, 1); else wx.removeStorageSync(DECLINED_KEY); } catch (e) { /* 存储失败只会多问一次 */ }
}

module.exports = { CITY_OPTIONS, DEFAULT_CITY, fallbackCity, message, readLastCity, saveLastCity, readLocationDeclined, saveLocationDeclined };
