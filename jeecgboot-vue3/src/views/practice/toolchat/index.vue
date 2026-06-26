<template>
  <div class="tool-chat-container">
    <!-- 左侧：会话列表 -->
    <div class="session-sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">工具对话</span>
        <a-button type="primary" size="small" @click="handleNewSession">
          <template #icon><PlusOutlined /></template>
          新对话
        </a-button>
      </div>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.id"
          :class="['session-item', { active: currentSessionId === s.id }]"
          @click="handleSwitchSession(s.id)"
        >
          <ToolOutlined class="session-icon" />
          <span class="session-title">{{ s.title || '新对话' }}</span>
        </div>
        <div v-if="sessions.length === 0" class="session-empty">暂无会话</div>
      </div>
    </div>

    <!-- 右侧：聊天区域 -->
    <div class="chat-main">
      <!-- 消息列表 -->
      <div class="chat-area" ref="chatAreaRef">
        <div v-if="currentMessages.length === 0" class="welcome-state">
          <div class="welcome-icon"><ToolOutlined /></div>
          <h2>Tool Calling 对话</h2>
          <p>模型会自动决定是否调用工具，写操作会先征求您的确认</p>
          <div class="preset-questions">
            <div
              v-for="q in presetQuestions"
              :key="q"
              class="question-card"
              @click="handlePresetQuestion(q)"
            >
              <RightOutlined class="q-icon" />
              <span>{{ q }}</span>
            </div>
          </div>
        </div>

        <div v-else class="message-list">
          <div
            v-for="(msg, idx) in currentMessages"
            :key="idx"
            :class="['message-item', msg.role === 'user' ? 'msg-user' : 'msg-ai']"
          >
            <!-- 用户消息 -->
            <div v-if="msg.role === 'user'" class="bubble user-bubble">
              {{ msg.content }}
            </div>

            <!-- AI 消息 -->
            <div v-else class="bubble ai-bubble">
              <div class="ai-avatar"><RobotOutlined /></div>
              <div class="ai-body">
                <!-- 工具调用详情 -->
                <div v-if="msg.toolCalls && msg.toolCalls.length > 0" class="tool-calls-section">
                  <div class="tool-calls-header">
                    <ThunderboltOutlined />
                    <span>调用了 {{ msg.toolCalls.length }} 个工具</span>
                    <a-tag v-if="msg.rounds" color="blue" size="small">{{ msg.rounds }} 轮</a-tag>
                  </div>
                  <div
                    v-for="(tc, tcIdx) in msg.toolCalls"
                    :key="tcIdx"
                    :class="['tool-call-card', `status-${tc.status}`]"
                  >
                    <div class="tc-header" @click="toggleToolCall(tc)">
                      <span :class="['tc-status-dot', `dot-${tc.status}`]"></span>
                      <span class="tc-name">{{ tc.toolName || tc.toolCode }}</span>
                      <a-tag v-if="tc.status === 'success'" color="green" size="small">成功</a-tag>
                      <a-tag v-else-if="tc.status === 'error'" color="red" size="small">失败</a-tag>
                      <a-tag v-else-if="tc.status === 'pending_confirm'" color="orange" size="small">待确认</a-tag>
                      <span v-if="tc.durationMs" class="tc-duration">{{ tc.durationMs }}ms</span>
                      <DownOutlined :class="['tc-arrow', { open: tc._expanded }]" />
                    </div>
                    <div v-if="tc._expanded" class="tc-detail">
                      <div class="tc-section">
                        <div class="tc-section-title">入参</div>
                        <pre class="tc-pre">{{ formatJson(tc.inputParams) }}</pre>
                      </div>
                      <div v-if="tc.outputResult" class="tc-section">
                        <div class="tc-section-title">结果</div>
                        <pre class="tc-pre">{{ formatJson(tc.outputResult) }}</pre>
                      </div>
                    </div>
                  </div>
                </div>

                <!-- 待确认操作区 -->
                <div v-if="msg.needsConfirm" class="confirm-section">
                  <div class="confirm-header">
                    <ExclamationCircleOutlined style="color: #fa8c16" />
                    <span>以下操作需要您确认</span>
                  </div>
                  <div
                    v-for="(tc, tcIdx) in msg.toolCalls"
                    :key="tcIdx"
                  >
                    <div v-if="tc.status === 'pending_confirm'" class="confirm-card">
                      <div class="confirm-info">
                        <div class="confirm-tool-name">{{ tc.toolName || tc.toolCode }}</div>
                        <pre class="confirm-params">{{ formatJson(tc.inputParams) }}</pre>
                      </div>
                      <a-button
                        type="primary"
                        size="small"
                        :loading="confirmLoading[tc.toolCode]"
                        @click="handleConfirmTool(msg, tc.toolCode)"
                      >
                        <template #icon><CheckOutlined /></template>
                        确认执行
                      </a-button>
                    </div>
                  </div>
                </div>

                <!-- AI 文本回复 -->
                <div v-if="msg.content" class="ai-text" v-html="formatContent(msg.content)"></div>

                <!-- 耗时标签 -->
                <div v-if="msg.costMs && !msg.loading" class="msg-meta">
                  <a-tag size="small">{{ msg.model }}</a-tag>
                  <a-tag size="small">{{ msg.costMs }}ms</a-tag>
                </div>

                <!-- 加载动画 -->
                <div v-if="msg.loading" class="typing-indicator">
                  <span class="dot"></span><span class="dot"></span><span class="dot"></span>
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
            placeholder="输入问题，模型会自动选择工具... (Enter 发送, Shift+Enter 换行)"
            :disabled="isLoading"
            @keydown="handleKeyDown"
            class="chat-input"
          />
          <a-button
            type="primary"
            :disabled="!inputText.trim() || isLoading"
            :loading="isLoading"
            @click="handleSend"
            class="send-btn"
          >
            <template #icon><SendOutlined /></template>
            发送
          </a-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, nextTick, computed } from 'vue';
import { defHttp } from '/@/utils/http/axios';
import { message as antMessage } from 'ant-design-vue';
import {
  PlusOutlined, ToolOutlined, RobotOutlined, RightOutlined,
  SendOutlined, ThunderboltOutlined, DownOutlined,
  ExclamationCircleOutlined, CheckOutlined,
} from '@ant-design/icons-vue';

// ==================== 类型 ====================

interface ToolCallDetail {
  toolCode: string;
  toolName: string;
  inputParams: string;
  outputResult: string;
  status: 'success' | 'error' | 'pending_confirm';
  durationMs: number;
  _expanded?: boolean;
}

interface ChatMessage {
  role: 'user' | 'ai';
  content: string;
  loading?: boolean;
  model?: string;
  costMs?: number;
  rounds?: number;
  needsConfirm?: boolean;
  toolCalls?: ToolCallDetail[];
  /** 记录本次请求的 confirmTools，确认时回传 */
  _confirmTools?: string[];
}

interface Session {
  id: string;
  title: string;
  messages: ChatMessage[];
}

// ==================== 状态 ====================

const sessions = ref<Session[]>([]);
const currentSessionId = ref('');
const inputText = ref('');
const isLoading = ref(false);
const confirmLoading = reactive<Record<string, boolean>>({});
const chatAreaRef = ref<HTMLDivElement | null>(null);
const scrollAnchorRef = ref<HTMLDivElement | null>(null);

const currentMessages = computed<ChatMessage[]>(() => {
  const s = sessions.value.find(s => s.id === currentSessionId.value);
  return s ? s.messages : [];
});

const presetQuestions = [
  '查一下订单 B100 的状态',
  '帮我查一下用户张三的信息',
  '帮我提一个 bug 工单，标题是登录页崩溃',
];

// ==================== 会话管理 ====================

function handleNewSession() {
  const id = 'sess_' + Date.now();
  sessions.value.unshift({ id, title: '新对话', messages: [] });
  currentSessionId.value = id;
}

function handleSwitchSession(id: string) {
  currentSessionId.value = id;
}

function ensureSession(): Session {
  if (!currentSessionId.value) handleNewSession();
  return sessions.value.find(s => s.id === currentSessionId.value)!;
}

// ==================== 消息操作 ====================

function scrollToBottom() {
  nextTick(() => {
    scrollAnchorRef.value?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  });
}

function addMessage(msg: ChatMessage) {
  const s = ensureSession();
  s.messages.push(msg);
  scrollToBottom();
}

// ==================== 发送请求 ====================

async function doSend(message: string, confirmTools: string[] = []) {
  const s = ensureSession();

  // 用户消息
  addMessage({ role: 'user', content: message });

  // AI 占位
  const aiIdx = s.messages.length;
  addMessage({ role: 'ai', content: '', loading: true });
  isLoading.value = true;

  try {
    const body: any = { message, sessionId: currentSessionId.value };
    if (confirmTools.length > 0) body.confirmTools = confirmTools;

    const res = await defHttp.post({ url: '/practice/tool/chat', data: body, timeout: 60 * 1000 });

    const detail: ToolCallDetail[] = (res.toolCalls || []).map((tc: any) => ({
      ...tc,
      _expanded: false,
    }));

    s.messages[aiIdx] = {
      role: 'ai',
      content: res.content || '',
      model: res.model,
      costMs: res.costMs,
      rounds: res.rounds,
      needsConfirm: res.needsConfirm || false,
      toolCalls: detail,
      _confirmTools: confirmTools,
    };
    scrollToBottom();
  } catch (e: any) {
    s.messages[aiIdx] = {
      role: 'ai',
      content: '请求失败: ' + (e?.message || e),
    };
  } finally {
    isLoading.value = false;
  }
}

function handleSend() {
  const text = inputText.value.trim();
  if (!text || isLoading.value) return;
  inputText.value = '';
  doSend(text);
}

function handlePresetQuestion(q: string) {
  if (isLoading.value) return;
  doSend(q);
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    handleSend();
  }
}

// ==================== 确认执行 ====================

async function handleConfirmTool(msg: ChatMessage, toolCode: string) {
  confirmLoading[toolCode] = true;
  try {
    // 收集所有已确认的工具（含之前已确认的 + 当前这个）
    const confirmed = [...(msg._confirmTools || []), toolCode];
    // 重新发送原始消息（从上一条 user message 取）
    const s = ensureSession();
    const msgIdx = s.messages.indexOf(msg);
    // 找到这条 AI 消息之前的最后一条 user message
    let originalMessage = '';
    for (let i = msgIdx - 1; i >= 0; i--) {
      if (s.messages[i].role === 'user') {
        originalMessage = s.messages[i].content;
        break;
      }
    }
    if (!originalMessage) {
      antMessage.error('找不到原始问题');
      return;
    }
    // 替换当前 AI 消息为加载状态
    s.messages[msgIdx] = { role: 'ai', content: '', loading: true };
    isLoading.value = true;

    const body = { message: originalMessage, sessionId: currentSessionId.value, confirmTools: confirmed };
    const res = await defHttp.post({ url: '/practice/tool/chat', data: body, timeout: 60 * 1000 });

    const detail: ToolCallDetail[] = (res.toolCalls || []).map((tc: any) => ({
      ...tc,
      _expanded: false,
    }));

    s.messages[msgIdx] = {
      role: 'ai',
      content: res.content || '',
      model: res.model,
      costMs: res.costMs,
      rounds: res.rounds,
      needsConfirm: res.needsConfirm || false,
      toolCalls: detail,
      _confirmTools: confirmed,
    };
    scrollToBottom();
  } catch (e: any) {
    antMessage.error('确认执行失败: ' + (e?.message || e));
  } finally {
    confirmLoading[toolCode] = false;
    isLoading.value = false;
  }
}

// ==================== 工具函数 ====================

function toggleToolCall(tc: ToolCallDetail) {
  tc._expanded = !tc._expanded;
}

function formatJson(str: string | null | undefined): string {
  if (!str) return '（空）';
  try { return JSON.stringify(JSON.parse(str), null, 2); } catch { return str; }
}

function formatContent(content: string): string {
  if (!content) return '';
  let s = content
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  s = s.replace(/```(\w*)\n?([\s\S]*?)```/g, (_, lang, code) =>
    `<pre class="code-block"><code>${code.trim()}</code></pre>`);
  s = s.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');
  s = s.replace(/\n/g, '<br>');
  return s;
}
</script>

<style scoped lang="less">
@primary: #1677ff;
@bg: #f5f5f5;
@border: #f0f0f0;
@text1: #262626;
@text2: #8c8c8c;

.tool-chat-container {
  display: flex; height: 100vh; background: @bg; overflow: hidden;
}

// ==================== 左侧会话栏 ====================
.session-sidebar {
  width: 240px; background: #fff; border-right: 1px solid @border;
  display: flex; flex-direction: column; flex-shrink: 0;
  .sidebar-header {
    padding: 16px; border-bottom: 1px solid @border;
    display: flex; align-items: center; justify-content: space-between;
    .sidebar-title { font-weight: 600; font-size: 15px; }
  }
  .session-list {
    flex: 1; overflow-y: auto; padding: 8px;
    .session-item {
      display: flex; align-items: center; gap: 8px; padding: 10px 12px;
      border-radius: 8px; cursor: pointer; margin-bottom: 4px; transition: all .2s;
      &:hover { background: #f7f8fa; }
      &.active { background: #e6f4ff; color: @primary; }
      .session-icon { font-size: 14px; color: @text2; }
      .session-title { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    }
    .session-empty { text-align: center; color: @text2; padding: 40px 0; font-size: 13px; }
  }
}

// ==================== 右侧聊天区 ====================
.chat-main {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
}

.chat-area {
  flex: 1; overflow-y: auto; padding: 20px; scroll-behavior: smooth;
}

// ==================== 欢迎 ====================
.welcome-state {
  max-width: 700px; margin: 60px auto 0; text-align: center;
  .welcome-icon { font-size: 48px; color: @primary; margin-bottom: 12px; }
  h2 { font-size: 22px; color: @text1; margin: 0 0 8px; }
  p { color: @text2; font-size: 14px; margin: 0 0 28px; }
}
.preset-questions {
  display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px;
  .question-card {
    display: flex; align-items: center; gap: 10px; padding: 14px 18px;
    background: #fff; border: 1px solid @border; border-radius: 10px;
    cursor: pointer; transition: all .2s; text-align: left;
    &:hover { border-color: @primary; box-shadow: 0 4px 12px rgba(0,0,0,.08); transform: translateY(-2px); }
    .q-icon { color: @primary; font-size: 13px; flex-shrink: 0; }
    span { font-size: 14px; color: @text1; line-height: 1.5; }
  }
}

// ==================== 消息 ====================
.message-list {
  max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px;
}
.message-item {
  display: flex; animation: fadeIn .3s ease;
  &.msg-user { justify-content: flex-end; }
  &.msg-ai { justify-content: flex-start; }
}
@keyframes fadeIn { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; } }

.bubble { max-width: 80%; padding: 12px 16px; border-radius: 12px; font-size: 14px; line-height: 1.6; }

.user-bubble {
  background: @primary; color: #fff; border-bottom-right-radius: 4px;
  white-space: pre-wrap; word-break: break-word;
}

.ai-bubble {
  display: flex; gap: 12px; background: transparent; padding: 0; max-width: 90%;
  .ai-avatar {
    width: 36px; height: 36px; border-radius: 50%; flex-shrink: 0;
    background: linear-gradient(135deg, @primary, #4096ff);
    display: flex; align-items: center; justify-content: center; color: #fff; font-size: 18px;
  }
  .ai-body {
    flex: 1; background: #fff; padding: 14px 16px; border-radius: 12px;
    border-bottom-left-radius: 4px; box-shadow: 0 2px 8px rgba(0,0,0,.06);
    border: 1px solid @border; min-width: 0;
  }
}

// ==================== 工具调用卡片 ====================
.tool-calls-section {
  margin-bottom: 12px;
  .tool-calls-header {
    display: flex; align-items: center; gap: 6px; font-size: 13px; color: @text2;
    margin-bottom: 8px; font-weight: 500;
  }
}

.tool-call-card {
  border: 1px solid @border; border-radius: 8px; margin-bottom: 6px; overflow: hidden;
  &.status-pending_confirm { border-color: #fa8c16; background: #fffbe6; }
  &.status-error { border-color: #ff4d4f; }

  .tc-header {
    display: flex; align-items: center; gap: 8px; padding: 8px 12px;
    cursor: pointer; font-size: 13px; transition: background .2s;
    &:hover { background: #fafafa; }
  }
  .tc-status-dot {
    width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
    &.dot-success { background: #52c41a; }
    &.dot-error { background: #ff4d4f; }
    &.dot-pending_confirm { background: #fa8c16; }
  }
  .tc-name { font-weight: 500; color: @text1; }
  .tc-duration { color: @text2; font-size: 12px; margin-left: auto; }
  .tc-arrow {
    font-size: 10px; color: @text2; transition: transform .2s;
    &.open { transform: rotate(180deg); }
  }

  .tc-detail { padding: 0 12px 10px; }
  .tc-section { margin-top: 8px; }
  .tc-section-title { font-size: 12px; font-weight: 600; color: @text2; margin-bottom: 4px; }
  .tc-pre {
    background: #f8f8f8; border: 1px solid @border; border-radius: 6px;
    padding: 8px 10px; font-size: 12px; font-family: 'Consolas', monospace;
    white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow: auto; margin: 0;
  }
}

// ==================== 确认区 ====================
.confirm-section {
  margin-bottom: 12px; padding: 12px; background: #fffbe6; border: 1px solid #ffe58f;
  border-radius: 8px;
  .confirm-header {
    display: flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600;
    color: #d48806; margin-bottom: 10px;
  }
}
.confirm-card {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 10px 12px; background: #fff; border: 1px solid @border; border-radius: 6px;
  margin-bottom: 6px;
  .confirm-info { flex: 1; min-width: 0; }
  .confirm-tool-name { font-weight: 600; font-size: 14px; color: @text1; margin-bottom: 4px; }
  .confirm-params {
    background: #f8f8f8; border-radius: 4px; padding: 6px 8px; font-size: 12px;
    font-family: 'Consolas', monospace; white-space: pre-wrap; word-break: break-all;
    max-height: 120px; overflow: auto; margin: 0;
  }
}

// ==================== 文本 & 元信息 ====================
.ai-text {
  color: @text1; line-height: 1.7;
  :deep(.code-block) {
    background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 8px;
    overflow-x: auto; margin: 8px 0; font-family: 'Consolas', monospace; font-size: 13px;
  }
  :deep(.inline-code) {
    background: rgba(0,0,0,.06); padding: 2px 6px; border-radius: 4px;
    font-family: 'Consolas', monospace; font-size: 13px;
  }
}
.msg-meta {
  display: flex; gap: 6px; margin-top: 10px; padding-top: 10px; border-top: 1px solid @border;
  :deep(.ant-tag) { margin: 0; font-size: 12px; }
}

.typing-indicator {
  display: flex; gap: 4px; padding: 6px 0;
  .dot {
    width: 8px; height: 8px; border-radius: 50%; background: @text2;
    animation: bounce 1.4s infinite ease-in-out both;
    &:nth-child(1) { animation-delay: -.32s; }
    &:nth-child(2) { animation-delay: -.16s; }
  }
}
@keyframes bounce { 0%,80%,100% { transform: scale(.6); opacity: .5; } 40% { transform: scale(1); opacity: 1; } }
.scroll-anchor { height: 1px; }

// ==================== 输入区 ====================
.input-area {
  background: #fff; border-top: 1px solid @border; padding: 14px 20px;
  .input-wrapper {
    max-width: 860px; margin: 0 auto; display: flex; gap: 12px; align-items: flex-end;
  }
  .chat-input {
    flex: 1; resize: none; border-radius: 8px; padding: 10px 14px; font-size: 14px;
    &:focus { border-color: @primary; box-shadow: 0 0 0 2px rgba(22,119,255,.1); }
  }
  .send-btn { height: 40px; border-radius: 8px; padding: 0 20px; }
}

// ==================== 响应式 ====================
@media (max-width: 768px) {
  .session-sidebar { display: none; }
  .input-wrapper { flex-direction: column; align-items: stretch; }
}
</style>
