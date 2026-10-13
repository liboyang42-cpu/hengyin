<template>
  <div class="app-container">
    <el-form
      :model="queryParams"
      ref="queryForm"
      :inline="true"
      v-show="showSearch"
      label-width="100px"
      size="medium"
      class="ry_form"
    >
      <el-form-item label="类型" prop="type">
        <el-select
          v-model="queryParams.type"
          placeholder="选择类型"
          size="small"
          style="width: 120px"
        >
          <el-option label="主题" value="1" />
          <el-option label="活动" value="2" />
          <el-option label="个人偏好" value="3" />
          <el-option label="模版" value="4" />
          <el-option label="品牌偏好" value="5" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select
          v-model="queryParams.status"
          placeholder="全部状态"
          size="small"
          style="width: 120px"
          clearable
        >
          <el-option label="显示" value="1" />
          <el-option label="隐藏" value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" prop="categoryName">
        <el-input
          v-model="queryParams.categoryName"
          placeholder="名称"
          clearable
          size="small"
        />
      </el-form-item>

      <el-form-item class="flex_one tr">
        <el-button
          type="primary"
          icon="el-icon-search"
          size="mini"
          @click="handleQuery"
          >搜索</el-button
        >
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery"
          >重置</el-button
        >
      </el-form-item>
    </el-form>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button
          type="primary"
          plain
          icon="el-icon-plus"
          size="mini"
          @click="handleAdd"
          >新增
        </el-button>
      </el-col>
    </el-row>

    <el-table
      v-loading="loading"
      :data="categoryList"
      border
      @selection-change="handleSelectionChange"
    >
      <el-table-column label="ID" prop="id" width="80" align="center" />
      <el-table-column label="名称" prop="categoryName" />

      <el-table-column label="类型" prop="type" width="150" align="center">
        <template v-slot="{ row }">
          {{ getTypeLabel(row.type) }}
        </template>
      </el-table-column>
      <el-table-column label="图标" prop="icon" width="80" align="center">
        <template slot-scope="{ row }">
          <img
            v-if="row.icon"
            :src="row.icon"
            style="height: 50px; width: 50px; border-radius: 50%; object-fit: cover;"
          />
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="排序" prop="sortId" width="80" align="center" />
      <el-table-column label="状态" prop="status" width="100" align="center">
        <template v-slot="{ row }">
          <el-tag :type="row.status == 1 ? 'success' : 'info'">
            {{ row.status == 1 ? '显示' : '隐藏' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="创建时间"
        align="center"
        prop="createTime"
        width="170"
      />
      <el-table-column label="操作人" align="center" prop="updateBy" width="100" />
      <el-table-column
        label="操作"
        class-name="small-padding fixed-width"
        width="120"
        align="center"
      >
        <template slot-scope="scope">
          <el-button
            size="mini"
            type="text"
            icon="el-icon-edit"
            @click="handleUpdate(scope.row)"
            >编辑
          </el-button>
          <el-button
            size="mini"
            type="text"
            icon="el-icon-delete"
            @click="handleDelete(scope.row)"
            >删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 添加或修改分类对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="500px" append-to-body>
      <el-form ref="form" :model="form" :rules="rules" label-width="108px">
        <el-form-item label="类型" prop="type">
          <el-select
            v-model="form.type"
            placeholder="请选择类型"
            style="width: 100%"
          >
            <el-option label="主题" value="1" />
            <el-option label="活动" value="2" />
            <el-option label="个人偏好" value="3" />
            <el-option label="模版" value="4" />
            <el-option label="品牌偏好" value="5" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类名称" prop="categoryName">
          <el-input v-model="form.categoryName" placeholder="分类名称" />
        </el-form-item>
        <el-form-item label="图片" prop="icon">
          <div style="display: flex; flex-direction: column; align-items: center;">
            <el-upload
              :action="uploadFileUrl"
              accept="image/jpeg,image/gif,image/png"
              list-type="picture-card"
              :headers="headers"
              :on-success="handleIconSuccess"
              :show-file-list="false"
              :before-upload="beforeUpload"
            >
              <img
                v-if="form.icon"
                :src="form.icon"
                style="width: 100px; height: 100px; object-fit: contain; border-radius: 6px;"
              />
              <div v-else style="text-align: center;">
                <i class="el-icon-plus" style="font-size: 28px; color: #8c939d;"></i>
                <div style="font-size: 12px; color: #8c939d;">点击上传</div>
              </div>
            </el-upload>
            
            <div v-if="form.icon" style="margin-top: 10px;">
              <el-button size="mini" type="text" @click="previewImage">预览</el-button>
              <el-button size="mini" type="text" @click="clearImage">删除</el-button>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="排序" prop="sortId">
          <el-input-number
            v-model="form.sortId"
            controls-position="right"
            :min="0"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">显示</el-radio>
            <el-radio :label="2">隐藏</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">提交</el-button>
        <el-button @click="cancel">取消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listCategory, getCategory, delCategory, addCategory, updateCategory } from "@/api/system/category"
import { getToken } from "@/utils/auth";

export default {
  name: "Category",
  data() {
    return {
      // 遮罩层
      loading: true,
      // 选中数组
      ids: [],
      // 非单个禁用
      single: true,
      // 非多个禁用
      multiple: true,
      // 显示搜索条件
      showSearch: true,
      // 分类表格数据
      categoryList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数 - 默认显示主题类型(type=1)
      queryParams: {
        parentId: 0,
        categoryName: null,
        status: null,
        sortId: null,
        type: "1", // 默认显示主题类型
      },
      // 表单参数
      form: {
        type: "1", // 默认新增时选择主题
        status: 1,
        parentId: 0,
        sortId: 0,
        icon: null,
      },
      // 表单校验
      rules: {
        type: [
          { required: true, message: "请选择类型", trigger: "change" }
        ],
        categoryName: [
          { required: true, message: "分类名称不能为空", trigger: "blur" }
        ],
        sortId: [
          { required: true, message: "排序不能为空", trigger: "blur" }
        ]
      },
      // 图片上传相关
      uploadFileUrl: process.env.VUE_APP_BASE_API + "/api/common/uploadOSS",
      headers: {
        Authorization: "Bearer " + getToken(),
      },
    };
  },
  created() {
    this.getList();
  },
  methods: {
    getTypeLabel(type) {
      switch(parseInt(type)) {
        case 1: return '主题';
        case 2: return '活动';
        case 3: return '个人偏好';
        case 4: return '模版';
        case 5: return '品牌偏好';
        default: return '未知';
      }
    },
    
    /** 查询分类列表 */
    getList() {
      this.loading = true;
      const query = { 
        ...this.queryParams,
        parentId: 0
      };
      
      console.log("查询参数:", query);
      
      listCategory(query).then((response) => {
        console.log("列表接口返回:", response);
        this.categoryList = response.rows || response.data || response || [];
        this.loading = false;
      }).catch((error) => {
        console.error("查询失败:", error);
        this.loading = false;
        this.categoryList = [];
      });
    },
    
    // 取消按钮
    cancel() {
      this.open = false;
      this.reset();
    },
    
    // 表单重置
    reset() {
      this.form = {
        id: null,
        parentId: 0,
        categoryName: "",
        type: this.queryParams.type, // 使用当前查询的类型
        status: 1,
        sortId: 0,
        icon: null,
      };
      if (this.$refs.form) {
        this.$refs.form.resetFields();
      }
    },
    
    /** 搜索按钮操作 */
    handleQuery() {
      this.getList();
    },
    
    /** 重置按钮操作 */
    resetQuery() {
      // 重置时只重置部分字段，类型保持当前选择
      this.queryParams = {
        ...this.queryParams,
        categoryName: null,
        status: null,
        sortId: null,
      };
      this.resetForm("queryForm");
      this.handleQuery();
    },
    
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map((item) => item.id);
      this.single = selection.length !== 1;
      this.multiple = !selection.length;
    },
    
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加分类";
    },
    
    /** 修改按钮操作 */
    handleUpdate(row) {
      console.log("点击编辑:", row);
      
      // 直接从表格行获取数据
      this.form = {
        id: row.id,
        parentId: row.parentId || 0,
        categoryName: row.categoryName || "",
        type: row.type ? row.type.toString() : this.queryParams.type,
        status: row.status || 1,
        sortId: row.sortId || 0,
        icon: row.icon || null,
      };
      
      console.log("编辑表单数据:", this.form);
      
      this.open = true;
      this.title = "修改分类";
    },
    
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate((valid) => {
        if (valid) {
          const submitData = {
            ...this.form,
            parentId: 0,
            type: parseInt(this.form.type),
            status: parseInt(this.form.status),
          };
          
          console.log("提交数据:", submitData);
          
          const request = this.form.id ? updateCategory(submitData) : addCategory(submitData);
          
          request.then((response) => {
            console.log("提交响应:", response);
            if (response.code === 200) {
              this.$modal.msgSuccess(this.form.id ? "修改成功" : "新增成功");
              this.open = false;
              this.getList();
            } else {
              this.$modal.msgError(response.msg || "操作失败");
            }
          }).catch((error) => {
            console.error("提交失败:", error);
            this.$modal.msgError("操作失败");
          });
        }
      });
    },
    
    /** 删除按钮操作 */
    handleDelete(row) {
      const id = row.id;
      this.$modal
        .confirm('是否确认删除分类编号为"' + id + '"的数据项？')
        .then(() => {
          return delCategory(id);
        })
        .then((response) => {
          if (response.code === 200) {
            this.getList();
            this.$modal.msgSuccess("删除成功");
          } else {
            this.$modal.msgError(response.msg || "删除失败");
          }
        })
        .catch(() => {});
    },
    
    // 图片上传相关方法
    beforeUpload(file) {
      const isImage = file.type.startsWith('image/');
      const isLt2M = file.size / 1024 / 1024 < 2;
      
      if (!isImage) {
        this.$modal.msgError('只能上传图片格式!');
        return false;
      }
      if (!isLt2M) {
        this.$modal.msgError('上传图片大小不能超过 2MB!');
        return false;
      }
      return true;
    },
    
    handleIconSuccess(res, file) {
      console.log("上传成功响应:", res);
      if (res.code === 200) {
        this.form.icon = res.url;
        // 强制更新视图
        this.form = {...this.form};
        this.$modal.msgSuccess("图片上传成功");
      } else {
        this.$modal.msgError(res.msg || "图片上传失败");
      }
    },
    
    clearImage() {
      this.form.icon = null;
    },
    
    previewImage() {
      if (this.form.icon) {
        window.open(this.form.icon, '_blank');
      }
    },
  },
};
</script>

<style>
.el-upload--picture-card {
  width: 100px;
  height: 100px;
  line-height: 100px;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  cursor: pointer;
  position: relative;
  overflow: hidden;
}

.el-upload--picture-card:hover {
  border-color: #409EFF;
}

.el-upload-list--picture-card .el-upload-list__item {
  width: 100px;
  height: 100px;
}
</style>