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
  ],
};

export default practiceAi;
