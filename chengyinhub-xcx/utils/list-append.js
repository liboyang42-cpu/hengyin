// P1-性能(P0-4):分页「加载更多」按数组路径回传新增项,替代整表 concat 全量重发。
// 微信 setData 对 `key[i]` 路径会同步扩展 this.data 与视图层数组,只有新行跨序列化线。
// 用法:const patch = appendByIndex('list', that.data.list.length, newItems);
//      Object.assign(patch, 其余标量字段) 或 setData(Object.assign(标量字面量, patch));
// 注意:传入的 existingLength 必须在 setData 之前取(append 后 this.data 会同步变长)。
function appendByIndex(key, existingLength, incoming) {
  const patch = {};
  if (!Array.isArray(incoming)) return patch;
  const base = Number.isInteger(existingLength) && existingLength >= 0 ? existingLength : 0;
  for (let i = 0; i < incoming.length; i++) {
    patch[key + '[' + (base + i) + ']'] = incoming[i];
  }
  return patch;
}

module.exports = { appendByIndex };
