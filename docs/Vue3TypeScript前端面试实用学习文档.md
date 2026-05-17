# Vue3 + TypeScript 前端面试实用学习文档

> 适合 Java 后端工程师补齐前后端分离项目常见前端问题。目标不是变成专业前端，而是能看懂 Vue3 + TypeScript + Vite + Pinia + Vue Router + Ant Design Vue 项目结构，能解释登录态、路由权限、接口封装、表单、构建部署和联调问题。JeecgBoot 前端项目使用 Vue3、Vite、TypeScript、Pinia、Vue Router 和 Ant Design Vue。

## 目录

- [一、后端为什么要懂一点前端](#一后端为什么要懂一点前端)
- [二、Vue3 核心概念](#二vue3-核心概念)
- [三、TypeScript 常见问题](#三typescript-常见问题)
- [四、Vite 构建与环境变量](#四vite-构建与环境变量)
- [五、路由、菜单和权限](#五路由菜单和权限)
- [六、Pinia 状态管理](#六pinia-状态管理)
- [七、Axios 接口封装与联调](#七axios-接口封装与联调)
- [八、表单、表格和后台系统体验](#八表单表格和后台系统体验)
- [九、构建部署常见问题](#九构建部署常见问题)
- [十、面试高频回答模板](#十面试高频回答模板)

---

## 一、后端为什么要懂一点前端

前后端分离项目里，后端经常会被问：

1. 登录 token 前端怎么保存和携带。
2. 菜单权限和按钮权限怎么控制。
3. 跨域怎么处理。
4. 接口返回结构怎么设计。
5. Long 类型 ID 到前端为什么会精度丢失。
6. 文件上传下载怎么联调。
7. 生产环境接口地址怎么切换。

你不需要能从零写复杂页面，但要能和前端协作，定位问题在浏览器、网关、后端还是数据库。

---

## 二、Vue3 核心概念

### 2.1 Composition API

Vue3 常用 `setup` 或 `<script setup>` 组织逻辑：

```vue
<script setup lang="ts">
import { ref, computed } from 'vue';

const count = ref(0);
const double = computed(() => count.value * 2);

function increase() {
  count.value++;
}
</script>
```

特点：

1. 逻辑按功能聚合，而不是按 data/methods/computed 分散。
2. 更适合 TypeScript。
3. 方便把通用逻辑抽成 hooks。

### 2.2 ref 和 reactive

| API | 适合 |
| --- | --- |
| `ref` | 基本类型、单个值、也可以包对象 |
| `reactive` | 对象、表单模型 |
| `computed` | 派生状态 |
| `watch` | 监听变化执行副作用 |

常见坑：

```ts
const count = ref(0);
console.log(count.value);
```

在脚本里要 `.value`，模板里会自动解包。

### 2.3 生命周期

常用：

1. `onMounted`：组件挂载后请求数据。
2. `onUnmounted`：清理定时器、事件监听。
3. `onActivated`：keep-alive 激活。
4. `onDeactivated`：keep-alive 失活。

后台系统列表页要注意：离开页面时取消未完成请求、清理定时器，避免内存泄漏和重复请求。

---

## 三、TypeScript 常见问题

### 3.1 为什么前端要用 TS

TypeScript 的价值：

1. 接口数据结构更清晰。
2. 组件 props 和 emit 更安全。
3. 重构时更容易发现错误。
4. IDE 提示更好。

后端协作点：

> 后端接口字段稳定、类型明确，前端 TS 才能发挥价值。字段一会儿数字一会儿字符串，会让前端类型和页面判断都变复杂。

### 3.2 接口类型定义

```ts
interface UserVO {
  id: string;
  username: string;
  realname: string;
  status: '0' | '1';
}
```

后端 Long ID 建议前端用 string 接收，避免超过 JavaScript 安全整数范围。

### 3.3 any 的问题

`any` 会绕过类型检查。项目里可以在边界少量使用，但核心业务数据应定义明确类型。

---

## 四、Vite 构建与环境变量

### 4.1 Vite 是什么

Vite 是现代前端构建工具，开发环境利用原生 ESM 启动快，生产环境通常用 Rollup 打包。

常见命令：

```bash
pnpm dev
pnpm build
pnpm preview
```

### 4.2 环境变量

常见文件：

```text
.env
.env.development
.env.production
```

前端只会暴露特定前缀的变量，如 `VITE_`：

```text
VITE_GLOB_API_URL=/jeecgboot
```

生产环境接口地址通常由：

1. 前端环境变量。
2. Nginx 反向代理。
3. 网关路由。

共同决定。

---

## 五、路由、菜单和权限

### 5.1 动态路由

后台系统通常登录后拉取用户菜单：

```text
登录成功
  -> 获取 token
  -> 获取用户信息
  -> 获取菜单权限
  -> 转成前端路由
  -> 动态 addRoute
```

### 5.2 菜单权限和接口权限

菜单权限控制“看不看得到入口”，接口权限控制“能不能真正访问”。

安全原则：

> 前端权限只改善体验，不能作为安全边界。用户可以绕过页面直接请求接口，所以后端必须校验接口权限和数据权限。

### 5.3 按钮权限

前端常用指令或组件控制按钮：

```vue
<a-button v-auth="'user:add'">新增</a-button>
```

后端仍要在新增接口做权限校验。

---

## 六、Pinia 状态管理

Pinia 用于管理跨组件共享状态，例如：

1. 当前用户。
2. token。
3. 菜单和路由。
4. 字典缓存。
5. 全局配置。

示意：

```ts
export const useUserStore = defineStore('user', {
  state: () => ({
    token: '',
    userInfo: null as UserVO | null,
  }),
  actions: {
    setToken(token: string) {
      this.token = token;
    },
  },
});
```

注意：敏感信息不要无脑放 localStorage。token 存储要结合 XSS、刷新体验和安全要求权衡。

---

## 七、Axios 接口封装与联调

### 7.1 请求拦截器

常见处理：

1. 添加 token。
2. 添加租户 id。
3. 添加请求 id。
4. 处理重复请求。
5. 统一 loading。

```ts
request.interceptors.request.use((config) => {
  config.headers.Authorization = `Bearer ${token}`;
  return config;
});
```

### 7.2 响应拦截器

常见处理：

1. 统一判断业务 code。
2. 401 跳登录。
3. 403 提示无权限。
4. 文件流特殊处理。
5. 错误消息统一展示。

### 7.3 跨域问题

跨域是浏览器同源策略导致的，不是后端接口本身不能访问。

解决方案：

1. 开发环境 Vite proxy。
2. 后端配置 CORS。
3. 生产环境 Nginx 或网关同域代理。

注意带 Cookie 的跨域请求需要额外配置 `credentials`，且不能使用通配 `*`。

### 7.4 文件下载

后端要设置：

```text
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="xxx.xlsx"
```

前端要按 blob 处理。后端返回错误时，如果仍是 blob，前端还要能解析错误信息。

---

## 八、表单、表格和后台系统体验

后台系统常见页面：

1. 查询表单。
2. 数据表格。
3. 新增编辑弹窗。
4. 导入导出。
5. 批量操作。
6. 详情页。

后端协作建议：

1. 分页接口统一 `pageNo`、`pageSize`、`total`、`records`。
2. 枚举字段提供字典接口或固定约定。
3. 日期格式统一。
4. 金额字段单位明确，元还是分。
5. 后端校验错误返回字段和信息，方便前端定位。
6. 批量操作返回成功数和失败原因。

---

## 九、构建部署常见问题

### 9.1 生产刷新 404

Vue Router history 模式需要 Nginx 配置 fallback：

```nginx
try_files $uri $uri/ /index.html;
```

否则刷新二级路由时，服务器会按真实路径找文件，导致 404。

### 9.2 静态资源路径错误

常见原因：

1. base 配置不对。
2. 应用部署在子路径。
3. Nginx root/alias 配置不对。
4. CDN 路径不一致。

### 9.3 接口 404 或跨域

排查：

```text
浏览器 Network 请求地址
  -> 是否命中前端代理或 Nginx
  -> 网关路由是否正确
  -> 后端 context-path 是否正确
  -> CORS 响应头是否正确
```

---

## 十、面试高频回答模板

### 10.1 Vue3 和 Vue2 有什么主要区别

> Vue3 引入 Composition API，更适合按业务逻辑组织代码，也更适合 TypeScript。响应式底层从 Object.defineProperty 转向 Proxy，能力更强。Vue3 还改进了性能、Tree Shaking 和组件类型推导。实际项目里，Vue3 常搭配 Vite、Pinia、Vue Router 4 和组件库做后台系统。

### 10.2 前端权限怎么做

> 登录后前端根据后端返回的菜单和权限标识生成动态路由，并控制菜单和按钮展示。但前端权限只是体验层，真正安全必须在后端接口鉴权和数据权限校验。否则用户可以绕过页面直接请求接口。

### 10.3 token 前端怎么携带

> 一般登录后保存 token，请求拦截器统一加到 Authorization Header。后端过滤器解析 token，构建当前用户上下文。token 存 localStorage 使用方便但怕 XSS，存 Cookie 要关注 CSRF 和 SameSite，所以要结合系统安全要求决定，并配合过期、刷新和强制下线机制。

### 10.4 跨域怎么排查

> 先看浏览器 Network 里的请求地址、方法和响应头。开发环境可以用 Vite proxy 代理到后端，生产环境通常用 Nginx 或网关做同域代理。后端 CORS 要允许正确的 Origin、Method、Header。如果带 Cookie，还要开启 credentials，且 Origin 不能是 `*`。

### 10.5 后端怎么配合前端减少联调问题

> 接口返回结构要统一，字段类型要稳定，分页、日期、金额、枚举、错误码要有规范。Long 类型 ID 最好给前端字符串，避免 JS 精度丢失。权限、租户、字典、导入导出这类通用能力要形成统一约定，否则每个页面都会重复踩坑。

