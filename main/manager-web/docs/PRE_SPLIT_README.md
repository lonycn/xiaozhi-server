# 智控台 Vue 3 开发版

管理页面统一使用 `src/layouts/AdminLayout.vue`：顶部账户与语言菜单、按权限和功能开关过滤的左侧导航、面包屑、可关闭的页面页签及右侧业务内容。桌面侧栏可收起并保存偏好；960px 以下改用遮罩式导航，选择页面或按 Escape 收起。角色、设备和声纹页面保留 agentId 查询参数，并高亮所属智能体菜单。页面离开时仍正常卸载以释放轮询、录音和播放资源，页签用于导航，不缓存已卸载页面的表单。新增业务页只编写内容，不再重复引入 HeaderBar 和页脚。

本目录是 `Dev` 分支上的独立 Vue 3 前端，按 `docs/ai-customer-service/VUE3_UPGRADE_PLAN.md` 迁移。原 `main/manager-web` 保留；两个前端共用现有 Dev API，不创建第二套用户或业务后端。后续前端迭代在本目录进行。

## 启动

推荐 Node.js 22.23.1，版本记录于 `.nvmrc`。在本目录执行 `npm ci`，使用自己的 `node_modules`，不要链接到旧前端的依赖目录。

在仓库根目录执行：

```sh
python3 dev/manage.py up
python3 dev/manage.py up
python3 dev/manage.py status
```

访问 <http://127.0.0.1:18894/#/conversations>，登录账号沿用本机 Dev 智控台。旧前端可通过 `python3 dev/manage.py legacy-up` 在 18892 启动，Dev API 在 18893。需要停止新前端时执行 `python3 dev/manage.py candidate-stop`，该命令保留后端与 Dev 数据。

也可直接在本目录执行 `npm run dev`。可选环境变量见 `.env.example`；私有覆盖放在忽略提交的 `.env.local`。`VITE_*` 是会进入浏览器的公开配置，禁止放入服务密钥。开发和静态预览默认代理 `/xiaozhi` 到 18893。

## 已迁移内容

| 范围 | 实现 |
| --- | --- |
| 框架 | Vue 3.5、Vite 8、Vue Router 4、Pinia、Element Plus、Vue I18n 11，依赖锁文件独立保存 |
| 页面与组件 | 原 24 个视图和 36 个业务组件，以及 App，共 61 个 SFC 均有对应迁移版本；额外提供 SVG 图标适配组件 |
| 登录与权限 | 真实验证码、SM2 `04` 前缀/模式 1、现有 TokenDTO、服务端用户信息与权限；Pinia 为唯一状态源 |
| 请求 | 现有回调 API 与统一对话共用鉴权和单次并发刷新；保留 FormData、JSON、Blob/ArrayBuffer、HTTP 错误语义 |
| 统一对话 | 会话创建、列表和历史分页、文字持久化、草稿、失败重试、中文输入法与位置展示；64 位 ID 保留字符串精度 |
| 业务模块 | 智能体/角色/设备、模型/供应器/参数/字典/模板、用户、知识库、固件、音色/声纹/音频、替换词、通讯录和功能配置 |
| 交互 | Vue 3 插槽、事件、v-model、Element Plus 表单/弹窗/分页、SVG 图标；六种语言及 Element Plus 语言联动 |
| 资源与媒体 | Vite 资源 URL、字体与 Opus worker 路径；播放拒绝处理、组件实例隔离和退出时资源回收 |
| 部署 | 独立 Dockerfile、nginx API 代理模板、Dev/PR 构建工作流；候选前端不注册 Service Worker |

部分文件暂时保留 `from 'vuex'` 写法，由 Vite 精确映射到 `src/adapters/store-helpers.js`。该薄适配层直接读写 Pinia，不包含 Vuex 运行时，也不保存第二份鉴权状态。新功能直接使用 Pinia。

## 验证

```sh
npm ci
npm test
npm run test:snapshot
npm run check:i18n
npm run build
```

测试覆盖 SM2 请求字段、Token 恢复与单次并发刷新、失效跳转、请求错误/上传/二进制、统一对话竞态与字符串游标、Vue 3 实际挂载的模型/业务组件以及媒体清理。六种语言的 1546 个键完成一致性检查。

2026-10-30 本机结果：130 个单元/组件测试、10 个接口/界面契约测试、13 个快照辅助测试及 2 个候选进程安全测试全部通过，生产构建通过；独立临时目录执行 `npm ci` 成功。依赖树仅有 Vue 3，不含 Vue 2、Vuex 或 Element UI 运行时。

本机浏览器已通过真实验证码登录，读取管理员、模型、模板、角色和绑定设备等 Dev 数据，打开模板编辑后取消，切换英文再恢复中文，并发送一条明确标注的 Dev 对话验证消息，刷新后历史仍可读取。登录和统一对话在 390px 手机宽度检查无横向溢出。生产静态预览也通过真实登录与刷新历史读取。浏览器联调不代表所有第三方服务或真实设备已经完成验收。

`npm run build && npm run preview` 可在 18895 验证生产静态产物，预览代理仍指向 Dev API。独立 nginx 镜像的构建/启动命令见 `dev/README.md`；部署时设置 `API_PROXY_TARGET`。构建配置中的 `VITE_PUBLIC_PATH` 可调整资源基础路径。

Docker 验证尝试在下载 Node/nginx 基础镜像元数据时遇到 Docker Hub 连接超时，尚未完成镜像构建、容器静态文件与容器代理验收。GitHub Actions 工作流已提供，是否通过应以远端实际运行结果为准。

## 本次边界与回退

框架迁移后已整合B1人工文字、B2实际AI文字执行、B3/B4私有附件与截图标注、非实时Omni、B5真人媒体/换房转接、P3手动分段实时AI及内部交接草稿。原Dev18894/API18893与原S3已恢复：Omni录音/有声视频、双浏览器技术音视频和标注发送/客户可见、P3技术录音采样问答与正常结束已有实际证据；浏览器换房转接已通过独立新房gen2、实际RTP及原专家写403技术验收，双代次房间cleaned/12席位释放已核对，空video修复后实际新通话挂断DOM/audio/video清零、全部技术track结束和390px手机布局/滚动composer可达已通过，P3结束后正常UI恢复B2文字实际成功。最新Vue逻辑全量387项单元/组件、10项契约、13项snapshot、6语言1840key及build通过；之后仅移动CSS断点修复再次build通过。B6真实外部身份/业务工具仍未配置并默认关闭；物理麦克风/摄像头/扬声器、手机/现场、TLS/TURN、弱网/长时及设备声学仍须独立验收。当前证据和APFS恢复快照无法定位的未闭合项见 [恢复与补充验收](../../docs/ai-customer-service/DEV_RECOVERY_VALIDATION_20261030.md)。

按本次“新建一个文件夹迭代”的要求，保留 `manager-web-next`，没有合并或删除旧目录。日常 `dev/manage.py up` 和默认 `Dockerfile-web` 构建路径已选用 Vue 3；Docker 构建默认保持新增会话/客服入口关闭，需构建参数 `VITE_CONVERSATION_ENABLED=true` 并同时启用后端两个开关才能开放。现有生产镜像和服务未切换。需要回退使用旧前端时执行 `python3 dev/manage.py legacy-up` 并打开 18892；不要删除 Dev 数据卷。此次不改变设备固件、OTA/WebSocket 地址或现有生产服务。

整合验证补充：149 项单元/组件测试、10 项契约测试、13 项快照辅助测试、7 项 Dev 进程测试通过，六种语言各 1605 个键一致，生产静态构建通过。真实浏览器使用隔离账号与数据库验证了档案配置、转人工、双向消息、刷新恢复、重新排队、结束/取消及明确恢复状态；390px 用户页和客服页检查无横向溢出。后端回归和实际部署边界见 [验证记录](../../docs/ai-customer-service/SUPPORT_B1_VALIDATION.md)。
