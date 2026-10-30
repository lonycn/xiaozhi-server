<template>
  <div class="welcome admin-page">
    
    <div class="main-wrapper">
      <div class="content-panel">
        <div class="content-area">
          <el-card class="provider-card" shadow="never">
            <div class="operation-header">
              <h2 class="page-title">{{ $t('header.providerManagement') }}</h2>
              <div class="right-operations">
                <el-input :placeholder="$t('providerManagement.searchPlaceholder')" v-model="searchName" class="search-input"
                  @keyup.enter="handleSearch" clearable />
                  <el-dropdown trigger="click" @command="handleSelectModelType" @visible-change="handleDropdownVisibleChange">
                    <CustomButton>
                      {{ $t('providerManagement.categoryFilter') }}{{ selectedModelTypeLabel }}<LegacyIcon class="el-icon-arrow-down el-icon--right"
                        :class="{ 'rotate-down': DropdownVisible }"></LegacyIcon>
                    </CustomButton>
                    <template #dropdown><el-dropdown-menu >
                      <el-dropdown-item command="">{{ $t('common.all') }}</el-dropdown-item>
                      <el-dropdown-item v-for="item in translatedModelTypes" :key="item.value" :command="item.value">
                        {{ item.label }}
                      </el-dropdown-item>
                    </el-dropdown-menu></template>
                  </el-dropdown>
                <CustomButton icon="el-icon-search" type="confirm" @click="handleSearch">{{ $t('common.search') }}</CustomButton>
              </div>
            </div>
            <CustomTable
              ref="providersTable"
              :data="filteredProvidersList"
              :columns="tableColumns"
              :loading="loading"
              :show-selection="true"
              :show-operations="true"
              :operations-label="$t('common.action')"
              :total="total"
              :current-page="currentPage"
              :page-size="pageSize"
              :page-size-options="pageSizeOptions"
              @size-change="handlePageSizeChange"
              @page-change="goToPage"
            >
              <template #selection="scope" >
                <el-checkbox v-model="scope.row.selected"></el-checkbox>
              </template>
              <template #modelType="scope" >
                <el-tag :type="getModelTypeTag(scope.row.modelType)">
                  {{ getModelTypeLabel(scope.row.modelType) }}
                </el-tag>
              </template>
              <template #fields="scope" >
                <el-popover placement="top-start" width="400" trigger="hover">
                  <div v-for="field in scope.row.fields" :key="field.key" class="field-item">
                    <span class="field-label">{{ field.label }}:</span>
                    <span class="field-type">{{ field.type }}</span>
                    <span v-if="isSensitiveField(field.key)" class="sensitive-tag">{{ $t('common.sensitive') }}</span>
                  </div>
                  <template #reference><el-button  size="small" link type="primary">{{ $t('providerManagement.viewFields') }}</el-button></template>
                </el-popover>
              </template>
              <template #operations="scope" >
                <el-button size="small" link type="primary" @click="editProvider(scope.row)">{{ $t('common.edit') }}</el-button>
                <el-button size="small" link type="primary" @click="deleteProvider(scope.row)">{{ $t('common.delete') }}</el-button>
              </template>
              <template #footer-btns>
                <div class="ctrl_btn">
                  <CustomButton :icon="isAllSelected ? 'el-icon-circle-close' : 'el-icon-circle-check'" size="small" @click="handleSelectAll">
                    {{ isAllSelected ? $t('common.deselectAll') : $t('common.selectAll') }}
                  </CustomButton>
                  <CustomButton type="add" icon="el-icon-plus" size="small" @click="showAddDialog">
                    {{ $t('common.add') }}
                  </CustomButton>
                  <CustomButton size="small" type="delete" icon="el-icon-delete" @click="deleteSelectedProviders">
                    {{ $t('common.delete') }}
                  </CustomButton>
                </div>
              </template>
            </CustomTable>
          </el-card>
        </div>
      </div>
    </div>

    <!-- 新增/编辑供应器对话框 -->
    <provider-dialog :title="dialogTitle" v-model:visible="dialogVisible" :form="providerForm" :model-types="modelTypes"
      @submit="handleSubmit" @cancel="dialogVisible = false" />

    
  </div>
</template>

<script>
import Api from "@/apis/api";
import ProviderDialog from "@/components/ProviderDialog.vue";
import CustomButton from "@/components/CustomButton.vue";
import CustomTable from "@/components/CustomTable.vue";

export default {
  components: { ProviderDialog, CustomButton, CustomTable },
  data() {
    return {
      searchName: "",
      searchModelType: "",
      providersList: [],
      modelTypes: [
        { value: "ASR", labelKey: 'providerManagement.modelType.ASR' },
        { value: "TTS", labelKey: 'providerManagement.modelType.TTS' },
        { value: "LLM", labelKey: 'providerManagement.modelType.LLM' },
        { value: "VLLM", labelKey: 'providerManagement.modelType.VLLM' },
        { value: "Intent", labelKey: 'providerManagement.modelType.Intent' },
        { value: "Memory", labelKey: 'providerManagement.modelType.Memory' },
        { value: "VAD", labelKey: 'providerManagement.modelType.VAD' },
        { value: "Plugin", labelKey: 'providerManagement.modelType.Plugin' },
        { value: "RAG", labelKey: 'providerManagement.modelType.RAG' }
      ],
      currentPage: 1,
      loading: false,
      pageSize: 10,
      pageSizeOptions: [10, 20, 50, 100],
      total: 0,
      dialogVisible: false,
      dialogTitle: "新增供应器",
      isAllSelected: false,
      DropdownVisible: false,
      sensitive_keys: ["api_key", "personal_access_token", "access_token", "token", "secret", "access_key_secret", "secret_key"],
      providerForm: {
        id: null,
        modelType: "",
        providerCode: "",
        name: "",
        fields: [],
        sort: 0
      },
      tableColumns: []
    };
  },
  created() {
    this.initTableColumns();
    this.fetchProviders();
  },
  computed: {
    translatedModelTypes() {
      return this.modelTypes.map(type => ({
        value: type.value,
        label: this.$t(type.labelKey)
      }));
    },
    selectedModelTypeLabel() {
      if (!this.searchModelType) return `（${this.$t('providerManagement.all')}）`;
      const selectedType = this.modelTypes.find(item => item.value === this.searchModelType);
      return selectedType ? `（${this.$t(selectedType.labelKey)}）` : "";
    },
    filteredProvidersList() {
      return this.providersList;
    }
  },
  methods: {
    initTableColumns() {
      this.tableColumns = [
        {
          prop: 'modelType',
          label: this.$t('providerManagement.category'),
          align: 'center',
          width: 200
        },
        {
          prop: 'providerCode',
          label: this.$t('providerManagement.providerCode'),
          align: 'center',
          width: 150
        },
        {
          prop: 'name',
          label: this.$t('common.name'),
          align: 'center'
        },
        {
          prop: 'fields',
          label: this.$t('providerManagement.fieldConfig'),
          align: 'center'
        },
        {
          prop: 'sort',
          label: this.$t('common.sort'),
          align: 'center',
          width: 80
        }
      ];
    },
    fetchProviders() {
      this.loading = true;

      Api.model.getModelProvidersPage(
        {
          page: this.currentPage,
          limit: this.pageSize,
          name: this.searchName,
          modelType: this.searchModelType
        },
        ({ data }) => {
          this.loading = false;
          if (data.code === 0) {
            this.providersList = data.data.list.map(item => {
              return {
                ...item,
                selected: false,
                fields: JSON.parse(item.fields)
              };
            });
            this.total = data.data.total;
          } else {
            this.$message.error({
              message: data.msg || '获取参数列表失败'
            });
          }
        }
      );
    },
    handleSearch() {
      this.currentPage = 1;
      this.fetchProviders();
    },
    handleSelectModelType(value) {
      this.searchModelType = value;
      this.handleSearch();
    },
    handleSelectAll() {
      this.isAllSelected = !this.isAllSelected;
      this.providersList.forEach(row => {
        row.selected = this.isAllSelected;
      });
    },
    showAddDialog() {
      this.dialogTitle = this.$t('common.addProvider');
      this.providerForm = {
        id: null,
        modelType: "",
        providerCode: "",
        name: "",
        fields: [],
        sort: 0
      };
      this.dialogVisible = true;
    },
    editProvider(row) {
      this.dialogTitle = this.$t('common.editProvider');
      this.providerForm = {
        ...row,
        fields: JSON.parse(JSON.stringify(row.fields))
      };
      this.dialogVisible = true;
    },
    handleSubmit({ form, done }) {
      if (form.id) {
        Api.model.updateModelProvider(form, ({ data }) => {
          if (data.code === 0) {
            this.fetchProviders();
            this.$message.success({
              message: this.$t('common.updateSuccess'),
              showClose: true
            });
            this.dialogVisible = false;
          } else {
            this.$message.error({
              message: data.msg || this.$t('common.updateFailure'),
              showClose: true
            });
          }
          done && done();
        });
      } else {
        Api.model.addModelProvider(form, ({ data }) => {
          if (data.code === 0) {
            this.fetchProviders();
            this.$message.success({
              message: this.$t('common.addSuccess'),
              showClose: true
            });
            this.total += 1;
            this.dialogVisible = false;
          } else {
            this.$message.error({
              message: data.msg || this.$t('common.addFailure'),
              showClose: true
            });
          }
          done && done();
        });
      }
    },
    deleteSelectedProviders() {
      const selectedRows = this.providersList.filter(row => row.selected);
      if (selectedRows.length === 0) {
        this.$message.warning({
          message: this.$t('providerManagement.selectToDelete'),
          showClose: true
        });
        return;
      }
      this.deleteProvider(selectedRows);
    },
    deleteProvider(row) {
      const providers = Array.isArray(row) ? row : [row];
      const providerCount = providers.length;

      this.$confirm(this.$t('providerManagement.confirmDelete', { count: providerCount }), this.$t('common.warning'), {
        confirmButtonText: this.$t('common.confirm'),
        cancelButtonText: this.$t('common.cancel'),
        type: 'warning',
      }).then(() => {
        const ids = providers.map(provider => provider.id);
        Api.model.deleteModelProviderByIds(ids, ({ data }) => {
          if (data.code === 0) {
            this.isAllSelected = false;
            this.fetchProviders();
            this.$message.success({
              message: this.$t('common.deleteSuccess'),
              showClose: true
            });
          } else {
            this.$message.error({
              message: data.msg || this.$t('common.deleteFailure'),
              showClose: true
            });
          }
        });
      }).catch(() => {
        this.$message({
          type: 'info',
          message: this.$t('common.deleteCancelled'),
          showClose: true,
          duration: 1000
        });
      });
    },
    getModelTypeTag(type) {
      const typeMap = {
        'ASR': 'success',
        'TTS': 'warning',
        'LLM': 'danger',
        'Intent': 'info',
        'Memory': '',
        'VAD': 'primary',
        'RAG': 'warning'
      };
      return typeMap[type] || '';
    },
    getModelTypeLabel(type) {
      const typeItem = this.modelTypes.find(item => item.value === type);
      return typeItem ? this.$t(typeItem.labelKey) : type;
    },
    isSensitiveField(fieldKey) {
      if (typeof fieldKey !== 'string') return false;
      return this.sensitive_keys.some(key =>
        fieldKey.toLowerCase().includes(key.toLowerCase())
      );
    },
    handlePageSizeChange(val) {
      this.pageSize = val;
      this.currentPage = 1;
      this.fetchProviders();
    },
    goToPage(page) {
      if (page !== this.currentPage) {
        this.currentPage = page;
        this.fetchProviders();
      }
    },
    handleDropdownVisibleChange(visible) {
      this.DropdownVisible = visible;
    }
  }
};
</script>

<style lang="scss" scoped>
.welcome {
  min-width: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  position: relative;
  flex-direction: column;
  background-size: cover;
  background: #eff4ff;
  -webkit-background-size: cover;
  -o-background-size: cover;
  overflow: hidden;
}

.main-wrapper {
  // 顶部 63px 底部 35px
  flex: 1;
  min-height: 0;
  height: auto;
  padding: 20px 22px 0;
  position: relative;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}

.operation-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 16px 0;
}

.page-title {
  font-weight: 500;
  font-size: 24px;
  margin: 0;
}

.right-operations {
  display: flex;
  gap: 10px;
  margin-left: auto;
  align-items: center;
}

.search-input {
  width: 240px;
}

.content-panel {
  display: flex;
  overflow: hidden;
  height: 100%;
  border-radius: 15px;
  background: transparent;
  border: 1px solid #fff;
}

.content-area {
  flex: 1;
  height: 100%;
  min-width: 0;
  overflow: auto;
  background-color: white;
  display: flex;
  flex-direction: column;
}

.provider-card {
  background: white;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: none;
  box-shadow: none;
  overflow: hidden;

  :deep(.el-card__body) {
    padding: 14px 20px;
    display: flex;
    flex-direction: column;
    flex: 1;
    overflow: hidden;
  }
}

.ctrl_btn {
  display: flex;
}

.field-item {
  padding: 5px 0;
  border-bottom: 1px solid #eee;
  display: flex;
  align-items: center;

  .field-label {
    flex: 1;
    font-weight: bold;
  }

  .field-type {
    width: 80px;
    color: #666;
  }

  .sensitive-tag {
    margin-left: 10px;
    color: #f56c6c;
    font-size: 12px;
  }
}

.rotate-down {
  transform: rotate(180deg);
  transition: transform 0.3s ease;
}

.el-icon-arrow-down {
  transition: transform 0.3s ease;
}

:deep(.el-table .el-button.is-link) {
  color: #7079aa;
}

:deep(.el-table .el-button.is-link:hover) {
  color: #5a64b5;
}
</style>

<style scoped>
.admin-page { min-width: 0; min-height: 0; width: 100%; height: 100%; background: transparent; }
.admin-page .operation-header, .admin-page .operation-bar { flex-wrap: wrap; gap: 12px; }
.admin-page .right-operations { flex-wrap: wrap; }
.admin-page .page-title { font-size: 20px; font-weight: 600; color: #263445; }
.admin-page .main-wrapper { min-height: 0; min-width: 0; flex: 1; }
.admin-page .content-panel, .admin-page .content-area { min-width: 0; min-height: 0; }
.admin-page .content-panel { border-radius: 6px; border: 1px solid #e5e9ef; background: #fff; }
@media (max-width: 760px) {
  .admin-page .right-operations { margin-left: 0; width: 100%; }
  .admin-page .search-input { max-width: 100%; }
}
</style>
