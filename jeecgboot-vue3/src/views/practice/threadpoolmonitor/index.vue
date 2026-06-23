<template>
  <div class="threadpool-monitor">
    <!-- 头部 -->
    <div class="page-header">
      <h1>
        <DashboardOutlined />
        线程池监控
      </h1>
      <p>实时查看 practice 模块线程池运行状态</p>
    </div>

    <!-- 操作栏 -->
    <div class="action-bar" style="margin-bottom: 16px">
      <a-space>
        <a-button type="primary" @click="fetchMetrics" :loading="loading">
          <ReloadOutlined /> 刷新
        </a-button>
        <a-switch v-model:checked="autoRefresh" checked-children="自动刷新" un-checked-children="手动" @change="toggleAutoRefresh" />
        <a-tag v-if="lastUpdate" color="blue">上次更新: {{ lastUpdate }}</a-tag>
      </a-space>
    </div>

    <!-- 线程池卡片 -->
    <a-row :gutter="16">
      <a-col :span="12" v-for="(metrics, poolName) in poolData" :key="poolName">
        <a-card :title="poolLabel(poolName)" :bordered="false" style="margin-bottom: 16px">
          <template #extra>
            <a-badge :status="metrics.isShutdown ? 'error' : 'processing'" :text="metrics.isShutdown ? '已关闭' : '运行中'" />
          </template>

          <!-- 核心指标 -->
          <a-row :gutter="[16, 16]">
            <a-col :span="8">
              <a-statistic title="活跃线程" :value="metrics.activeCount" :suffix="'/ ' + metrics.corePoolSize" />
            </a-col>
            <a-col :span="8">
              <a-statistic title="存活线程" :value="metrics.poolSize" :suffix="'/ ' + metrics.maxPoolSize" />
            </a-col>
            <a-col :span="8">
              <a-statistic title="历史峰值" :value="metrics.largestPoolSize" />
            </a-col>
          </a-row>

          <!-- 队列 -->
          <a-divider style="margin: 12px 0" />
          <a-row :gutter="[16, 16]">
            <a-col :span="12">
              <a-statistic title="等待任务" :value="metrics.queueSize" :suffix="'/ ' + metrics.queueCapacity" />
            </a-col>
            <a-col :span="12">
              <a-progress
                :percent="queuePercent(metrics)"
                :status="queuePercent(metrics) > 80 ? 'exception' : 'normal'"
                :stroke-width="12"
              />
            </a-col>
          </a-row>

          <!-- 累计统计 -->
          <a-divider style="margin: 12px 0" />
          <a-row :gutter="[16, 16]">
            <a-col :span="6">
              <a-statistic title="已提交" :value="metrics.totalSubmitted" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="已完成" :value="metrics.totalCompleted" value-style="color: #3f8600" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="失败" :value="metrics.totalFailed" :value-style="{ color: metrics.totalFailed > 0 ? '#cf1322' : '#3f8600' }" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="拒绝" :value="metrics.totalRejected" :value-style="{ color: metrics.totalRejected > 0 ? '#cf1322' : '#3f8600' }" />
            </a-col>
          </a-row>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { DashboardOutlined, ReloadOutlined } from '@ant-design/icons-vue';
import { defHttp } from '/@/utils/http/axios';
import { message as antMessage } from 'ant-design-vue';

interface PoolMetrics {
  poolName: string;
  corePoolSize: number;
  maxPoolSize: number;
  activeCount: number;
  poolSize: number;
  largestPoolSize: number;
  queueSize: number;
  queueCapacity: number;
  totalSubmitted: number;
  totalCompleted: number;
  totalFailed: number;
  totalRejected: number;
  isShutdown: boolean;
  isTerminated: boolean;
}

const API_BASE = '/practice/threadpool/metrics';

const loading = ref(false);
const autoRefresh = ref(true);
const lastUpdate = ref('');
const poolData = ref<Record<string, PoolMetrics>>({});

let timer: ReturnType<typeof setInterval> | null = null;

function poolLabel(name: string): string {
  const map: Record<string, string> = {
    stream: 'Stream 流式线程池',
    async: 'Async 异步线程池',
  };
  return map[name] || name;
}

function queuePercent(m: PoolMetrics): number {
  if (!m.queueCapacity) return 0;
  return Math.round((m.queueSize / m.queueCapacity) * 100);
}

async function fetchMetrics() {
  loading.value = true;
  try {
    const res = await defHttp.get({ url: API_BASE });
    poolData.value = res;
    lastUpdate.value = new Date().toLocaleTimeString();
  } catch (e: any) {
    antMessage.error('获取监控数据失败: ' + (e.message || '未知错误'));
  } finally {
    loading.value = false;
  }
}

function toggleAutoRefresh(checked: boolean) {
  if (checked) {
    timer = setInterval(fetchMetrics, 3000);
  } else {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }
}

onMounted(() => {
  fetchMetrics();
  if (autoRefresh.value) {
    timer = setInterval(fetchMetrics, 3000);
  }
});

onUnmounted(() => {
  if (timer) {
    clearInterval(timer);
  }
});
</script>

<style scoped lang="less">
.threadpool-monitor {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  margin-bottom: 24px;

  h1 {
    font-size: 22px;
    font-weight: 600;
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 6px 0;
  }

  p {
    color: #8c8c8c;
    font-size: 14px;
    margin: 0;
  }
}

.action-bar {
  display: flex;
  align-items: center;
}
</style>
