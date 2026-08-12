# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [v1.1.0] - 2026-08-13

### 新增
- **Pilot 遥控器接入模式**：支持遥控器直接接入云平台，MQTT 连接后直接上线（无需注册流程），包含 update_topo、live_capacity 上报
- **Pilot 遥控器 OSD 构建**：支持不同遥控器型号的差异化字段上报
- 开源协作基础设施：CONTRIBUTING.md、CHANGELOG.md、CODE_OF_CONDUCT.md
- GitHub Issue/PR 模板、Dependabot 配置
- GitHub Actions Release 工作流（Tauri 安装包自动构建发布）
- README 界面截图（主界面、注册配置、设备控制、消息日志、位置模拟、监控器）
- JaCoCo 测试覆盖率报告

### 变更
- README 新增「适用场景」「交流与支持」「支持作者」「Roadmap」章节
- README「不支持的功能」改为「Roadmap」正向表述
- 精简 README「开发工作流」，移除内部流程细节

### 修复
- **MQTT 日志缓冲区性能缺陷（运行 5 分钟后崩溃）**：`ArrayList.remove(0)` 为 O(n) 操作，满载时每条消息需移动 1999 个元素；替换为 `ArrayDeque.pollFirst()` O(1) 操作
- **M30 OSD/State 字段协议对齐**：`payloads` 从 OSD 移到 state topic（pushMode=1）；`rid_state` 仅 M30/M30T 上报；`exit_wayline_when_rc_lost` 废弃字段不上报；`cameras` 子字段全部补齐；`{type-subtype-gimbalindex}.payload_index` 按 pushMode 归属到正确 topic
- **FFmpeg 安装 EventSource 资源泄漏**：组件卸载时未关闭 EventSource 和 installTimer，导致定时器和 SSE 连接泄漏
- M-2 诊断日志过时描述修复（`measure_target_error_state` 值更正、已移除字段描述清理）
- M4D OSD builder 类注释过时修复（`is_near_area_limit/is_near_height_limit` 已提升到基类）

### 优化
- `getLogs()` 从返回全部 2000 条改为最新 500 条，减少网络传输和前端渲染压力
- 前端 `logs` 数组增加防御性截断（`slice(-500)`），避免后端异常导致浏览器渲染过多 DOM

## [v1.0.0] - 2026-08-09

### 首个开源版本
- 多机型支持：Dock1/Dock2/Dock3 + M30/M3D/M4D 系列飞行器
- 完整注册流程：config → airport_bind_status → airport_organization_get → airport_organization_bind → update_topo 上线
- OSD/State 属性上报、航线任务模拟、直播、媒体上传、HMS 告警
- 位置模拟：高德地图选点（自动获取海拔）或手动输入坐标
- 监控器页面：独立 MQTT 客户端，实时查看设备遥测与下行指令
- 诊断系统：DJI Cloud API 方法覆盖率统计
- 桌面端打包：Tauri + 内置精简 JRE
