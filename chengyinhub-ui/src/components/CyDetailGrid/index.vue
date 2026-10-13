<template>
  <section class="cy-detail-grid">
    <h3 v-if="title" class="cy-detail-grid__title">{{ title }}</h3>
    <dl v-if="visibleItems.length" class="cy-detail-grid__items" :style="gridStyle">
      <div
        v-for="item in visibleItems"
        :key="item.key"
        class="cy-detail-grid__item"
        :style="{ gridColumn: `span ${itemSpan(item)}` }"
      >
        <dt>{{ item.label }}</dt>
        <dd>
          <slot :name="item.key" :item="item">{{ displayValue(item.value) }}</slot>
        </dd>
      </div>
    </dl>
    <el-empty v-else :description="emptyText" :image-size="80" />
  </section>
</template>

<script>
export default {
  name: 'CyDetailGrid',
  props: {
    title: { type: String, default: '' },
    items: { type: Array, default: () => [] },
    columns: {
      type: Number,
      default: 2,
      validator: value => [1, 2, 3].indexOf(value) !== -1
    },
    emptyText: { type: String, default: '暂无详情' },
    emptyValue: { type: String, default: '—' }
  },
  computed: {
    safeColumns() {
      return [1, 2, 3].indexOf(this.columns) !== -1 ? this.columns : 2
    },
    visibleItems() {
      return this.items.filter(item => item && item.key && item.label && item.hidden !== true)
    },
    gridStyle() {
      return { '--cy-detail-grid-columns': this.safeColumns }
    }
  },
  methods: {
    itemSpan(item) {
      const span = Math.floor(Number(item.span) || 1)
      return Math.min(Math.max(span, 1), this.safeColumns)
    },
    displayValue(value) {
      return value === undefined || value === null || value === '' ? this.emptyValue : value
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-detail-grid__title { margin: 0 0 var(--cy-space-2-5); color: var(--cy-color-text-primary); font-size: 16px; line-height: 1.5; }
.cy-detail-grid__items { display: grid; grid-template-columns: repeat(var(--cy-detail-grid-columns), minmax(0, 1fr)); gap: var(--cy-space-1-5); margin: 0; }
.cy-detail-grid__item { min-width: 0; padding: var(--cy-space-2-5) 16px; border: 1px solid var(--cy-color-border-subtle); border-radius: var(--cy-radius-control); background: var(--cy-color-bg-subtle); }
.cy-detail-grid__item dt { color: var(--cy-color-text-tertiary); font-size: 12px; line-height: 1.5; }
.cy-detail-grid__item dd { min-width: 0; margin: var(--cy-space-0-5) 0 0; overflow-wrap: anywhere; color: var(--cy-color-text-primary); font-size: 14px; line-height: 1.65; }

@media (max-width: 720px) {
  .cy-detail-grid__items { grid-template-columns: 1fr; }
  .cy-detail-grid__item { grid-column: 1 / -1 !important; }
}
</style>
