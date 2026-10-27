// 2026-10-27 业主 G-7:设置行里的地址只显示到路名(含门牌号),省市区与楼层房号不进行尾。
// 认不出路名(「上海市黄浦区咖啡」这类自由文本)就原样返回,交给行组件单行省略,不硬切。
const ROAD = /[^省市区县镇乡\s,，]{1,12}?(?:大道|路|街|巷|胡同|弄)(?:\d+(?:-\d+)?号)?/;

function streetOnly(address) {
  const text = String(address == null ? '' : address).trim();
  if (!text) return '';
  const hit = text.match(ROAD);
  return hit ? hit[0] : text;
}

module.exports = { streetOnly };
