# P3 Python realtime worker验证

2026-10-30；基线 `c41c71f`，共享worktree `work/realtime-ai`。本交付只修改Python独立realtime_worker、其测试与REALTIME_WORKER文档；Java/Vue/Dev由其他责任代理处理。未提交、推送、读取私有部署凭据、启动实际worker服务或调用外部提供方；测试只创建临时loopback HTTP/WebSocket服务，无数据库操作。

## 已测范围

同一最终代码在Python3.11.15与Python3.13.13上，各28项全部通过、无跳过；不能把两个兼容性运行相加当成56个独立用例。证据：根workspace `work/realtime-worker-network-py311.log`、`work/realtime-worker-network-py313.log`。3.11日志中的asyncio慢任务调试行只记录测试协程及时间，不含用户内容或秘密。

测试实际经过三个网络端点：aiohttp浏览器WS客户端→本worker aiohttp WS服务→独立websockets模拟提供方，同时本worker通过实际httpx HTTP请求访问独立aiohttp模拟Java。没有用对象mock代替这三段网络连接；提供方WSS连接器仅把受限测试endpoint映射到临时loopback WS，生产URL校验仍严格要求配置的workspace Bailian WSS。

覆盖：

- 现代session.created/update/updated，manual PCM16 mono16k输入、PCM16 mono24k音频返回、Java先接受转写与assistant文本再向浏览器输出，真实格式usage三整数/无usage为空、只取一个文本表示避免重复。模拟usage452/95/547是协议fixture，不是本次真实提供方调用或计费。
- 精确Origin、首body短ticket、5秒内鉴权（测试缩短timeout）、ticket单用、拒绝URL ticket与浏览器Bearer、连接数限制、未ready输入拒绝、默认disabled/私有0600配置/只bindloopback、字符串session/provider标识与数字epoch。
- Camera初始明确consent；真实JPEG解码、实际input_image_buffer.append网络发送、同turn先音频、1fps、无同意拒绝；URL/伪装/未知客户端assistant事件不转发。图片为测试生成的单色技术样本，不是客户现场材料。
- 历史context逐条item ACK后ready；持久文本注入匹配Java固定provider item ID，ACK后才请求回复，缺ACK明确失败且不伪造成功、不重复注入。音频busy期间不派发文本。
- 真实input_audio_buffer.committed→Java input.committed ACK→response.create的顺序；ASR final既可先到，也可在response.started后迟到。committed被Java拒绝时不请求模型回复。历史消息顺序与占位填充由Java模块的实库测试负责，本网络测试不冒称覆盖数据库。
- 提供方typed/untyped错误脱敏、明确UNSUPPORTED_TOOL并关联当前response、重复event ID内容变化拒绝、重复事件不重写history、乱序delta先缓存后按started顺序提交；客户端不能伪造assistant输出；manual拒绝未经请求的provider response。
- 中断立即清播放、provider真实done后Java取消/命令ACK、partial保留、迟到delta抑制。专测“输出最后一次授权时才取到interrupt”、“delta精确409围栏不前进sequence”、“完成回执已canceled不泄漏全文”、“provider已结束后取到中断命令不再cancel”、“上一取消轮音频迟到新一轮不泄漏”；取消真实格式usage同样传Java。
- `active:false`、HTTP不可达、撤lease、terminal callback receipt立即关闭、不写迟到callbacks、无权限音频不发provider；callback ACK丢失重试完全相同sequence/event ID/body且只保存一次；有界队列压力与时长超限终结；应用shutdown后provider、浏览器、HTTP client、readers/timer均释放，无后台Session任务。

`compileall`与定向 `git diff --check`通过。独立测试环境位于 `/tmp/xiaozhi-realtime-worker-test-311` 与 `/tmp/xiaozhi-realtime-worker-test-venv`（3.13），未修改既有ESP32环境依赖；运行需使用 `realtime_worker/requirements.txt`。

## 实际验收边界

本worker暂未通过真实Dev/提供方端到端，留给父代理执行Java真实身份/ticket→本worker→百炼→实际输入转写、音频/文字、usage持久化、同会话普通文字恢复、人工接管。父最新授权后独立provider probe已成功，并非当前403受阻；独立probe成功仍不等于本worker或浏览器链路已验收。

交付模式明确是manual技术MVP，不是VAD自动通话、WebRTC或完整视频轨道；摄像头是受限JPEG采样。需要真实浏览器/安卓麦克风权限与回声/播放测试、HTTPS/WSS反向代理、弱网与恢复、实际模型授权/限流/配额/错误、现场证据与专家对照。测试静音PCM/生成JPEG及普通TTS不能替代机械异响判断验收。没有可信业务工具adapter，任何function/MCP调用都明确失败，没有执行或生成伪工具结果。
