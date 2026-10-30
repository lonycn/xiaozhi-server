# 非实时 Omni 声音桥接验证

日期：2026-10-30。worktree `work/omni-audio`，基线 `9cfb7af`。本次没有启动或重启 Dev 服务、没有读取部署凭据或调用真实提供方、没有改动生产/业务数据库、没有提交或推送。

已完成：

- AI worker 39项，全部通过。使用真实 OpenAI Python SDK + httpx MockTransport 验证独立 Omni client/key、`input_audio` Data URI、文本+图像采样帧+原始音轨、完整180秒音轨、7,499,982字节上限/235秒超限错误、取消媒体下载、403显式失败且无模型回退、字符串大整数ID、只消费回复不消费reasoning、真实提供方格式usage（109/248/357，其中audio44）。这里的静音PCM及usage为明确的协议测试数据，不是现场声音、真实推断结果或实际计费。
- Dev配置与管理30项，全部通过。新增测试验证默认无Omni、显式复用所选Bailian配置、输出文件0600、Java property、拒绝未知模型；API properties不包含模型API key。命令和进程行为均mock，不操作真实服务。
- Java AI模块33项，全部通过、0跳过。覆盖规划/转写兼容、Omni raw选择、缺失raw不以transcript替代、视频frame共存、当前run模型+附件variant精确授权、lease/epoch/账号/人工接管拒绝、音频usage合法性及既有流回调。
- 上述Java中9项使用全新隔离MySQL库 `xiaozhi_ai_test_omni_20261030_01`，原生loopback端口34776。检查服务器session/global为`+00:00`、system为CST；JDBC `serverTimezone=UTC`与实际session一致。包括新增Omni claim、模型选择、usage_json音频明细持久化、下一轮只保留历史文本、人工接管撤销读取。附件服务mock只隔离对象存储，该测试不冒称真实媒体解码/上传联调。既有迁移在该新库重复执行通过，未改迁移文件；库与记录保留，无DROP/DELETE/TRUNCATE。
- `git diff --check`通过。

可复现的定向命令：

```sh
PYTHONPATH=main/xiaozhi-server:main/xiaozhi-server/tests <prepared-python> -m unittest test_ai_worker test_ai_worker_media test_ai_worker_omni -q
python3 -m unittest discover -s dev/tests -p 'test_*.py' -q
# AI_TEST_JDBC_URL必须指向另一个全新xiaozhi_ai_test_omni_*库，密码通过环境提供。
# sourceTimezone/serverTimezone须与真实MySQL session匹配，不复用已初始化测试库。
mvn -f main/manager-api/pom.xml -DskipTests=false -Dtest='xiaozhi.modules.ai.*Test' test
```

本地证据位于根workspace的 `work/omni-java-tests.log`、`work/omni-worker-tests.log`、`work/omni-dev-config-tests.log`。这些文件不含provider key或音频base64。

待父代理执行：真实Dev私有上传→检查/规范化→无ASR依赖的Omni音轨+视频frame→持久回复和真实usage；模型权限、限流、真实用户重试与UI可理解的错误；取得授权的真实环境/机械声与人工专家对照。普通TTS或静音PCM通过不代表压缩机异响能力验收，非实时Omni成功也不代表Realtime可用。超出inline字节上限的长录音目前明确失败，不支持后台分段或自动摘要。

## 父任务真实链路补验

主工作树已整合。原Dev实际S3录音上传→派生音轨→Omni→SSE/持久回复通过，actual usage183/175/358，input_audio_tokens44。随后Docker VM存储I/O错误中断Dev连接；备份恢复副本的新S3上传也返回STORAGE_UNAVAILABLE，因此不将后续文件系统结果称作S3验证。

独立API19897的私有文件系统真实链路完成录音和有声视频：录音usage183/118/301、audio44；视频含采样帧与原音轨，usage865/217/1082、audio16。两例均使用实际百炼qwen3.8-omni-flash，持久回复和SSE通过；样本为仓库音频及技术视频，不是客户/机械现场。证明work/omni-filesystem-e2e-proof.json；原Dev录音及中断记录work/omni-dev-e2e-proof.json和日志均保留。

当前整合Java全量457项、AI worker39项、Dev37项通过。长音轨inline限制和现场机械声对照仍为上述明确边界。
