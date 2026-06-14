<template>
  <div class="page-container">
    <!-- 头部区域 -->
    <div class="chat-header">
      <div class="header-content">
        <h1 class="header-title">
          <RobotOutlined class="title-icon" />
          AI 需求分析助手
        </h1>
        <p class="header-subtitle">
          基于大语言模型的智能需求分析工具，帮助您快速梳理、分析和设计软件需求
        </p>
      </div>
    </div>

    <!-- 页面 Tab 切换 -->
    <div class="page-tabs">
      <a-tabs v-model:activeKey="activeTab" size="middle" class="inner-tabs">
        <a-tab-pane key="chat" tab="对话">
          <!-- 模式选择器 -->
          <div class="mode-selector">
            <a-radio-group v-model:value="chatMode" button-style="solid" size="middle">
              <a-radio-button value="normal">
                <MessageOutlined />
                普通对话
              </a-radio-button>
              <a-radio-button value="stream">
                <ThunderboltOutlined />
                流式输出
              </a-radio-button>
              <a-radio-button value="structured">
                <FileTextOutlined />
                需求分析
              </a-radio-button>
            </a-radio-group>
          </div>

          <!-- 聊天区域 -->
          <div class="chat-area" ref="chatAreaRef">
            <!-- 欢迎状态 -->
            <div v-if="messages.length === 0" class="welcome-state">
              <div class="welcome-icon">
                <BulbOutlined />
              </div>
              <h2 class="welcome-title">开始对话</h2>
              <p class="welcome-desc">选择一个示例问题，或直接输入您的需求</p>
              <div class="preset-questions">
                <div
                  v-for="(question, index) in presetQuestions"
                  :key="index"
                  class="question-card"
                  @click="handlePresetQuestion(question)"
                >
                  <RightOutlined class="question-icon" />
                  <span class="question-text">{{ question }}</span>
                </div>
              </div>
            </div>

            <!-- 消息列表 -->
            <div v-else class="message-list">
              <div
                v-for="(msg, index) in messages"
                :key="index"
                :class="['message-item', msg.role === 'user' ? 'message-user' : 'message-ai']"
              >
                <div v-if="msg.role === 'user'" class="message-bubble user-bubble">
                  <div class="message-content">{{ msg.content }}</div>
                </div>
                <div v-else class="message-bubble ai-bubble">
                  <div class="ai-avatar">
                    <RobotOutlined />
                  </div>
                  <div class="ai-content-wrapper">
                    <div class="message-content" v-html="formatContent(msg.content)"></div>
                    <div v-if="msg.metadata && !msg.loading" class="message-meta">
                      <a-tag color="blue">{{ msg.metadata.model }}</a-tag>
                      <a-tag>{{ msg.metadata.costMs }}ms</a-tag>
                      <a-tag color="green">Prompt: {{ msg.metadata.promptTokens }}</a-tag>
                      <a-tag color="orange">Completion: {{ msg.metadata.completionTokens }}</a-tag>
                    </div>
                    <div v-if="msg.loading" class="typing-indicator">
                      <span class="typing-dot"></span>
                      <span class="typing-dot"></span>
                      <span class="typing-dot"></span>
                    </div>
                  </div>
                </div>
              </div>
              <div ref="scrollAnchorRef" class="scroll-anchor"></div>
            </div>
          </div>

          <!-- 输入区域 -->
          <div class="input-area">
            <div class="input-wrapper">
              <a-textarea
                v-model:value="inputText"
                :auto-size="{ minRows: 1, maxRows: 4 }"
                placeholder="输入您的需求或问题... (Enter 发送, Shift+Enter 换行)"
                :disabled="isLoading"
                @keydown="handleKeyDown"
                class="chat-input"
              />
              <div class="input-actions">
                <a-button
                  type="primary"
                  :disabled="!inputText.trim() || isLoading"
                  :loading="isLoading"
                  @click="handleSend"
                  class="send-btn"
                >
                  <template #icon>
                    <SendOutlined />
                  </template>
                  发送
                </a-button>
                <a-button
                  @click="handleClear"
                  :disabled="messages.length === 0 || isLoading"
                  class="clear-btn"
                >
                  <DeleteOutlined />
                  清空
                </a-button>
              </div>
            </div>
          </div>
        </a-tab-pane>

        <a-tab-pane key="logs" tab="调用日志">
          <div class="log-panel">
            <!-- 今日概览统计卡片 -->
            <div class="stat-section">
              <h3 class="section-title">
                <DashboardOutlined />
                今日概览
                <a-button type="link" size="small" @click="loadTodayStats" :loading="statLoading">
                  <ReloadOutlined />
                  刷新
                </a-button>
              </h3>
              <div class="stat-cards">
                <div class="stat-card">
                  <div class="stat-value">{{ todayStats.callCount || 0 }}</div>
                  <div class="stat-label">调用次数</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value">{{ todayStats.totalTokens || 0 }}</div>
                  <div class="stat-label">总 Token</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value">{{ todayStats.avgDurationMs ? Number(todayStats.avgDurationMs).toFixed(0) : 0 }}<span class="stat-unit">ms</span></div>
                  <div class="stat-label">平均耗时</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value stat-success">{{ todayStats.successCount || 0 }}</div>
                  <div class="stat-label">成功</div>
                </div>
                <div class="stat-card">
                  <div class="stat-value stat-fail">{{ todayStats.failCount || 0 }}</div>
                  <div class="stat-label">失败</div>
                </div>
              </div>
            </div>

            <!-- 按模型统计 -->
            <div class="stat-section" v-if="modelStats.length > 0">
              <h3 class="section-title">
                <PieChartOutlined />
                模型调用分布
              </h3>
              <div class="model-stats">
                <div v-for="item in modelStats" :key="item.modelName" class="model-stat-row">
                  <span class="model-name">{{ item.modelName }}</span>
                  <span class="model-count">{{ item.callCount }} 次</span>
                  <span class="model-tokens">{{ item.totalTokens }} tokens</span>
                  <span class="model-avg">{{ item.avgDurationMs ? Number(item.avgDurationMs).toFixed(0) : 0 }}ms</span>
                </div>
              </div>
            </div>

            <!-- 最近调用日志表格 -->
            <div class="stat-section">
              <h3 class="section-title">
                <UnorderedListOutlined />
                最近调用记录
                <a-button type="link" size="small" @click="loadLogs" :loading="logLoading">
                  <ReloadOutlined />
                  刷新
                </a-button>
              </h3>
              <a-table
                :columns="logColumns"
                :data-source="logList"
                :loading="logLoading"
                :pagination="{ current: logPage, pageSize: 10, total: logTotal, onChange: onLogPageChange }"
                row-key="id"
                size="small"
                :scroll="{ x: 900 }"
                class="log-table"
              >
                <template #bodyCell="{ column, record }">
                  <template v-if="column.key === 'status'">
                    <a-tag :color="record.status === 'success' ? 'green' : 'red'">
                      {{ record.status === 'success' ? '成功' : '失败' }}
                    </a-tag>
                  </template>
                  <template v-if="column.key === 'durationMs'">
                    {{ record.durationMs ? record.durationMs + 'ms' : '-' }}
                  </template>
                  <template v-if="column.key === 'tokens'">
                    {{ (record.promptTokens || 0) + (record.completionTokens || 0) }}
                  </template>
                  <template v-if="column.key === 'responseBody'">
                    <span class="cell-ellipsis" :title="record.responseBody">
                      {{ record.responseBody ? record.responseBody.substring(0, 50) + (record.responseBody.length > 50 ? '...' : '') : '-' }}
                    </span>
                  </template>
                </template>
              </a-table>
            </div>
          </div>
        </a-tab-pane>
      </a-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, onMounted } from 'vue';
import { defHttp } from '/@/utils/http/axios';
import {
  RobotOutlined,
  MessageOutlined,
  ThunderboltOutlined,
  FileTextOutlined,
  BulbOutlined,
  RightOutlined,
  SendOutlined,
  DeleteOutlined,
  DashboardOutlined,
  PieChartOutlined,
  UnorderedListOutlined,
  ReloadOutlined,
} from '@ant-design/icons-vue';
import { message as antMessage } from 'ant-design-vue';

// ==================== 类型定义 ====================

interface MessageMetadata {
  model: string;
  costMs: number;
  requestId: string;
  promptTokens: number;
  completionTokens: number;
}

interface ChatMessage {
  role: 'user' | 'ai';
  content: string;
  loading?: boolean;
  metadata?: MessageMetadata;
}

interface PracticeChatResponse {
  content: string;
  model: string;
  costMs: number;
  requestId: string;
  promptTokens: number;
  completionTokens: number;
}

interface ApiResult {
  success: boolean;
  result?: PracticeChatResponse;
  message?: string;
}

type ChatMode = 'normal' | 'stream' | 'structured';

interface CallStat {
  callCount: number;
  totalPromptTokens: number;
  totalCompletionTokens: number;
  totalTokens: number;
  avgDurationMs: number;
  successCount: number;
  failCount: number;
  modelName?: string;
}

// ==================== 响应式状态 ====================

const activeTab = ref('chat');
const chatMode = ref<ChatMode>('normal');
const messages = ref<ChatMessage[]>([]);
const inputText = ref('');
const isLoading = ref(false);
const chatAreaRef = ref<HTMLDivElement | null>(null);
const scrollAnchorRef = ref<HTMLDivElement | null>(null);

// 日志相关状态
const statLoading = ref(false);
const logLoading = ref(false);
const todayStats = ref<Partial<CallStat>>({});
const modelStats = ref<CallStat[]>([]);
const logList = ref<any[]>([]);
const logPage = ref(1);
const logTotal = ref(0);

// ==================== 预设问题 ====================

const presetQuestions = [
  '做一个用户注册功能，支持邮箱和手机号注册',
  '设计一个订单管理系统的数据表结构',
  '分析一个在线教育平台的核心功能需求',
  '帮我设计一个RESTful API的用户管理接口',
];

// ==================== API 配置 ====================

const API_BASE = '/jeecgboot/practice/chat';
// defHttp 自动添加 /jeecgboot 前缀，这里不带
const LOG_API_BASE = '/ai/callLog';

const API_ENDPOINTS = {
  normal: `${API_BASE}/send`,
  stream: `${API_BASE}/stream`,
  structured: `${API_BASE}/structured`,
};

// ==================== 日志表格列定义 ====================

const logColumns = [
  { title: '模型', dataIndex: 'modelName', key: 'modelName', width: 130 },
  { title: '场景', dataIndex: 'bizType', key: 'bizType', width: 100 },
  { title: '状态', key: 'status', width: 70 },
  { title: '耗时', key: 'durationMs', width: 90 },
  { title: 'Token', key: 'tokens', width: 80 },
  { title: 'IP', dataIndex: 'clientIp', key: 'clientIp', width: 120 },
  { title: '接口', dataIndex: 'apiPath', key: 'apiPath', width: 180, ellipsis: true },
  { title: '回复摘要', key: 'responseBody', width: 200 },
  { title: '时间', dataIndex: 'createTime', key: 'createTime', width: 170 },
];

// ==================== Tab 切换时加载数据 ====================

watch(activeTab, (newTab) => {
  if (newTab === 'logs') {
    loadTodayStats();
    loadLogs();
  }
});

// ==================== 日志 API 调用 ====================

async function loadTodayStats(): Promise<void> {
  statLoading.value = true;
  try {
    const [todayData, modelData] = await Promise.all([
      defHttp.get({ url: `${LOG_API_BASE}/stat/today` }),
      defHttp.get({ url: `${LOG_API_BASE}/stat/byModel` }),
    ]);
    if (todayData) todayStats.value = todayData;
    if (modelData) modelStats.value = modelData;
  } catch (e) {
    console.error('加载统计失败', e);
  } finally {
    statLoading.value = false;
  }
}

async function loadLogs(): Promise<void> {
  logLoading.value = true;
  try {
    const data = await defHttp.get({ url: `${LOG_API_BASE}/list`, params: { pageNo: logPage.value, pageSize: 10 } });
    if (data) {
      logList.value = data.records || [];
      logTotal.value = data.total || 0;
    }
  } catch (e) {
    console.error('加载日志失败', e);
  } finally {
    logLoading.value = false;
  }
}

function onLogPageChange(page: number): void {
  logPage.value = page;
  loadLogs();
}

// ==================== 工具函数 ====================

function formatContent(content: string): string {
  if (!content) return '';
  let formatted = content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
  formatted = formatted.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) => {
    return `<pre class="code-block"><code class="language-${lang}">${code.trim()}</code></pre>`;
  });
  formatted = formatted.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');
  formatted = formatted.replace(/\n/g, '<br>');
  return formatted;
}

function scrollToBottom(): void {
  nextTick(() => {
    if (scrollAnchorRef.value) {
      scrollAnchorRef.value.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  });
}

function addUserMessage(content: string): void {
  messages.value.push({ role: 'user', content });
  scrollToBottom();
}

function addAiMessagePlaceholder(): number {
  const index = messages.value.length;
  messages.value.push({ role: 'ai', content: '', loading: true });
  scrollToBottom();
  return index;
}

function updateAiMessage(
  index: number,
  content: string,
  metadata?: MessageMetadata,
  loading: boolean = false,
): void {
  if (index >= 0 && index < messages.value.length) {
    messages.value[index] = { ...messages.value[index], content, metadata, loading };
    scrollToBottom();
  }
}

// ==================== 聊天 API 调用 ====================

async function sendNormalRequest(userMessage: string, messageIndex: number): Promise<void> {
  try {
    const response = await fetch(API_ENDPOINTS.normal, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: userMessage }),
    });
    if (!response.ok) throw new Error(`HTTP 错误: ${response.status}`);
    const data: ApiResult = await response.json();
    if (data.success && data.result) {
      const { content, model, costMs, requestId, promptTokens, completionTokens } = data.result;
      updateAiMessage(messageIndex, content, { model, costMs, requestId, promptTokens, completionTokens }, false);
    } else {
      updateAiMessage(messageIndex, `请求失败: ${data.message || '未知错误'}`, undefined, false);
    }
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : '网络请求失败';
    updateAiMessage(messageIndex, `错误: ${errorMsg}`, undefined, false);
    antMessage.error('请求失败，请稍后重试');
  }
}

async function sendStreamRequest(userMessage: string, messageIndex: number): Promise<void> {
  let accumulatedContent = '';
  try {
    const response = await fetch(API_ENDPOINTS.stream, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: userMessage }),
    });
    if (!response.ok) throw new Error(`HTTP 错误: ${response.status}`);
    if (!response.body) throw new Error('响应体为空');

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split('\n\n');
      buffer = events.pop() || '';

      for (const eventStr of events) {
        if (!eventStr.trim()) continue;
        let eventType = 'message';
        let eventData = '';
        const lines = eventStr.split('\n');
        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6).trim();
          else if (line.startsWith('data:')) eventData = line.slice(5).trim();
        }
        switch (eventType) {
          case 'message':
            accumulatedContent += eventData;
            updateAiMessage(messageIndex, accumulatedContent, undefined, true);
            break;
          case 'done':
            try {
              const metadata: MessageMetadata = JSON.parse(eventData);
              updateAiMessage(messageIndex, accumulatedContent, metadata, false);
            } catch {
              updateAiMessage(messageIndex, accumulatedContent, undefined, false);
            }
            break;
          case 'error':
            updateAiMessage(messageIndex, `流式输出错误: ${eventData}`, undefined, false);
            break;
        }
      }
    }
    if (messages.value[messageIndex]?.loading) {
      updateAiMessage(messageIndex, accumulatedContent, undefined, false);
    }
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : '流式请求失败';
    updateAiMessage(messageIndex, `错误: ${errorMsg}`, undefined, false);
    antMessage.error('流式请求失败，请稍后重试');
  }
}

async function sendStructuredRequest(userMessage: string, messageIndex: number): Promise<void> {
  try {
    const response = await fetch(API_ENDPOINTS.structured, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: userMessage }),
    });
    if (!response.ok) throw new Error(`HTTP 错误: ${response.status}`);
    const data: ApiResult = await response.json();
    if (data.success && data.result) {
      const { content, model, costMs, requestId, promptTokens, completionTokens } = data.result;
      updateAiMessage(messageIndex, content, { model, costMs, requestId, promptTokens, completionTokens }, false);
    } else {
      updateAiMessage(messageIndex, `请求失败: ${data.message || '未知错误'}`, undefined, false);
    }
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : '网络请求失败';
    updateAiMessage(messageIndex, `错误: ${errorMsg}`, undefined, false);
    antMessage.error('请求失败，请稍后重试');
  }
}

// ==================== 事件处理 ====================

async function handleSend(): Promise<void> {
  const trimmedText = inputText.value.trim();
  if (!trimmedText || isLoading.value) return;
  addUserMessage(trimmedText);
  inputText.value = '';
  const aiMessageIndex = addAiMessagePlaceholder();
  isLoading.value = true;
  try {
    switch (chatMode.value) {
      case 'normal': await sendNormalRequest(trimmedText, aiMessageIndex); break;
      case 'stream': await sendStreamRequest(trimmedText, aiMessageIndex); break;
      case 'structured': await sendStructuredRequest(trimmedText, aiMessageIndex); break;
    }
  } finally {
    isLoading.value = false;
  }
}

function handleKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    handleSend();
  }
}

function handlePresetQuestion(question: string): void {
  inputText.value = question;
  handleSend();
}

function handleClear(): void {
  if (isLoading.value) return;
  messages.value = [];
}
</script>

<style scoped lang="less">
@primary-color: #1677ff;
@bg-color: #f5f5f5;
@white: #ffffff;
@border-color: #f0f0f0;
@text-primary: #262626;
@text-secondary: #8c8c8c;
@shadow-sm: 0 2px 8px rgba(0, 0, 0, 0.06);
@shadow-md: 0 4px 12px rgba(0, 0, 0, 0.08);
@max-width: 900px;

.page-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: @bg-color;
  overflow: hidden;
}

// ==================== 头部 ====================
.chat-header {
  background: linear-gradient(135deg, @primary-color 0%, #4096ff 100%);
  padding: 20px 20px;
  box-shadow: @shadow-md;
  z-index: 10;
  .header-content { max-width: @max-width; margin: 0 auto; text-align: center; }
  .header-title {
    color: @white; font-size: 22px; font-weight: 600; margin: 0 0 6px 0;
    display: flex; align-items: center; justify-content: center; gap: 10px;
    .title-icon { font-size: 26px; }
  }
  .header-subtitle { color: rgba(255, 255, 255, 0.85); font-size: 13px; margin: 0; }
}

// ==================== Tab ====================
.page-tabs {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
  :deep(.inner-tabs) {
    flex: 1; display: flex; flex-direction: column;
    .ant-tabs-nav { background: @white; padding: 0 20px; margin: 0; }
    .ant-tabs-content-holder { flex: 1; overflow: hidden; }
    .ant-tabs-content { height: 100%; }
    .ant-tabs-tabpane { height: 100%; display: flex; flex-direction: column; overflow: hidden; }
  }
}

// ==================== 模式选择器 ====================
.mode-selector {
  background: @white; padding: 12px 20px; border-bottom: 1px solid @border-color;
  display: flex; justify-content: center;
  :deep(.ant-radio-button-wrapper) {
    display: flex; align-items: center; gap: 6px; padding: 0 20px; height: 36px; font-size: 14px;
  }
}

// ==================== 聊天区域 ====================
.chat-area {
  flex: 1; overflow-y: auto; padding: 20px; scroll-behavior: smooth;
  &::-webkit-scrollbar { width: 8px; }
  &::-webkit-scrollbar-track { background: transparent; }
  &::-webkit-scrollbar-thumb { background-color: rgba(0,0,0,0.15); border-radius: 4px; }
}

.welcome-state {
  max-width: @max-width; margin: 0 auto; padding: 30px 20px; text-align: center;
  .welcome-icon { font-size: 44px; color: @primary-color; margin-bottom: 12px; }
  .welcome-title { font-size: 22px; font-weight: 600; color: @text-primary; margin: 0 0 6px 0; }
  .welcome-desc { font-size: 14px; color: @text-secondary; margin: 0 0 24px 0; }
}
.preset-questions {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px;
  max-width: 700px; margin: 0 auto;
  .question-card {
    display: flex; align-items: center; gap: 12px; padding: 14px 18px;
    background: @white; border-radius: 12px; border: 1px solid @border-color;
    cursor: pointer; transition: all 0.2s ease; text-align: left;
    &:hover { border-color: @primary-color; box-shadow: @shadow-md; transform: translateY(-2px); }
    .question-icon { color: @primary-color; font-size: 14px; flex-shrink: 0; }
    .question-text { font-size: 14px; color: @text-primary; line-height: 1.5; }
  }
}

.message-list {
  max-width: @max-width; margin: 0 auto; display: flex; flex-direction: column; gap: 16px;
}
.message-item {
  display: flex; animation: fadeIn 0.3s ease;
  &.message-user { justify-content: flex-end; }
  &.message-ai { justify-content: flex-start; }
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.message-bubble {
  max-width: 75%; padding: 12px 16px; border-radius: 12px;
  .message-content {
    font-size: 14px; line-height: 1.6; word-wrap: break-word; white-space: pre-wrap;
    :deep(.code-block) {
      background: #1e1e1e; color: #d4d4d4; padding: 12px 16px; border-radius: 8px;
      overflow-x: auto; margin: 8px 0; font-family: 'Consolas', monospace; font-size: 13px; line-height: 1.5;
      code { background: transparent; padding: 0; color: inherit; }
    }
    :deep(.inline-code) {
      background: rgba(0,0,0,0.06); padding: 2px 6px; border-radius: 4px;
      font-family: 'Consolas', monospace; font-size: 13px;
    }
  }
}
.user-bubble {
  background: @primary-color; color: @white; border-bottom-right-radius: 4px;
  .message-content { color: @white; }
}
.ai-bubble {
  display: flex; gap: 12px; background: transparent; padding: 0; max-width: 85%;
  .ai-avatar {
    width: 36px; height: 36px; border-radius: 50%;
    background: linear-gradient(135deg, @primary-color 0%, #4096ff 100%);
    display: flex; align-items: center; justify-content: center;
    color: @white; font-size: 18px; flex-shrink: 0;
  }
  .ai-content-wrapper {
    flex: 1; background: @white; padding: 12px 16px; border-radius: 12px;
    border-bottom-left-radius: 4px; box-shadow: @shadow-sm; border: 1px solid @border-color;
    min-width: 0;
    .message-content { color: @text-primary; }
  }
}

.message-meta {
  display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; padding-top: 12px;
  border-top: 1px solid @border-color;
  :deep(.ant-tag) { margin: 0; font-size: 12px; }
}

.typing-indicator {
  display: flex; gap: 4px; padding: 8px 0;
  .typing-dot {
    width: 8px; height: 8px; border-radius: 50%; background-color: @text-secondary;
    animation: typingBounce 1.4s infinite ease-in-out both;
    &:nth-child(1) { animation-delay: -0.32s; }
    &:nth-child(2) { animation-delay: -0.16s; }
  }
}
@keyframes typingBounce {
  0%, 80%, 100% { transform: scale(0.6); opacity: 0.5; }
  40% { transform: scale(1); opacity: 1; }
}
.scroll-anchor { height: 1px; }

// ==================== 输入区域 ====================
.input-area {
  background: @white; border-top: 1px solid @border-color; padding: 14px 20px;
  box-shadow: 0 -2px 8px rgba(0,0,0,0.04);
  .input-wrapper {
    max-width: @max-width; margin: 0 auto; display: flex; gap: 12px; align-items: flex-end;
  }
  .chat-input {
    flex: 1; resize: none; border-radius: 8px; border: 1px solid @border-color;
    padding: 10px 14px; font-size: 14px; line-height: 1.5; transition: all 0.2s ease;
    &:focus { border-color: @primary-color; box-shadow: 0 0 0 2px rgba(22,119,255,0.1); }
    &:disabled { background-color: #fafafa; cursor: not-allowed; }
  }
  .input-actions {
    display: flex; gap: 8px; flex-shrink: 0;
    .send-btn { height: 40px; border-radius: 8px; display: flex; align-items: center; gap: 6px; padding: 0 20px; }
    .clear-btn { height: 40px; border-radius: 8px; display: flex; align-items: center; gap: 6px; }
  }
}

// ==================== 日志面板 ====================
.log-panel {
  flex: 1; overflow-y: auto; padding: 20px;
  &::-webkit-scrollbar { width: 8px; }
  &::-webkit-scrollbar-thumb { background-color: rgba(0,0,0,0.15); border-radius: 4px; }
}

.section-title {
  font-size: 15px; font-weight: 600; color: @text-primary; margin: 0 0 14px 0;
  display: flex; align-items: center; gap: 8px;
  .ant-btn { margin-left: auto; }
}

.stat-section {
  max-width: @max-width; margin: 0 auto 24px auto;
}

.stat-cards {
  display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px;
}
.stat-card {
  background: @white; border-radius: 10px; padding: 16px; text-align: center;
  border: 1px solid @border-color; box-shadow: @shadow-sm;
  .stat-value { font-size: 28px; font-weight: 700; color: @text-primary; line-height: 1.2; }
  .stat-unit { font-size: 14px; font-weight: 400; color: @text-secondary; margin-left: 2px; }
  .stat-label { font-size: 13px; color: @text-secondary; margin-top: 4px; }
  .stat-success { color: #52c41a; }
  .stat-fail { color: #ff4d4f; }
}

// 模型分布
.model-stats {
  background: @white; border-radius: 10px; border: 1px solid @border-color; overflow: hidden;
}
.model-stat-row {
  display: flex; align-items: center; gap: 16px; padding: 10px 16px;
  border-bottom: 1px solid @border-color; font-size: 13px;
  &:last-child { border-bottom: none; }
  .model-name { flex: 1; font-weight: 500; color: @text-primary; }
  .model-count { color: @primary-color; font-weight: 500; width: 60px; text-align: right; }
  .model-tokens { color: @text-secondary; width: 100px; text-align: right; }
  .model-avg { color: @text-secondary; width: 70px; text-align: right; }
}

// 日志表格
.log-table {
  :deep(.ant-table) { font-size: 13px; }
  .cell-ellipsis { color: @text-secondary; }
}

// ==================== 响应式 ====================
@media screen and (max-width: 768px) {
  .stat-cards { grid-template-columns: repeat(3, 1fr); }
  .chat-header { padding: 14px; .header-title { font-size: 18px; } }
  .mode-selector :deep(.ant-radio-button-wrapper) { padding: 0 12px; font-size: 13px; }
  .input-area .input-wrapper { flex-direction: column; align-items: stretch; }
}
</style>
