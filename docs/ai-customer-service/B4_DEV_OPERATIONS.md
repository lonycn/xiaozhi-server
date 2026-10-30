# B4 Dev 操作与验证边界

B4 的标注功能默认关闭，依赖会话、B3 私有附件及正在工作的 media worker。协议、权限与数据边界以 [B4 实施契约](./B4_IMPLEMENTATION_CONTRACT.md) 为准。本页说明 Dev 的显式开关，不代表已经部署或验收。

从该工作目录的仓库根目录操作，首先按 B3 的现有 Dev 方式启用私有媒体，并确保当前 API 构建包含 B4 实现；标注开关不会构建 API 或配置新的存储：

```sh
python3 dev/manage.py media-status
python3 dev/manage.py annotations-enable
python3 dev/manage.py annotations-status
python3 dev/manage.py annotations-disable
```

`annotations-enable` 要求媒体已经启用并存在私有 `media-worker.json`，复用现有存储、worker 和票据配置。它检查 worker 实际使用的 `annotation_font`；若未指定，则使用仓库内 `main/manager-web/public/generator/static/fonts/noto_qwen.ttf`，缺失时拒绝启用。字体由本机 Python worker 读取，Java API 容器不需要安装字体。该检查只确认文件存在，不证明字体可渲染或媒体处理成功；场景不能指定字体。

开关将状态保存在 Dev 私有媒体设置中，重新生成私有 API 配置并按既有方式重建 API 容器；它不会启动或重启 media worker、初始化数据库、改桶或清理卷。`media-disable` 同时关闭标注；重新开启媒体后仍须显式开启标注。关闭标注保留既有数据与对象。这里的命令是实际运行操作，会重建 Dev API，执行前须确认目标 checkout 和现有 Dev 环境。

配置单元测试只验证默认关闭、前置条件、字体路径选择和现有私有媒体配置保留。独立数据库、真实媒体存储、精确视频截图、字体成图、授权、浏览器交互和端到端流程需要分别验证并记录；配置成功不能替代这些验收。现场真实材料和设备验证仍是独立工作。
