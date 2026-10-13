<template>
  <div class="app-container">
    <el-form :model="queryParams" ref="queryForm" size="small" :inline="true" v-show="showSearch" label-width="68px">

      <el-form-item label="显示类型" prop="showType">
        <DictRadio v-model="queryParams.showType" :radioData="dict.type.app_banner_show_type" size="small"
          :show-all="'all'"  @change="handleQuery"></DictRadio>
      </el-form-item>
     
      <!-- <el-form-item>
        <el-button type="primary" icon="el-icon-search" size="mini" @click="handleQuery">搜索</el-button>
        <el-button icon="el-icon-refresh" size="mini" @click="resetQuery">重置</el-button>
      </el-form-item> -->
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="el-icon-plus" size="mini" @click="handleAdd"
          v-hasPermi="['system:banner:add']">新增</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="success" plain icon="el-icon-edit" size="mini" :disabled="single" @click="handleUpdate"
          v-hasPermi="['system:banner:edit']">修改</el-button>
      </el-col>
      <el-col :span="1.5">
        <el-button type="danger" plain icon="el-icon-delete" size="mini" :disabled="multiple" @click="handleDelete"
          v-hasPermi="['system:banner:remove']">删除</el-button>
      </el-col>
    </el-row>

    <el-table v-loading="loading" :data="tbannerList" @selection-change="handleSelectionChange" border>
      <el-table-column type="selection" width="55" align="center" />
      <el-table-column label="显示类型" align="center" prop="showType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.app_banner_show_type" :value="scope.row.showType" />
        </template>
      </el-table-column>
      <el-table-column label="点击类型" align="center" prop="linkType">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.app_banner_event_type" :value="scope.row.linkType" />
        </template>
      </el-table-column>
      <el-table-column label="图片地址" align="center" prop="picUrl">
        <template slot-scope="scope">
          <el-image style="width: 200px; height: 80px" :src="scope.row.picUrl" :preview-src-list="srcList"
            @click="clickPicture(scope.row)">
          </el-image>
        </template>
      </el-table-column>
      <el-table-column label="排序" align="center" prop="sortId" />
      <el-table-column label="状态" align="center" prop="status">
        <template slot-scope="scope">
          <dict-tag :options="dict.type.sys_business_status" :value="scope.row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" align="center" class-name="small-padding fixed-width" fixed="left">
        <template slot-scope="scope">
          <el-button size="mini" type="text" icon="el-icon-edit" @click="handleUpdate(scope.row)"
            v-hasPermi="['system:banner:edit']">修改</el-button>
          <el-button size="mini" type="text" icon="el-icon-delete" @click="handleDelete(scope.row)"
            v-hasPermi="['system:banner:remove']">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <pagination v-show="total > 0" :total="total" :page.sync="queryParams.pageNum" :limit.sync="queryParams.pageSize"
      @pagination="getList" />

    <!-- 添加或修改轮播图对话框 -->
    <el-dialog :title="title" :visible.sync="open" width="850px" append-to-body :close-on-click-modal="false">
      <el-form ref="form" :model="form" :rules="rules" label-width="150px">
        <el-form-item label="显示类型" prop="showType">
          <el-select v-model="form.showType" placeholder="请选择显示类型">
            <el-option v-for="dict in dict.type.app_banner_show_type" :key="dict.value" :label="dict.label"
              :value="dict.value"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="点击类型" prop="linkType">
          <el-select v-model="form.linkType" placeholder="请选择点击类型" @change="onTypeChange($event)">
            <el-option v-for="dict in dict.type.app_banner_event_type" :key="dict.value" :label="dict.label"
              :value="dict.value"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item :label="form.linkType == 2 ? '游戏ID' : dataTitle" prop="dataId"
          v-if="form.linkType == 2" >
          <el-input v-model="form.dataId" />
        </el-form-item>
        <!-- <el-form-item :label="getLabel(form.linkType)"  prop="dataId"
          v-if="[2, 3, 4, 5, 6].includes(form.linkType)">
          <el-input v-model="form.dataId" />
        </el-form-item> -->
        <el-form-item label="图片" prop="picUrl">
          <el-upload :action="uploadFileUrl" accept="image/jpeg,image/gif,image/png" list-type="picture-card"
            :on-preview="handlePictureCardPreview" v-model="form.picUrl" :headers="headers" :on-success="handleSuccess"
            :on-change="onUploadChange" :show-file-list="false" :on-remove="handleRemove" :class="{ hide: hideUpload }">
            <img class="el-upload-list__item-thumbnail" style="width:200px;height:80px" v-if="form.picUrl"
              :src="form.picUrl">
            <i v-else slot="default" class="el-icon-plus"></i>
          </el-upload>
        </el-form-item>
        <!-- <el-form-item label="服务类型" class="is-required" prop="url" v-if="form.type == 2">
          <el-select v-model="form.url" placeholder="请选择" style="width: 100%;">
            <el-option v-for="item in dict.type.service_type" :key="item.id" :label="item.label" :value="item.value">
            </el-option>
          </el-select>
        </el-form-item> -->
        <el-form-item label="详细内容" prop="contents" v-if="form.linkType == 1">
          <editor v-model="form.contents" :min-height="300" />
        </el-form-item>
        <el-row>
          <el-col :span="12">
            <el-form-item label="排序" prop="sortId">
              <el-input-number v-model="form.sortId" controls-position="right" :min="0" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-radio-group v-model="form.status">
                <el-radio v-for="dict in dict.type.sys_business_status" :key="dict.value" :label="dict.value">{{
                  dict.label }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <div slot="footer" class="dialog-footer">
        <el-button type="primary" @click="submitForm">确 定</el-button>
        <el-button @click="cancel">取 消</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script>
import { listBanner, getBanner, delBanner, addBanner, updateBanner } from "@/api/system/banner";
import {
  getToken
} from "@/utils/auth";
export default {
  name: "Banner",
  dicts: ['sys_normal_disable', 'app_banner_event_type', 'sys_business_status', 'app_banner_show_type'],
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
      // 总条数
      total: 0,
      // 轮播图表格数据
      tbannerList: [],
      // 弹出层标题
      title: "",
      // 是否显示弹出层
      open: false,
      // 查询参数
      queryParams: {
        pageNum: 1,
        pageSize: 10,
        type: undefined,
        picUrl: undefined,
        contents: undefined,
        sortId: undefined,
        status: undefined,
      },
      // 表单参数
      form: {},
      // 表单校验
      rules: {
        showType: [{
          required: true,
          message: "显示类型必须选择",
          trigger: "blur"
        }],
        linkType: [{
          required: true,
          message: "点击类型必须选择",
          trigger: "blur"
        }],
        picUrl: [{
          required: true,
          message: "请选择轮播图片",
          trigger: "blur"
        }],
      },
      uploadFileUrl: process.env.VUE_APP_BASE_API + "/api/common/uploadOSS",
      headers: {
        Authorization: "Bearer " + getToken(),
      },
      hideUpload: false,
      limitCount: 1,
      srcList: [

      ],
      dataTitle: '游戏ID'
    };
  },
  created() {
    this.getList();
  },
  methods: {
    getLabel(linkType) {
      console.log('getLabel called with:', linkType); // 改用 console.log 调试
      switch(linkType) {
        case 2: return '游戏ID';
        case 3:
        case 4: return '商品ID';
        default: return '';
      }
   },
    /** 查询轮播图列表 */
    getList() {
      this.loading = true;
      listBanner(this.queryParams).then(response => {
        this.tbannerList = response.rows;
        this.total = response.total;
        this.loading = false;
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
        type: "0",
        linkType: '0',
        picUrl: undefined,
        contents: undefined,
        sortId: undefined,
        status: "0",
      };
      this.resetForm("form");
    },
    /** 搜索按钮操作 */
    handleQuery() {
      this.queryParams.pageNum = 1;
      this.getList();
    },
    /** 重置按钮操作 */
    resetQuery() {
      this.resetForm("queryForm");
      this.handleQuery();
    },
    // 多选框选中数据
    handleSelectionChange(selection) {
      this.ids = selection.map(item => item.id)
      this.single = selection.length !== 1
      this.multiple = !selection.length
    },
    /** 新增按钮操作 */
    handleAdd() {
      this.reset();
      this.open = true;
      this.title = "添加轮播图";
    },
    /** 修改按钮操作 */
    handleUpdate(row) {
      this.reset();
      const id = row.id || this.ids
      getBanner(id).then(response => {
        this.form = response.data;
        this.form.showType = response.data.showType + ''
        this.form.linkType = response.data.linkType + ''
        this.form.status = response.data.status + ''
        this.open = true;
        this.title = "修改轮播图";
      });
    },
    /** 提交按钮 */
    submitForm() {
      this.$refs["form"].validate(valid => {

        if (this.form.type == 2) {
          if (this.form.dataId == null || this.form.dataId == '') {
            this.$message.error("请输入游戏ID")
            return false
          }
        }

        if (this.form.picUrl == null || this.form.picUrl == '') {
          this.$message.error("请选择轮播图片")
          return false
        }

        if (valid) {
          if (this.form.id != null) {
            updateBanner(this.form).then(response => {
              this.$modal.msgSuccess("修改成功");
              this.open = false;
              this.getList();
            });
          } else {
            addBanner(this.form).then(response => {
              this.$modal.msgSuccess("新增成功");
              this.open = false;
              this.getList();
            });
          }
        }
      });
    },
    /** 删除按钮操作 */
    handleDelete(row) {
      const ids = row.id || this.ids;
      this.$modal.confirm('是否确认删除轮播图编号为"' + ids + '"的数据项？').then(function () {
        return delBanner(ids);
      }).then(() => {
        this.getList();
        this.$modal.msgSuccess("删除成功");
      }).catch(() => { });
    },
    onUploadChange(file, fileList) {
      this.hideUpload = fileList.length >= this.limitCount;
    },
    handleRemove(file, fileList) {
      this.hideUpload = fileList.length >= this.limitCount;
      this.fileList = fileList
      console.log(fileList);
    },
    handlePictureCardPreview(file) {
      this.dialogImageUrl = file.url;
      this.dialogVisible = true;
    },
    handleSuccess(res, file, fileList) {
      console.log(res)
      if (res.code === 200) {
        this.form.picUrl = res.url
      } else {
        this.$modal.msgError(res.msg);
      }
    },
    onTypeChange(e) {
      console.log(e)
      this.form.type = e
    },
    clickPicture(row) {
      var srclist = []
      srclist.push(row.picUrl)
      this.srcList = srclist
    }
  }
};
</script>

<style rel="stylesheet/scss" lang="scss">
.el-upload--picture-card {
  width: 200px;
  height: 80px;
  line-height: 80px
}

.el-upload-list--picture-card .el-upload-list__item {
  width: 200px;
  height: 80px;
}
</style>
