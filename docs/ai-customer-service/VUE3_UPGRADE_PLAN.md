# 小智 Server 管理前端 Vue 3 升级计划

执行进度（2026-10-30）：独立 Vue 3 工程迁移、默认 Dev/构建入口切换和 B1 人工文字工作台整合已完成。源码继续保留在 `main/manager-web-next`，旧 Vue 2 目录保留回退；实际验证和未完成范围见 [B1 验证记录](SUPPORT_B1_VALIDATION.md)。以下保留原实施计划。

- 版本：V1.0
- 日期：2026-10-30
- 执行分支：`Dev`
- 代码基线：`ed10d462d39644364d86db30c692a7236983db16`
- 仓库：<https://github.com/lonycn/xiaozhi-server>
- 本机仓库：`/Users/lengwang/code/esp32/backend/xiaozhi-server/repo`

本次升级将现有管理前端迁移到 Vue 3、Vite 和 Element Plus，保留登录、智能体、设备、模型配置和统一对话的业务行为。采用临时候选前端逐模块迁移，现有 Dev 页面在迁移过程中继续使用，全部验收后再切换默认入口。

本文件是后续 Codex 的实施任务书。本轮仅交付计划；创建候选应用、安装依赖、修改前端和切换服务均由新的实施任务执行。

## 1 升级目标与范围

### 1.1 交付目标

1. 使用 Vue 3 及其维护中的组件生态，移除对 Vue 2 和 Element UI 2 的运行依赖。
2. 用 Vite 替代 Vue CLI 和 Webpack，保留开发代理、热更新、静态资源与生产部署能力。
3. 保留现有路由、权限、六种界面语言、业务表单、消息历史和音频处理行为。
4. 将登录和公共配置迁入唯一的 Pinia 状态源，继续使用现有 manager-api。
5. 让统一对话页面成为后续人工客服、媒体标注和音视频工作的可维护前端基础。
6. 建立可重复安装、构建、测试、切换和回退的方法，最终恢复一个主维护前端目录。

Vue 官方已结束 Vue 2 的维护，Vue 3 官方工程工具采用 Vite。升级的长期方向有依据，具体迁移顺序则按本项目的业务依赖确定。[Vue 2 维护说明](https://v2.vuejs.org/eol/)、[Vue 工程入门](https://vuejs.org/guide/quick-start)。

### 1.2 保留边界

| 范围 | 本次处理 |
| --- | --- |
| `main/manager-web` | 现有 Vue 2 基线，候选前端验收完成前保留 |
| `main/manager-api` | 继续作为业务和身份控制面，保持接口与鉴权语义 |
| `main/xiaozhi-server` | 保持 ESP32、模型、OTA、WebSocket、MCP 链路 |
| MySQL、Redis、上传文件 | 沿用 Dev 独立环境，迁移前端无需重建数据 |
| 已部署的本机智控台 | 继续运行，不用候选前端替换它 |
| AI 回复、附件上传、人工客服、LiveKit | 按原多模态规划另行实现，本次只保留接入位置 |
| UI 设计 | 保留现有品牌、信息结构和关键操作，允许改善一致性及响应式布局 |

这次不同时进行全仓 TypeScript 改写、后台权限重建、数据库迁移或完整视觉重设计。已有 Options API 组件可以继续使用；新基础模块可采用 Composition API。[Vue API 风格说明](https://vuejs.org/guide/introduction#api-styles)。

## 2 当前源码与开发环境

### 2.1 源码规模和依赖

当前 `main/manager-web/src` 有 **61 个 Vue 单文件组件**，其中 `views` 目录 24 个，其他组件 37 个。静态匹配包含约 54 处 `.sync`、45 处 `.native`、130 处旧插槽写法、3 处 `$scopedSlots`、9 处旧销毁钩子。这些是迁移检查点数量，不等于缺陷数量。

| 项目 | 当前状态 | 对迁移的影响 |
| --- | --- | --- |
| Vue | `^2.6.14` | 替换应用创建和插件注册方式 |
| UI | Element UI `^2.15.14` | 表单、表格、弹窗、图标需逐项适配 |
| Router | `^3.6.5`，Hash 路由 | 保留 `/#/...`，迁移路由创建与守卫 |
| 状态 | Vuex `^3.6.2` | 登录、公共配置、API 请求与状态耦合 |
| 国际化 | vue-i18n `^8.28.2` | 迁移初始化、locale 响应性及组件库语言 |
| 构建 | Vue CLI 5、Webpack | 重做环境变量、代理、资源与缓存配置 |
| 请求层 | Flyio 回调封装，统一对话使用独立 fetch client | 保留旧接口调用契约并统一错误与刷新行为 |
| 测试 | Vitest 1、Vue Test Utils 1、Node 契约/快照测试 | Vue 3 使用原生 SFC 挂载测试，迁移旧测试工具 |
| PWA | Workbox、自定义 Service Worker、可选 CDN | 缓存中含旧 Vue 运行时，需要专门切换策略 |
| Docker 构建 | `Dockerfile-web` 使用 `node:18` 和 `npm install` | 不能直接构建目标工具链；应升级 Node 并使用锁文件 |

### 2.2 当前 Dev 环境

| 项目 | 当前值 |
| --- | --- |
| Git 分支 | `Dev`，当前已与远端同步 |
| 前端 | `http://127.0.0.1:18892` |
| Dev API | `http://127.0.0.1:18893/xiaozhi` |
| 数据服务 | `xiaozhi-server-dev` 独立 MySQL、Redis 和持久卷 |
| 启停 | `python3 dev/manage.py up --build`、`status`、`stop` |
| 当前 Node/npm | Node `22.23.1`、npm `10.9.8` |
| 统一对话 | 新建、文字保存、历史读取、位置展示；没有 AI 自动回复 |

实施前重新核对当前分支和未提交修改。后续提交可能已经超过本文基线，不要重置用户工作。

### 2.3 本机特别注意事项

- 当前 checkout 使用 **sparse checkout**。`.github/workflows`、部分部署文档未显示在工作目录时，先通过 Git 树确认，再按需取出，不能据此认定文件不存在。
- 当前 `main/manager-web/node_modules` 是指向本机旧智控台安装目录的符号链接。候选应用必须拥有自己的依赖目录和锁文件，不能向该符号链接中安装 Vue 3。
- 仓库忽略规则会忽略新目录的 `package-lock.json` 等部分 JSON 文件。提交时核对新应用配置和锁文件确实被跟踪；仅对确定的源码文件使用精确的 `git add --sparse -f -- <文件>`。
- `dev/.runtime` 含配置、数据库副本和私有凭据，始终保留忽略规则，不加入 Git。

## 3 技术选型

### 3.1 目标栈

| 层次 | 选择 | 说明 |
| --- | --- | --- |
| 应用 | Vue 3 | 保留可用的 Options API，新增通用逻辑采用 Composition API |
| 工具链 | Vite、`@vitejs/plugin-vue` | 不将整个 Node `process.env` 注入浏览器 |
| UI | Element Plus、官方图标组件 | 与现有 Element 交互较接近，优先减少表单和表格重写 |
| 路由 | Vue Router 的 Vue 3 版本 | 首批使用兼容的 4.x，保持手工路由和 Hash 模式 |
| 状态 | Pinia | 一个认证状态源，旧请求层可使用短期适配器 |
| 国际化 | Vue I18n 的 Vue 3 版本 | 保留原六种语言和 key，逐步采用 Composition 模式 |
| 测试 | Vitest、Vue Test Utils 2 | 测试真实渲染、交互与状态，不只测试脚本文本 |
| 包管理 | npm + `package-lock.json` | 独立安装，CI 使用 `npm ci` |
| 开发预览 | 候选前端端口 18894 | 与现有 18892 并存，使用同一个 Dev API |

本次采用 Vue 官方工具链和 Element Plus，不引入一整套包含自有登录、权限、用户中心和请求协议的后台模板。确需采用管理后台框架时，应先验证许可证、维护情况、现有 SM2 登录与 Shiro 权限的适配成本，再记录替代决定。

Element Plus 提供 Element 2 到 Element Plus 的迁移说明，但迁移构建仍有组件兼容限制。因此本计划不以 `@vue/compat` 加载整套旧 Element UI 作为默认方案。[Element Plus 迁移说明](https://element-plus.org/en-US/guide/migration)。

### 3.2 版本锁定建议

以下是 2026-10-30 查询 npm 官方 registry 后得到的**候选组合**。它们用于开始兼容验证，完成最小工程测试后才能成为最终锁文件。

| 包 | 建议起始版本 |
| --- | --- |
| `vue`、`@vue/compiler-sfc` | `3.5.44`，两者保持一致 |
| `vite` | `8.3.5` |
| `@vitejs/plugin-vue` | `6.0.10` |
| `vue-router` | `4.6.4` |
| `pinia` | `3.0.4` |
| `element-plus` | `2.14.7` |
| `vue-i18n` | `11.4.16` |
| `@vue/test-utils` | `2.5.1` |
| `vitest`、覆盖率插件 | `5.0.5`，版本一致 |
| Node/npm | 优先使用当前已安装 Node `22.23.1`、npm `10.9.8` |

查询时 `vue-router` 的 `latest` 已是 `5.4.0`，`pinia` 的 `latest` 已是 `4.1.0`。本轮先选仍可获取且未标记废弃的兼容版本，控制额外迁移范围；执行时重新核对维护状态和 peer dependencies。若兼容线已不合适，优先改为受维护的版本，并记录验证结果，不能机械安装 `@latest`。

版本查询可在 npm 官方 registry 的包元数据核对，例如 [Vue](https://registry.npmjs.org/vue/latest)、[Vite](https://registry.npmjs.org/vite/latest)、[Vue Router](https://registry.npmjs.org/vue-router)、[Pinia](https://registry.npmjs.org/pinia)。Vite、插件、测试器和国际化库各自的 Node 条件不同，应按选定组合的最高要求锁定环境。[Vite 环境要求](https://vite.dev/guide/)。

新增 Pinia 等包所需的 TypeScript 开发依赖可以安装，但允许旧 JavaScript SFC 迁入，不把业务代码全面类型改写作为首阶段前置条件。

## 4 迁移方式与目录安排

### 4.1 候选应用先并存

在同一仓库临时增加 `main/manager-web-next`，承载 Vue 3 候选应用；现有 `main/manager-web` 继续运行。两者共用 Dev manager-api、用户和权限，不建设第二套后台服务。

```text
main/
  manager-web/                 现有 Vue 2 基线
  manager-web-next/            临时 Vue 3 候选应用
    package.json
    package-lock.json
    index.html
    vite.config.ts
    src/
      main.ts
      App.vue
      router/
      stores/                 auth 和公共配置
      apis/                   请求、刷新与业务模块
      adapters/               必要的短期兼容层
      components/
      views/
      i18n/
      assets/
      styles/
    tests/
dev/
  manage.py                   增加候选前端管理能力
docs/ai-customer-service/
  VUE3_UPGRADE_PLAN.md
```

候选应用只迁入已准备好的模块。尚未迁入的管理功能可以明确链接回旧后台，不将 Vue 2 组件直接加载进 Vue 3 应用，也不伪造“已迁移”的占位页。

### 4.2 登录与端口

- 旧前端保持 `18892`，Vue 3 候选前端使用 `18894`，后端保持 `18893`。
- 两个前端均以相对 `/xiaozhi` 请求，通过各自开发代理连接 Dev API。
- 不同端口属于不同浏览器 origin，`localStorage` 不共享。候选应用应独立完成正常登录，不能通过 URL 参数、跨窗口明文传递 Token 来免登录。
- 初期保留 Hash 路由，例如 `/#/login`、`/#/home`、`/#/conversations`。路由基路径通过 `createWebHashHistory(base)` 设置。[Router 迁移说明](https://router.vuejs.org/guide/migration/)。
- 当前尚未要求服务端跨前端单点登录。本次不扩展 SSO 或改变 Token 策略。

### 4.3 最终收敛

全部业务和部署验收通过后，将 Vue 3 候选应用提升为正式的 `main/manager-web`。旧源码保存在明确的 Git 回退提交或标签中，停止维护临时候选目录和双套依赖。候选工程不是永久增加第二个管理后台。

## 5 必须完成的代码适配

| 当前入口或写法 | 迁移任务 | 重点验收 |
| --- | --- | --- |
| `src/main.js` 的 `new Vue`、`Vue.use` | 改为 `createApp` 和应用级插件安装 | 初始化只执行一次，首次请求带有效 Token |
| `Vue.prototype.$eventBus` | 语言切换优先改为 Pinia/i18n 响应性；确有跨组件事件才用轻量 emitter | 不重复监听，卸载时释放 |
| `src/router/index.js` | `createRouter`、Hash history、守卫和按需加载 | 原路径可达；重复导航不强制整页刷新 |
| `src/store/index.js` | auth、userInfo、pubConfig 迁入 Pinia | 持久化、退出、刷新和权限一致 |
| `src/i18n/index.js` | `createI18n` 与 Element Plus locale | 简中、繁中、英、德、越、葡巴不丢 key |
| `.sync` | 组件显式 prop 与 `update:xxx` 事件，必要时 `v-model:xxx` | 不盲目把所有 visible 改成同一 prop |
| Element 弹窗 `visible` | 按 Element Plus API 适配为 modelValue/v-model | 取消、关闭、保存、loading、嵌套弹窗正确 |
| `slot`、`slot-scope`、`$scopedSlots` | 具名 `#slot`、`$slots` 与作用域参数 | 自定义表格、行操作、tooltip、选择列一致 |
| `.native` | 明确组件 emits，使用实际事件或组件根节点事件策略 | Enter、blur、paste、drop 不重复触发 |
| `beforeDestroy`、`destroyed`、实例事件 API | 新生命周期，显式清理监听器与异步任务 | 页面切换无重复请求、录音和计时器泄漏 |
| Element 字体图标 | 官方图标组件或统一图标包装 | 不出现方块字，图标按钮有可读名称 |
| `Vue.set`、`$set` 等 | 使用 Vue 3 响应式赋值 | 统一对话各会话草稿仍独立响应 |
| `require`、动态图片路径 | ESM 静态 import、资源映射或 `import.meta.glob` | 品牌、语言 logo、用户头像、文件类型图正常 |
| `src/utils/index.js` 的 `require('sm-crypto')` | 核实包的 ESM/CommonJS 导入方式后适配 | SM2 前缀、模式和后端解密一致 |
| `public/index.html` 的 EJS 模板 | Vite HTML 与环境变量替换 | 标题、meta、favicon、base 路径正确 |
| `vue.config.js` | Vite 配置、代理、alias、生产路径与资源策略 | 不依赖浏览器中的全局 `require`、`process` 或 Buffer 假垫片 |
| `src/components/AudioPlayer.vue` 等 | 核对音频库、WASM、Worker、对象 URL 的打包和清理 | 实际可播放，不只通过构建 |
| `v-code-diff`、`vue-axios` 等 | 检查 Vue 3 兼容导出；不用的包装依赖再删除 | 快照差异视图和网络功能不回归 |
| 自定义 CSS 对 `.el-*` 的覆盖 | 逐项适配 Element Plus DOM 和弹层 | 表单、表格、滚动、层级、移动布局正确 |

组件命名、事件和样式的变化必须按模块核对，不用全局搜索替换代替交互验证。

## 6 身份与请求契约

### 6.1 登录流程

候选前端继续调用现有 `pub-config`、验证码、登录、用户信息和刷新接口。保留现有请求字段和加密过程：取得服务端 SM2 公钥，按现有规则将验证码与密码组合，使用相同模式加密并保留 `04` 前缀，再提交 `captchaId` 等字段。

保留 Token DTO 的 `token`、`expire`、`clientHash` 及本地 `_localStoredAt` 语义；具体持久化转换先阅读当前实现，不自行猜测字段单位。只在需要修复已确认问题时调整行为，并补测试。

首次进入受保护页面前恢复持久化身份；退出时清理 auth、userInfo 及相关会话视图。登录失败、验证码失效、Token 到期与 401 必须可恢复。

### 6.2 刷新与 API 兼容

原 `src/apis/httpRequest.js` 已有接近过期时的单飞刷新逻辑。新请求层保留“并发请求只刷新一次、刷新后用新 Token 发请求、失败后统一退出”的业务保证，不建立多个互不协调的刷新器。

旧业务模块的回调调用方式可在短期适配器中保留，统一底层请求和认证来源；新模块优先 Promise。不要同一阶段重写所有 API 方法、后端路径和组件逻辑。

响应仍为 `{code,msg,data}`。需要同时处理旧接口的 HTTP 200 业务错误，以及统一对话接口实际返回的 HTTP 400/401/403/404/409/415。不能将失败响应当成空成功数据。

`conversationClient.mjs` 的可注入 transport、幂等编号、超时提示与错误映射可复用。现有 API 文档仍以 `docs/ai-customer-service/OPENAPI.yaml` 和 `MESSAGE_SCHEMA.json` 为准。

### 6.3 状态和权限

Pinia 是候选应用唯一认证状态源；若旧模块还通过 `store.commit/getters` 访问，兼容层只转发到同一个 Pinia store，不缓存第二份身份。

菜单权限与功能开关都保留，服务端仍执行最终权限判断。保留 `conversation-enabled` 和前端对话入口开关，不能因升级默认开放尚未完成的能力。

## 7 环境变量 静态资源与缓存

### 7.1 环境变量映射

| Vue CLI 当前值 | Vite 目标 |
| --- | --- |
| `VUE_APP_API_BASE_URL` | `VITE_API_BASE_URL`，Dev 为 `/xiaozhi` |
| `VUE_APP_CONVERSATION_ENABLED` | `VITE_CONVERSATION_ENABLED`，显式比较字符串 `true` |
| `VUE_APP_ENV_LABEL` | `VITE_ENV_LABEL` |
| `VUE_APP_PUBLIC_PATH` | Vite `base` 与 `import.meta.env.BASE_URL` |
| `VUE_APP_TITLE`、description、keywords | 对应公开 `VITE_*` 与 HTML 替换 |
| `VUE_APP_H5_URL` | 对应公开配置，保留现有移动跳转语义，先验证再启用 |
| `VUE_APP_API_PROXY_TARGET` | Vite 配置端的代理变量，默认指向 `127.0.0.1:18893` |

`VITE_*` 会进入浏览器构建产物，只允许公开配置。百炼、OSS、LiveKit、数据库和服务间密钥继续留在服务端；不能整体映射 `process.env`。[Vite 环境变量说明](https://vite.dev/guide/env-and-mode.html)。

### 7.2 资源和音频

迁移时保留 `public/generator/static/fonts` 等路径，这些字体也被 Java 容器用于验证码。检查 `Dockerfile-web` 和 `dev/compose.yml` 中的字体挂载/复制，不将它们当作无关前端素材删除。

动态头像、语言 logo、文件图标应改为显式资源表或可分析的 glob。验证音频解码库的 WASM/Worker URL、MIME、开发代理与生产相对路径，不能仅在本机开发模式下可用。

### 7.3 Service Worker 切换

当前 Service Worker 包含 Vue 2、Router 3、Vuex 3、Element UI 的 CDN 地址。Vue 3 候选应用默认不注册这些旧缓存脚本，先用本地打包资产完成核心功能。

正式接管原 origin 前，单独实现并验证缓存迁移：仅处理属于本应用的旧 registration/scope 和缓存名称，释放旧运行时、HTML 和静态资源缓存；保留用户数据及其他应用缓存。不能依靠要求用户手工“清空所有浏览器数据”完成升级。

如果最终继续提供离线/PWA 能力，用候选构建工具验证的 Workbox 方案恢复；尚未恢复时明确列入验收差异，不静默删除既有对外能力。

## 8 分阶段实施任务

每个阶段在 `Dev` 保存可审核的小批提交，更新迁移清单和验证结果。候选前端失败时修复候选应用，旧预览继续工作。

### M0 基线与版本锁定

- 读取仓库指引、本文、原多模态规划、`dev/README.md`、当前路由、状态、认证、构建及测试配置。
- 核对分支、未提交修改、Node、符号链接、稀疏检出、锁文件与忽略规则。
- 建立页面清单，逐项标注是否有真实入口、角色/功能条件、迁移和验收状态。
- 记录旧前端开发/生产构建、测试与关键页面截图基线；恢复当前开关与用户状态。
- 验证候选版本 peer dependencies 与最小工程，锁定独立 package-lock 和 Node 配置。

**完成条件：** 原预览正常，迁移清单和版本决定可复核；不改业务代码与数据库。

### M1 候选工程 登录与统一对话

- 新建 `main/manager-web-next`，配置 Vue 3、Vite、Element Plus、Router、Pinia、i18n 和测试。
- 创建应用壳、登录、退出、公共配置、权限守卫和唯一请求层。
- 迁入 `ConversationWorkspace.vue` 与 client；保留草稿、中文输入法、并发切换、失败重试和历史分页行为。
- 给 Dev 管理脚本增加独立候选前端启停与 PID 管理，18894 正常预览。

**完成条件：** 正常登录，新建对话、发送、刷新取回历史，401 与重复请求恢复，手机布局可用；旧 18892 正常。

### M2 公共组件与核心管理

- 先迁移 HeaderBar、CustomDialog、CustomTable、CustomPagination、CustomButton 等公共组件。
- 依次迁移首页、智能体、角色、设备、模型与供应器页面。
- 迁移快照显示、恢复、差异视图和表单校验，复核编辑保存和取消行为。
- 迁移语言切换、权限菜单、功能开关与基础移动布局。

**完成条件：** 核心管理业务与旧版一致，没有直接加载 Element UI 2；写操作在 Dev 数据上验证，不操作真实设备控制。

### M3 其余模块与媒体

按以下源码分组迁移，实际入口以路由和组件引用为准：

| 分组 | 页面文件 |
| --- | --- |
| 身份 | `login`、`register`、`retrievePassword` |
| 智能体 | `home`、`roleConfig`、`AgentTemplateManagement`、`TemplateQuickConfig` |
| 设备 | `DeviceManagement`、`AddressBookManagement` |
| 配置与权限 | `ModelConfig`、`ProviderManagement`、`ParamsManagement`、`DictManagement`、`FeatureManagement`、`ServerSideManager`、`UserManagement` |
| 知识库 | `KnowledgeBaseManagement`、`KnowledgeBaseItem` |
| 语音资源 | `VoicePrint`、`VoiceCloneManagement`、`VoiceResourceManagement`、`ReplacementWordManagement` |
| 固件管理 | `OtaManagement` |
| 统一对话 | `ConversationWorkspace` |

保留上传、进度、下载、音频播放、历史查看、分页筛选、验证码和变更密码等子流程。开发环境缺少外部服务的项目保留开关和正确错误提示，不能用假成功替代业务结果。

**完成条件：** 每个原有页面都有迁移状态与相应测试，媒体模块有真实浏览器验证；候选应用仍不接管 ESP32。

### M4 构建 部署和缓存迁移

- 将 `Dockerfile-web` 的 Node 18 构建阶段改为已锁定、满足工具链的 Node 版本，使用 `npm ci`。
- 核对 CI 的 `test.yml`、`docker-image.yml`、构建路径、Docker COPY、Makefile 与资源路径；稀疏检出缺失文件先取出再编辑。
- 建立生产静态预览，验证 base 路径、Hash 深链、API 代理、音频资源和刷新。
- 验证缓存升级、版本变化与回退行为，保持候选 Dev 不污染原入口的缓存。

**完成条件：** 干净依赖安装可重复，候选开发与生产构建均可使用，浏览器升级缓存后不再加载旧 Vue 2。

### M5 默认切换与目录收敛

- 对所有页面完成验收矩阵，处理未解决项；外部服务实测缺口与现有能力对照说明。
- 记录最后可回退的 Vue 2 提交或标签，保留部署和缓存回退路径。
- 将已验收候选应用提升为 `main/manager-web`，同步构建、Dev 脚本、CI、文档和忽略规则。
- 在 Dev 将默认 18892 切为 Vue 3；原运行智控台及生产部署作为另外的发布步骤处理。
- 候选目录退场，清除 Vue 2、旧模板编译器、Element UI 2、Vuex 3 和 Vue CLI 的最终运行依赖。

**完成条件：** Dev 默认入口运行 Vue 3，主维护前端只有一套，工作树和远端 Dev 提交一致，回退路径可执行。

## 9 测试与验收

### 9.1 测试迁移

当前前端最近一轮记录为 76 项单元测试、10 项契约测试，另有独立快照测试脚本。这是 Vue 2 的基线，不是 Vue 3 已通过的证明。

保留原业务断言，逐项迁移测试环境。纯工具/client 测试尽量原样复用；现有通过 `vue-template-compiler`、`parseComponent`、`new Function` 和 `new Vue` 运行组件脚本的测试，改为 Vue 3 SFC 的真实挂载与交互。插件和 mock 按 Vue Test Utils 2 的 `global` 配置处理。[测试工具迁移说明](https://test-utils.vuejs.org/migration/)。

不以删除失败测试、降低断言或只比较源码字符串的方式宣称迁移成功。后端未改动时不需要每个 UI 提交都重跑全仓；切换前完成受影响接口和整体回归。

### 9.2 必须通过的业务检查

| 类别 | 验收场景 |
| --- | --- |
| 登录 | 正确/错误密码，验证码失效、刷新，真实 SM2 登录；不绕过验证码或服务端鉴权 |
| 身份恢复 | 登录后刷新、深链进入、过期 Token、损坏本地值、主动退出 |
| 并发刷新 | 多个请求只刷新一次；新 Token 生效；失败统一退出，避免死循环 |
| 权限 | 普通用户/管理员菜单、功能开关、受保护路由、无权限 API 错误 |
| 业务配置 | 新建/编辑/取消，JSON 字段、校验错误、分页筛选、快照与差异视图 |
| 对话 | 创建、发送、刷新历史、跨会话草稿、失败保留、同编号重试去重、快速切换防旧响应覆盖 |
| 中文输入 | IME 候选确认不发送，Enter 发送、Shift+Enter 换行 |
| 语言 | 六种语言恢复与切换、组件库 locale、缺失 key、日期和时间展示 |
| 弹层 | 表单聚焦、遮罩、Esc/关闭、确认、loading、嵌套 z-index、滚动锁 |
| 文件与声音 | 上传/下载、错误与进度、对象 URL 释放、音频播放和停止；外部服务另记真实结果 |
| 布局 | 桌面与至少 390px 手机宽度，无横向溢出，输入区和弹窗可操作 |
| 部署 | 生产预览、深链刷新、API 代理、资源 MIME、WASM/Worker、字体 |
| 更新缓存 | 从旧版本到 Vue 3，无旧运行时混装；回退后恢复正常 |

浏览器验证至少包括 URL/标题、页面非空、无框架错误遮罩、控制台无相关错误，以及实际点击路径与截图。构建通过不替代登录、发送、音频和生产静态预览的验证。

### 9.3 命令约定

现有命令保持可用：

```sh
cd main/manager-web
npm test
npm run test:snapshot
npm run build
```

以下是**候选工程创建后应提供的脚本**，现在尚不存在，实施时补齐：

```sh
cd main/manager-web-next
npm ci
npm run dev
npm run test:unit
npm run test:contract
npm run build
npm run preview
```

具体测试脚本按迁移后的真实目录组织；必须确保契约、组件、纯工具和快照覆盖实际执行，避免脚本空跑。若新增类型检查，再提供对应命令并在 CI 运行。

## 10 开发切换与回退

### 10.1 候选环境管理

计划扩展 `dev/manage.py`，支持独立启动、停止和查看 Vue 3 候选进程。命令参数由实现阶段确定，并同步到 `dev/README.md`；旧 `up/status/stop` 的默认行为在切换验收前保留。

PID 管理继续校验进程身份和启动时间，避免停止别的服务。新增前端占用 18894 前检查端口，不通过强杀占用进程解决冲突。MySQL/Redis 仍使用既有 Dev 容器和持久卷。

### 10.2 回退触发条件

登录/权限失败、关键表单数据错误、对话丢失或重复、音频功能回归、生产资源加载失败、缓存混装时，停止默认切换，保持或恢复旧 Dev 前端入口。不能以改后端鉴权、清空数据或关闭测试来规避问题。

回退采用明确的前端提交、构建产物和启动配置。前端升级不需要数据库恢复；保留 Dev 数据、账号和上传文件。候选应用未切换时，停止候选进程即可。

最终上线到原智控台或其他服务器时，单独准备版本、构建与缓存回退方案；不要因 Dev 前端升级而自动部署到现有运行环境。

## 11 每阶段应保存的产物

- 可运行的代码提交和准确的依赖锁文件。
- 页面/功能迁移清单，包含来源、目标、状态和未完成项。
- 版本选择和兼容偏离说明。
- 受影响测试结果、浏览器验证及必要的桌面/手机截图。
- 开发启动、生产构建、切换与回退说明。

全项目完成标准为：Dev 默认前端确实运行 Vue 3，旧业务功能通过验收，构建和部署可重复，旧运行依赖退出，数据与后端协议保持兼容。只有新建一个 Vue 3 首页或把版本号改成 3，不算完成升级。

## 12 给新 Codex 对话的起始指令

将以下内容作为新对话的第一条消息即可：

```text
请在 /Users/lengwang/code/esp32/backend/xiaozhi-server/repo 中，基于 Dev 分支实施 Vue 3 升级。

先阅读：
1. docs/ai-customer-service/VUE3_UPGRADE_PLAN.md
2. dev/README.md
3. docs/ai-customer-service/DECISIONS.md
4. 仓库根目录 xiaozhi-server-multimodal-support-plan-v1.md

先确认当前提交和未提交修改。当前基线是 ed10d462d39644364d86db30c692a7236983db16，
如果 Dev 已有后续提交，以实际最新代码为准，不重置用户工作。

按计划先完成 M0，再实现 M1：在 main/manager-web-next 创建独立的 Vue 3 + Vite +
Element Plus 候选应用，接入现有 Dev API、正常登录、Pinia、路由与统一对话。
保留旧 Dev 18892，候选使用 18894，API 保持 18893。

注意现有 manager-web/node_modules 是符号链接；新工程使用自己的依赖目录和锁文件。
保留 SM2、验证码、Token 刷新、服务端权限和现有接口，不复制第二套用户或业务后端。
完成真实登录、新建会话、发送、刷新历史与错误恢复测试，再逐阶段迁移其余页面。

所有日常开发与可审核提交保存在 Dev。按阶段交付代码、测试和文档，
迁移期间保持旧预览可用。完整验收后再提升 Vue 3 为默认前端。

本次任务是前端升级。不要同时实现尚未完成的 AI 回复、人工客服、附件或音视频；
不要重建数据库、修改 ESP32 固件/OTA/MCP，也不要自动部署到原运行智控台。
```
