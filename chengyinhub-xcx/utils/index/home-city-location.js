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

module.exports = { CITY_OPTIONS, message };
