<template>
  <div class="tool-manage-page">
    <a-tabs v-model:activeKey="activeTab">
      <!-- ==================== Tab 1: 工具定义 ==================== -->
      <a-tab-pane key="definition" tab="工具定义">
        <a-card :bordered="false">
          <!-- 操作栏 -->
          <div class="table-toolbar">
            <a-space>
              <a-button type="primary" @click="loadDefinitions">
                <template #icon><ReloadOutlined /></template>
                刷新
              </a-button>
              <a-tag color="blue">共 {{ definitionData.length }} 个工具</a-tag>
              <a-tag color="green">启用 {{ definitionData.filter(d => d.status === 'active').length }} 个</a-tag>
            </a-space>
          </div>

          <a-table
            :columns="defColumns"
            :data-source="definitionData"
            :loading="defLoading"
            :pagination="false"
            row-key="id"
            size="middle"
            :expandedRowKeys="expandedKeys"
            @expand="onExpand"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'status'">
                <a-tag :color="record.status === 'active' ? 'green' : 'default'">
                  {{ record.status === 'active' ? '启用' : '禁用' }}
                </a-tag>
              </template>
              <template v-if="column.dataIndex === 'category'">
                <a-tag :color="categoryColor(record.category)">{{ record.category }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'endpointType'">
                <a-tag>{{ record.endpointType }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'isReadOnly'">
                <a-tag :color="record.isReadOnly ? 'blue' : 'orange'">
                  {{ record.isReadOnly ? '只读' : '写操作' }}
                </a-tag>
              </template>
              <template v-if="column.dataIndex === 'requireConfirm'">
                <a-tag v-if="record.requireConfirm" color="red">需确认</a-tag>
                <span v-else style="color: #999">—</span>
              </template>
            </template>

            <!-- 展开行：显示 description 和 parametersSchema -->
            <template #expandedRowRender="{ record }">
              <div class="expand-detail">
                <p><strong>工具描述：</strong>{{ record.description }}</p>
                <p><strong>处理器引用：</strong><code>{{ record.handlerRef }}</code></p>
                <p v-if="record.parametersSchema">
                  <strong>参数 Schema：</strong>
                </p>
                <pre v-if="record.parametersSchema" class="schema-pre">{{ formatJson(record.parametersSchema) }}</pre>
              </div>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>

      <!-- ==================== Tab 2: 调用日志 ==================== -->
      <a-tab-pane key="log" tab="调用日志">
        <a-card :bordered="false">
          <div class="table-toolbar">
            <a-space>
              <a-select
                v-model:value="logFilter.toolCode"
                placeholder="按工具筛选"
                allow-clear
                style="width: 200px"
                @change="loadLogs"
              >
                <a-select-option v-for="d in definitionData" :key="d.toolCode" :value="d.toolCode">
                  {{ d.toolName }} ({{ d.toolCode }})
                </a-select-option>
              </a-select>
              <a-select
                v-model:value="logFilter.status"
                placeholder="按状态筛选"
                allow-clear
                style="width: 140px"
                @change="loadLogs"
              >
                <a-select-option value="success">成功</a-select-option>
                <a-select-option value="error">失败</a-select-option>
                <a-select-option value="timeout">超时</a-select-option>
              </a-select>
              <a-button type="primary" @click="loadLogs">
                <template #icon><SearchOutlined /></template>
                查询
              </a-button>
              <a-button @click="resetLogFilter">重置</a-button>
            </a-space>
          </div>

          <a-table
            :columns="logColumns"
            :data-source="logData"
            :loading="logLoading"
            :pagination="logPagination"
            row-key="id"
            size="middle"
            @change="onLogTableChange"
            :expandedRowKeys="logExpandedKeys"
            @expand="onLogExpand"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'status'">
                <a-tag :color="logStatusColor(record.status)">{{ logStatusText(record.status) }}</a-tag>
              </template>
              <template v-if="column.dataIndex === 'durationMs'">
                {{ record.durationMs }}ms
              </template>
            </template>

            <!-- 展开行：显示入参和结果 -->
            <template #expandedRowRender="{ record }">
              <div class="expand-detail">
                <div class="detail-section">
                  <strong>调用入参：</strong>
                  <pre class="schema-pre">{{ formatJson(record.inputParams) }}</pre>
                </div>
                <div class="detail-section">
                  <strong>执行结果：</strong>
                  <pre class="schema-pre">{{ formatJson(record.outputResult) }}</pre>
                </div>
                <div v-if="record.errorMsg" class="detail-section">
                  <strong style="color: #ff4d4f">错误信息：</strong>
                  <span style="color: #ff4d4f">{{ record.errorMsg }}</span>
                </div>
              </div>
            </template>
          </a-table>
        </a-card>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue';
import { defHttp } from '/@/utils/http/axios';

const API_BASE = '/practice/tool';

// ==================== Tab 控制 ====================
const activeTab = ref('definition');

// ==================== 工具定义 ====================
const defLoading = ref(false);
const definitionData = ref<any[]>([]);
const expandedKeys = ref<string[]>([]);

const defColumns = [
  { title: '工具编码', dataIndex: 'toolCode', width: 160 },
  { title: '工具名称', dataIndex: 'toolName', width: 140 },
  { title: '分类', dataIndex: 'category', width: 90 },
  { title: '端点类型', dataIndex: 'endpointType', width: 120 },
  { title: '读写', dataIndex: 'isReadOnly', width: 80 },
  { title: '需确认', dataIndex: 'requireConfirm', width: 90 },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '超时(ms)', dataIndex: 'timeoutMs', width: 100 },
];

async function loadDefinitions() {
  defLoading.value = true;
  try {
    const res = await defHttp.get({ url: `${API_BASE}/definition/active` });
    definitionData.value = res || [];
  } catch (e) {
    console.error('加载工具定义失败', e);
  } finally {
    defLoading.value = false;
  }
}

function onExpand(expanded: boolean, record: any) {
  expandedKeys.value = expanded ? [record.id] : [];
}

function categoryColor(cat: string) {
  const map: Record<string, string> = { query: 'cyan', write: 'orange', notify: 'purple', system: 'default' };
  return map[cat] || 'default';
}

// ==================== 调用日志 ====================
const logLoading = ref(false);
const logData = ref<any[]>([]);
const logExpandedKeys = ref<string[]>([]);
const logPagination = reactive({ current: 1, pageSize: 10, total: 0 });
const logFilter = reactive<Record<string, any>>({ toolCode: undefined, status: undefined });

const logColumns = [
  { title: '调用时间', dataIndex: 'createTime', width: 180 },
  { title: '工具编码', dataIndex: 'toolCode', width: 140 },
  { title: '工具名称', dataIndex: 'toolName', width: 130 },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '耗时', dataIndex: 'durationMs', width: 100 },
  { title: '模型', dataIndex: 'modelName', width: 150 },
  { title: '会话ID', dataIndex: 'sessionId', width: 140, ellipsis: true },
];

async function loadLogs() {
  logLoading.value = true;
  try {
    const params: any = {
      pageNo: logPagination.current,
      pageSize: logPagination.pageSize,
    };
    if (logFilter.toolCode) params.toolCode = logFilter.toolCode;
    if (logFilter.status) params.status = logFilter.status;
    const res = await defHttp.get({ url: `${API_BASE}/log/list`, params });
    logData.value = res.records || [];
    logPagination.total = res.total || 0;
  } catch (e) {
    console.error('加载调用日志失败', e);
  } finally {
    logLoading.value = false;
  }
}

function onLogTableChange(pag: any) {
  logPagination.current = pag.current;
  logPagination.pageSize = pag.pageSize;
  loadLogs();
}

function onLogExpand(expanded: boolean, record: any) {
  logExpandedKeys.value = expanded ? [record.id] : [];
}

function resetLogFilter() {
  logFilter.toolCode = undefined;
  logFilter.status = undefined;
  logPagination.current = 1;
  loadLogs();
}

function logStatusColor(s: string) {
  return s === 'success' ? 'green' : s === 'error' ? 'red' : 'orange';
}
function logStatusText(s: string) {
  return s === 'success' ? '成功' : s === 'error' ? '失败' : s === 'timeout' ? '超时' : s;
}

// ==================== 工具函数 ====================
function formatJson(str: string | null | undefined): string {
  if (!str) return '（空）';
  try {
    return JSON.stringify(JSON.parse(str), null, 2);
  } catch {
    return str;
  }
}

onMounted(() => {
  loadDefinitions();
  loadLogs();
});
</script>

<style scoped lang="less">
.tool-manage-page {
  padding: 16px;
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}

.expand-detail {
  padding: 8px 16px;

  p {
    margin-bottom: 8px;
  }
}

.detail-section {
  margin-bottom: 12px;
}

.schema-pre {
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 8px 12px;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
