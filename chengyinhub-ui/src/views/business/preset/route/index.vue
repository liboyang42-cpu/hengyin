<template>
  <div class="app-container">
    <el-alert title="预设路线使用范围" description="上架路线可供公开查询；当前发布页尚未接入选择与独立草稿复制。来源版本、引用重映射及已发布内容冻结待接入，保存不代表玩家内容已更新。" type="info" :closable="false" show-icon />
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="城市" prop="city">
        <el-input v-model="queryParams.city" placeholder="城市" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="路线名称" prop="name">
        <el-input v-model="queryParams.name" placeholder="路线名称" clearable @keyup.enter.native="handleQuery" />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="queryParams.status" placeholder="状态" clearable>
          <el-option label="上架" value="1" />
          <el-option label="下架" value="0" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:presetRoute:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete" v-hasPermi="['business:presetRoute:remove']">删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <cy-resource-state v-if="loadError" kind="error" title="预设路线暂时无法读取" description="请重新读取；若仍失败，请核对查看权限。" retry-text="重新读取" :retrying="loading" @retry="getList" />
    <el-table v-else v-loading="loading" :data="routeList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="60" />
      <el-table-column label="封面" align="center" prop="previewImg" width="80">
        <template slot-scope="scope">
          <el-image v-if="scope.row.previewImg" :src="scope.row.previewImg" style="width:48px;height:48px" fit="cover" />
        </template>
      </el-table-column>
      <el-table-column label="城市" align="center" prop="city" width="90" />
      <el-table-column label="路线名称" align="center" prop="name" />
      <el-table-column label="距离(km)" align="center" prop="totalDistance" width="90" />
      <el-table-column label="排序" align="center" prop="sort" width="70" />
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === '1' ? 'success' : 'info'">{{ scope.row.status === '1' ? '上架' : scope.row.status === '0' ? '下架' : '待确认' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置更新时间" prop="updateTime" min-width="160" />
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" width="150">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:presetRoute:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['business:presetRoute:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="!loadError && total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="820px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-row>
          <el-col :span="12">
            <el-form-item label="城市" prop="city">
              <el-input v-model="form.city" placeholder="如:上海" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="路线名称" prop="name">
              <el-input v-model="form.name" placeholder="如:武康路文艺线" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="封面图URL" prop="previewImg">
          <el-input v-model="form.previewImg" placeholder="路线静态预览图 OSS 地址" />
        </el-form-item>
        <el-row>
          <el-col :span="8">
            <el-form-item label="距离(km)" prop="totalDistance">
              <el-input-number v-model="form.totalDistance" :min="0" :precision="2" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="时长(分)" prop="totalTime">
              <el-input-number v-model="form.totalTime" :min="0" controls-position="right" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="排序" prop="sort">
              <el-input-number v-model="form.sort" :min="0" controls-position="right" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-row>
          <el-col :span="12">
            <el-form-item label="默认分类ID" prop="defaultCategoryIds">
              <el-input v-model="form.defaultCategoryIds" placeholder="逗号分隔" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态" prop="status">
              <el-radio-group v-model="form.status">
                <el-radio label="1">上架</el-radio>
                <el-radio label="0">下架</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">站点(按顺序,经纬度从地图取)</el-divider>
        <el-button type="primary" plain size="mini" icon="el-icon-plus" @click="addNode" style="margin-bottom:10px">添加站点</el-button>
        <el-table :data="form.nodes" size="mini" border>
          <el-table-column label="序" type="index" width="45" align="center" />
          <el-table-column label="站点名称">
            <template slot-scope="scope"><el-input v-model="scope.row.name" placeholder="名称" /></template>
          </el-table-column>
          <el-table-column label="地址">
            <template slot-scope="scope"><el-input v-model="scope.row.address" placeholder="地址" /></template>
          </el-table-column>
          <el-table-column label="经度" width="120">
            <template slot-scope="scope"><el-input v-model="scope.row.longitude" placeholder="lng" /></template>
          </el-table-column>
          <el-table-column label="纬度" width="120">
            <template slot-scope="scope"><el-input v-model="scope.row.latitude" placeholder="lat" /></template>
          </el-table-column>
          <el-table-column label="停留(分)" width="90">
            <template slot-scope="scope"><el-input v-model="scope.row.nodeTime" placeholder="30" /></template>
          </el-table-column>
          <el-table-column label="操作" width="60" align="center">
            <template slot-scope="scope">
              <el-button size="mini" type="text" icon="el-icon-delete" @click="removeNode(scope.$index)" />
            </template>
          </el-table-column>
        </el-table>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import CyResourceState from '@/components/CyResourceState'
import { listPresetRoute, getPresetRoute, delPresetRoute, addPresetRoute, updatePresetRoute } from "@/api/business/presetRoute"

export default {
  components: { CyResourceState },
  name: "PresetRoute",
  data() {
    return {
      loading: true,
      loadError: false,
      requestToken: 0,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      routeList: [],
      title: "",
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        city: null,
        name: null,
        status: null
      },
      form: {},
      rules: {
        name: [{ required: true, message: "路线名称不能为空", trigger: "blur" }],
        status: [{ required: true, message: "状态不能为空", trigger: "change" }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      const requestToken = ++this.requestToken
      this.loading = true
      this.loadError = false
      return listPresetRoute(this.queryParams).then(response => {
        if (requestToken !== this.requestToken) return
        if (!Array.isArray(response.rows)) throw new Error('列表数据不完整')
        this.routeList = response.rows
        this.total = response.total
      }).catch(() => {
        if (requestToken !== this.requestToken) return
        this.routeList = []
        this.total = 0
        this.loadError = true
      }).finally(() => {
        if (requestToken === this.requestToken) this.loading = false
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        id: null,
        city: null,
        name: null,
        previewImg: null,
        totalDistance: 0,
        totalTime: 0,
        defaultCategoryIds: null,
        sort: 0,
        status: "1",
        nodes: []
      }
      this.resetForm("form")
    },
    handleQuery() {
      this.queryParams.pageNum = 1
      this.getList()
    },
    resetQuery() {
      this.resetForm("queryForm")
      this.handleQuery()
    },
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    addNode() {
      if (!this.form.nodes) { this.$set(this.form, 'nodes', []) }
      this.form.nodes.push({ name: '', address: '', longitude: '', latitude: '', nodeTime: 30 })
    },
    removeNode(index) {
      this.form.nodes.splice(index, 1)
    },
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加城市路线"
    },
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getPresetRoute(id).then(response => {
        this.form = response.data
        if (!this.form.nodes) { this.$set(this.form, 'nodes', []) }
        this.open = true
        this.title = "修改城市路线"
      })
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updatePresetRoute(this.form).then(() => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addPresetRoute(this.form).then(() => {
              this.$modal.msgSuccess("新增成功")
              this.open = false
              this.getList()
            })
          }
        }
      })
    },
    handleDelete(row) {
      const ids = row.id || this.ids
      this.$modal.confirm('是否确认删除路线编号为"' + ids + '"的数据项？').then(() => {
        return delPresetRoute(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    }
  }
}
</script>
