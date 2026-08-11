# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 新增
- 开源协作基础设施：CONTRIBUTING.md、CHANGELOG.md、CODE_OF_CONDUCT.md
- GitHub Issue/PR 模板、Dependabot 配置
- GitHub Actions Release 工作流（Tauri 安装包自动构建发布）
- README 界面截图（主界面、注册配置、设备控制、消息日志、位置模拟、监控器）
- JaCoCo 测试覆盖率报告

### 变更
- README 新增「适用场景」「交流与支持」「支持作者」「Roadmap」章节
- README「不支持的功能」改为「Roadmap」正向表述
- 精简 README「开发工作流」，移除内部流程细节

## [v1.0.0] - 2026-08-09

### 首个开源版本
- 多机型支持：Dock1/Dock2/Dock3 + M30/M3D/M4D 系列飞行器
- 完整注册流程：config → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo 上线
- OSD/State 属性上报、航线任务模拟、直播、媒体上传、HMS 告警
- 位置模拟：高德地图选点（自动获取海拔）或手动输入坐标
- 监控器页面：独立 MQTT 客户端，实时查看设备遥测与下行指令
- 诊断系统：DJI Cloud API 方法覆盖率统计
- 桌面端打包：Tauri + 内置精简 JRE
