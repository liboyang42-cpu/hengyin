// 第十二轮 k1 第 9 项(j1 N2):金额输入框打字时只做「留数字、只留一个小数点、最多两位小数」,保留字符串;
// 受控 input 在中途 Number() 会把刚敲的小数点抹掉(39. → 39),再敲 99 就成了 3999。数值化留到校验与提交。
function typingPrice(raw) {
  let s = String(raw == null ? '' : raw).replace(/[^\d.]/g, '')
  const dot = s.indexOf('.')
  if (dot >= 0) s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '').slice(0, 2)
  return s
}

// 合法金额:非负,整数或一到两位小数(「39.」「.5」都不算填完)
function isValidPrice(value) {
  return /^\d+(\.\d{1,2})?$/.test(String(value == null ? '' : value).trim())
}

module.exports = { typingPrice, isValidPrice }
