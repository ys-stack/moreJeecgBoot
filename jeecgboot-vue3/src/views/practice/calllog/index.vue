<template>
  <div class="call-log-page">
    <!-- 顶部统计卡片 -->
    <div class="stat-cards">
      <a-card class="stat-card" :bordered="false">
        <a-statistic title="今日调用次数" :value="todayStat.callCount" suffix="次">
          <template #prefix><BarChartOutlined /></template>
        </a-statistic>
      </a-card>
      <a-card class="stat-card" :bordered="false">
        <a-statistic title="今日总Token" :value="todayStat.totalTokens" />
      </a-card>
      <a-card class="stat-card" :bordered="false">
        <a-statistic title="平均耗时" :value="todayStat.avgDurationMs" suffix="ms" :precision="0" />
      </a-card>
      <a-card class="stat-card" :bordered="false">
        <a-statistic title="今日费用" :value="todayStat.totalCost" prefix="¥" :precision="4" />
      </a-card>
    </div>

    <!-- 筛选 + 表格 -->
    <a-card :bordered="false" style="margin-top: 16px">
      <div class="table-toolbar">
        <a-space>
          <a-range-picker v-model:value="dateRange" @change="onDateChange" />
          <a-button type="primary" @click="loadData">
            <template #icon><SearchOutlined /></template>
            查询
          </a-button>
          <a-button @click="resetFilter">重置</a-button>
        </a-space>
        <a-space>
          <a-button @click="loadTodayStat">
            <template #icon><ReloadOutlined /></template>
            刷新统计
          </a-button>
        </a-space>
      </div>

      <a-table
        :columns="columns"
        :data-source="tableData"
        :loading="loading"
        :pagination="pagination"
        row-key="id"
        size="middle"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'status'">
            <a-tag :color="record.status === 'success' ? 'green' : 'red'">
              {{ record.status === 'success' ? '成功' : '失败' }}
            </a-tag>
          </template>
          <template v-if="column.dataIndex === 'bizType'">
            <a-tag color="blue">{{ record.bizType }}</a-tag>
          </template>
          <template v-if="column.dataIndex === 'durationMs'">
            {{ record.durationMs }}ms
          </template>
          <template v-if="column.dataIndex === 'costEstimate'">
            ¥{{ record.costEstimate?.toFixed(4) || '0.0000' }}
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 按模型分组统计 -->
    <a-card title="各模型调用统计" :bordered="false" style="margin-top: 16px">
      <a-table
        :columns="modelColumns"
        :data-source="modelStats"
        :pagination="false"
        row-key="modelName"
        size="middle"
        :loading="modelLoading"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'totalCost'">
            ¥{{ record.totalCost?.toFixed(4) || '0.0000' }}
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue';
import { BarChartOutlined, SearchOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { defHttp } from '/@/utils/http/axios';

const API_BASE = '/ai/callLog';

// ===== 今日统计（字段对应 AiCallStatVO） =====
const todayStat = reactive({
  callCount: 0,
  totalTokens: 0,
  avgDurationMs: 0,
  totalCost: 0,
  successCount: 0,
  failCount: 0,
});

async function loadTodayStat() {
  try {
    const res = await defHttp.get({ url: `${API_BASE}/stat/today` });
    Object.assign(todayStat, res);
  } catch (e) {
    console.error('加载今日统计失败', e);
  }
}

// ===== 表格 =====
const loading = ref(false);
const tableData = ref<any[]>([]);
const pagination = reactive({ current: 1, pageSize: 10, total: 0 });
const dateRange = ref<any[]>([]);

const columns = [
  { title: '调用时间', dataIndex: 'createTime', width: 180 },
  { title: '业务类型', dataIndex: 'bizType', width: 100 },
  { title: '模型', dataIndex: 'modelName', width: 160 },
  { title: '状态', dataIndex: 'status', width: 80 },
  { title: '输入Token', dataIndex: 'promptTokens', width: 100 },
  { title: '输出Token', dataIndex: 'completionTokens', width: 100 },
  { title: '耗时', dataIndex: 'durationMs', width: 100 },
  { title: '费用', dataIndex: 'costEstimate', width: 100 },
  { title: '用户', dataIndex: 'userName', width: 100 },
];

async function loadData() {
  loading.value = true;
  try {
    const params: any = {
      pageNo: pagination.current,
      pageSize: pagination.pageSize,
    };
    if (dateRange.value && dateRange.value.length === 2) {
      params.createTime_begin = dateRange.value[0].format('YYYY-MM-DD');
      params.createTime_end = dateRange.value[1].format('YYYY-MM-DD');
    }
    const res = await defHttp.get({ url: `${API_BASE}/list`, params });
    tableData.value = res.records || [];
    pagination.total = res.total || 0;
  } catch (e) {
    console.error('加载日志列表失败', e);
  } finally {
    loading.value = false;
  }
}

function onTableChange(pag: any) {
  pagination.current = pag.current;
  pagination.pageSize = pag.pageSize;
  loadData();
}

function onDateChange() {
  pagination.current = 1;
  loadData();
}

function resetFilter() {
  dateRange.value = [];
  pagination.current = 1;
  loadData();
}

// ===== 按模型统计 =====
const modelLoading = ref(false);
const modelStats = ref<any[]>([]);
const modelColumns = [
  { title: '模型名称', dataIndex: 'modelName' },
  { title: '调用次数', dataIndex: 'callCount' },
  { title: '总Token', dataIndex: 'totalTokens' },
  { title: '总费用', dataIndex: 'totalCost' },
  { title: '成功次数', dataIndex: 'successCount' },
  { title: '失败次数', dataIndex: 'failCount' },
];

async function loadModelStat() {
  modelLoading.value = true;
  try {
    const res = await defHttp.get({ url: `${API_BASE}/stat/byModel` });
    modelStats.value = res || [];
  } catch (e) {
    console.error('加载模型统计失败', e);
  } finally {
    modelLoading.value = false;
  }
}

onMounted(() => {
  loadTodayStat();
  loadData();
  loadModelStat();
});
</script>

<style scoped lang="less">
.call-log-page {
  padding: 16px;
}

.stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}

.stat-card {
  text-align: center;
}

.table-toolbar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16px;
}
</style>
