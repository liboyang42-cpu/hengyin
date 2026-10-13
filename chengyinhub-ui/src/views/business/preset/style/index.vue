<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="风格名称" prop="name">
        <el-input v-model="queryParams.name" placeholder="请输入风格名称" clearable @keyup.enter.native="handleQuery" />
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
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd" v-hasPermi="['business:presetStyle:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete" v-hasPermi="['business:presetStyle:remove']">删除</el-button>
      </el-col>
      <right-toolbar :showSearch.sync="showSearch" @queryTable="getList"></right-toolbar>
    </el-row>

    <el-table v-loading="loading" :data="styleList" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="ID" align="center" prop="id" width="60" />
      <el-table-column label="封面" align="center" prop="coverImg" width="80">
        <template slot-scope="scope">
          <el-image v-if="scope.row.coverImg" :src="scope.row.coverImg" style="width:48px;height:48px" fit="cover" />
        </template>
      </el-table-column>
      <el-table-column label="风格名称" align="center" prop="name" />
      <el-table-column label="默认标题" align="center" prop="defaultTitle" :show-overflow-tooltip="true" />
      <el-table-column label="排序" align="center" prop="sort" width="70" />
      <el-table-column label="状态" align="center" prop="status" width="80">
        <template slot-scope="scope">
          <el-tag :type="scope.row.status === '1' ? 'success' : 'info'">{{ scope.row.status === '1' ? '上架' : '下架' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)" v-hasPermi="['business:presetStyle:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)" v-hasPermi="['business:presetStyle:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total>0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize" @pagination="getList" />

    <el-dialog :title="title" :visible.sync="open" width="600px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="风格名称" prop="name">
          <el-input v-model="form.name" placeholder="如:深夜城市探险" />
        </el-form-item>
        <el-form-item label="封面图URL" prop="coverImg">
          <el-input v-model="form.coverImg" placeholder="OSS 封面图地址" />
        </el-form-item>
        <el-form-item label="默认标题" prop="defaultTitle">
          <el-input v-model="form.defaultTitle" placeholder="进建议版预填的标题" />
        </el-form-item>
        <el-form-item label="默认副标题" prop="defaultSubtitle">
          <el-input v-model="form.defaultSubtitle" />
        </el-form-item>
        <el-form-item label="默认简介" prop="defaultDesc">
          <el-input v-model="form.defaultDesc" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="默认分类ID" prop="defaultCategoryIds">
          <el-input v-model="form.defaultCategoryIds" placeholder="逗号分隔,如 1,3" />
        </el-form-item>
        <el-form-item label="token色值" prop="toneTokens">
          <el-input v-model="form.toneTokens" type="textarea" :rows="2" placeholder='可选,JSON,如 {"--cy-accent":"#1A1A1A"}' />
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" controls-position="right" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="form.status">
            <el-radio label="1">上架</el-radio>
            <el-radio label="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listPresetStyle, getPresetStyle, delPresetStyle, addPresetStyle, updatePresetStyle } from "@/api/business/presetStyle"

export default {
  name: "PresetStyle",
  data() {
    return {
      loading: true,
      ids: [],
      single: true,
      multiple: true,
      showSearch: true,
      total: 0,
      styleList: [],
      title: "",
      open: false,
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        name: null,
        status: null
      },
      form: {},
      rules: {
        name: [{ required: true, message: "风格名称不能为空", trigger: "blur" }],
        status: [{ required: true, message: "状态不能为空", trigger: "change" }]
      }
    }
  },
  created() {
    this.getList()
  },
  methods: {
    getList() {
      this.loading = true
      listPresetStyle(this.queryParams).then(response => {
        this.styleList = response.rows
        this.total = response.total
        this.loading = false
      })
    },
    cancel() {
      this.open = false
      this.reset()
    },
    reset() {
      this.form = {
        id: null,
        name: null,
        coverImg: null,
        toneTokens: null,
        defaultTitle: null,
        defaultSubtitle: null,
        defaultDesc: null,
        defaultCategoryIds: null,
        sort: 0,
        status: "1"
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
    handleAdd() {
      this.reset()
      this.open = true
      this.title = "添加主题风格"
    },
    handleUpdate(row) {
      this.reset()
      const id = row.id || this.ids
      getPresetStyle(id).then(response => {
        this.form = response.data
        this.open = true
        this.title = "修改主题风格"
      })
    },
    submitForm() {
      this.$refs["form"].validate(valid => {
        if (valid) {
          if (this.form.id != null) {
            updatePresetStyle(this.form).then(() => {
              this.$modal.msgSuccess("修改成功")
              this.open = false
              this.getList()
            })
          } else {
            addPresetStyle(this.form).then(() => {
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
      this.$modal.confirm('是否确认删除风格编号为"' + ids + '"的数据项？').then(() => {
        return delPresetStyle(ids)
      }).then(() => {
        this.getList()
        this.$modal.msgSuccess("删除成功")
      }).catch(() => {})
    }
  }
}
</script>
