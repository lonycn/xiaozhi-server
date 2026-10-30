# B4 精确截图与标注验证记录

日期：2026-10-30。实施契约见 [B4_IMPLEMENTATION_CONTRACT.md](./B4_IMPLEMENTATION_CONTRACT.md)；所有本次媒体均为仓库技术样本，不是客户或现场证据。

## 已验证

- 主仓 Java 全量测试 334 项通过、0 失败、0 跳过，见 `work/b4-java-full-native-r4.log`。Vue 此前全量 282 项通过；修复 `!sending` 导致的忙碌状态误报撤权后，最新相关定向 70 项通过，见 `work/b4-browser-busy-regression-web.log`。Dev 配置测试 29 项、媒体 worker 92 项、AI worker 最新 30 项通过。
- 隔离 fresh native 测试库的真实 HTTP 协议、解码和标注分别通过 33、64、30 项，共 127 项，日志为 `work/b3-http-native-protocol.log`、`work/b3-http-native-decoder.log` 和 `work/b4-http-native-annotation-r2.log`。视频请求 501 ms 取到真实 600 ms 帧；覆盖五类图形、中文、客服发送、隐藏背景证据和未发送草稿隔离。
- 浏览器 P1/native 路径完成画图、保存成图、加入发送草稿时保留原有文字以及发送成功。390×844 视口截图中画布 358×358、footer 位置 760，小于视口高度，未观察到横向溢出。忙碌状态误报撤权问题修复后通过上述 70 项定向回归。
- Dev 已显式启用 B4 标注和 B3 私有媒体，使用管理员账号完成真实 UI 登录。媒体与 AI 的实际云链路和保留原有数据情况见 [B3_VALIDATION.md](./B3_VALIDATION.md)。

## 边界

此次 HTTP 与浏览器使用技术样本，真实客户资料、目标终端、网络切换及现场业务流程尚未验收；截图生成、草稿保存和实际发送是不同步骤。浏览器通过不代表手机原生端或实时摄像头截图可用。B3 视频输入 AI 的原消息真实重试仍待完成，与 B4 指定时间帧提取通过是两项不同验证。

隔离测试 Docker VM 曾有间歇性响应长延迟；native r3 一项失败来自测试库默认时区配置错误，失败库保留，之后 UTC fresh 库 r4 全量通过。见 [B3 验证记录](./B3_VALIDATION.md)，不能以此推断所有环境故障均有相同根因。功能默认关闭，Dev 显式启用不等于生产部署。
