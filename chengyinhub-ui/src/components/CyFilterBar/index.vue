<template>
  <div class="cy-filter-bar">
    <div class="cy-filter-bar__controls">
      <div class="cy-filter-bar__fields">
        <slot />
      </div>
      <div class="cy-filter-bar__actions">
        <el-button type="primary" size="small" icon="el-icon-search" native-type="button" @click="submit">查询</el-button>
        <el-button size="small" icon="el-icon-refresh" native-type="button" @click="reset">重置</el-button>
        <el-button
          v-if="hasMoreFilters"
          type="text"
          size="small"
          native-type="button"
          :aria-expanded="String(moreOpen)"
          @click="toggleMore"
        >{{ moreOpen ? '收起筛选' : '更多筛选' }}<i :class="moreOpen ? 'el-icon-arrow-up' : 'el-icon-arrow-down'" /></el-button>
      </div>
    </div>

    <div v-if="hasMoreFilters && moreOpen" class="cy-filter-bar__more">
      <slot name="more" />
    </div>

    <div v-if="activeFilters.length" class="cy-filter-bar__selected" aria-label="已选条件">
      <span>已选条件</span>
      <button
        v-for="filter in activeFilters"
        :key="filter.key"
        type="button"
        class="cy-filter-bar__chip"
        @click="removeFilter(filter.key)"
      >{{ filter.label }}：{{ filter.value }}<i class="el-icon-close" /></button>
      <button type="button" class="cy-filter-bar__clear" @click="reset">清除全部</button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'CyFilterBar',
  props: {
    activeFilters: { type: Array, default: () => [] }
  },
  data() {
    return { moreOpen: false }
  },
  computed: {
    hasMoreFilters() {
      return Boolean(this.$slots.more)
    }
  },
  methods: {
    submit() {
      this.$emit('submit')
    },
    reset() {
      this.$emit('reset')
    },
    removeFilter(key) {
      this.$emit('remove-filter', key)
    },
    toggleMore() {
      this.moreOpen = !this.moreOpen
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-filter-bar { padding: 0 var(--cy-space-page) 16px; }
.cy-filter-bar__controls { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.cy-filter-bar__fields { display: flex; flex: 1; flex-wrap: wrap; align-items: flex-start; gap: 0 12px; }
.cy-filter-bar__fields ::v-deep .el-form-item { margin-right: 0; }
.cy-filter-bar__actions { display: flex; flex: none; flex-wrap: wrap; align-items: center; gap: 8px; }
.cy-filter-bar__actions .el-button + .el-button { margin-left: 0; }
.cy-filter-bar__more { display: flex; flex-wrap: wrap; gap: 0 12px; padding-top: 2px; }
.cy-filter-bar__more ::v-deep .el-form-item { margin-right: 0; }
.cy-filter-bar__selected { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding-top: 2px; color: var(--cy-color-text-tertiary); font-size: 12px; }
.cy-filter-bar__chip, .cy-filter-bar__clear { display: inline-flex; align-items: center; gap: 4px; min-height: 24px; border: 0; background: transparent; color: var(--cy-color-brand); font: inherit; cursor: pointer; }
.cy-filter-bar__chip { padding: 2px 8px; border-radius: 12px; background: var(--cy-color-brand-soft); }
.cy-filter-bar__clear { padding: 2px 0; }
.cy-filter-bar__chip:focus-visible, .cy-filter-bar__clear:focus-visible { outline: 3px solid var(--cy-color-focus); outline-offset: 2px; }

@media (max-width: 900px) {
  .cy-filter-bar__controls { flex-direction: column; }
  .cy-filter-bar__actions { width: 100%; }
}
</style>
