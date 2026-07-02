<template>
  <div class="rag-chat-container">
    <!-- 左侧：会话列表 -->
    <div class="session-sidebar">
      <div class="sidebar-header">
        <span class="sidebar-title">会话列表</span>
        <a-button type="primary" size="small" @click="handleNewSession">
          <template #icon><PlusOutlined /></template>
          新对话
        </a-button>
      </div>
      <div class="session-list">
        <div
          v-for="session in sessions"
          :key="session.id"
          :class="['session-item', { active: currentSessionId === session.id }]"
          @click="handleSwitchSession(session.id)"
        >
          <MessageOutlined class="session-icon" />
          <span class="session-title">{{ session.title || '新对话' }}</span>
          <span class="session-count">{{ session.messageCount || 0 }}</span>
        </div>
        <div v-if="sessions.length === 0" class="session-empty">暂无会话</div>
      </div>
    </div>

    <!-- 右侧：聊天区域 -->
    <div class="chat-main">
      <!-- 顶部工具栏 -->
      <div class="chat-toolbar">
        <span class="toolbar-title">RAG 知识库问答</span>
        <div class="kb-selector">
          <span class="kb-label">知识库：</span>
          <a-select
            v-model:value="selectedKbId"
            placeholder="全部知识库"
            allowClear
            style="width: 200px"
            :loading="kbLoading"
          >
            <a-select-option value="">全部知识库</a-select-option>
            <a-select-option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">
              {{ kb.name }}
            </a-select-option>
          </a-select>
        </div>
      </div>

      <!-- 消息列表 -->
      <div class="message-area" ref="messageAreaRef">
        <div v-if="messages.length === 0" class="welcome-tip">
          <RobotOutlined class="welcome-icon" />
          <p>你好！我是知识库问答助手。</p>
          <p>请选择知识库并输入问题，我会基于知识库内容为你解答。</p>
        </div>
        <div v-for="(msg, idx) in messages" :key="idx" :class="['message-row', msg.role]">
          <!-- 用户消息 -->
          <div v-if="msg.role === 'user'" class="user-bubble">
            <span class="bubble-text">{{ msg.content }}</span>
          </div>
          <!-- AI 消息 -->
          <div v-if="msg.role === 'assistant'" class="assistant-bubble">
            <div class="assistant-avatar">
              <RobotOutlined />
            </div>
            <div class="assistant-content">
              <div class="answer-text" v-html="formatAnswer(msg.content)"></div>
              <!-- 参考来源 -->
              <div v-if="msg.references && msg.references.length > 0" class="references">
                <div class="ref-title">
                  <FileTextOutlined /> 参考来源（{{ msg.references.length }} 条）
                </div>
                <div v-for="(ref, rIdx) in msg.references" :key="rIdx" class="ref-item">
                  <span class="ref-index">[{{ rIdx + 1 }}]</span>
                  <span class="ref-file">{{ ref.sourceFileName || '未知' }}</span>
                  <span v-if="ref.headingPath" class="ref-heading"> · {{ ref.headingPath }}</span>
                  <span class="ref-score">相关度: {{ (ref.score * 100).toFixed(0) }}%</span>
                </div>
              </div>
              <!-- 模型信息 -->
              <div class="msg-meta">
                <span v-if="msg.model">{{ msg.model }}</span>
                <span v-if="msg.durationMs"> · {{ msg.durationMs }}ms</span>
              </div>
            </div>
          </div>
        </div>
        <!-- 处理状态指示器（搜索中 / 思考中） -->
        <div v-if="streamingPhase !== 'idle' && messages.length > 0 && messages[messages.length - 1]?.role === 'user'" class="message-row assistant">
          <div class="assistant-bubble">
            <div class="assistant-avatar"><RobotOutlined /></div>
            <div class="assistant-content">
              <!-- 阶段1: 检索知识库 -->
              <div :class="['phase-card', streamingPhase === 'searching' ? 'phase-active' : 'phase-done']">
                <LoadingOutlined v-if="streamingPhase === 'searching'" spin style="color: #1890ff" />
                <CheckCircleOutlined v-else style="color: #52c41a" />
                <span class="phase-text">{{ streamingPhase === 'searching' ? '正在检索知识库...' : '知识库检索完成' }}</span>
              </div>
              <!-- 阶段2: 思考中（meta 到达后显示，首个 token 到达后变完成） -->
              <div v-if="streamingPhase === 'thinking' || streamingPhase === 'streaming'"
                   :class="['phase-card', streamingPhase === 'thinking' ? 'phase-active' : 'phase-done']">
                <LoadingOutlined v-if="streamingPhase === 'thinking'" spin style="color: #1890ff" />
                <CheckCircleOutlined v-else style="color: #52c41a" />
                <span class="phase-text">{{ streamingPhase === 'thinking' ? '正在思考回答...' : '思考完成' }}</span>
              </div>
            </div>
          </div>
        </div>
        <div ref="scrollAnchorRef"></div>
      </div>

      <!-- 输入区域 -->
      <div class="input-area">
        <a-textarea
          v-model:value="inputText"
          placeholder="输入你的问题... (Enter 发送, Shift+Enter 换行)"
          :auto-size="{ minRows: 1, maxRows: 4 }"
          @keydown="handleKeyDown"
          :disabled="loading"
        />
        <a-button type="primary" :loading="loading" :disabled="!inputText.trim()" @click="handleSend">
          <template #icon><SendOutlined /></template>
          发送
        </a-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
  import { ref, onMounted, nextTick } from 'vue';
  import { message as antMessage } from 'ant-design-vue';
  import {
    PlusOutlined,
    MessageOutlined,
    RobotOutlined,
    SendOutlined,
    FileTextOutlined,
    LoadingOutlined,
    CheckCircleOutlined,
  } from '@ant-design/icons-vue';
  import { defHttp } from '/@/utils/http/axios';
  import { getToken } from '/@/utils/auth/index';
  import MarkdownIt from 'markdown-it';
  import hljs from 'highlight.js';
  import 'highlight.js/styles/github.css';

  const md = new MarkdownIt({
    html: false,
    linkify: true,
    breaks: true,
    typographer: true,
    highlight(str: string, lang: string) {
      if (lang && hljs.getLanguage(lang)) {
        try {
          return hljs.highlight(str, { language: lang }).value;
        } catch (_) {}
      }
      return '';
    },
  });

  // ==================== 状态 ====================
  const sessions = ref<any[]>([]);
  const currentSessionId = ref<string>('');
  const messages = ref<any[]>([]);
  const inputText = ref('');
  const loading = ref(false);
  const selectedKbId = ref<string>('');
  const knowledgeBases = ref<any[]>([]);
  const kbLoading = ref(false);

  const messageAreaRef = ref<HTMLElement>();
  const scrollAnchorRef = ref<HTMLElement>();
  // 流式处理阶段：idle → searching → thinking → streaming → idle
  const streamingPhase = ref<'idle' | 'searching' | 'thinking' | 'streaming'>('idle');

  // ==================== 初始化 ====================
  onMounted(async () => {
    await Promise.all([loadSessions(), loadKnowledgeBases()]);
  });

  async function loadSessions() {
    try {
      const res = await defHttp.get({ url: '/practice/rag/sessions' });
      sessions.value = res || [];
    } catch (e) {
      console.error('加载会话列表失败', e);
    }
  }

  async function loadKnowledgeBases() {
    kbLoading.value = true;
    try {
      const res = await defHttp.get({ url: '/practice/rag/knowledge-bases' });
      knowledgeBases.value = res || [];
    } catch (e) {
      console.error('加载知识库列表失败', e);
    } finally {
      kbLoading.value = false;
    }
  }

  // ==================== 会话操作 ====================
  function handleNewSession() {
    currentSessionId.value = '';
    messages.value = [];
  }

  async function handleSwitchSession(sessionId: string) {
    if (currentSessionId.value === sessionId) return;
    currentSessionId.value = sessionId;
    try {
      const res = await defHttp.get({ url: `/practice/rag/messages/${sessionId}` });
      messages.value = (res || []).map((m: any) => ({
        role: m.role,
        content: m.content,
        references: m.ragContext ? tryParseReferences(m.ragContext) : [],
        model: m.modelName,
        durationMs: m.durationMs,
      }));
      scrollToBottom();
    } catch (e) {
      antMessage.error('加载消息失败');
    }
  }

  function tryParseReferences(ragContext: string): any[] {
    try {
      const parsed = JSON.parse(ragContext);
      return Array.isArray(parsed)
        ? parsed.map((r: any) => ({
            sourceFileName: r.sourceFileName,
            headingPath: r.headingPath,
            score: r.score,
            content: r.content,
          }))
        : [];
    } catch {
      return [];
    }
  }

  // ==================== 流式发送消息 ====================
  async function handleSend() {
    const query = inputText.value.trim();
    if (!query || loading.value) return;

    // 添加用户消息到界面
    messages.value.push({ role: 'user', content: query });
    inputText.value = '';
    loading.value = true;
    streamingPhase.value = 'searching';
    scrollToBottom();

    // AI 消息索引，首条事件到达时才创建
    let aiMsgIndex = -1;

    try {
      const token = getToken();
      const response = await fetch('/jeecgboot/practice/rag/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Access-Token': token as string,
        },
        body: JSON.stringify({
          query,
          sessionId: currentSessionId.value || undefined,
          knowledgeBaseId: selectedKbId.value || undefined,
          topK: 5,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP 错误: ${response.status}`);
      }
      if (!response.body) {
        throw new Error('响应体为空');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      let accumulatedContent = '';

      // 辅助：确保 AI 消息已创建
      function ensureAiMessage() {
        if (aiMsgIndex === -1) {
          aiMsgIndex = messages.value.length;
          messages.value.push({
            role: 'assistant',
            content: '',
            references: [],
            model: '',
            durationMs: 0,
          });
        }
      }

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
            if (line.startsWith('event:')) {
              eventType = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              eventData = line.slice(5).trim();
            }
          }

          switch (eventType) {
            case 'meta':
              ensureAiMessage();
              streamingPhase.value = 'thinking';
              try {
                const meta = JSON.parse(eventData);
                if (meta.sessionId) {
                  currentSessionId.value = meta.sessionId;
                }
                if (meta.references) {
                  messages.value[aiMsgIndex].references = meta.references;
                }
                if (meta.model) {
                  messages.value[aiMsgIndex].model = meta.model;
                }
              } catch (e) {
                console.warn('解析 meta 事件失败', e);
              }
              scrollToBottom();
              break;

            case 'message':
              ensureAiMessage();
              if (streamingPhase.value !== 'streaming') {
                streamingPhase.value = 'streaming';
              }
              accumulatedContent += eventData;
              messages.value[aiMsgIndex].content = accumulatedContent;
              scrollToBottom();
              break;

            case 'done':
              streamingPhase.value = 'idle';
              loadSessions();
              break;

            case 'error':
              streamingPhase.value = 'idle';
              ensureAiMessage();
              accumulatedContent += `\n\n⚠️ ${eventData}`;
              messages.value[aiMsgIndex].content = accumulatedContent;
              break;
          }
        }
      }

      // 如果流结束但从未创建 AI 消息（异常情况）
      if (aiMsgIndex === -1) {
        messages.value.push({
          role: 'assistant',
          content: '未收到任何响应',
          references: [],
          model: '',
          durationMs: 0,
        });
      }
    } catch (e: any) {
      streamingPhase.value = 'idle';
      const errMsg = e?.message || '请求失败';
      if (aiMsgIndex !== -1) {
        messages.value[aiMsgIndex].content = `抱歉，请求出错：${errMsg}`;
      } else {
        messages.value.push({
          role: 'assistant',
          content: `抱歉，请求出错：${errMsg}`,
          references: [],
          model: '',
          durationMs: 0,
        });
      }
    } finally {
      loading.value = false;
      streamingPhase.value = 'idle';
      scrollToBottom();
    }
  }

  function handleKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }

  // ==================== 工具方法 ====================
  function formatAnswer(text: string): string {
    if (!text) return '';
    return md.render(text);
  }

  function scrollToBottom() {
    nextTick(() => {
      scrollAnchorRef.value?.scrollIntoView({ behavior: 'smooth' });
    });
  }
</script>

<style lang="less" scoped>
  .rag-chat-container {
    display: flex;
    height: calc(100vh - 120px);
    background: #f5f5f5;
  }

  // ==================== 左侧会话列表 ====================
  .session-sidebar {
    width: 260px;
    background: #fff;
    border-right: 1px solid #e8e8e8;
    display: flex;
    flex-direction: column;
    flex-shrink: 0;
  }

  .sidebar-header {
    padding: 16px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid #f0f0f0;
  }

  .sidebar-title {
    font-weight: 600;
    font-size: 15px;
  }

  .session-list {
    flex: 1;
    overflow-y: auto;
    padding: 8px;
  }

  .session-item {
    display: flex;
    align-items: center;
    padding: 10px 12px;
    border-radius: 8px;
    cursor: pointer;
    margin-bottom: 4px;
    transition: all 0.2s;

    &:hover {
      background: #f0f5ff;
    }
    &.active {
      background: #e6f4ff;
      border-left: 3px solid #1890ff;
    }
  }

  .session-icon {
    margin-right: 8px;
    color: #8c8c8c;
  }

  .session-title {
    flex: 1;
    font-size: 13px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .session-count {
    font-size: 11px;
    color: #bfbfbf;
    margin-left: 4px;
  }

  .session-empty {
    text-align: center;
    color: #bfbfbf;
    padding: 40px 0;
    font-size: 13px;
  }

  // ==================== 右侧聊天区域 ====================
  .chat-main {
    flex: 1;
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .chat-toolbar {
    padding: 12px 20px;
    background: #fff;
    border-bottom: 1px solid #e8e8e8;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .toolbar-title {
    font-weight: 600;
    font-size: 15px;
  }

  .kb-selector {
    display: flex;
    align-items: center;
  }

  .kb-label {
    font-size: 13px;
    color: #595959;
    margin-right: 8px;
  }

  // ==================== 消息区域 ====================
  .message-area {
    flex: 1;
    overflow-y: auto;
    padding: 20px;
  }

  .welcome-tip {
    text-align: center;
    padding: 80px 20px;
    color: #8c8c8c;

    .welcome-icon {
      font-size: 48px;
      color: #bfbfbf;
      margin-bottom: 16px;
    }
    p {
      margin: 8px 0;
      font-size: 14px;
    }
  }

  .message-row {
    margin-bottom: 16px;
    display: flex;

    &.user {
      justify-content: flex-end;
    }
    &.assistant {
      justify-content: flex-start;
    }
  }

  // 用户消息气泡
  .user-bubble {
    max-width: 70%;
    .bubble-text {
      display: inline-block;
      background: #1890ff;
      color: #fff;
      padding: 10px 16px;
      border-radius: 16px 16px 4px 16px;
      font-size: 14px;
      line-height: 1.6;
      word-break: break-word;
    }
  }

  // AI 消息气泡
  .assistant-bubble {
    display: flex;
    max-width: 80%;

    .assistant-avatar {
      width: 36px;
      height: 36px;
      background: #e6f4ff;
      border-radius: 50%;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-right: 10px;
      flex-shrink: 0;
      color: #1890ff;
    }

    .assistant-content {
      background: #fff;
      border-radius: 4px 16px 16px 16px;
      padding: 14px 18px;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);

      .answer-text {
        font-size: 14px;
        line-height: 1.7;
        color: #262626;

        :deep(pre) {
          background: #f6f8fa;
          padding: 12px;
          border-radius: 6px;
          overflow-x: auto;
          margin: 8px 0;
          font-size: 13px;

          code {
            background: transparent;
            padding: 0;
            border-radius: 0;
            font-size: 13px;
          }
        }
        :deep(code) {
          background: #f0f0f0;
          padding: 2px 6px;
          border-radius: 3px;
          font-size: 13px;
        }
        :deep(h1),
        :deep(h2),
        :deep(h3),
        :deep(h4) {
          margin: 14px 0 8px;
          font-weight: 600;
          line-height: 1.4;
        }
        :deep(h1) { font-size: 18px; }
        :deep(h2) { font-size: 16px; }
        :deep(h3) { font-size: 15px; }
        :deep(h4) { font-size: 14px; }
        :deep(p) {
          margin: 6px 0;
        }
        :deep(ul),
        :deep(ol) {
          padding-left: 22px;
          margin: 6px 0;
        }
        :deep(li) {
          margin: 3px 0;
          line-height: 1.7;
        }
        :deep(blockquote) {
          border-left: 3px solid #d9d9d9;
          padding-left: 12px;
          color: #595959;
          margin: 8px 0;
        }
        :deep(table) {
          border-collapse: collapse;
          margin: 8px 0;
          width: 100%;
        }
        :deep(th),
        :deep(td) {
          border: 1px solid #e8e8e8;
          padding: 6px 10px;
          text-align: left;
          font-size: 13px;
        }
        :deep(th) {
          background: #fafafa;
          font-weight: 600;
        }
        :deep(strong) {
          font-weight: 600;
        }
        :deep(hr) {
          border: none;
          border-top: 1px solid #e8e8e8;
          margin: 12px 0;
        }
        :deep(a) {
          color: #1890ff;
          text-decoration: none;
          &:hover {
            text-decoration: underline;
          }
        }
      }
    }
  }

  // 参考来源
  .references {
    margin-top: 12px;
    padding-top: 10px;
    border-top: 1px dashed #e8e8e8;

    .ref-title {
      font-size: 12px;
      color: #8c8c8c;
      margin-bottom: 6px;
    }
    .ref-item {
      font-size: 12px;
      color: #595959;
      padding: 3px 0;
      line-height: 1.5;
    }
    .ref-index {
      color: #1890ff;
      margin-right: 4px;
    }
    .ref-file {
      font-weight: 500;
    }
    .ref-heading {
      color: #8c8c8c;
    }
    .ref-score {
      color: #52c41a;
      margin-left: 8px;
    }
  }

  .msg-meta {
    margin-top: 6px;
    font-size: 11px;
    color: #bfbfbf;
  }

  // 处理阶段状态卡片
  .phase-card {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 12px;
    border-radius: 6px;
    font-size: 13px;
    margin-bottom: 4px;
    transition: all 0.3s ease;

    &.phase-active {
      background: #f0f5ff;
      color: #1890ff;
    }
    &.phase-done {
      background: #f6ffed;
      color: #52c41a;
    }

    .phase-text {
      font-weight: 500;
    }
  }

  // ==================== 输入区域 ====================
  .input-area {
    padding: 16px 20px;
    background: #fff;
    border-top: 1px solid #e8e8e8;
    display: flex;
    gap: 12px;
    align-items: flex-end;

    :deep(.ant-input) {
      border-radius: 12px;
      resize: none;
    }
  }
</style>
