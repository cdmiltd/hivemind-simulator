# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [v1.1.1] - 2026-08-14

### 新增
- **Pilot 上云完整实现**：TopicSchema 分层差异化（Pilot/Dock Topic 分离）、DRC 协议策略（RC Pro 走 services / RC Plus 2 走 DRC 通道）、云控授权流程、负载控制统一路由、遥控器 OSD 构建（RC Plus/RC Plus 2/RC Pro）、多机型飞行器 OSD（M400/Mavic 3/M4 系列）、Pilot HTTP/WebSocket 接口模拟（地图元素/态势感知/媒体/航线/MOP）
- **机场功能扩展**：PSDK 喊话器、ESDK 互联互通、OTA 固件升级、自定义飞行区、AirSense、远程日志、解锁 License 模拟器
- **WHIP 降级 RTMP 自动推流**：平台下发 `url_type=4`（WebRTC）但 ffmpeg 不支持 WHIP 时，自动将 WebRTC URL 转换为 RTMP URL 推流（ZLM 做 RTMP→WebRTC 转换），无需修改平台配置
- **航线任务模拟器大幅扩展**：全流程任务模拟（上传/下发/执行/进度/完成）

### 优化
- **前端架构重构**：index.html 拆分为外壳 + dock-panel.js + pilot-panel.js，DOCK/PILOT 主内容页全面切换，provide/inject 共享上下文
- Tauri 启动时序优化：`window.show()` 后使用 JS fetch 重试机制加载后端页面，消除启动瞬间 127.0.0.1 无法访问的闪烁
- NSIS 安装/卸载钩子：使用 `cmd /c taskkill` 替代 PowerShell（避免引号转义问题），确保残留 Java sidecar 进程被终止，避免升级时 `java.dll` 文件占用错误

### 修复
- **OSD 导出无数据**：前端传所有相关设备 SN（机场/遥控器+飞行器），后端改为按 topic 后缀过滤（不依赖 payload 字段，避免遗漏 Dock 分组上报）
- DRC 事件推送格式（timestamp 替代 seq）、心跳响应多余 seq 字段

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
