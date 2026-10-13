import Vue from 'vue'

/**
 * 事件总线 - 用于组件间通信
 * 使用方式：
 * 1. 触发事件：this.$bus.$emit('event-name', data)
 * 2. 监听事件：this.$bus.$on('event-name', callback)
 * 3. 移除监听：this.$bus.$off('event-name')
 */

// 创建 Vue 实例作为事件总线
const bus = new Vue()

// 可选：添加一些常用方法的快捷方式
bus.emit = bus.$emit.bind(bus)
bus.on = bus.$on.bind(bus)
bus.off = bus.$off.bind(bus)
bus.once = bus.$once.bind(bus)

export default bus