<template>
  <section class="cy-data-table" :class="`cy-data-table--${density}`" aria-label="数据列表">
    <header class="cy-data-table__toolbar">
      <div class="cy-data-table__summary">
        <strong>{{ total }} 项结果</strong>
        <span>{{ selectable && selectedRows.length ? `已选 ${selectedRows.length} 项` : '按当前条件显示' }}</span>
      </div>

      <div v-if="$scopedSlots['toolbar-actions']" class="cy-data-table__toolbar-actions">
        <slot name="toolbar-actions" :selected-rows="selectedRows" :selection-count="selectedRows.length" />
      </div>

      <div class="cy-data-table__tools">
        <el-tooltip v-if="showRefresh" content="刷新列表" placement="top">
          <el-button circle size="mini" icon="el-icon-refresh" aria-label="刷新列表" @click="$emit('refresh')" />
        </el-tooltip>
        <el-popover v-if="columnStates.length" placement="bottom-end" width="180" trigger="click">
          <div class="cy-data-table__column-menu">
            <p>显示列</p>
            <el-checkbox
              v-for="column in columnStates"
              :key="column.key"
              :value="column.visible"
              :disabled="column.locked"
              @change="setColumnVisibility(column.key, $event)"
            >{{ column.label }}</el-checkbox>
          </div>
          <el-tooltip slot="reference" content="列设置" placement="top">
            <el-button circle size="mini" icon="el-icon-menu" aria-label="列设置" />
          </el-tooltip>
        </el-popover>
        <el-dropdown trigger="click" @command="setDensity">
          <el-button class="cy-data-table__density" size="mini" plain>
            {{ density === 'compact' ? '紧凑' : '舒展' }}<i class="el-icon-arrow-down el-icon--right" />
          </el-button>
          <el-dropdown-menu slot="dropdown">
            <el-dropdown-item command="compact">紧凑显示</el-dropdown-item>
            <el-dropdown-item command="comfortable">舒展显示</el-dropdown-item>
          </el-dropdown-menu>
        </el-dropdown>
      </div>
    </header>

    <el-table
      v-loading="loading"
      :data="data"
      :empty-text="emptyText"
      class="cy-data-table__table"
      :class="`cy-data-table__table--${density}`"
      @selection-change="handleSelectionChange"
    >
      <el-table-column v-if="selectable" type="selection" width="55" align="center" />
      <el-table-column
        v-for="column in visibleColumns"
        :key="column.key"
        :prop="column.prop"
        :label="column.label"
        :width="column.width"
        :min-width="column.minWidth"
        :fixed="column.fixed"
        :align="column.align"
        :show-overflow-tooltip="column.showOverflowTooltip"
      >
        <template slot-scope="scope">
          <slot :name="column.key" :row="scope.row" :column="column" :index="scope.$index">
            {{ formatCell(scope.row, column, scope.$index) }}
          </slot>
        </template>
      </el-table-column>
    </el-table>

    <pagination
      v-show="total > 0"
      :total="total"
      :page="page"
      :limit="limit"
      @pagination="handlePagination"
    />
  </section>
</template>

<script>
export default {
  name: 'CyDataTable',
  props: {
    data: { type: Array, default: () => [] },
    columns: { type: Array, default: () => [] },
    loading: { type: Boolean, default: false },
    total: { type: Number, default: 0 },
    page: { type: Number, default: 1 },
    limit: { type: Number, default: 10 },
    emptyText: { type: String, default: '当前条件下没有结果' },
    defaultDensity: { type: String, default: 'compact' },
    showRefresh: { type: Boolean, default: true },
    selectable: { type: Boolean, default: false }
  },
  data() {
    return {
      density: this.defaultDensity,
      columnStates: [],
      selectedRows: []
    }
  },
  computed: {
    visibleColumns() {
      const columns = this.columnStates.length ? this.columnStates : this.columns
      return columns.filter(column => column.visible !== false)
    }
  },
  watch: {
    columns: {
      immediate: true,
      handler(columns) {
        this.columnStates = columns.map(column => Object.assign({}, column, {
          visible: column.locked ? true : column.visible !== false
        }))
      }
    }
  },
  methods: {
    formatCell(row, column, index) {
      if (typeof column.formatter === 'function') {
        return column.formatter(row, column, index)
      }
      return column.prop ? row[column.prop] : ''
    },
    setDensity(density) {
      this.density = density
    },
    setColumnVisibility(key, visible) {
      const target = this.columnStates.find(column => column.key === key)
      const visibleBusinessColumns = this.columnStates.filter(column => column.visible && !column.locked)
      if (!target || target.locked || (!visible && !target.locked && visibleBusinessColumns.length === 1)) {
        return
      }
      target.visible = visible
    },
    handlePagination({ page, limit }) {
      this.$emit('update:page', page)
      this.$emit('update:limit', limit)
      this.$emit('pagination', { page, limit })
    },
    handleSelectionChange(rows) {
      if (!this.selectable) return
      this.selectedRows = rows
      this.$emit('selection-change', rows)
    }
  }
}
</script>

<style lang="scss" scoped>
.cy-data-table {
  overflow: hidden;
  border: 1px solid var(--cy-color-border-subtle);
  border-radius: var(--cy-radius-card);
  background: var(--cy-color-bg-surface);
  box-shadow: var(--cy-shadow-card);
}

.cy-data-table__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 60px;
  padding: 0 var(--cy-space-page);
  border-bottom: 1px solid var(--cy-color-border-subtle);
}

.cy-data-table__summary { min-width: 0; }
.cy-data-table__summary strong, .cy-data-table__summary span { display: block; }
.cy-data-table__summary strong { color: var(--cy-color-text-primary); font-size: 14px; }
.cy-data-table__summary span { margin-top: var(--cy-legacy-3); color: var(--cy-color-text-tertiary); font-size: 12px; }
.cy-data-table__toolbar-actions { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.cy-data-table__tools { display: flex; flex: none; align-items: center; gap: 8px; }
.cy-data-table__tools .el-button + .el-button { margin-left: 0; }
.cy-data-table__density { color: var(--cy-color-text-secondary); }

.cy-data-table__column-menu p { margin: 0 0 8px; color: var(--cy-color-text-tertiary); font-size: 12px; font-weight: 700; }
.cy-data-table__column-menu ::v-deep .el-checkbox { display: flex; margin: 8px 0; color: var(--cy-color-text-secondary); }

.cy-data-table__table ::v-deep::before { display: none; }
.cy-data-table__table ::v-deep th { height: 40px; background: var(--cy-color-bg-subtle); color: var(--cy-color-text-secondary); font-size: 12px; font-weight: 700; }
.cy-data-table__table ::v-deep td { border-bottom-color: var(--cy-color-border-subtle); color: var(--cy-color-text-primary); }
.cy-data-table__table ::v-deep .el-table__row { transition: background-color 160ms ease; }
.cy-data-table__table ::v-deep .el-table__row:hover > td { background: var(--cy-color-bg-subtle); }
.cy-data-table__table--compact ::v-deep td { height: 40px; padding: 4px 0; }
.cy-data-table__table--comfortable ::v-deep td { height: 56px; padding: 8px 0; }
.cy-data-table__table ::v-deep .el-table__empty-block { min-height: 220px; }
.cy-data-table__table ::v-deep .el-table__empty-text { color: var(--cy-color-text-tertiary); }

@media (max-width: 720px) {
  .cy-data-table__toolbar { align-items: flex-start; padding: var(--cy-space-2-5) 16px; }
  .cy-data-table__toolbar-actions { flex-wrap: wrap; justify-content: flex-end; }
  .cy-data-table__tools { flex-wrap: wrap; justify-content: flex-end; }
  .cy-data-table__density { padding: 7px; }
}
</style>
