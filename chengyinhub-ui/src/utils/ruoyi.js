/**
 * 通用js方法封装处理
 * Copyright (c) 2019 ruoyi
 */

// 统一时间输入合同(后台/官方/小程序同一规则):
//   只接受 Date、有限正数(含 1970 前合法历史瞬时)、精确 10/13 位数字字符串、带偏移 ISO、
//   严格合法日历串(裸串/date-only = 中国时间, 支持 - 或 / 分隔与可选毫秒/可选秒)。
//   10 位秒统一 ×1000; 0 哨兵/布尔/数组/对象/非法日历(如 2026-02-30)/非法时分秒/超 Date 范围
//   一律失败关闭(NaN), 不抛错。绝对时刻统一按中国字段展示。parseTime 复用 toTimestamp, 只此一份解析。

function isValidCalendar(y, mo, d, h, mi, s) {
  if (mo < 1 || mo > 12) return false
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0
  const maxDay = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][mo - 1]
  if (d < 1 || d > maxDay) return false
  return h >= 0 && h <= 23 && mi >= 0 && mi <= 59 && s >= 0 && s <= 59
}

function pad2(n) { return n < 10 ? '0' + n : String(n) }

// Date 能表示的瞬时范围(±8.64e15 ms); 超范围的有限数字不得产出 NaN 文案, 直接失败关闭。
function isValidEpoch(ts) {
  return Number.isFinite(ts) && !Number.isNaN(new Date(ts).getTime())
}

// 裸中国日历串: 支持 - 或 / 分隔、可选 时:分[:秒[.毫秒]]; 严格校验后再补 +08:00。
function parseChinaString(str) {
  const match = str.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2})(?:\.\d{1,9})?)?)?$/)
  if (!match) return NaN
  const y = Number(match[1]); const mo = Number(match[2]); const d = Number(match[3])
  const h = match[4] ? Number(match[4]) : 0
  const mi = match[5] ? Number(match[5]) : 0
  const s = match[6] ? Number(match[6]) : 0
  if (!isValidCalendar(y, mo, d, h, mi, s)) return NaN
  const ts = Date.parse(`${match[1]}-${pad2(mo)}-${pad2(d)}T${pad2(h)}:${pad2(mi)}:${pad2(s)}+08:00`)
  return isValidEpoch(ts) ? ts : NaN
}

// 带偏移 ISO: 同样严格校验真实日历与时分秒(不能只交给 Date.parse, 否则 2026-02-30 会滚成 3 月 2 日)。
function parseOffsetIso(str) {
  const match = str.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d\d:?\d\d)$/i)
  if (!match) return NaN
  const y = Number(match[1]); const mo = Number(match[2]); const d = Number(match[3])
  const h = Number(match[4]); const mi = Number(match[5])
  const s = match[6] ? Number(match[6]) : 0
  if (!isValidCalendar(y, mo, d, h, mi, s)) return NaN
  const ts = Date.parse(str.replace(' ', 'T'))
  return isValidEpoch(ts) ? ts : NaN
}

// 把后端时间解析成绝对时刻(epoch ms)。用于展示与"到期比较"这类与 Date.now() 比大小的场景。
export function toTimestamp(time) {
  if (time === null || time === undefined || time === '') return NaN
  if (time instanceof Date) {
    const ts = time.getTime()
    return isValidEpoch(ts) ? ts : NaN
  }
  if (typeof time === 'number') {
    if (!Number.isFinite(time) || time === 0) return NaN
    const ms = Number.isInteger(time) && time > 0 && String(time).length === 10 ? time * 1000 : time
    return isValidEpoch(ms) ? ms : NaN
  }
  if (typeof time !== 'string') return NaN
  const str = time.trim()
  if (!str) return NaN
  if (/^\d{10}$/.test(str)) {
    const ms = Number(str) * 1000
    return isValidEpoch(ms) ? ms : NaN
  }
  if (/^\d{13}$/.test(str)) {
    const ms = Number(str)
    return isValidEpoch(ms) ? ms : NaN
  }
  if (/[zZ]$|[+-]\d\d:?\d\d$/.test(str)) return parseOffsetIso(str)
  return parseChinaString(str)
}

// 日期格式化: 复用 toTimestamp, 保证展示与到期比较同一套解析。
export function parseTime(time, pattern) {
  if (arguments.length === 0 || time === null || time === undefined || time === '') {
    return null
  }
  const ts = toTimestamp(time)
  if (Number.isNaN(ts)) {
    return null
  }
  const format = pattern || '{y}-{m}-{d} {h}:{i}:{s}'
  const date = new Date(ts + 8 * 60 * 60 * 1000)
  const formatObj = {
    y: date.getUTCFullYear(),
    m: date.getUTCMonth() + 1,
    d: date.getUTCDate(),
    h: date.getUTCHours(),
    i: date.getUTCMinutes(),
    s: date.getUTCSeconds(),
    a: date.getUTCDay()
  }
  const time_str = format.replace(/{(y|m|d|h|i|s|a)+}/g, (result, key) => {
    let value = formatObj[key]
    // Note: getDay() returns 0 on Sunday
    if (key === 'a') { return ['日', '一', '二', '三', '四', '五', '六'][value] }
    if (result.length > 0 && value < 10) {
      value = '0' + value
    }
    return value || 0
  })
  return time_str
}

// 表单重置
export function resetForm(refName) {
  if (this.$refs[refName]) {
    this.$refs[refName].resetFields()
  }
}

// 添加日期范围
export function addDateRange(params, dateRange, propName) {
  let search = params
  search.params = typeof (search.params) === 'object' && search.params !== null && !Array.isArray(search.params) ? search.params : {}
  dateRange = Array.isArray(dateRange) ? dateRange : []
  if (typeof (propName) === 'undefined') {
    search.params['beginTime'] = dateRange[0]
    search.params['endTime'] = dateRange[1]
  } else {
    search.params['begin' + propName] = dateRange[0]
    search.params['end' + propName] = dateRange[1]
  }
  return search
}

// 回显数据字典
export function selectDictLabel(datas, value) {
  if (value === undefined) {
    return ""
  }
  var actions = []
  Object.keys(datas).some((key) => {
    if (datas[key].value == ('' + value)) {
      actions.push(datas[key].label)
      return true
    }
  })
  if (actions.length === 0) {
    actions.push(value)
  }
  return actions.join('')
}

// 回显数据字典（字符串、数组）
export function selectDictLabels(datas, value, separator) {
  if (value === undefined || value.length ===0) {
    return ""
  }
  if (Array.isArray(value)) {
    value = value.join(",")
  }
  var actions = []
  var currentSeparator = undefined === separator ? "," : separator
  var temp = value.split(currentSeparator)
  Object.keys(value.split(currentSeparator)).some((val) => {
    var match = false
    Object.keys(datas).some((key) => {
      if (datas[key].value == ('' + temp[val])) {
        actions.push(datas[key].label + currentSeparator)
        match = true
      }
    })
    if (!match) {
      actions.push(temp[val] + currentSeparator)
    }
  })
  return actions.join('').substring(0, actions.join('').length - 1)
}

// 字符串格式化(%s )
export function sprintf(str) {
  var args = arguments, flag = true, i = 1
  str = str.replace(/%s/g, function () {
    var arg = args[i++]
    if (typeof arg === 'undefined') {
      flag = false
      return ''
    }
    return arg
  })
  return flag ? str : ''
}

// 转换字符串，undefined,null等转化为""
export function parseStrEmpty(str) {
  if (!str || str == "undefined" || str == "null") {
    return ""
  }
  return str
}

// 数据合并
export function mergeRecursive(source, target) {
  for (var p in target) {
    try {
      if (target[p].constructor == Object) {
        source[p] = mergeRecursive(source[p], target[p])
      } else {
        source[p] = target[p]
      }
    } catch (e) {
      source[p] = target[p]
    }
  }
  return source
}

/**
 * 构造树型结构数据
 * @param {*} data 数据源
 * @param {*} id id字段 默认 'id'
 * @param {*} parentId 父节点字段 默认 'parentId'
 * @param {*} children 孩子节点字段 默认 'children'
 */
export function handleTree(data, id, parentId, children) {
  let config = {
    id: id || 'id',
    parentId: parentId || 'parentId',
    childrenList: children || 'children'
  }

  var childrenListMap = {}
  var tree = []
  for (let d of data) {
    let id = d[config.id]
    childrenListMap[id] = d
    if (!d[config.childrenList]) {
      d[config.childrenList] = []
    }
  }

  for (let d of data) {
    let parentId = d[config.parentId]
    let parentObj = childrenListMap[parentId]
    if (!parentObj) {
      tree.push(d)
    } else {
      parentObj[config.childrenList].push(d)
    }
  }
  return tree
}

/**
* 参数处理
* @param {*} params  参数
*/
export function tansParams(params) {
  let result = ''
  for (const propName of Object.keys(params)) {
    const value = params[propName]
    var part = encodeURIComponent(propName) + "="
    if (value !== null && value !== "" && typeof (value) !== "undefined") {
      if (typeof value === 'object') {
        for (const key of Object.keys(value)) {
          if (value[key] !== null && value[key] !== "" && typeof (value[key]) !== 'undefined') {
            let params = propName + '[' + key + ']'
            var subPart = encodeURIComponent(params) + "="
            result += subPart + encodeURIComponent(value[key]) + "&"
          }
        }
      } else {
        result += part + encodeURIComponent(value) + "&"
      }
    }
  }
  return result
}

// 返回项目路径
export function getNormalPath(p) {
  if (p.length === 0 || !p || p == 'undefined') {
    return p
  }
  let res = p.replace('//', '/')
  if (res[res.length - 1] === '/') {
    return res.slice(0, res.length - 1)
  }
  return res
}

// 验证是否为blob格式
export function blobValidate(data) {
  return data.type !== 'application/json'
}
