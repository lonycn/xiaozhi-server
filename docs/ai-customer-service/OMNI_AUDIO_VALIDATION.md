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
