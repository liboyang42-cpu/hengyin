// 发布广场模板摘要的展示归一化。
// 后端历史数据同时存在数字分钟与已带「分钟」单位的字符串，展示层只应补一次单位。
function formatDurationMinutes(value) {
  if (value === null || value === undefined) return '';
  var text = String(value).trim();
  if (!text) return '';
  text = text.replace(/\s*(分钟|min(?:ute)?s?)$/i, '');
  return text ? text + '分钟' : '';
}

module.exports = {
  formatDurationMinutes: formatDurationMinutes,
  gameTemplateCover: gameTemplateCover
};

// 只补缺失封面，不替换作者上传的图；本地展示值不写回服务器。
function gameTemplateCover(template) {
  var covers = {
    '闭眼味觉师': 'blind-taste', '沉默点单': 'silent-order',
    '便利店调酒师': 'diy-drink', '治愈音乐角': 'music-corner',
    '午夜电台': 'midnight-radio', '低碳行动': 'low-carbon', '今日城市签': 'daily-city-sign'
  };
  var source = template || {};
  var name = covers[source.title];
  // 采用后改名仍按原玩法补图；配置不能读取时保持其它页面既有缺图态。
  if (!name && source.advancedConfigJson) {
    try {
      var config = typeof source.advancedConfigJson === 'string' ? JSON.parse(source.advancedConfigJson) : source.advancedConfigJson;
      var mechanics = { blindTaste: 'blind-taste', silentOrder: 'silent-order', diyName: 'diy-drink',
        musicCorner: 'music-corner', timeWindow: 'midnight-radio', steps: 'low-carbon', dailySign: 'daily-city-sign' };
      var section = Object.keys(mechanics).find(function (key) { return config && config[key] && config[key].enabled === true; });
      name = mechanics[section];
    } catch (e) { /* 只影响缺图展示，不修造玩法数据。 */ }
  }
  return source.imgUrl || (name ? '/images/interaction-templates/' + name + '.svg' : '');
}
