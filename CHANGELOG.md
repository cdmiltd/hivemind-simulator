# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [v1.5.0] - 2026-08-27

### 新增
- **飞行位置连续插值模拟**：航线任务、DRC flyto、一键起飞三类飞行场景的位置（经纬度/高度）从"阶段瞬移"升级为连续匀速推进（0.5s 步进，与 OSD 0.5Hz 上报对齐），平台轨迹地图可绘制连续飞行轨迹
  - **DRC 杆量积分**：`stick_control` 杆量（[-65536, 65536] 线性归一化）驱动经纬度/高度/偏航连续变化（满杆水平 10m/s、垂直 5m/s、偏航 60°/s），attitudePitch/Roll 随杆量映射 ±15°；断流 >0.5s 时间步封顶防瞬移
  - **航线模式插值**：起飞→航线飞行→返航→降落各步骤设定目标点，插值器匀速推进（水平 10m/s、垂直 3m/s），消除 100 米瞬移跳变
  - **flyto/一键起飞插值**：按指令 `max_speed` 匀速飞向目标点，到达自动停止；`fly_to_point_stop` 悬停当前位置；事件时序（wayline_ok/task_finish）保持固定调度与位置解耦，联调时序稳定
- **Swagger 接口文档**：集成 springdoc-openapi，启动后访问 `http://localhost:9090/swagger-ui.html` 人工查阅，`/v3/api-docs`（OpenAPI JSON）供 AI 助手与代码生成器消费
- **README 英文简介**：头部新增英文摘要段，提升搜索引擎与 AI 收录定位
- TC-DRC-061~065（杆量积分）/ TC-WAYLINE-024~026（航线插值）/ TC-FLY-033~036（flyto 插值）/ TC-API-DOC-001~003（Swagger）

### 设计说明
- **drone_control（Dock1 废弃接口）不做位置模拟**：DJI 已废弃（Dock2/3 不支持），P-9 诊断日志引导平台迁移 `stick_control`
- **航线模式不做偏航/云台模拟**：真机由 KMZ 航线动作定义，模拟器不解析 KMZ
- `simulate_mission.is_enable=1` 室内调试模式不启动位置插值（既有语义保留）

## [v1.4.3] - 2026-08-26

### 新增
- **SN 手动修改**：模拟器注册面板新增机场/飞行器 SN 输入框，无需改配置文件即可切换设备身份。SN 生成采用三级优先级：手动覆盖 > 唯一模式生成 SN > 机型 defaultSn，覆盖值持久化到 live-config.json。约束：在线状态下拒绝修改 SN（须先关机）；SN 仅允许 MQTT 安全字符 `[0-9A-Za-z_-]`（长度 1~32）；SN 变更视为换设备，前端自动重置 `localStorage.registered`，新 SN 生效后必须重新注册到第三方平台
- TC-REG-032~037（SN 手动修改）/ TC-LOC-020（机场位置动态上报）

### 修复
- **机场 OSD 位置不随位置模拟更新**：机场 OSD 的 `latitude/longitude/height` 与 Dock3 `self_converge_coordinate` 原从 yml 静态配置读取，前端位置模拟设置新坐标后仍上报成都默认值。改为从 `RuntimeConfig` 动态读取，下一次 `osd_info_push` 即上报新坐标

## [v1.4.2] - 2026-08-25

### 新增
- **DRC 拍照信息推送**：实现 `drc_camera_photo_info_push` 事件（此前整个事件未实现）。平台下发 `drc_camera_photo_take` 后立即推送 `in_progress`（progress 0%），拍照完成（2 秒模拟）推送 `ok`（100%）并归零 photoState；`drc_camera_photo_stop` 可中断流程。对齐 DJI Dock2/Dock3 remote-control「拍照信息推送」文档
- **DRC 状态可视化**：模拟器页面飞行器状态栏与调试器页面新增 DRC 状态标签（已连接=绿色 / 未连接），数据源统一为 `DeviceState.drcState`（`/api/drone/position` 新增 `drc_state` 字段）
- **云台控制闭环**：`drc_gimbal_reset`（按 reset_mode 回中/俯仰向下）与 `drc_camera_screen_drag`（按速度增量）更新 state 的 gimbalPitch/Roll/Yaw，经 `osd_info_push` 与飞行器 OSD 的 `gimbal_pitch/roll/yaw` 上报形成闭环（原硬编码 0.0）
- **调试器指令回复显示**：监控器订阅 `thing/product/+/services_reply`，`sendCommand` 按 tid 缓存回复并等待 3 秒；前端显示设备执行结果（成功=绿色 / failed=红色 / 超时提示），不再以「已发送」掩盖失败
- **调试器远程调试模式自动处理**：下发 cover_open 等远程调试类指令前自动检查 debug_mode，未进入时先发 `debug_mode_open`（对齐真机行为）
- **OSD 协议字段补齐**：M30/M30T 飞行器 OSD 追加 `country`（国家区域码，pushMode=0）；`drc_camera_state_push` 追加 `photo_format`（7=RJPEG）；`drc_camera_osd_info_push` 的 ir_lense 追加 `thermal_supported_palette_styles`（[1,6,11]）

### 修复
- **一键起飞相对高度异常（-519.5m / 仅 1.5m）**：`takeoff_to_point`/`fly_to_point` 的 `target_height` 恢复 DJI 文档椭球高（WGS84）语义——`elevation=target_height`、`height=target_height−机场海拔`；调试器一键起飞参数改为协议合法值（`target_height=机场椭球高+30`、`security_takeoff_height=20`，补 Dock3 必填的 commander_mode_lost_action 等字段），修正后爬升至 20m 安全高度再飞抵目标
- **DRC 模式无法进入**：`drc_mode_enter` 缺失 `mqtt_broker` 时 SDK 反序列化抛异常导致 result=1，改为宽容处理（记录告警仍进入 DRC 模式）；调试器同时补全协议完整 data（mqtt_broker/hsi_frequency/osd_frequency）
- **调试器 OSD 数据被覆盖**：MonitorService OSD 缓存改用 `compute` 合并多 topic 消息的 data 字段，drc_state（Group 2）不再被后续 Group 3 消息覆盖
- **调试器 topic 过滤失效**：过滤选项修正（`drc/down`、`drc/up`、`property/set` 等），匹配逻辑由 `endsWith('/'+f.replace(' ','/'))` 改为 `endsWith('/'+f)`
- **模拟器远程调试模式校验**：cover_open 等 Job 指令执行前检查 debugMode，未开启返回 `status=failed` 并记录 M-2 诊断日志（对齐真机 check_work_mode 行为）

## [v1.4.1] - 2026-08-25

### 修复
- **多实例 SN 冲突**：v1.4.0 Docker 多实例编排中三实例共用同一 defaultSn 导致 MQTT topic 互踩（原 `DOCK3_001` 等环境变量格式无效且代码未读取，已删除）。新增 `simulator.sn.unique-enabled` 配置：启用后每个实例启动时生成 defaultSn 前缀 + 随机后缀的唯一 SN，与设备型号成对持久化，重启后设备身份不变；型号切换时重新生成（幂等）。`docker-compose-multi.yml` 三实例默认启用

### 新增
- **直播推流记录**：直播推流配置面板新增推流记录区域，每次 `live_start_push` 生成一条记录（含平台下发地址、实际推流地址、状态与失败原因），用于核对平台下发的推流地址是否配错。失败记录（513013）保留供排错；WHIP 降级 RTMP 等容错场景标注实际推流地址；`live_stop_push`/设备下线自动更新状态；有界保留最近 20 条

### 变更
- **版本基线对齐**：v1.4.0 发布产物内部版本显示 1.3.2（tag 绕过版本号递增流程所致），自本版本起版本号与 tag 恢复一致

## [v1.3.2] - 2026-08-20

### 修复
- **Docker 部署完善**：非 root 用户运行、三 Named Volumes 持久化（config/media/videos）、EMQX 可选 profile、HEALTHCHECK 健康检查、JVM 强制 IPv4
- **MQTT 连接健壮性**：地址自动剥离 tcp:// 前缀；容器内 localhost 自动映射 host.docker.internal（本地与 Docker 填写方式一致）；初始连接失败分类修正，同机 Docker 部署提示直连容器网络方案
- **媒体文件预置**：新增媒体/视频目录 Web 上传（路径净化+扩展名校验）与样例自动释放，解决 Docker 环境文件预置难题；「上报媒体」与「上传媒体」职责分离
- **开机状态持久化**：电源状态改由后端管理，刷新页面不再回到可开机状态；未注册时点击「开机」直接弹出注册弹窗
- **设备状态展示**：Title 栏拆分网关（机场/遥控器）与飞行器独立状态标签（含型号），飞行器三态（离线/休眠/开机）

## [v1.1.2] - 2026-08-14

### 新增
- **MQTT 消息日志本地持久化**：每条消息以 JSON Lines 格式写入本地文件（按日期滚动），文件位置 `${user.home}/.hivemind-simulator/logs/messages-yyyy-MM-dd.jsonl`，重启后历史消息不丢失
- **日志区上拉懒加载**：滚动到顶部自动从本地文件加载更早的历史消息，历史消息独立于轮询缓冲不会被覆盖
- **日志文件下载**：新增"下载日志"按钮，下载当天完整的原始日志文件（.jsonl 格式）
- `/api/logs` 接口新增 `beforeTime`/`limit` 分页参数，支持历史消息查询
- 新增 `/api/logs/download`（下载日志文件）、`/api/logs/files`（文件列表）接口

### 变更
- "导出OSD"按钮改为"下载日志"按钮，精简功能重叠（原 `/api/logs/export` 接口保留供外部工具调用）
- 监控器同步改造：`exportOsdLogs` → `exportLogs`，移除 OSD topic 过滤，支持所有消息类型
- `MqttClientManager.addLog()` 同时写入内存缓冲和本地文件，新增 `ts` 字段供分页查询

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
