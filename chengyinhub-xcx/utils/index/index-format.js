// FE-12 首页格式化纯函数。从 pages/index/index.js 抽出:日期时间展示格式化 + 主题列表日期整形。
// 无 wx / 无 this / 无副作用,可直接单测。逻辑与原 index 内联实现逐字一致。

// 日期时间展示 "YYYY年M月D日 周X HH:mm"(24 小时制)。
function formatDateTimeForDisplay(date) {
  if (!date) return '';

  // 星期几的映射
  const weekDays = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  // 月份映射
  const months = ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'];

  const weekDay = weekDays[date.getDay()];
  const year = date.getFullYear();
  const month = months[date.getMonth()];
  const day = date.getDate();

  // j1 F18:24 小时制,0 点不再读成「上午12:xx」。
  const formattedHours = date.getHours().toString().padStart(2, '0');
  const minutes = date.getMinutes().toString().padStart(2, '0');

  return `${year}年${month}${day}日 ${weekDay} ${formattedHours}:${minutes}`;
}

// 主题列表日期整形:就地补 month/day/dateTime 与 endmonth/endday/endDateTime(= 原 processTopicDate)。
// 保持原「就地修改并返回同一 list」语义;仅在字段缺失时补,不覆盖已有 dateTime/endDateTime。
function processTopicDate(list) {
  for (let i = 0; i < list.length; i++) {
    if (list[i]['startDate'] && !list[i]['dateTime']) {
      const originalStartDate = list[i]['startDate'];
      const [datePart, timePart] = originalStartDate.split(' ');

      if (datePart) {
        const [year, month, day] = datePart.split('-');
        list[i]['month'] = month;
        list[i]['day'] = day;
        let hourMinute = '00:00';
        if (timePart) {
          const timeParts = timePart.split(':');
          hourMinute = `${timeParts[0]}:${timeParts[1]}`;
        }
        list[i]['dateTime'] = `${month}.${day} ${hourMinute}`;
      }
    }

    if (list[i]['endDate'] && !list[i]['endDateTime']) {
      const originalEndDate = list[i]['endDate'];
      const [datePart, timePart] = originalEndDate.split(' ');

      if (datePart) {
        const [year, month, day] = datePart.split('-');
        list[i]['endmonth'] = month;
        list[i]['endday'] = day;
        let hourMinute = '00:00';
        if (timePart) {
          const timeParts = timePart.split(':');
          hourMinute = `${timeParts[0]}:${timeParts[1]}`;
        }
        list[i]['endDateTime'] = `${month}.${day} ${hourMinute}`;
      }
    }
  }
  return list;
}

module.exports = {
  formatDateTimeForDisplay: formatDateTimeForDisplay,
  processTopicDate: processTopicDate,
};
