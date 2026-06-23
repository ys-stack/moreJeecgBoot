import type { AppRouteModule } from '/@/router/types';
import { LAYOUT } from '/@/router/constant';

const practiceAi: AppRouteModule = {
  path: '/practice-ai',
  name: 'PracticeAi',
  component: LAYOUT,
  redirect: '/practice-ai/assistant',
  meta: {
    orderNo: 9000,
    icon: 'ant-design:robot-outlined',
    title: 'AI 练习',
  },
  children: [
    {
      path: 'assistant',
      name: 'PracticeAiAssistant',
      component: () => import('/@/views/practice/aiassistant/index.vue'),
      meta: {
        title: '需求分析助手',
        icon: 'ant-design:bulb-outlined',
      },
    },
    {
      path: 'knowledge-base',
      name: 'PracticeKnowledgeBase',
      component: () => import('/@/views/practice/knowledgebase/index.vue'),
      meta: {
        title: '知识库管理',
        icon: 'ant-design:database-outlined',
      },
    },
    {
      path: 'doc-manager',
      name: 'PracticeDocManager',
      component: () => import('/@/views/practice/docmanager/index.vue'),
      meta: {
        title: '文档管理',
        icon: 'ant-design:folder-open-outlined',
      },
    },
    {
      path: 'batch-parse',
      name: 'PracticeBatchParse',
      component: () => import('/@/views/practice/batchparse/index.vue'),
      meta: {
        title: '批量解析',
        icon: 'ant-design:thunderbolt-outlined',
      },
    },
    {
      path: 'rag-chat',
      name: 'PracticeRagChat',
      component: () => import('/@/views/practice/ragchat/index.vue'),
      meta: {
        title: 'RAG问答',
        icon: 'ant-design:message-outlined',
      },
    },
    {
      path: 'threadpool-monitor',
      name: 'PracticeThreadPoolMonitor',
      component: () => import('/@/views/practice/threadpoolmonitor/index.vue'),
      meta: {
        title: '线程池监控',
        icon: 'ant-design:dashboard-outlined',
      },
    },
  ],
};

export default practiceAi;
