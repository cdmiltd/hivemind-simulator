# TDD 规格测试文档

- 日期：2026-08-09
- 定位：记录产品中容易搞错的规格陷阱，作为测试依据和 AI 优化依据
- 关联：[设计文档](superpowers/specs/2026-08-08-dji-dock-simulator-design.md) | [AGENTS.md](../AGENTS.md)

## 1. TDD 开发模式

TDD（测试驱动开发）是本项目的标准开发方式：

1. **先写测试**：新增功能或修复 Bug 前，先在本文档编写或更新规格测试用例（基于 DJI 官方文档规格，非实现代码）
2. **再实现代码**：按测试用例描述的预期行为实现
3. **优化依据**：优化阶段以本文档为依据，确保优化不破坏已有规格行为

> 测试用例基于 DJI Cloud API 官方文档（https://developer.dji.com/doc/cloud-api-tutorial/cn/），不基于实现代码推测。

## 2. 规格测试用例

### 2.1 MQTT 连接

#### TC-MQTT-001：Spring 启动不自动连接
- **给定**：模拟器 Spring Boot 应用启动
- **当**：应用启动完成
- **那么**：MQTT 客户端不自动连接
- **错误后果**：启动即连接会导致用户未配置就触发注册流程

#### TC-MQTT-002：仅在用户点击注册后连接
- **给定**：设备已开机，用户已填写 MQTT 地址和凭证
- **当**：用户点击「注册到第三方平台」
- **那么**：建立 MQTT 连接并执行注册流程
- **错误后果**：未点击就连接，用户无法控制注册时机

#### TC-MQTT-003：开机自动重连条件
- **给定**：设备关机前曾注册成功（`localStorage.registered=true`）
- **当**：设备再次开机
- **那么**：尝试自动重连 MQTT
- **补充**：若 `registered=false`，开机不自动重连

#### TC-MQTT-004：关机断开 MQTT
- **给定**：MQTT 已连接
- **当**：用户点击关机
- **那么**：MQTT 客户端必须断开连接
- **错误后果**：关机后仍保持连接，平台误认为设备在线

#### TC-MQTT-005：连接超时 3 秒
- **给定**：用户点击注册，MQTT 地址不可达
- **当**：等待连接
- **那么**：3 秒后超时，返回错误
- **错误后果**：超时太长用户等待太久，太短正常网络抖动也会失败

#### TC-MQTT-006：区分地址错误与凭证错误
- **给定**：MQTT 连接失败
- **当**：Paho reason code 非 `REASON_CODE_FAILED_AUTHENTICATION`(5)/`REASON_CODE_NOT_AUTHORIZED`(6)
- **那么**：返回 `P-2`，前端提示「无法链接到第三方平台」（地址错误）
- **当**：Paho reason code 为 `REASON_CODE_FAILED_AUTHENTICATION`(5)（CONNACK code 4: Bad User Name or Password）
- **那么**：返回 `P-3`，前端提示「第三方平台凭证有误」（凭证错误）
- **当**：Paho reason code 为 `REASON_CODE_NOT_AUTHORIZED`(6)（CONNACK code 5: Not Authorized，如账号不存在）
- **那么**：返回 `P-3`，前端提示「第三方平台凭证有误」（凭证错误）
- **核实依据**：EMQX 对账号不存在返回 CONNACK code 5(Not Authorized)，对密码错误返回 CONNACK code 4(Bad User Name or Password)；Paho v3 分别映射为 REASON_CODE_NOT_AUTHORIZED(6) 和 REASON_CODE_FAILED_AUTHENTICATION(5)
- **错误后果**：将凭证错误误判为地址错误，用户无法定位问题

#### TC-MQTT-007：host 必须用 127.0.0.1
- **给定**：Windows 环境
- **当**：MQTT/Redis host 配置为 `localhost`
- **那么**：可能因 IPv6 解析问题导致连接失败
- **正确做法**：host 必须设为 `127.0.0.1`

### 2.2 注册流程时序

#### TC-REG-001：注册时序顺序
- **给定**：用户点击注册，MQTT 连接成功
- **当**：执行注册流程
- **那么**：时序必须为 `config → airport_bind_status → airport_organization_get → airport_organization_bind`
- **错误后果**：顺序错误会导致绑定失败或平台拒绝

#### TC-REG-002：update_topo 不在注册流程内
- **给定**：注册流程执行中
- **当**：airport_organization_bind 完成
- **那么**：注册流程结束，update_topo 不在注册流程中
- **补充**：update_topo 是注册成功后的上线步骤（见 TC-ONLINE-001）

#### TC-REG-003：每一步无条件执行
- **给定**：注册流程中 airport_bind_status 返回已绑定
- **当**：执行后续步骤
- **那么**：airport_organization_get 和 airport_organization_bind 仍需执行
- **错误后果**：跳过步骤会导致绑定状态不一致

#### TC-REG-004：绑定码错误停止注册
- **给定**：airport_organization_get 或 airport_organization_bind 返回 result=210229
- **当**：收到回复
- **那么**：停止注册流程，返回错误码 BIND_CODE_INVALID，提示「组织ID与绑定码错误」
- **错误后果**：继续执行会绑定到错误的组织

#### TC-REG-005：config 请求超时重试
- **给定**：config 请求发送后未收到 requests_reply
- **当**：等待 REPLY_TIMEOUT_SECONDS 超时
- **那么**：重试，最多 3 次，间隔 3 秒
- **当**：3 次全失败
- **那么**：停止注册，返回 ERR_NO_PLATFORM_REPLY
- **错误后果**：不重试会导致偶发网络抖动时注册失败

#### TC-REG-006：app_license 比对
- **给定**：config 回复包含 `app_license` 字段
- **当**：本地未配置 app_license（留空）
- **那么**：跳过 License 校验，继续后续注册步骤
- **当**：本地配置的 app_license 与回复中的不一致
- **那么**：停止注册，返回错误码 -6
- **当**：一致
- **那么**：继续后续注册步骤
- **错误后果**：License 不匹配时继续注册，平台会拒绝后续操作

#### TC-REG-007：License 首次注册锁定
- **给定**：localStorage 中无 `locked_app_license`（首次注册或已清除）
- **当**：用户点击「注册到第三方平台」
- **那么**：注册界面显示 DJI License 输入行，用户可输入
- **当**：用户输入 license 并提交
- **那么**：将输入的 app_license 存入 `localStorage['locked_app_license']`（无论后续注册成功与否）
- **那么**：继续发送 config 请求，与云端返回的 app_license 比对（TC-REG-006）
- **错误后果**：首次输入错误 license 被锁定，后续无法通过修改输入重试，桌面应用必须重装

#### TC-REG-008：License 锁定后注册界面隐藏输入行
- **给定**：localStorage 中已有 `locked_app_license`（非首次注册）
- **当**：用户打开注册弹窗
- **那么**：注册界面隐藏 DJI License 输入行，显示「已锁定」标签
- **那么**：用户无需再次输入 license
- **当**：用户提交注册
- **那么**：前端直接使用 `localStorage['locked_app_license']` 作为 app_license 发送 config 请求
- **核实依据**：DJI License 是第三方平台通过 config 回复下发给模拟器的，用户在模拟器侧再次输入不起作用；模拟真实机场 license 首次注册后不可更改的行为

#### TC-REG-009：自动重连跳过注册流程，直接上线
- **给定**：设备已注册（`localStorage.registered=true`），开机自动重连
- **当**：`registerToPlatform(silent=true)` 被调用
- **那么**：跳过 License 锁定校验，不修改 `locked_app_license`
- **那么**：`/api/online` 收到 `skip_register=true`，调用 `onlineOnly()` 而非 `online()`
- **那么**：跳过 config/airport_bind_status/airport_organization_get/airport_organization_bind 四步注册
- **那么**：直接执行 update_topo 上线 + publishLiveCapacity
- **核实依据**：DJI 机场关机前已注册成功，开机重连只需重新上线（update_topo），无需重复注册

#### TC-REG-010：MQTT 连接失败时终止注册流程
- **给定**：用户输入错误的 MQTT 地址（不可达）
- **当**：`/api/connect` 返回 `success=false` + `P-2`（地址不可达）
- **那么**：前端显示「无法链接到第三方平台」
- **那么**：不调用 `/api/online`，终止注册流程
- **那么**：`localStorage.registered` 设为 `false`，`mqttError` 设为 `true`
- **错误后果**：MQTT 连接失败仍显示注册成功，用户误以为已连接平台

#### TC-REG-011：MQTT 连接成功但注册失败时显示错误
- **给定**：MQTT 连接成功（`/api/connect` 返回 `success=true`），但注册流程失败
- **当**：config 请求 3 次超时（`/api/online` 返回 `success=false` + `P-1`）
- **那么**：前端显示「上线失败：未收到平台应答」
- **那么**：不显示「已注册」成功消息，不设置 `localStorage.registered=true`
- **那么**：`localStorage.registered` 设为 `false`，`mqttError` 设为 `true`
- **错误后果**：注册失败仍显示注册成功，用户误以为已注册

#### TC-REG-012：MQTT 连接成功且注册成功时显示成功
- **给定**：MQTT 连接成功，注册流程全部成功
- **当**：`/api/online` 返回 `success=true`
- **那么**：前端显示「已注册: {broker}」
- **那么**：`localStorage.registered` 设为 `true`，`mqttError` 设为 `false`

#### TC-REG-013：连接失败时不保存错误密码
- **给定**：用户输入错误的 MQTT 密码，后端已有正确密码
- **当**：`/api/connect` 返回 `success=false`（凭证错误 P-3）
- **那么**：后端 RuntimeConfig 恢复原始密码（不保存错误密码）
- **那么**：前端不调用 `saveConnConfig()`（不保存到 localStorage）
- **那么**：用户清空密码后再次注册，后端使用原始正确密码连接成功
- **错误后果**：错误密码覆盖原密码，清空密码后无法使用原密码连接

#### TC-REG-014：注册成功后才保存配置
- **给定**：用户修改了 MQTT 地址、密码等配置
- **当**：注册流程全部成功（`/api/online` 返回 `success=true`）
- **那么**：前端调用 `saveConnConfig()` 保存配置到 localStorage
- **当**：注册失败（MQTT 连接失败或注册流程失败）
- **那么**：前端不调用 `saveConnConfig()`，localStorage 保持上一次成功注册时的配置
- **错误后果**：注册失败也保存配置，错误密码/地址覆盖原配置

#### TC-REG-015：SN 由设备型号决定（不可手动配置）
- **给定**：application.yml 配置了 `dock-type: DOCK3` 和 `drone-type: M4TD`
- **当**：模拟器启动
- **那么**：RuntimeConfig 从 `DeviceType.defaultSn()` 获取 SN（DOCK3 → `7UUXN1Q00A008W`，M4TD → `1081F8HGD25110010059`）
- **那么**：所有 MQTT topic 使用该 SN（`thing/product/{sn}/...`）
- **那么**：前端设备信息面板显示该 SN
- **那么**：yml 不支持 `dock-sn` / `drone-sn` 配置（已移除，SN 完全由设备型号决定）
- **SN 生成时机**：启动时根据 `dock-type` / `drone-type` 生成，运行时切换型号不自动更新 SN（与 MQTT 监听器在构造函数中绑定 SN 的设计一致）

### 2.3 上线流程

#### TC-ONLINE-001：注册成功后自动上线
- **给定**：注册流程全部成功
- **当**：airport_organization_bind 返回 result=0
- **那么**：自动执行 update_topo 上线
- **那么**：update_topo 的 `sub_devices` 根据飞行器激活状态决定：
  - 飞行器激活（`droneActivated=true`）：`sub_devices` 包含飞行器
  - 飞行器休眠（`droneActivated=false`）：`sub_devices` 为空（只有机场通过 MQTT 连接上线，飞行器未上线）

#### TC-ONLINE-002：update_topo 超时不停止流程
- **给定**：update_topo 发送后未收到 status_reply
- **当**：等待超时
- **那么**：不停止上线流程，继续 state.setOnline(true) + publishLiveCapacity()
- **那么**：记录 P-1 诊断日志（平台未回复 status_reply），不影响上线流程
- **核实依据**：[DJI 设备管理时序图](https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html)中 update_topo 后直接进入 osd 推送，未规定必须等待回复
- **错误后果**：超时停止会导致设备无法上线

#### TC-ONLINE-003：update_topo status_reply result 非 0 停止上线
- **给定**：收到 status_reply，data.result 非 0
- **当**：解析回复
- **那么**：停止上线流程，不执行 `state.setOnline(true)` 和 `publishLiveCapacity()`
- **那么**：返回上线失败，前端提示上线失败
- **核实依据**：status_reply 是平台对 update_topo 的确认，result≠0 表示平台拓扑更新失败，设备不应继续上线
- **待核实**：真实机场收到 result≠0 后是否继续执行上线后续流程（代码中已标注 TODO）
- **错误后果**：如果 result≠0 仍继续上线，无法检测平台 update_topo 处理异常

#### TC-ONLINE-004：update_topo data 顶层不含 domain
- **给定**：构造 update_topo 的 data
- **当**：在 data 顶层添加 domain 字段
- **那么**：会被 hivemind 误判为 Autel 设备
- **正确做法**：domain 字段只在 sub_devices 元素中
- **错误后果**：设备被识别为非 DJI 设备

#### TC-ONLINE-005：飞行器激活状态控制 drone OSD 推送
- **给定**：设备已上线（`state.online=true`），飞行器处于激活状态（`droneActivated=true`）
- **当**：定时触发 OSD 推送（0.5Hz）
- **那么**：推送 dock OSD（`thing/product/{dockSn}/osd`）和 drone OSD（`thing/product/{droneSn}/osd`）
- **给定**：设备已上线，飞行器处于休眠状态（`droneActivated=false`，**默认值**）
- **当**：定时触发 OSD 推送
- **那么**：只推送 dock OSD，**不推送** drone OSD
- **核实依据**：DJI 文档时序图中 osd 为 `loop[0.5HZ 定频推送]`，state 为 `opt[事件性上报]`；真实机场开机后飞行器默认休眠，需 `open_drone_cover` 等指令才激活
- **文档 URL**：https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html
- **错误后果**：飞行器休眠时仍推送 drone OSD，平台误认为飞行器在线

#### TC-ONLINE-006：state 属性为事件性上报，不在定频推送中
- **给定**：DJI 设备管理时序图
- **当**：区分 osd 和 state 推送方式
- **那么**：osd 属性以 0.5Hz 定频推送（`thing/product/{sn}/osd`）
- **那么**：state 属性为**事件性上报**（`thing/product/{sn}/state`），属性变化时才推送，**不放入定频推送**
- **那么**：state 推送在上线流程中触发（如 `live_capacity` 在上线时推送一次），后续仅在属性变更时推送
- **核实依据**：DJI 文档原文"osd 属性会以 0.5 HZ定频上报，state属性会在属性变化时上报"；时序图中 state 标注为 `opt[事件性上报]`
- **错误后果**：将 state 放入定频推送会导致平台收到重复的属性变更通知，与 DJI 真机行为不符

#### TC-ONLINE-007：飞行器激活时推送 drone state 初始属性
- **给定**：设备已上线，飞行器从休眠切换为激活（`droneActivated` false→true）
- **当**：状态切换发生时
- **那么**：推送一次 drone state 到 `thing/product/{droneSn}/state`（事件性上报）
- **那么**：包含所有 pushMode=1 的飞行器属性（payloads、wpmz_version、firmware_version、compatible_status、firmware_upgrade_status、mode_code_reason、home_longitude、home_latitude、control_source、low_battery_warning_threshold、serious_low_battery_warning_threshold、rth_mode、current_rth_mode、commander_mode_lost_action、current_commander_flight_mode、commander_flight_height、psdk_ui_resource、psdk_widget_values）
- **那么**：飞行器从激活切换为休眠时不推送（无属性变更需要上报）
- **那么**：设备未上线时不推送（`state.online=false` 时跳过）
- **核实依据**：DJI 设备管理时序图中上线流程包含"设备（飞行器）属性推送 Topic: thing/product/{device_sn}/state"；pushMode=1 属性在状态变化时上报
- **文档 URL**：https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/properties.html
- **错误后果**：飞行器激活后平台未收到 drone state，无法识别负载、固件版本等信息

#### TC-ONLINE-008：飞行器休眠时发送 update_topo 通知平台
- **给定**：设备已上线，飞行器从激活切换为休眠（`droneActivated` true→false）
- **当**：状态切换发生时
- **那么**：发送 update_topo 到 `sys/product/{gateway_sn}/status`，`sub_devices` 为空
- **那么**：机场仍在线（`state.online` 不变），不影响 dock OSD 推送
- **那么**：设备未上线时不发送（`state.online=false` 时跳过）
- **TODO 待核实**：真实机场在飞行器休眠时发送的 update_topo 中 `sub_devices` 字段格式，当前默认为空列表，需根据实际场景下的真实指令为准
- **核实依据**：DJI SDK 源码中 `sub_devices` 为空 → `INBOUND_STATUS_OFFLINE`；设备管理时序图中下线流程发送空 `sub_devices`
- **文档 URL**：https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html
- **错误后果**：飞行器休眠后平台仍认为飞行器在线，后续指令发往不存在的飞行器

### 2.4 配置管理

#### TC-CFG-001：MQTT 配置为顶层共享
- **给定**：application.yml
- **当**：MQTT 配置位于 `simulator.mqtt` 下
- **那么**：监控器无法共享 MQTT 配置
- **正确做法**：MQTT 配置必须为顶层 `mqtt:`，模拟器和监控器共享
- **错误后果**：监控器无法读取 MQTT 连接参数

#### TC-CFG-002：client-id-prefix 分模拟器和监控器
- **给定**：MQTT 配置
- **当**：模拟器和监控器使用相同的 client-id-prefix
- **那么**：MQTT 连接会互相踢下线
- **正确做法**：`simulator-client-id-prefix` 和 `monitor-client-id-prefix` 独立配置

#### TC-CFG-003：app-license 占位符
- **给定**：application.yml 中 `app-license: ""`
- **当**：桌面端用户首次使用
- **那么**：用户在注册弹窗填写 DJI License，通过 /api/connect 覆盖 RuntimeConfig
- **补充**：填写后保存到 localStorage，下次自动填充

#### TC-CFG-004：配置链路覆盖顺序
- **给定**：application.yml 配置了默认值
- **当**：前端 REST API 提供新值
- **那么**：RuntimeConfig 被覆盖，使用前端值
- **当**：前端未提供值
- **那么**：使用 application.yml 默认值
- **正确顺序**：`application.yml` → `SimulatorProperties` → `RuntimeConfig` → 前端 REST API

### 2.5 错误码

> 设计背景：错误码按责任方分类，用字母前缀区分（P=第三方平台、S=模拟器、M=监控器），避免扩展时数字打架。
> DJI result 码（如 0/1/210229）是协议层码，直接透传到 MQTT 回复；P/S/M 码是诊断层码，只进日志和 UI，不放入 MQTT 回复。
> 真相源：[设计文档 §8 错误码体系](../specs/2026-08-08-dji-dock-simulator-design.md#8-错误码体系)

#### TC-ERR-001：错误码按责任方分类（P/S/M 前缀）
- **给定**：模拟器自定义错误码
- **当**：定义新错误码
- **那么**：必须用 `{责任方}-{序号}` 格式，字母前缀区分责任方：
  - `P-*` = 第三方平台问题（反馈平台修复）
  - `S-*` = 模拟器问题（需开发者处理）
  - `M-*` = 监控器问题（预留）
- **那么**：DJI result 码（如 210229）保持原样透传，不加前缀
- **错误后果**：纯数字码扩展时易冲突（如 -7 是平台错误还是模拟器错误无法区分）

#### TC-ERR-002：P 类错误码（平台责任）
- **给定**：平台下发消息或回复异常
- **当**：诊断错误原因
- **那么**：使用 P 类错误码：
  - `P-1` 平台无响应（requests/events 超时未收到 reply）
  - `P-2` 地址不可达（MQTT 连接地址错误）
  - `P-3` 凭证错误（MQTT 认证失败）
  - `P-4` License 不匹配（config 回复的 app_license 不符）
  - `P-5` JSON 格式错误（平台下发非合法 JSON）—— 阶段 2 实现
  - `P-6` 必填字段缺失（缺 tid/bid/method/data）—— 阶段 2 实现
  - `P-7` 字段类型错误（method 非字符串等）—— 阶段 2 实现
  - `P-8` Dock 能力不匹配（平台给当前 Dock 下发了不支持的指令）—— 阶段 2 实现
- **处理建议**：反馈平台修复

#### TC-ERR-003：S 类错误码（模拟器责任）
- **给定**：模拟器自身问题导致无法处理
- **当**：诊断错误原因
- **那么**：使用 S 类错误码：
  - `S-1` MQTT 未连接（模拟器未建立 MQTT 连接）
  - `S-2` 未覆盖指令（method 在 DJI 规范存在但模拟器无 handler）—— 阶段 2 实现
  - `S-3` 解析异常（NPE/ClassCastException 等模拟器内部 Bug）—— 阶段 2 实现
- **处理建议**：`S-2` 评估是否扩展模拟器；`S-3` 反馈模拟器修复

#### TC-ERR-004：HMS 错误码基于 hms.json
- **给定**：需要上报 HMS 告警
- **当**：查找错误码映射
- **那么**：必须基于 `src/main/resources/hms.json`，不能硬编码

#### TC-ERR-005：业务逻辑不抛异常
- **给定**：业务逻辑遇到拒绝场景（如设备未开机就注册）
- **当**：处理拒绝
- **那么**：返回 HTTP 200 + `success=false` + `message`（明确拒绝原因），不抛异常
- **错误后果**：抛异常会导致前端收到 500 错误，无法显示友好提示

#### TC-ERR-006：诊断码不污染 MQTT 回复
- **给定**：模拟器收到无法解析的 MQTT 消息
- **当**：处理解析失败
- **那么**：P/S/M 诊断码只进日志和 UI，不放入 `services_reply`/`set_reply`/`drc/up` 回复
- **那么**：MQTT 回复中的 `result` 字段仍用 DJI 码（0/1/210229）
- **理由**：平台若发了格式错误的消息，需要的是自己的日志，不是模拟器的回复；模拟器定位是调试工具，诊断信息面向开发者

### 2.6 前端约定

#### TC-FE-001：Vue 自定义元素显式闭合
- **给定**：Vue DOM 模板中使用 Element Plus 组件
- **当**：使用 `<el-input ... />` 自闭合
- **那么**：渲染异常
- **正确做法**：必须用 `<el-input ...></el-input>` 显式闭合

#### TC-FE-002：不适用值用 `-` 显示
- **给定**：某个字段值不适用或未获取
- **当**：前端展示
- **那么**：显示 `-`，不显示 `0` 或 `--`
- **错误后果**：`0` 会被误认为有效数据值

#### TC-FE-003：MQTT 标签样式
- **给定**：MQTT 未连接
- **当**：显示 MQTT 状态标签
- **那么**：标签无背景色（plain 样式）
- **当**：连接失败
- **那么**：标签文字为红色
- **当**：已连接
- **那么**：标签为绿色背景

#### TC-FE-004：配置持久化到 localStorage
- **给定**：用户填写配置（含 app_license）并点击注册
- **当**：提交注册请求前
- **那么**：配置已保存到 localStorage（无论注册成功与否）
- **当**：下次打开页面
- **那么**：从 localStorage 自动填充配置

#### TC-FE-005：CSS/JS 资源本地化
- **给定**：模拟器/监控器页面加载
- **当**：引用 Element Plus CSS/JS、Vue JS 等第三方库
- **那么**：必须从本地 `/vendor/` 目录加载，不引用外网 CDN（unpkg/googleapis）
- **那么**：不引用 Google Fonts（国内访问极不稳定，依赖系统字体 fallback）
- **错误后果**：外网 CDN 访问慢或不可达时，页面 CSS 迟迟不加载，界面长时间混乱

#### TC-FE-006：v-cloak 加载占位
- **给定**：页面开始加载，CSS/JS 尚未下载完成
- **当**：浏览器解析 HTML
- **那么**：`#app` 上的 `v-cloak` 属性隐藏 Vue 模板（`[v-cloak]{display:none}`），避免 `{{ }}` 等未编译内容暴露
- **那么**：显示独立的加载占位（spinner + "加载中…"），纯内联 CSS 不依赖外部资源
- **当**：Vue 挂载完成后
- **那么**：移除 `v-cloak` 属性，显示完整页面；移除加载占位
- **错误后果**：未样式化内容闪烁（FOUC），用户体验差

#### TC-FE-007：favicon 提供
- **给定**：浏览器加载页面
- **当**：请求 `/favicon.ico`
- **那么**：HTML `<head>` 中声明 `<link rel="icon" href="/favicon.svg">`
- **那么**：提供 `favicon.svg` 文件，避免 404
- **错误后果**：控制台报 404 错误，标签页无图标

#### TC-FE-008：注册前清空旧平台指令列表
- **给定**：用户主动点击「注册到第三方平台」（`silent=false`）
- **当**：调用 `/api/connect` 之前
- **那么**：清空模拟器指令通讯窗口（前端 `logs.value = []`）
- **那么**：同时调用 `DELETE /api/logs` 清空后端日志
- **当**：开机自动重连（`silent=true`）
- **那么**：不清空指令列表（同一平台重连，保留历史记录）
- **错误后果**：不同平台的指令混在一起，难以区分来源
- **补充**：监控器页面（monitor.html）在连接前同样清空旧日志

#### TC-FE-009：关机清空模拟器指令列表
- **给定**：模拟器已连接第三方平台，指令通讯窗口有记录
- **当**：用户点击「关机」
- **那么**：前端清空指令列表（`logs.value = []`）
- **那么**：不调用后端 DELETE 接口，不影响监控器页面的指令列表
- **错误后果**：关机后旧指令残留，下次开机后与新指令混淆

#### TC-FE-010：注册按钮 UI 即时反馈
- **给定**：用户点击「确认注册」按钮
- **当**：设置 `connecting.value = true` 后
- **那么**：必须 `await nextTick()` 等待 Vue 完成 DOM 更新（按钮渲染为 loading 状态）
- **那么**：然后才执行后续 HTTP 请求（清空日志 → MQTT 连接 → 注册/上线）
- **错误后果**：Vue 响应式更新被后续 `await` 延迟，点击后 UI 无即时反馈，感觉卡顿

#### TC-FE-011：飞行器激活/休眠互斥按钮
- **给定**：右侧面板「无人机是否在舱」开关后面
- **当**：显示飞行器状态切换 UI
- **那么**：提供「休眠」「激活」两个互斥按钮（如 el-radio-group 或 el-segmented）
- **当**：用户点击「激活」
- **那么**：调用 `PUT /api/state` 设置 `droneActivated=true`，drone OSD 开始推送
- **当**：用户点击「休眠」
- **那么**：调用 `PUT /api/state` 设置 `droneActivated=false`，drone OSD 停止推送
- **那么**：dock OSD 不受影响，始终推送
- **错误后果**：无法在运行时切换飞行器激活状态，无法模拟飞行器休眠场景

### 2.7 DJI 协议细节

#### TC-DJI-001：drone-model-key 值
- **给定**：M4D 无人机
- **当**：配置 drone-model-key
- **那么**：值为 `0-100-0`（不是 `0-100-1`）
- **补充**：`0-100-1` 对应的是 M4TD（Matrice 4TD），不是 M4D
- **核实依据**：[DJI 产品支持](https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html)枚举值表：Matrice 4D = 0-100-0，Matrice 4TD = 0-100-1
- **错误后果**：model-key 错误会导致平台无法识别设备型号

#### TC-DJI-002：config 返回字段
- **给定**：发送 config 请求
- **当**：收到 requests_reply
- **那么**：回复 data 包含 `app_id, app_key, app_license, ntp_server_host, ntp_server_port`
- **补充**：app_license 用于与本地配置比对（见 TC-REG-006）

#### TC-DJI-003：OSD 上报频率
- **给定**：设备已上线
- **当**：定时上报 OSD
- **那么**：频率为 0.5Hz（每 2 秒一次）
- **Topic**：`thing/product/{device_sn}/osd`

#### TC-DJI-004：update_topo 上线与下线区别
- **给定**：设备上线
- **当**：发送 update_topo
- **那么**：`sub_devices` 非空（包含 dock + drone）
- **给定**：设备下线
- **当**：发送 update_topo
- **那么**：`sub_devices` 为空

#### TC-DJI-005：live_capacity 上报 topic 为 state
- **给定**：设备上线后上报直播能力 `live_capacity`
- **当**：选择上报 topic
- **那么**：必须用 `thing/product/{device_sn}/state`（pushMode=1 对应 state topic）
- **那么**：不能用 `thing/product/{device_sn}/property`（非 DJI 标准 topic）
- **核实依据**：DJI Cloud API 设备属性文档 `live_capacity` 的 `pushMode=1` → state topic；直播功能文档「直播能力更新」Topic 为 `thing/product/{device_sn}/state`
- **文档 URL**：https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/live.html
- **错误后果**：平台无法从 state topic 接收直播能力，导致无法发起直播

#### TC-DJI-006：live_capacity 数据结构对齐 DJI 文档
- **给定**：构造 `live_capacity` 上报数据
- **当**：组装数据结构
- **那么**：必须使用 DJI 文档标准字段名和层级：
  - `live_capacity.available_video_number`（int）
  - `live_capacity.coexist_video_number_max`（int）
  - `live_capacity.device_list`（数组，不是 `device`）
    - `device_list[].sn`（设备序列号）
    - `device_list[].available_video_number`（int）
    - `device_list[].coexist_video_number_max`（int）
    - `device_list[].camera_list`（数组，不是 `available_video`）
      - `camera_list[].camera_index`（格式 `{type-subtype-gimbalindex}`，如 `165-0-7`）
      - `camera_list[].available_video_number`（int）
      - `camera_list[].coexist_video_number_max`（int）
      - `camera_list[].video_list`（数组）
        - `video_list[].video_index`（如 `normal-0`）
        - `video_list[].video_type`（如 `normal`）
        - `video_list[].switchable_video_types`（数组，如 `["normal"]`）
- **那么**：不包含非标准字段（如 `camera_mode`、`type`）
- **核实依据**：DJI Cloud API 直播功能文档「直播能力更新」数据结构
- **错误后果**：字段名错误（如 `device` 而非 `device_list`）导致平台解析失败

### 2.8 桌面端打包（Tauri）

#### TC-TAURI-001：WebView2 安装模式
- **给定**：Tauri 打包配置
- **当**：目标机器缺少 WebView2
- **那么**：必须自动在线安装（downloadBootstrapper 模式）
- **错误后果**：应用无法启动

#### TC-TAURI-002：端口固定 19090
- **给定**：Tauri 打包
- **当**：配置 server.port
- **那么**：必须为 19090，避免与其他服务冲突

#### TC-TAURI-003：Java sidecar 无窗口
- **给定**：Tauri 启动 Java sidecar
- **当**：创建进程
- **那么**：必须使用 CREATE_NO_WINDOW 标志，抑制 CMD 窗口弹出

#### TC-TAURI-004：日志路径
- **给定**：Tauri 应用运行
- **当**：写入日志文件
- **那么**：日志路径为 `%LOCALAPPDATA%/com.cdmi.dock-simulator/logs/`
- **错误后果**：写到 Program Files 会因权限不足失败

#### TC-TAURI-005：resource_dir 路径前缀
- **给定**：Tauri 获取 resource_dir
- **当**：路径以 `\\?\` 开头
- **那么**：必须 strip 前缀，否则 Java -jar 无法识别路径

#### TC-TAURI-006：启动前清理孤儿 sidecar 进程
- **给定**：上次 Tauri 应用被强制关闭（任务管理器/系统崩溃），Java sidecar 成为孤儿进程，仍占用 19090 端口
- **当**：用户再次启动桌面应用，spawn 新 sidecar 之前
- **那么**：`kill_orphan_on_port` 检测到 19090 端口被占用，通过 `netstat -ano` 查到占用进程 PID，用 `taskkill /PID xxx /F` 终止
- **那么**：等待端口释放（最多 5 秒），释放后继续启动新 sidecar
- **那么**：simulator.log 记录清理过程：端口被占用 → 发现 PID → 终止 → 端口已释放
- **核实依据**：Tauri Drop trait 在进程被强制杀死时不执行，需主动清理

#### TC-TAURI-007：HTTP 健康检查替代 TCP 端口检测
- **给定**：Java sidecar 启动中，Tomcat 已绑定 19090 端口但 Spring 上下文尚未完全初始化
- **当**：`wait_for_server` 轮询检测后端就绪状态
- **那么**：不使用 `TcpStream::connect`（仅检测 TCP 端口可连接，会误判为已就绪）
- **那么**：使用 `http_health_check` 发送 GET `/api/connection` 请求，检查返回 HTTP 状态码是否为 200
- **那么**：只有 Spring Boot 完全启动并可以处理 HTTP 请求时才认为后端就绪
- **那么**：如果 Spring Boot 启动失败（如 bean 初始化异常），HTTP 请求不会返回 200，`wait_for_server` 超时后窗口仍显示但日志记录警告
- **核实依据**：TCP 端口可连接 ≠ HTTP 服务可用；Tomcat 绑定端口后 Spring 上下文可能仍在初始化或已失败

#### TC-TAURI-008：WebView URL 使用 127.0.0.1 而非 localhost
- **给定**：tauri.conf.json 中 WebView 加载的 URL
- **当**：配置 `"url": "http://127.0.0.1:19090"`
- **那么**：不使用 `localhost`，避免 Windows 上 DNS 将 `localhost` 解析为 IPv6 `::1` 而非 IPv4 `127.0.0.1`，导致 sidecar 只监听 IPv4 时连接被拒绝
- **那么**：main.rs 中 `http_health_check`、`kill_orphan_on_port` 同样使用 `127.0.0.1`
- **核实依据**：Windows 的 IPv6 优先策略可能导致 localhost 解析为 ::1；与 MQTT/Redis 配置统一使用 127.0.0.1 的约定一致

#### TC-TAURI-009：jlink 精简 JRE 必须包含完整模块集
- **给定**：使用 `jlink` 创建精简 JRE 供 Tauri 打包使用
- **当**：`--add-modules` 缺失必要模块
- **那么**：Spring Boot 启动时报 `NoClassDefFoundError`，sidecar 崩溃，WebView 报"127.0.0.1 拒绝连接"
- **那么**：已知缺失场景：
  - 缺 `java.desktop` → `java.beans.PropertyEditorSupport`（Spring Boot BindConverter）
  - 缺 `java.security.jgss` → `org.ietf.jgss.GSSException`（Tomcat createDefaultRealm）
- **那么**：jlink `--add-modules` 必须包含完整列表（通过 `jdeps --print-module-deps` 分析 BOOT-INF/lib 得出）：
  ```
  java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,
  java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.sql,
  java.transaction.xa,java.xml,jdk.compiler,jdk.crypto.cryptoki,jdk.crypto.ec,
  jdk.jfr,jdk.unsupported
  ```
- **核实方法**：打包前先用精简 JRE 本地运行 `java -jar app.jar` 确认 Spring Boot 启动成功，再执行 `cargo tauri build`
- **错误后果**：桌面应用安装后启动 sidecar 立即崩溃，WebView 报"127.0.0.1 拒绝连接"

### 2.9 设备类型枚举

#### TC-DEV-001：model_key 格式与解析
- **给定**：DeviceType 枚举
- **当**：调用 `DOCK3.modelKey()`
- **那么**：返回 `"3-3-0"`
- **当**：调用 `DeviceType.fromModelKey("0-100-1")`
- **那么**：返回 `M4TD`

#### TC-DEV-002：机场与飞行器兼容性
- **给定**：DOCK3 机场
- **当**：调用 `DeviceType.isCompatible(DOCK3, M4TD)`
- **那么**：返回 true
- **当**：调用 `DeviceType.isCompatible(DOCK3, M30)`
- **那么**：返回 false（Dock3 只能配 M4D/M4TD）

#### TC-DEV-003：update_topo 从枚举获取 type/sub_type
- **给定**：配置 dock-type=DOCK3, drone-type=M4TD
- **当**：发送 update_topo
- **那么**：data.type=3, data.sub_type=0（从 dockType 获取）
- **那么**：sub_devices[0].type=100, sub_devices[0].sub_type=1（从 droneType 获取）
- **错误后果**：硬编码 sub_type=0 会导致 M4TD 被识别为 M4D（第一阶段修复的 BUG）

#### TC-DEV-004：update_topo 含 index 字段
- **给定**：发送 update_topo
- **当**：构造 sub_devices 元素
- **那么**：必须包含 `"index": "A"` 字段

#### TC-DEV-005：前端设备类型联动
- **给定**：用户在配置弹窗选择 Dock1
- **当**：飞行器类型下拉刷新
- **那么**：只显示 M30/M30T 选项
- **当**：选择 Dock3
- **那么**：只显示 M4D/M4TD 选项

### 2.10 OSD 策略

#### TC-OSD-001：Dock3 OSD 使用 snake_case
- **给定**：dock-type=DOCK3
- **当**：上报 OSD
- **那么**：字段名为 snake_case（如 `mode_code`, `cover_state`, `drone_in_dock`）
- **那么**：version 字段为 `"dock3"`

#### TC-OSD-002：Dock1/Dock2 OSD 使用 camelCase
- **给定**：dock-type=DOCK1 或 DOCK2
- **当**：上报 OSD
- **那么**：字段名为 camelCase（如 `modeCode`, `coverState`, `droneInDock`）
- **那么**：version 字段为 `"dock1"`
- **错误后果**：字段名风格不匹配会导致平台 Jackson 反序列化静默失败

#### TC-OSD-003：运行时切换策略
- **给定**：设备已上线，当前 dock-type=DOCK3
- **当**：通过 REST API 切换 dock-type=DOCK1
- **那么**：下次 OSD 上报自动使用 camelCase 字段名

### 2.11 负载类型支持

#### TC-PAYLOAD-001：飞行器 OSD 包含负载信息
- **给定**：drone-type=M4TD
- **当**：上报飞行器 OSD
- **那么**：包含 `current_camera_type`=99 和 `camera_index`="99-0-0"（M4TD Camera）

#### TC-PAYLOAD-002：直播能力上报
- **给定**：设备已上线
- **当**：上报机场 OSD
- **那么**：包含 `live_capacity` 字段
- **那么**：`video_list` 包含飞行器主相机（switchable=0）和机场相机（switchable=1）

### 2.12 OSD 字段集策略（Builder 模式）

> 设计背景：不同 Dock 版本（Dock1/Dock2/Dock3）和机型家族（M4D/M30/M3D）的 OSD 字段集不同。
> 采用策略模式 + 模板方法：`DockOsdBuilder` 按 Dock 版本划分字段集，`DroneOsdBuilder` 按机型家族划分字段集。
> `OsdStrategy`（命名风格）与 `OsdBuilder`（字段集）是两个正交维度，前者注入后者复用。

#### TC-BUILDER-001：DockOsdBuilder 按 Dock 版本选择字段集
- **给定**：dock-type=DOCK1
- **当**：上报机场 OSD
- **那么**：使用 Dock1OsdBuilder（supports DOCK1）
- **那么**：Dock1 特有字段：`putter_state`/`electric_supply_voltage`（Dock2/Dock3 properties 列表均无）
- **那么**：Dock1 的 sub_device 使用 `product_type` 字段名（Dock2/Dock3 使用 `device_model_key`）
- **给定**：dock-type=DOCK2
- **当**：上报机场 OSD
- **那么**：使用 Dock2OsdBuilder（supports DOCK2）
- **那么**：Dock2 特有字段：`home_position_is_valid`/`heading`（Dock1 无此字段）
- **给定**：dock-type=DOCK3
- **当**：上报机场 OSD
- **那么**：使用 Dock3OsdBuilder（supports DOCK3）
- **那么**：Dock3 特有字段：`home_position_is_valid`/`heading`
- **那么**：三版共有字段（由 AbstractDockOsdBuilder 提供）：`air_conditioner`/`supplement_light_state`/`silent_mode`
- **核实依据**（以 properties 列表为准；示例 JSON 中出现的 putter_state/electric_supply_voltage 为文档复制粘贴错误）：
  - [Dock1 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)：含 `putter_state`/`electric_supply_voltage`/`air_conditioner`/`silent_mode`/`supplement_light_state`
  - [Dock2 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)：含 `air_conditioner`/`silent_mode`/`supplement_light_state`/`home_position_is_valid`/`heading`，无 `putter_state`/`electric_supply_voltage`
  - [Dock3 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3-properties.html)：含 `air_conditioner`/`silent_mode`/`supplement_light_state`/`home_position_is_valid`/`heading`，无 `putter_state`/`electric_supply_voltage`
- **错误后果**：字段集不匹配会导致平台解析异常或遗漏关键字段

#### TC-BUILDER-002：DroneOsdBuilder 按机型家族选择
- **给定**：drone-type=M4TD 或 M4D
- **当**：上报飞行器 OSD
- **那么**：使用 M4DDroneOsdBuilder（supports M4D/M4TD）
- **那么**：字段集包含 `wireless_link_topo`/`cameras`（M4D 家族特有）
- **给定**：drone-type=M30T 或 M30
- **当**：上报飞行器 OSD
- **那么**：使用 M30DroneOsdBuilder（supports M30/M30T）
- **那么**：字段集包含 `payloads`/`distance_limit_status`/`rth_altitude`（M30 家族特有），不包含 `wireless_link_topo`
- **核实依据**：
  - [M4D properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html)：含 `wireless_link_topo`
  - [M30 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)：含 `payloads`，无 `wireless_link_topo`
- **错误后果**：M30 上报 `wireless_link_topo` 会导致平台解析异常

#### TC-BUILDER-003：红外字段按机型 sub_type 条件上报
- **给定**：drone-type=M4TD（sub_type=1，含红外相机）
- **当**：上报飞行器 OSD 的 `cameras` 数组
- **那么**：包含 `thermal_gain_mode`/`thermal_isotherm_state`/`thermal_current_palette_style`
- **给定**：drone-type=M4D（sub_type=0，无红外）
- **当**：上报飞行器 OSD 的 `cameras` 数组
- **那么**：不包含 `thermal_*` 字段
- **核实依据**：[M4D properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html) `thermal_*` 字段（`thermal_gain_mode`/`thermal_isotherm_state`/`thermal_current_palette_style` 等）
- **错误后果**：M4D 上报 `thermal_*` 字段会导致平台解析异常

#### TC-BUILDER-004：共用字段由抽象基类提供
- **给定**：任意 Dock 版本
- **当**：上报机场 OSD
- **那么**：基础共用字段必上报：`mode_code`/`latitude`/`longitude`/`height`/`network_state`/`storage`/`live_capacity`/`sub_device`
- **那么**：三版共有机械结构与环境监测字段必上报：`cover_state`/`drone_in_dock`/`drone_charge_state`/`temperature`/`humidity`/`wind_speed`/`rainfall`/`backup_battery`
- **那么**：三版共有控制字段必上报：`air_conditioner`/`supplement_light_state`/`silent_mode`
- **那么**：版本特有字段由对应 Builder 的 `appendDockSpecific` 追加
- **设计依据**：模板方法模式，避免共用字段在多个 Builder 重复
- **核实依据**：上述字段在 Dock1/Dock2/Dock3 properties 列表中均存在

#### TC-BUILDER-005：OsdStrategy 与 OsdBuilder 正交
- **给定**：dock-type=DOCK1
- **当**：上报机场 OSD
- **那么**：字段名为 camelCase（OsdStrategy 处理命名）
- **那么**：字段集为 Dock1 特有（DockOsdBuilder 处理字段集）
- **说明**：命名风格与字段集是两个独立维度，`OsdStrategy` 注入到 Builder 中复用，避免在 Builder 内重复写 snake_case/camelCase 转换逻辑

#### TC-BUILDER-006：OsdContext 封装依赖
- **给定**：Builder 构造 OSD
- **当**：读取状态/配置
- **那么**：通过 `OsdContext` 获取 `state`/`props`/`runtimeConfig`/`strategy`
- **那么**：Builder 不直接注入 Spring Bean（构造时只注入 `OsdContext`）
- **设计依据**：避免 Builder 方法参数列表过长，符合 facade 模式

#### TC-BUILDER-007：运行时切换机型 Builder 选择
- **给定**：设备已上线，drone-type=M4D
- **当**：通过 REST API 切换 drone-type=M30T
- **那么**：下次 OSD 上报使用 M30DroneOsdBuilder
- **那么**：字段集从 M4D 特有切换为 M30 特有

#### TC-BUILDER-008：backup_battery 结构体字段对齐 DJI 规格
- **给定**：任意 Dock 版本
- **当**：上报机场 OSD 的 `backup_battery` 字段
- **那么**：子字段为 `switch`/`voltage`/`temperature`（三版一致）
- **那么**：不包含 `percent`（DJI 规格中无此字段）
- **核实依据**：[Dock1 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html) backup_battery: switch/voltage/temperature
- **错误后果**：上报 `percent` 会导致平台解析异常

#### TC-BUILDER-009：network_state 不包含 rtt 字段
- **给定**：任意 Dock 版本
- **当**：上报机场 OSD 的 `network_state` 字段
- **那么**：子字段为 `type`/`quality`/`rate`（三版一致）
- **那么**：不包含 `rtt`（DJI 规格中无此字段）
- **核实依据**：[Dock1 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html) network_state: type/quality/rate
- **错误后果**：上报 `rtt` 会导致平台解析异常

#### TC-BUILDER-010：sub_device 字段名按 Dock 版本区分
- **给定**：dock-type=DOCK1
- **当**：上报机场 OSD 的 `sub_device` 字段
- **那么**：子设备枚举值字段名为 `product_type`
- **给定**：dock-type=DOCK2 或 DOCK3
- **当**：上报机场 OSD 的 `sub_device` 字段
- **那么**：子设备枚举值字段名为 `device_model_key`
- **核实依据**：[Dock1 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html) sub_device 用 `product_type`；[Dock2 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html) / [Dock3 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3-properties.html) sub_device 用 `device_model_key`
- **错误后果**：Dock1 使用 `device_model_key` 会导致平台无法识别子设备型号

### 2.13 属性设置（property/set）处理

> 设计背景：DJI Cloud API 中 accessMode=rw 的属性可通过 `thing/product/{gateway_sn}/property/set` 下发设置。
> 模拟器需正确处理：更新本地状态 + 回复 property/set_reply + 下次 OSD 反映新值。
> accessMode=r 的属性为只读状态，PropertySetHandler 仅回复不更新本地状态。
> 核实依据：[Dock1 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html) | [Dock2 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)

#### TC-PROP-001：silent_mode（rw）可通过 property/set 设置
- **给定**：任意 Dock 版本（三版共有），设备已上线
- **当**：收到 property/set，data 包含 `{"silent_mode": 1}`
- **那么**：DeviceState.silentMode 更新为 1
- **那么**：回复 property/set_reply，data 回显 `{"silent_mode": 1}`
- **那么**：下次 OSD 上报 silent_mode=1（静音模式）
- **核实依据**：[Dock1/Dock2/Dock3 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html) silent_mode accessMode=rw（三版均有）

#### TC-PROP-002：air_conditioner_state（r）只读，property/set 不更新本地状态
- **给定**：任意 Dock 版本（三版共有），设备已上线
- **当**：收到 property/set，data 包含 `{"air_conditioner_state": 1}`
- **那么**：PropertySetHandler 仅回复 property/set_reply（data 回显）
- **那么**：DeviceState 中不持久化 air_conditioner_state（它是设备自管理的运行状态）
- **那么**：下次 OSD 上报 air_conditioner_state 仍为模拟值（不受 property/set 影响）
- **核实依据**：[Dock1/Dock2/Dock3 properties](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html) air_conditioner accessMode=r（三版均有，只读）

#### TC-PROP-003：调试面板可通过 REST API 设置 silentMode
- **给定**：任意 Dock 版本（三版共有）
- **当**：前端调用 PUT /api/state，body 包含 `{"silentMode": 1}`
- **那么**：DeviceState.silentMode 更新为 1
- **那么**：下次 OSD 上报 silent_mode=1
- **说明**：调试面板直接修改模拟器内部状态，不走 MQTT property/set，用于快速模拟场景

#### TC-PROP-004：监控器可通过 MQTT property/set 设置 silent_mode
- **给定**：监控器已连接 MQTT，选中任意 Dock 版本设备
- **当**：监控器发送 property/set 到 `thing/product/{sn}/property/set`，data 包含 `{"silent_mode": 1}`
- **那么**：模拟器收到后更新 DeviceState.silentMode=1
- **那么**：回复 property/set_reply
- **那么**：监控器在 OSD 日志中观察到 silent_mode=1
- **说明**：监控器模拟平台下发 property/set，测试模拟器的协议处理能力

### 2.14 MQTT 消息诊断

> 设计背景：监控器作为 MQTT 旁路观察者，订阅了全部双向 topic（services/services_reply、events/events_reply 等）。
> 诊断功能分析日志中的请求-回复配对情况，检测第三方平台代码异常（如缺失 handler、错误码回复等）。
> 纯前端实现，直接分析 monitor.html 的 logs 数组，无需后端改动。

#### TC-DIAG-001：识别请求-回复配对
- **给定**：监控器日志中有 `services` 请求和对应的 `services_reply`，tid 相同
- **当**：用户点击诊断按钮
- **那么**：报告中显示该配对，含请求 topic、回复 topic、method、耗时
- **配对规则**：services↔services_reply、property/set↔property/set_reply、events↔events_reply、requests↔requests_reply、status↔status_reply

#### TC-DIAG-002：检测缺失回复
- **给定**：监控器日志中有 `events` 请求但无 tid 匹配的 `events_reply`
- **当**：用户点击诊断按钮
- **那么**：错误列表显示「缺失回复」，标注 topic 类型、method、tid、时间
- **平台异常推断**：平台未注册该事件的 handler

#### TC-DIAG-003：检测回复错误码
- **给定**：监控器日志中有 `services_reply`，其 `data.result` 或 `result` 非 0
- **当**：用户点击诊断按钮
- **那么**：错误列表显示「回复错误码」，标注 result 值
- **平台异常推断**：平台业务逻辑拒绝（如绑定码错误 210229）

#### TC-DIAG-004：检测孤儿回复
- **给定**：监控器日志中有 `services_reply` 但无 tid 匹配的 `services` 请求
- **当**：用户点击诊断按钮
- **那么**：警告列表显示「孤儿回复」，提示可能超出日志窗口

#### TC-DIAG-005：无需配对的消息不报异常
- **给定**：监控器日志中有 `osd`/`state`/`drc/up` 消息
- **当**：用户点击诊断按钮
- **那么**：这些消息不计入需配对统计，不报异常
- **说明**：osd/state/drc/up 为单向上报，无回复

#### TC-DIAG-006：配对按 tid 精确匹配
- **给定**：`services` 请求 tid=abc，`services_reply` tid=xyz（不匹配）
- **当**：用户点击诊断按钮
- **那么**：services 标记为「缺失回复」，services_reply 标记为「孤儿回复」

#### TC-DIAG-007：JSON 格式错误检测（P-5）
- **给定**：模拟器收到 services/property_set/drc_down 消息，payload 非合法 JSON（如 `"{method:xxx}"` 缺引号）
- **当**：`objectMapper.readTree(payload)` 抛 `JsonParseException`
- **那么**：`ProtocolValidator.classifyException(e)` 返回 `PLATFORM_JSON_FORMAT_ERROR`（P-5）
- **那么**：`log.error` 输出 `[P-5] JSON 格式错误`，不修改 MQTT 回复格式
- **平台异常推断**：平台下发的消息不是合法 JSON

#### TC-DIAG-008：必填字段缺失检测（P-6）
- **给定**：模拟器收到 services 消息，JSON 合法但缺 `tid`/`bid`/`method` 之一
- **当**：`ProtocolValidator.validateFields(node)` 校验必填字段
- **那么**：返回 `PLATFORM_FIELD_MISSING`（P-6）
- **那么**：`log.error` 输出 `[P-6] 必填字段缺失`，标注缺失的字段名
- **平台异常推断**：平台未按 DJI 协议填充必填字段

#### TC-DIAG-009：字段类型错误检测（P-7）
- **给定**：模拟器收到 services 消息，`method` 字段为数字而非字符串（如 `{"method":123}`）
- **当**：`ProtocolValidator.validateFields(node)` 校验字段类型
- **那么**：返回 `PLATFORM_FIELD_TYPE_ERROR`（P-7）
- **那么**：`log.error` 输出 `[P-7] 字段类型错误`，标注期望类型与实际类型
- **平台异常推断**：平台字段类型与 DJI 规范不符

#### TC-DIAG-010：Dock 能力不匹配检测（P-8）
- **给定**：当前 Dock 类型为 Dock1，平台下发 `flighttask_stop`（仅 Dock2/3 支持）
- **当**：`WaylineTaskSimulator.isCommandSupported("flighttask_stop")` 返回 false
- **那么**：`log.warn` 输出当前 Dock 类型不支持该命令，返回 `result=1`
- **那么**：诊断码为 `PLATFORM_DOCK_CAPABILITY_MISMATCH`（P-8）
- **平台异常推断**：平台应知 Dock 能力，下发了不支持的指令
- **实现状态**：已在阶段 1 实现（TC-WAYLINE-013/014/015），阶段 2 补充诊断码日志

#### TC-DIAG-011：未覆盖指令检测（S-2）
- **给定**：模拟器收到 services 消息，method 不在 `WAYLINE_METHODS`/`LIVE_METHODS`/`MEDIA_METHODS`/`DRC_METHODS`/`FLY_METHODS` 等已知集合中
- **当**：`routeCommand` 走到 default 分支
- **那么**：`log.warn` 输出 `[S-2] 未覆盖指令`，返回占位 `result=0`
- **处理建议**：评估是否需要扩展模拟器实现该指令
- **注意**：这不一定是模拟器问题，也可能是 DJI 协议演进或平台瞎编 method，需人工核对 DJI 文档

#### TC-DIAG-012：解析异常检测（S-3）
- **给定**：模拟器处理 services 命令时，handler 内部抛 `NullPointerException`/`ClassCastException`
- **当**：内层 catch 块捕获异常
- **那么**：`ProtocolValidator.classifyException(e)` 返回 `SIMULATOR_PARSE_BUG`（S-3）
- **那么**：`log.error` 输出 `[S-3] 解析异常（疑似Bug）`，返回 `result=1`
- **处理建议**：反馈模拟器修复

#### TC-DIAG-013：S/P/M 日志采集到 DiagnosticLogRecorder
- **给定**：各 Handler catch 块检测到 P-5/P-6/P-7/P-8/S-2/S-3 诊断码
- **当**：`log.error`/`log.warn` 输出诊断码的同时
- **那么**：同步调用 `DiagnosticLogRecorder.record(code, method, detail)` 写入内存缓冲区
- **那么**：每条记录包含：时间戳、诊断码（如 P-5）、method、描述

#### TC-DIAG-014：前端日志面板展示 S/P/M 日志
- **给定**：模拟器运行中，DiagnosticLogRecorder 已采集多条诊断记录
- **当**：用户点击 index.html 的「日志」菜单
- **那么**：展示日志面板，列表显示所有诊断记录
- **那么**：每行展示：时间 | 诊断码 | method | 描述
- **那么**：支持按责任方分类筛选（全部/P 类/S 类/M 类）

#### TC-DIAG-015：断开 MQTT 时清空诊断日志
- **给定**：模拟器已连接 MQTT，DiagnosticLogRecorder 有历史记录
- **当**：用户断开第三方平台（MQTT disconnect）
- **那么**：`DiagnosticLogRecorder.clear()` 清空所有诊断记录
- **那么**：前端日志面板变为空
- **理由**：S/P/M 是实时诊断，断开后已过时，重新连接后从零采集

#### TC-DIAG-016：重新连接后继续采集
- **给定**：模拟器断开后重新连接 MQTT
- **当**：新的诊断事件发生
- **那么**：DiagnosticLogRecorder 从空开始继续采集
- **那么**：前端日志面板展示新的诊断记录

### 2.15 DRC 远程控制

#### TC-DRC-001：DRC 消息格式为 {method, data, seq}
- **给定**：DRC 模式已激活（drc_state=2）
- **当**：设备通过 `drc/up` topic 推送 DRC 消息
- **那么**：消息体为 `{"method":"drc_xxx","data":{...},"seq":N}`
- **那么**：不包含 tid/bid/timestamp/version 字段（与 OSD 格式不同）
- **核实依据**：[Dock3 remote-control](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html) 所有 DRC 消息示例均为 `{method, data, seq}` 三字段

#### TC-DRC-002：DRC 事件 seq 自动递增
- **给定**：DRC 模式已激活
- **当**：设备连续推送多个 DRC 事件（如 drc_drone_state_push）
- **那么**：每条消息的 seq 递增（1, 2, 3, ...）
- **那么**：seq 线程安全（多线程调用不会重复）

#### TC-DRC-003：DRC 命令回复使用与命令相同的 seq
- **给定**：平台下发 `{"method":"drc_force_landing","data":{},"seq":42}` 到 `drc/down`
- **当**：设备回复到 `drc/up`
- **那么**：回复消息 seq=42（与命令相同）
- **那么**：回复 data 含 `result` 字段（0=成功，非 0=错误）
- **核实依据**：[Dock3 remote-control 强制降落](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#强制降落) 回复示例 seq 与命令一致

#### TC-DRC-004：MQTT 连接时订阅 drc/down topic
- **给定**：MQTT 连接成功
- **当**：执行 topic 订阅
- **那么**：订阅 `thing/product/{gateway_sn}/drc/down`（QoS 1）
- **那么**：与 services/property_set 等下行 topic 一起订阅

#### TC-DRC-005：DRC 命令处理器按 method 分发
- **给定**：DRC 命令处理器已注册监听 `drc/down`
- **当**：收到 `{"method":"drc_force_landing","data":{},"seq":1}`
- **那么**：按 method 路由到对应处理器
- **那么**：未实现的 method 回复 result=0 占位（保证平台不报错）

#### TC-DRC-006：DRC 模式未激活时不推送 DRC 事件
- **给定**：drc_state=0（未进入 DRC 模式）
- **当**：OSD 定时上报触发
- **那么**：不向 `drc/up` topic 推送任何 DRC 事件
- **说明**：DRC 事件仅在 DRC 模式激活时推送

#### TC-DRC-007：移除错误的 DRC OSD 格式
- **给定**：DRC 模式激活
- **当**：DRC 事件推送
- **那么**：不再使用 `{tid, bid, timestamp, data, gateway}` 格式（OSD 格式）
- **那么**：不再推送无 method 字段的 DRC 消息
- **说明**：旧实现 buildDrcOsdJson() 使用 wrapOsd() 包装，格式错误，已移除

#### TC-DRC-008：drc_drone_state_push 字段集
- **给定**：DRC 模式已激活（drc_state=2）
- **当**：设备推送 `drc_drone_state_push` 事件
- **那么**：data 包含 3 个字段：`mode_code`/`stealth_state`/`night_lights_state`
- **那么**：`mode_code` 取自 `DeviceState.droneModeCode`
- **那么**：`stealth_state`/`night_lights_state` 为 int 类型（0=关闭, 1=开启）
- **核实依据**：[Dock3 remote-control 飞行器状态信息上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#飞行器状态信息上报)

#### TC-DRC-009：drc_drone_state_push 推送频率与 OSD 一致
- **给定**：DRC 模式已激活
- **当**：OSD 定时上报触发（每 2 秒）
- **那么**：同一周期内推送 `drc_drone_state_push` 到 `drc/up`
- **那么**：seq 自动递增（与其他 DRC 事件共享计数器）

#### TC-DRC-010：stealth_state 和 night_lights_state 默认关闭
- **给定**：设备初始状态
- **当**：推送 `drc_drone_state_push`
- **那么**：`stealth_state=0`（隐蔽模式关闭）
- **那么**：`night_lights_state=0`（夜航灯关闭）

#### TC-DRC-011：drc_camera_state_push 字段集
- **给定**：DRC 模式已激活（drc_state=2）
- **当**：设备推送 `drc_camera_state_push` 事件
- **那么**：data 包含 3 个顶层字段：`payload_index`/`camera_state`/`media_storage`
- **那么**：`payload_index` 格式为 `{type-subtype-gimbalindex}`（如 "81-0-0"）
- **那么**：`camera_state` 包含 `camera_mode`/`recording_state`/`photo_state`/`record_time`/`remain_photo_num`/`night_mode_settings` 等
- **那么**：`media_storage` 包含 `photo_storage_settings`/`video_storage_settings` 数组
- **核实依据**：[Dock3 remote-control 相机状态上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#相机状态上报)

#### TC-DRC-012：drc_camera_state_push 默认值
- **给定**：设备初始状态
- **当**：推送 `drc_camera_state_push`
- **那么**：`camera_mode=0`（拍照模式）
- **那么**：`recording_state=0`（空闲）
- **那么**：`photo_state=0`（空闲）
- **那么**：`record_time=0`
- **那么**：`remain_photo_num>0`（有剩余拍照张数）

#### TC-DRC-013：drc_camera_state_push 与 drc_drone_state_push 共享 seq 计数器
- **给定**：DRC 模式已激活
- **当**：同一周期内推送多个 DRC 事件
- **那么**：`drc_drone_state_push` 和 `drc_camera_state_push` 的 seq 连续递增（不重置）

#### TC-DRC-014：drc_camera_osd_info_push 字段集
- **给定**：DRC 模式已激活（drc_state=2）
- **当**：设备推送 `drc_camera_osd_info_push` 事件
- **那么**：data 包含 6 个顶层字段：`payload_index`/`wide_lense`/`zoom_lense`/`ir_lense`/`measure_target`/`liveview`
- **那么**：`wide_lense` 包含曝光模式/ISO/快门速度/曝光值/光圈值
- **那么**：`zoom_lense` 包含曝光/对焦/变焦倍数/光圈值
- **那么**：`ir_lense` 包含红外变焦/调色盘/增益/等温线/全局温度
- **那么**：`measure_target` 包含激光测距经纬度/海拔/距离
- **那么**：`liveview` 包含视场角区域坐标
- **核实依据**：[Dock3 remote-control 摄像头osd推送](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#摄像头osd-推送)

#### TC-DRC-015：drc_camera_osd_info_push 默认值
- **给定**：设备初始状态
- **当**：推送 `drc_camera_osd_info_push`
- **那么**：`zoom_factor` 初始值为合理正数（如 7.0）
- **那么**：`thermal_global_temperature_min` < `thermal_global_temperature_max`
- **那么**：`measure_target_distance` 默认 0（无测距目标）

#### TC-DRC-016：drc_camera_osd_info_push 与前两个事件共享 seq 计数器
- **给定**：DRC 模式已激活
- **当**：同一周期内推送 3 个 DRC 事件
- **那么**：seq 连续递增：drone_state_push(N) → camera_state_push(N+1) → camera_osd_info_push(N+2)

#### TC-DRC-017：drc_force_landing 指令处理
- **给定**：平台通过 `drc/down` 下发 `{"method":"drc_force_landing","data":{},"seq":N}`
- **当**：模拟器收到指令
- **那么**：通过 `drc/up` 回复 `{"method":"drc_force_landing","data":{"result":0},"seq":N}`
- **那么**：飞行器 mode_code 变为降落状态
- **核实依据**：[Dock3 remote-control 强制降落](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#强制降落)

#### TC-DRC-018：drone_emergency_stop 指令处理
- **给定**：平台通过 `drc/down` 下发 `{"method":"drone_emergency_stop","data":{},"seq":N}`
- **当**：模拟器收到指令
- **那么**：通过 `drc/up` 回复 `{"method":"drone_emergency_stop","data":{"result":0},"seq":N}`
- **那么**：可取消 drc_force_landing / drc_emergency_landing 的降落过程
- **核实依据**：[Dock3 remote-control 急停](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#急停)

#### TC-DRC-019：drc_emergency_landing 指令处理
- **给定**：平台通过 `drc/down` 下发 `{"method":"drc_emergency_landing","data":{},"seq":N}`
- **当**：模拟器收到指令
- **那么**：通过 `drc/up` 回复 `{"method":"drc_emergency_landing","data":{"result":0},"seq":N}`
- **那么**：飞行器 mode_code 变为降落状态（与 force_landing 不同：受避障影响可能中止）
- **核实依据**：[Dock3 remote-control 紧急降落](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#紧急降落)

#### TC-DRC-020：DRC 指令回复 seq 与请求一致
- **给定**：平台下发 DRC 指令 seq=42
- **当**：模拟器回复
- **那么**：回复消息的 seq=42（不使用事件计数器自增的 seq）

#### TC-DRC-021：drc_camera_night_mode_set 指令处理
- **给定**：平台下发 `{"method":"drc_camera_night_mode_set","data":{"payload_index":"99-0-0","mode":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_camera_night_mode_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 nightMode 更新为 1（开启）
- **核实依据**：[Dock3 remote-control 夜景模式设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#夜景模式设置)

#### TC-DRC-022：drc_camera_denoise_level_set 指令处理
- **给定**：平台下发 `{"method":"drc_camera_denoise_level_set","data":{"payload_index":"99-0-0","level":2},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_camera_denoise_level_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 denoiseLevel 更新为 2（增强降噪 15fps）
- **核实依据**：[Dock3 remote-control 降噪等级设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#降噪等级设置)

#### TC-DRC-023：drc_camera_night_vision_enable 指令处理
- **给定**：平台下发 `{"method":"drc_camera_night_vision_enable","data":{"payload_index":"99-0-0","enable":true},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_camera_night_vision_enable","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 nightVisionEnable 更新为 true
- **核实依据**：[Dock3 remote-control 黑白夜视使能](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#黑白夜视使能)

#### TC-DRC-024：drc_infrared_fill_light_enable 指令处理
- **给定**：平台下发 `{"method":"drc_infrared_fill_light_enable","data":{"payload_index":"99-0-0","enable":true},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_infrared_fill_light_enable","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 infraredFillLightEnable 更新为 true
- **核实依据**：[Dock3 remote-control 近红外补光使能](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#近红外补光使能)

#### TC-DRC-025：drc_light_brightness_set 指令处理
- **给定**：平台下发 `{"method":"drc_light_brightness_set","data":{"psdk_index":1,"group":0,"brightness":80},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_light_brightness_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 lightBrightness 更新为 80
- **核实依据**：[Dock3 remote-control 探照灯亮度设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#探照灯-亮度设置)

#### TC-DRC-026：drc_light_mode_set 指令处理
- **给定**：平台下发 `{"method":"drc_light_mode_set","data":{"psdk_index":1,"group":0,"mode":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_light_mode_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 lightMode 更新为 1（常亮）
- **核实依据**：[Dock3 remote-control 探照灯模式设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#探照灯-模式设置)

#### TC-DRC-027：drc_light_fine_tuning_set 指令处理
- **给定**：平台下发 `{"method":"drc_light_fine_tuning_set","data":{"psdk_index":1,"position":0,"value":2,"saved":false},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_light_fine_tuning_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 lightLeftAngle 更新为 2（position=0 为左灯）
- **核实依据**：[Dock3 remote-control 探照灯左右角度微调](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#探照灯-左右角度微调)

#### TC-DRC-028：drc_light_calibration 指令处理
- **给定**：平台下发 `{"method":"drc_light_calibration","data":{"psdk_index":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_light_calibration","data":{"result":0},"seq":N}`
- **核实依据**：[Dock3 remote-control 探照灯云台校准](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#探照灯-云台校准)

#### TC-DRC-029：drc_speaker_play_mode_set 指令处理
- **给定**：平台下发 `{"method":"drc_speaker_play_mode_set","data":{"psdk_index":1,"play_mode":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_speaker_play_mode_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 speakerPlayMode 更新为 1（循环播放）
- **核实依据**：[Dock3 remote-control 喊话器模式设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-模式设置)

#### TC-DRC-030：drc_speaker_tts_set 指令处理
- **给定**：平台下发 `{"method":"drc_speaker_tts_set","data":{"psdk_index":1,"volume":80,"type":0,"language":0,"speed":50},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_speaker_tts_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 speakerVolume 更新为 80，speakerPlaying 设为 true
- **核实依据**：[Dock3 remote-control 喊话器TTS喊话设置](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-tts喊话设置)

#### TC-DRC-031：drc_speaker_play_volume_set 指令处理
- **给定**：平台下发 `{"method":"drc_speaker_play_volume_set","data":{"psdk_index":1,"play_volume":60},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_speaker_play_volume_set","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 speakerVolume 更新为 60
- **核实依据**：[Dock3 remote-control 喊话器设置音量](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-设置音量)

#### TC-DRC-032：drc_speaker_play_stop 指令处理
- **给定**：平台下发 `{"method":"drc_speaker_play_stop","data":{"psdk_index":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_speaker_play_stop","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 speakerPlaying 设为 false
- **核实依据**：[Dock3 remote-control 喊话器停止播放](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-停止播放)

#### TC-DRC-033：drc_speaker_replay 指令处理
- **给定**：平台下发 `{"method":"drc_speaker_replay","data":{"psdk_index":1},"seq":N}`
- **当**：模拟器收到指令
- **那么**：回复 `{"method":"drc_speaker_replay","data":{"result":0},"seq":N}`
- **那么**：DeviceState 的 speakerPlaying 设为 true
- **核实依据**：[Dock3 remote-control 喊话器重新播放](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-重新播放)

#### TC-DRC-034：drc_psdk_floating_window_text 事件推送
- **给定**：DRC 模式已激活
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `{"method":"drc_psdk_floating_window_text","data":{"psdk_index":0,"floating_window_text":""},"seq":N}`
- **核实依据**：[Dock3 remote-control PSDK浮窗推送](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#psdk-浮窗推送)

#### TC-DRC-035：drc_psdk_state_info 事件推送（探照灯状态）
- **给定**：DRC 模式已激活，平台已下发 `drc_light_brightness_set` brightness=80
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `drc_psdk_state_info`，其中 `light.brightness=80`（反映指令变更后的状态）
- **那么**：psdk_name="Searchlight"，psdk_index=1
- **核实依据**：[Dock3 remote-control PSDK状态上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#psdk-状态上报)

#### TC-DRC-036：drc_psdk_state_info 事件推送（喊话器状态）
- **给定**：DRC 模式已激活，平台已下发 `drc_speaker_play_volume_set` play_volume=60
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `drc_psdk_state_info`，其中 `speaker.play_volume=60`（反映指令变更后的状态）
- **那么**：psdk_name="Speaker"，psdk_index=2
- **核实依据**：[Dock3 remote-control PSDK状态上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#psdk-状态上报)

#### TC-DRC-037：drc_psdk_ui_resource 事件推送
- **给定**：DRC 模式已激活
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `{"method":"drc_psdk_ui_resource","data":{"psdk_index":0,"psdk_ready":1,"object_key":"..."},"seq":N}`
- **核实依据**：[Dock3 remote-control PSDK UI资源包上传](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#psdk-ui资源包上传)

#### TC-DRC-038：drc_ai_info_push 事件推送
- **给定**：DRC 模式已激活
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `drc_ai_info_push`，含 identify_on/spotlight_zoom_on/ai_spotlight_zoom/ai_model_list/selected_ai_model
- **核实依据**：[Dock3 remote-control AI状态上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#ai状态上报)

#### TC-DRC-039：drc_speaker_play_progress 事件推送
- **给定**：DRC 模式已激活，平台已下发 `drc_speaker_tts_set` 触发 TTS 播放
- **当**：DRC 事件推送周期到达
- **那么**：通过 `drc/up` 推送 `drc_speaker_play_progress`，含 psdk_index/result/status/progress
- **那么**：status="success"，progress.percent=100（模拟器播放立即完成）
- **核实依据**：[Dock3 remote-control 喊话器音频播放进度](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/remote-control.html#喊话器-音频播放进度)

#### TC-DRC-040：DRC 模式进入后开始推送事件
- **给定**：DRC 模式未激活（drc_state=0），无 DRC 事件推送
- **当**：平台下发 `drc_mode_enter` 成功，drc_state 变为非 0
- **那么**：下一个 OSD 周期开始推送 8 条 DRC 事件
- **核实依据**：DRC 事件仅在 drc_state != 0 时推送（DeviceSimulator.publishOsd 条件判断）

#### TC-DRC-041：DRC 模式退出后停止推送事件
- **给定**：DRC 模式已激活，正在推送 DRC 事件
- **当**：平台下发 `drc_mode_exit` 成功，drc_state 变为 0
- **那么**：下一个 OSD 周期不再推送任何 DRC 事件
- **核实依据**：DRC 事件仅在 drc_state != 0 时推送（DeviceSimulator.publishOsd 条件判断）

#### TC-DRC-042：未注册的 DRC 方法返回占位 result=0
- **给定**：平台下发 `{"method":"unknown_method","data":{},"seq":N}`
- **当**：模拟器收到未注册的 DRC 方法
- **那么**：回复 `{"method":"unknown_method","data":{"result":0},"seq":N}`（占位成功，不报错）
- **核实依据**：DrcCommandHandler.handleDrcCommand 中 handler==null 时返回 result=0

#### TC-DRC-043：探照灯亮度指令→PSDK状态闭环验证
- **给定**：DRC 模式已激活
- **当**：平台下发 `drc_light_brightness_set` brightness=80
- **那么**：下一个周期的 `drc_psdk_state_info`（探照灯）中 `light.brightness=80`
- **核实依据**：指令更新 DeviceState.lightBrightness → 事件读取同一字段

#### TC-DRC-044：喊话器音量指令→PSDK状态闭环验证
- **给定**：DRC 模式已激活
- **当**：平台下发 `drc_speaker_play_volume_set` play_volume=60
- **那么**：下一个周期的 `drc_psdk_state_info`（喊话器）中 `speaker.play_volume=60`
- **核实依据**：指令更新 DeviceState.speakerVolume → 事件读取同一字段

#### TC-DRC-045：相机夜景模式指令→camera_state_push 闭环验证
- **给定**：DRC 模式已激活
- **当**：平台下发 `drc_camera_night_mode_set` mode=1
- **那么**：下一个周期的 `drc_camera_state_push` 中 `night_mode_settings.night_mode=1`
- **核实依据**：指令更新 DeviceState.nightMode → 事件读取同一字段

#### TC-DRC-046：每个 DRC 周期推送事件完整性
- **给定**：DRC 模式已激活
- **当**：一个 OSD 周期（2 秒）内推送 DRC 事件
- **那么**：依次推送 8 条基础事件，seq 连续递增：
  1. `drc_drone_state_push`
  2. `drc_camera_state_push`
  3. `drc_camera_osd_info_push`
  4. `drc_psdk_floating_window_text`
  5. `drc_psdk_state_info`（探照灯，psdk_index=1）
  6. `drc_psdk_state_info`（喊话器，psdk_index=2）
  7. `drc_psdk_ui_resource`
  8. `drc_ai_info_push`
- **那么**：当 speakerPlaying=true 时，在第 6 条后额外推送 `drc_speaker_play_progress`（共 9 条）

#### TC-DRC-047：DRC 指令处理器异常时返回 result=1
- **给定**：平台下发合法 DRC 指令，但处理器执行时抛出异常
- **当**：模拟器捕获异常
- **那么**：回复 `{"method":"...","data":{"result":1},"seq":N}`（错误码 1）
- **那么**：不中断后续 DRC 事件推送
- **核实依据**：DrcCommandHandler.handleDrcCommand 中 try-catch 返回 result=1

### 2.16 指令飞行（drc.html）

> 设计背景：DJI Cloud API 中「指令飞行」（drc.html）与「远程控制」（remote-control.html，见 2.15）是两套独立协议：
> - **远程控制**（2.15）：走 `drc/down`、`drc/up` topic，消息格式 `{method, data, seq}`，处理 drc_xxx 实时控制指令（飞行安全/相机/探照灯/喊话器）
> - **指令飞行**（本节）：走 `services`、`events` topic，消息格式 `{tid, bid, method, data, timestamp}`，处理飞行任务指令（一键起飞/flyto/控制权抢夺）及进度事件
>
> 指令飞行包含两类：
> - **Service 类**（云→设备，services topic）：`flight_authority_grab`、`payload_authority_grab`、`drc_mode_enter`、`drc_mode_exit`、`takeoff_to_point`、`fly_to_point`
> - **Event 类**（设备→云，events topic）：`fly_to_point_progress`、`takeoff_to_point_progress`、`obstacle_avoidance_notify`、`joystick_invalid_notify`、`camera_photo_take_progress`、`poi_status_notify`
>
> 核实依据：
> - [Dock1 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html)
> - [Dock2 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/drc.html)
> - [Dock3 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html)

#### TC-FLY-001：指令飞行与远程控制协议通道隔离
- **给定**：DRC 模式已激活（drc_state=2）
- **当**：平台下发 `fly_to_point`（指令飞行）
- **那么**：通过 `thing/product/{gateway_sn}/services` 接收，回 `services_reply`（格式 `{tid, bid, method, data, timestamp}`）
- **那么**：不通过 `drc/down`/`drc/up` 通道处理（那是远程控制通道）
- **核实依据**：[Dock3 drc.html flyto](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#flyto-飞向目标点) topic 为 `thing/product/{gateway_sn}/services`

#### TC-FLY-002：fly_to_point 指令处理
- **给定**：平台通过 services 下发 `{"method":"fly_to_point","data":{"fly_to_id":"xxx","max_speed":12,"points":[{"latitude":12.23,"longitude":12.23,"height":100}]}}`
- **当**：模拟器收到指令
- **那么**：立即回 `services_reply`，method=`fly_to_point`，data.result=0
- **那么**：解析 fly_to_id、max_speed、points[0]（目标点）存入 DeviceState 当前飞行任务
- **那么**：调度 `fly_to_point_progress` 事件序列（见 TC-FLY-006）
- **核实依据**：[Dock3 drc.html flyto](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#flyto-飞向目标点) 回复 data 仅含 result

#### TC-FLY-003：takeoff_to_point 指令处理
- **给定**：平台通过 services 下发 `{"method":"takeoff_to_point","data":{"flight_id":"xxx","target_latitude":12.23,"target_longitude":12.32,"target_height":100,"security_takeoff_height":100,"max_speed":12,...}}`
- **当**：模拟器收到指令
- **那么**：立即回 `services_reply`，method=`takeoff_to_point`，data.result=0
- **那么**：解析 flight_id、target_latitude/longitude/height、security_takeoff_height、max_speed 存入 DeviceState
- **那么**：调度 `takeoff_to_point_progress` 事件序列（见 TC-FLY-008），**不再使用通用 output.status=ok 占位**
- **核实依据**：[Dock3 drc.html 一键起飞](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞) 回复 data 仅含 result；进度事件为专用 `takeoff_to_point_progress`

#### TC-FLY-004：takeoff_to_point 不再走通用异步占位
- **给定**：平台下发 `takeoff_to_point`
- **当**：模拟器处理指令
- **那么**：不从 `ASYNC_JOB_METHODS` 走通用 `events(method=takeoff_to_point, output.status=ok)`（旧实现）
- **那么**：改用专用 `takeoff_to_point_progress` 事件（method 不同，字段集不同）
- **错误后果**：通用 output.status=ok 缺少 flight_id/track_id/planned_path_points 等字段，平台无法识别起飞进度
- **核实依据**：[Dock3 drc.html 一键起飞结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞结果事件通知) method 为 `takeoff_to_point_progress`

#### TC-FLY-005：flight_authority_grab 指令处理
- **给定**：平台通过 services 下发 `{"method":"flight_authority_grab","data":{}}`
- **当**：模拟器收到指令
- **那么**：立即回 `services_reply`，method=`flight_authority_grab`，data.result=0
- **那么**：不发 events 进度事件（同步指令，无异步确认）
- **核实依据**：[Dock3 drc.html 飞行控制权抢夺](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#飞行控制权抢夺) 回复 data 仅含 result，无进度事件

#### TC-FLY-006：fly_to_point_progress 事件字段集
- **给定**：平台已下发 `fly_to_point`，fly_to_id=xxx，目标点 (12.23, 12.23, 100)
- **当**：模拟器调度进度事件
- **那么**：通过 `events` topic 上报，method=`fly_to_point_progress`
- **那么**：data 包含字段：`fly_to_id`/`status`/`result`/`way_point_index`/`remaining_distance`/`remaining_time`/`planned_path_points`
- **那么**：`planned_path_points` 为数组，元素含 `latitude`/`longitude`/`height`（椭球高）
- **那么**：bid 与原始 services 指令的 bid 一致（hivemind 通过 bid 关联 ACK）
- **核实依据**：[Dock3 drc.html flyto 执行结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#flyto-执行结果事件通知)

#### TC-FLY-007：fly_to_point_progress 状态流转
- **给定**：平台已下发 `fly_to_point`
- **当**：模拟器调度进度事件
- **那么**：状态流转为 `wayline_progress`（执行中）→ `wayline_ok`（执行成功）
- **那么**：`wayline_progress` 时 remaining_distance>0、remaining_time>0、way_point_index=0
- **那么**：`wayline_ok` 时 remaining_distance=0、remaining_time=0、result=0
- **核实依据**：[Dock3 drc.html flyto 执行结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#flyto-执行结果事件通知) status 枚举 {wayline_cancel, wayline_failed, wayline_ok, wayline_progress}

#### TC-FLY-008：takeoff_to_point_progress 事件字段集
- **给定**：平台已下发 `takeoff_to_point`，flight_id=xxx
- **当**：模拟器调度进度事件
- **那么**：通过 `events` topic 上报，method=`takeoff_to_point_progress`
- **那么**：data 包含字段：`status`/`result`/`flight_id`/`track_id`/`way_point_index`/`remaining_distance`/`remaining_time`/`planned_path_points`
- **那么**：`track_id` 为非空字符串（航迹 ID）
- **那么**：bid 与原始 services 指令的 bid 一致
- **核实依据**：[Dock3 drc.html 一键起飞结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞结果事件通知)

#### TC-FLY-009：takeoff_to_point_progress 状态流转
- **给定**：平台已下发 `takeoff_to_point`
- **当**：模拟器调度进度事件
- **那么**：状态流转为 `task_ready`（准备起飞）→ `wayline_progress`（执行中）→ `wayline_ok`（执行成功）→ `task_finish`（任务完成）
- **那么**：`task_finish` 时 result=0，标识一键起飞任务完成
- **核实依据**：[Dock3 drc.html 一键起飞结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞结果事件通知) status 枚举 {task_finish, task_ready, wayline_cancel, wayline_failed, wayline_ok, wayline_progress}

#### TC-FLY-010：obstacle_avoidance_notify 事件字段集（Dock3 专属）
- **给定**：dock-type=DOCK3，飞行任务执行中
- **当**：通过 REST API 触发避障记录上报
- **那么**：通过 `events` topic 上报，method=`obstacle_avoidance_notify`
- **那么**：data 包含字段：`wayline_uuid`/`flight_id`/`obstacles`/`is_final_report`
- **那么**：`obstacles` 数组元素含 `id`/`type`/`timestamp`/`latitude`/`longitude`/`height`/`wayline_id`/`waypoint_index`
- **那么**：`type` 枚举 {0:绕行开始, 1:绕行结束, 2:避障刹停}
- **那么**：`is_final_report`=true 标识该避障事件组已上报完成
- **核实依据**：[Dock3 drc.html 避障记录上报事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#避障记录上报事件通知)（仅 Dock3 文档有此事件）

#### TC-FLY-011：joystick_invalid_notify 事件字段集
- **给定**：DRC 模式已激活，飞行控制可用
- **当**：通过 REST API 触发飞行控制无效通知
- **那么**：通过 `events` topic 上报，method=`joystick_invalid_notify`
- **那么**：data 包含字段：`reason`（int）
- **那么**：`reason` 枚举 {0:遥控器失联, 1:低电量返航, 2:低电量降落, 3:靠近限飞区, 4:遥控器夺权}
- **核实依据**：[Dock3 drc.html DRC-飞行控制无效原因通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#drc-飞行控制无效原因通知)

#### TC-FLY-012：camera_photo_take_progress 事件字段集
- **给定**：相机处于全景拍照模式（camera_mode=3）
- **当**：通过 REST API 触发拍照进度上报
- **那么**：通过 `events` topic 上报，method=`camera_photo_take_progress`
- **那么**：data 包含字段：`output`/`result`
- **那么**：`output.status` 枚举 {fail, in_progress, ok}
- **那么**：`output.progress.current_step` 枚举 {3000:未开始或已结束, 3002:正在拍摄, 3005:合成中}
- **那么**：`output.progress.percent` 范围 0-100
- **那么**：`output.ext.camera_mode`=3（全景拍照）
- **核实依据**：[Dock3 drc.html 上报拍照进度](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#上报拍照进度)

#### TC-FLY-013：poi_status_notify 事件字段集（Dock1 专属）
- **给定**：dock-type=DOCK1，飞行器已起飞
- **当**：通过 REST API 触发 POI 环绕状态通知
- **那么**：通过 `events` topic 上报，method=`poi_status_notify`
- **那么**：data 包含字段：`status`/`reason`/`circle_radius`/`circle_speed`/`max_circle_speed`
- **那么**：`status` 枚举 {failed, in_progress, ok}
- **那么**：`reason` 枚举 {0:正常, 1:未适配负载, 2:不支持该相机模式, 3:非法命令, 4:定位失败, 5:飞行器未起飞, 6:飞行模式错误, 7:该模式下不可用, 8:丢失遥控器或图传信号}
- **核实依据**：[Dock1 drc.html 飞行控制—POI 环绕状态信息通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html#飞行控制-poi-环绕状态信息通知)（仅 Dock1 文档有此事件）

#### TC-FLY-014：Dock3 才支持 obstacle_avoidance_notify
- **给定**：dock-type=DOCK1 或 DOCK2
- **当**：通过 REST API 触发避障记录上报
- **那么**：返回拒绝（success=false），提示「避障记录上报仅 Dock3 支持」
- **核实依据**：[Dock1 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html) 和 [Dock2 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/drc.html) 均无 obstacle_avoidance_notify 事件

#### TC-FLY-015：Dock1 才支持 poi_status_notify
- **给定**：dock-type=DOCK2 或 DOCK3
- **当**：通过 REST API 触发 POI 环绕状态通知
- **那么**：返回拒绝（success=false），提示「POI 环绕状态通知仅 Dock1 支持」
- **核实依据**：[Dock2 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/drc.html) 和 [Dock3 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html) 均无 poi_status_notify 事件

#### TC-FLY-016：drc_status_notify 已废弃不实现
- **给定**：任意 Dock 版本
- **当**：模拟器收到 drc_status_notify 相关需求
- **那么**：不实现该事件上报
- **那么**：DRC 链路状态通过 `drc_state` 设备属性上报（state topic）替代
- **核实依据**：[Dock3 drc.html DRC 链路状态通知（已废弃）](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#drc-链路状态通知-已废弃) 文档明确标注「本协议不再维护且不准确，通过机场上报的设备属性 drc_state 或使用 DRC-心跳可感知更准确的DRC链路状态」

#### TC-FLY-017：fly_to_point 与 takeoff_to_point 进度事件 bid 关联
- **给定**：平台下发 `fly_to_point`，bid=abc-123
- **当**：模拟器调度 fly_to_point_progress 事件
- **那么**：事件报文的 bid=abc-123（与原始指令一致）
- **那么**：hivemind 通过 bid 将 events 与 services ACK 关联，置 ACK=SUCCESS
- **错误后果**：bid 不一致会导致 ACK 永远 IN_PROGRESS → 30 秒超时 FAILED
- **核实依据**：异步指令双阶段确认机制（AsyncCommandSimulator），hivemind DjiCommandAckService.updateByProgress 通过 bid 关联 events 与 services ACK

#### TC-FLY-018：fly_to_point planned_path_points 包含起飞点与目标点
- **给定**：平台下发 `fly_to_point`，目标点 (12.23, 12.23, 100)，当前飞行器位置 (13.0, 13.0, 80)
- **当**：模拟器上报 fly_to_point_progress
- **那么**：`planned_path_points` 至少包含起飞点与目标点两个元素
- **那么**：每个元素含 `latitude`/`longitude`/`height`（椭球高）
- **核实依据**：[Dock3 drc.html flyto 执行结果事件通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#flyto-执行结果事件通知) planned_path_points 为规划的轨迹点列表

### 2.17 远程调试（cmd.html）

> 设计背景：DJI Cloud API「远程调试」走 services/services_reply + events topic，分为同步指令（cmd，仅 services_reply）和异步任务（job，services_reply + events 进度）。
>
> 核实依据：[Dock3 远程调试](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html) | [Dock2 远程调试](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html) | [Dock1 远程调试](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html)

#### TC-RD-001：Job 指令双阶段确认机制
- **给定**：平台通过 services 下发 `{"method":"cover_open","data":{},"bid":"xxx"}`
- **当**：模拟器收到指令
- **那么**：立即回 `services_reply`，method=`cover_open`，data.result=0
- **那么**：随后通过 `events` 上报进度，method=`cover_open`，bid=`xxx`（与原始 services 一致）
- **那么**：进度事件 output.status 流转：`in_progress` → `ok`
- **那么**：进度事件 output.progress.percent 流转：50 → 100
- **核实依据**：[Dock3 远程调试-打开舱盖](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html#打开舱盖) Services 下行 + Events 上行

#### TC-RD-002：cover_open 状态同步
- **给定**：state.coverOpen=false
- **当**：模拟器收到 `cover_open` 指令并完成进度事件
- **那么**：state.coverOpen 变为 true（OSD 下次上报反映此变更）
- **核实依据**：cover_open 语义为打开舱盖，OSD 中 cover_state 字段应同步

#### TC-RD-003：cover_close / cover_force_close 状态同步
- **给定**：state.coverOpen=true
- **当**：模拟器收到 `cover_close` 或 `cover_force_close` 指令
- **那么**：state.coverOpen 变为 false
- **核实依据**：[Dock1 远程调试-强制关舱盖](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html#强制关舱盖)

#### TC-RD-004：drone_open 状态同步
- **给定**：state.droneInDock=true, state.dockModeCode=0
- **当**：模拟器收到 `drone_open` 指令并完成进度事件
- **那么**：state.droneInDock 变为 false（飞行器已离开舱内）
- **核实依据**：drone_open 语义为飞行器开机，OSD 中 drone_in_dock 字段应同步

#### TC-RD-005：drone_close 状态同步
- **给定**：state.droneInDock=false
- **当**：模拟器收到 `drone_close` 指令并完成进度事件
- **那么**：state.droneInDock 变为 true（飞行器已回到舱内）
- **核实依据**：drone_close 语义为飞行器关机

#### TC-RD-006：charge_open / charge_close 状态同步
- **给定**：state.droneChargeState=2（充满）
- **当**：模拟器收到 `charge_open` 指令
- **那么**：state.droneChargeState 变为 1（充电中）
- **当**：模拟器收到 `charge_close` 指令
- **那么**：state.droneChargeState 变为 0（未充电）
- **核实依据**：OSD 中 drone_charge_state 字段应同步

#### TC-RD-007：putter_open / putter_close 状态同步（仅 Dock1）
- **给定**：dock-type=DOCK1, state.putterExpanded=false
- **当**：模拟器收到 `putter_open` 指令
- **那么**：state.putterExpanded 变为 true
- **当**：模拟器收到 `putter_close` 指令
- **那么**：state.putterExpanded 变为 false
- **核实依据**：[Dock1 远程调试-推杆展开](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html)（仅 Dock1 文档有此指令）

#### TC-RD-008：device_reboot / device_format / drone_format 无状态变更
- **给定**：任意设备状态
- **当**：模拟器收到 `device_reboot` / `device_format` / `drone_format` 指令
- **那么**：回 services_reply result=0 + 进度事件（in_progress → ok）
- **那么**：DeviceState 无变更（这些指令不改变机场物理状态字段）
- **核实依据**：device_reboot 语义为重启机场（模拟器不模拟断开重连），format 为数据格式化

#### TC-RD-009：同步 Cmd 指令仅回 services_reply
- **给定**：平台下发 `{"method":"debug_mode_open","data":{},"bid":"xxx"}`
- **当**：模拟器收到指令
- **那么**：立即回 `services_reply`，method=`debug_mode_open`，data.result=0
- **那么**：**不发送** events 进度事件（同步指令无进度上报）
- **核实依据**：[Dock3 远程调试-调试模式打开](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html) 仅 Services 下行 + services_reply，无 Events 上行

#### TC-RD-010：Dock1 才支持 putter_open / putter_close
- **给定**：dock-type=DOCK2 或 DOCK3
- **当**：模拟器收到 `putter_open` 或 `putter_close` 指令
- **那么**：回 services_reply result=0（占位应答，不报错）
- **那么**：**不发送** events 进度事件，**不更新** DeviceState
- **核实依据**：[Dock2 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html) 和 [Dock3 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html) 均无 putter 指令

#### TC-RD-011：Dock2+3 才支持 esim_activate / esim_operator_switch
- **给定**：dock-type=DOCK1
- **当**：模拟器收到 `esim_activate` 或 `esim_operator_switch` 指令
- **那么**：回 services_reply result=0（占位应答）
- **那么**：**不发送** events 进度事件
- **核实依据**：[Dock1 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html) 无 esim 指令

#### TC-RD-012：Dock3 才支持 rtk_calibration
- **给定**：dock-type=DOCK1 或 DOCK2
- **当**：模拟器收到 `rtk_calibration` 指令
- **那么**：回 services_reply result=0（占位应答）
- **那么**：**不发送** events 进度事件
- **核实依据**：[Dock1 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html) 和 [Dock2 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html) 均无 rtk_calibration 指令

#### TC-RD-013：进度事件 bid 关联
- **给定**：平台下发 `cover_open`，bid=abc-123
- **当**：模拟器调度 cover_open 进度事件
- **那么**：events 报文的 bid=abc-123（与原始 services 一致）
- **那么**：hivemind 通过 bid 将 events 与 services ACK 关联，置 ACK=SUCCESS
- **错误后果**：bid 不一致会导致 ACK 永远 IN_PROGRESS → 30 秒超时 FAILED
- **核实依据**：异步指令双阶段确认机制，hivemind DjiCommandAckService.updateByProgress 通过 bid 关联

#### TC-RD-014：进度事件 output 字段结构
- **给定**：模拟器发送 job 指令的进度事件
- **当**：事件 status=in_progress
- **那么**：data 包含 `output.status`="in_progress", `output.progress.percent`=50, `result`=0
- **当**：事件 status=ok
- **那么**：data 包含 `output.status`="ok", `output.progress.percent`=100, `result`=0
- **核实依据**：[Dock3 远程调试 Events](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html) output 结构含 status + progress.percent

#### TC-RD-015：不再使用通用 AsyncCommandSimulator 占位
- **给定**：模拟器收到远程调试 Job 指令（如 cover_open）
- **那么**：**不使用** AsyncCommandSimulator 的通用 output.status=ok 单次事件
- **那么**：使用 RemoteDebugSimulator 的专用进度事件序列（in_progress → ok + percent + 状态同步）
- **核实依据**：AsyncCommandSimulator 仅发终态 status=ok 无中间进度，不符合 DJI 文档的 in_progress → ok 流转

### 2.18 直播功能（live.html）

> 设计背景：DJI Cloud API「直播功能」走 services/services_reply topic，全部为同步指令（无 Events 进度事件）。
>
> 核实依据：[Dock3 直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) | [Dock2 直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/live.html) | [Dock1 直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/live.html)

#### TC-LIVE-001：live_start_push 解析与状态记录
- **给定**：平台下发 `{"method":"live_start_push","data":{"url":"rtmp://192.168.1.1:8080/live","url_type":1,"video_id":"SN/39-0-7/normal-0","video_quality":3}}`
- **当**：模拟器收到指令
- **那么**：回 services_reply result=0
- **那么**：activeStreams 中新增一条记录，包含 video_id、url、url_type、video_quality
- **核实依据**：[Dock3 live.html 开始直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) Data 含 url/url_type/video_id/video_quality

#### TC-LIVE-002：live_start_push 幂等更新
- **给定**：activeStreams 已存在 video_id="SN/39-0-7/normal-0" 的推流记录
- **当**：再次收到相同 video_id 的 live_start_push
- **那么**：不产生重复记录，更新已有记录的 url/quality
- **核实依据**：同一 video_id 重复推流应幂等，避免产生重复记录

#### TC-LIVE-003：live_stop_push 清除推流
- **给定**：activeStreams 中存在 video_id="SN/39-0-7/normal-0" 的推流
- **当**：收到 `{"method":"live_stop_push","data":{"video_id":"SN/39-0-7/normal-0"}}`
- **那么**：回 services_reply result=0
- **那么**：activeStreams 中该 video_id 的记录被移除
- **核实依据**：[Dock3 live.html 停止直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) Data 含 video_id

#### TC-LIVE-004：live_set_quality 更新清晰度
- **给定**：activeStreams 中存在 video_id="SN/39-0-7/normal-0"，quality=3
- **当**：收到 `{"method":"live_set_quality","data":{"video_id":"SN/39-0-7/normal-0","video_quality":4}}`
- **那么**：回 services_reply result=0
- **那么**：该推流记录的 quality 更新为 4
- **核实依据**：[Dock3 live.html 设置直播清晰度](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) video_quality 枚举 0=自适应/1=流畅/2=标清/3=高清/4=超清

#### TC-LIVE-005：live_camera_change 解析与状态跟踪
- **给定**：activeStreams 中存在 video_id="SN/39-0-7/normal-0"
- **当**：收到 `{"method":"live_camera_change","data":{"video_id":"SN/39-0-7/normal-0","camera_position":1}}`
- **那么**：回 services_reply result=0
- **那么**：该推流记录的 camera_position 更新为 1（舱外）
- **核实依据**：[Dock3 live.html 直播相机切换](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) Data 含 video_id + camera_position（0=舱内,1=舱外）

#### TC-LIVE-006：live_lens_change 解析与状态跟踪
- **给定**：设备有活跃推流
- **当**：收到 `{"method":"live_lens_change","data":{"video_type":"zoom"}}`
- **那么**：回 services_reply result=0
- **那么**：当前 video_type 状态更新为 "zoom"
- **核实依据**：[Dock3 live.html 设置直播镜头](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) Data 含 video_type（zoom/wide/normal/ir），无 video_id（全局设置）

#### TC-LIVE-007：Dock1 不支持 live_camera_change
- **给定**：dock-type=DOCK1
- **当**：收到 `live_camera_change` 指令
- **那么**：回 services_reply result=0（占位应答，不报错）
- **那么**：不更新任何推流记录的 camera_position
- **核实依据**：[Dock1 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/live.html) 无 live_camera_change 指令

#### TC-LIVE-008：Dock2/Dock3 支持 live_camera_change
- **给定**：dock-type=DOCK2 或 DOCK3
- **当**：收到 `live_camera_change` 指令
- **那么**：回 services_reply result=0
- **那么**：解析 video_id + camera_position 并更新推流记录
- **核实依据**：[Dock2 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/live.html) 和 [Dock3 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) 均有 live_camera_change

#### TC-LIVE-009：三 Dock 均支持 live_lens_change
- **给定**：dock-type=DOCK1 或 DOCK2 或 DOCK3
- **当**：收到 `live_lens_change` 指令
- **那么**：回 services_reply result=0
- **那么**：解析 video_type 并更新状态
- **核实依据**：三 Dock live.html 均有 live_lens_change 指令

#### TC-LIVE-010：直播无 Events 进度事件
- **给定**：模拟器收到任意直播指令（live_start_push / live_stop_push / live_set_quality / live_camera_change / live_lens_change）
- **那么**：仅回 services_reply，**不发送** events 进度事件
- **核实依据**：三 Dock live.html 均为 Service（同步指令），无 Events 上行

#### TC-LIVE-011：WHIP 推流能力检测（启动时）
- **给定**：`simulator.live.real-push-enabled=true`，`ffmpeg-path` 配置了有效路径
- **当**：模拟器启动时执行 `ffmpeg -muxers` 检测是否含 `whip`
- **那么**：若支持 whip，设置 `whipSupported=true`，日志记录"FFmpeg 支持 WHIP 推流"
- **那么**：若不支持 whip，设置 `whipSupported=false`，记录 S-4 诊断日志"本机 ffmpeg 不支持 WHIP，降级为协议模拟"
- **核实依据**：FFmpeg ≥ 8.0 需 `--enable-muxer=whip` 编译，大多数预编译版不支持（[live777 文档](https://live777.pages.dev/guide/ffmpeg)）

#### TC-LIVE-012：live_start_push 启动 WHIP 推流（url_type=4）
- **给定**：`whipSupported=true`，`video-dir` 配置有效，video_id="SN/165-0-7/normal-0"
- **当**：收到 `{"method":"live_start_push","data":{"url":"http://192.168.1.1:8080/rtc/v1/whip/?app=live&stream=test","url_type":4,"video_id":"SN/165-0-7/normal-0","video_quality":3}}`
- **那么**：回 services_reply result=0
- **那么**：启动 ffmpeg 进程，命令格式 `ffmpeg -re -stream_loop -1 -i {videoFile} -c:v libx264 -profile:v baseline -preset ultrafast -tune zerolatency -bf 0 -c:a libopus -ar 48000 -ac 2 -ab 32k -f whip {url}`
- **那么**：video_id 与 Process 绑定，记录到活跃推流进程表
- **核实依据**：[Dock3 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) url_type=4=WebRTC(WHIP)；[ossrs/ffmpeg-webrtc](https://github.com/ossrs/ffmpeg-webrtc/discussions/47) 推流命令

#### TC-LIVE-013：WHIP 不支持时降级协议模拟
- **给定**：`whipSupported=false`（本机 ffmpeg 不支持 WHIP）
- **当**：收到 `live_start_push` 且 `url_type=4`
- **那么**：仍回 services_reply result=0（协议层正常）
- **那么**：不启动 ffmpeg 进程（仅协议模拟）
- **那么**：记录 S-4 诊断日志"本机 ffmpeg 不支持 WHIP，未真实推流"
- **核实依据**：降级策略确保协议层与真机一致，但媒体流层缺失需明确记录，避免平台误判 WHIP 链路已测

#### TC-LIVE-014：live_stop_push 停止 ffmpeg 进程
- **给定**：video_id="SN/165-0-7/normal-0" 的 WHIP 推流进程正在运行
- **当**：收到 `{"method":"live_stop_push","data":{"video_id":"SN/165-0-7/normal-0"}}`
- **那么**：回 services_reply result=0
- **那么**：对应 ffmpeg 进程被 destroy，从活跃推流进程表移除
- **核实依据**：[Dock3 live.html 停止直播](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html)

#### TC-LIVE-015：应用关闭时清理所有 ffmpeg 进程
- **给定**：有 2 路活跃 WHIP 推流进程
- **当**：模拟器应用关闭（@PreDestroy）
- **那么**：所有 ffmpeg 进程被 destroy，无进程泄漏
- **核实依据**：防止进程泄漏，确保应用关闭后无残留 ffmpeg 进程占用资源

### 2.19 媒体管理（media.html / file.html）

> 设计背景：DJI Cloud API「媒体管理」走 events + services + requests 三种 topic，三 Dock 协议完全一致（无差异）。
> - Event 上行：`file_upload_callback`（文件上传结果，need_reply=1）、`highest_priority_upload_flighttask_media`（优先级上报，need_reply=1）
> - Service 下行：`upload_flighttask_media_prioritize`（调整最高优先级）→ services_reply result=0
> - Requests 上行：`storage_config_get`（获取临时凭证）→ requests_reply 返回凭证
>
> 核实依据：[Dock3 media.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) | [Dock2 file.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/file.html) | [Dock1 file.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/file.html)

#### TC-MEDIA-001：file_upload_callback 事件结构
- **给定**：航线任务完成，触发媒体上传
- **当**：模拟器上报 `file_upload_callback` 事件
- **那么**：事件含 `method=file_upload_callback`、`need_reply=1`、`bid`、`tid`、`gateway`、`timestamp`
- **那么**：`data.file` 含 `object_key`/`path`/`name`/`ext`/`metadata`
- **那么**：`data.file.ext` 含 `flight_id`/`drone_model_key`/`payload_model_key`/`is_original`
- **那么**：`data.file.metadata` 含 `gimbal_yaw_degree`/`absolute_altitude`/`relative_altitude`/`create_time`/`shoot_position`
- **那么**：`data.flight_task` 含 `uploaded_file_count`/`expected_file_count`（hivemind 据此统计上传计数）
- **核实依据**：[Dock3 media.html 媒体文件上传结果上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) Data 结构

#### TC-MEDIA-002：storage_config_get 请求获取凭证
- **给定**：媒体上传流程开始
- **当**：模拟器发送 `storage_config_get` 请求（data.module=0）
- **那么**：收到 requests_reply，`data.output` 含 `bucket`/`credentials`/`endpoint`/`provider`/`region`/`object_key_prefix`
- **那么**：`credentials` 含 `access_key_id`/`access_key_secret`/`expire`/`security_token`
- **核实依据**：[Dock3 media.html 获取上传临时凭证](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) requests_reply 结构

#### TC-MEDIA-003：object_key 用 object_key_prefix 构造
- **给定**：storage_config_get 回复 `object_key_prefix="abc-123"`
- **当**：模拟器构造 file_upload_callback 的 `object_key`
- **那么**：`object_key` 以 `object_key_prefix` 为前缀（如 `abc-123/{flight_id}/{file_name}`）
- **错误后果**：object_key 不带前缀会导致云端无法定位文件
- **核实依据**：[Dock3 media.html] object_key_prefix 字段说明"对象存储桶的 Key 的前缀"

#### TC-MEDIA-004：highest_priority_upload_flighttask_media 事件上报
- **给定**：媒体上传流程开始
- **当**：模拟器上报优先级
- **那么**：事件含 `method=highest_priority_upload_flighttask_media`、`need_reply=1`、`data.flight_id`
- **核实依据**：[Dock3 media.html 媒体文件上传优先级上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) Event 结构

#### TC-MEDIA-005：upload_flighttask_media_prioritize 回 result=0
- **给定**：云端下发 `{"method":"upload_flighttask_media_prioritize","data":{"flight_id":"xxx"}}`
- **当**：模拟器收到指令
- **那么**：回 services_reply `result=0`
- **那么**：记录当前最高优先级 flight_id（后续媒体上传以此 flight_id 为优先）
- **核实依据**：[Dock3 media.html 调整上传的文件为最高优先级](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) Service 结构

#### TC-MEDIA-006：file_upload_callback 上报后等待 events_reply
- **给定**：模拟器上报 `file_upload_callback`（need_reply=1）
- **当**：云端回复 events_reply（tid 匹配，result=0）
- **那么**：模拟器收到 events_reply 后，该文件才算上传完成，继续下一个文件
- **核实依据**：DJI need_reply=1 机制要求云端回复 events_reply，设备据此确认收到
- **说明**：模拟器用 tid 匹配 events_reply（与 DockOnlineService.sendRequest 的 tid+CompletableFuture 模式一致）

#### TC-MEDIA-007：events_reply 超时不阻塞后续上传
- **给定**：模拟器上报 `file_upload_callback`（need_reply=1）
- **当**：等待 events_reply 超时（云端未回复）
- **那么**：记录 warn 日志，继续上传下一个文件（不阻塞整个媒体上传流程）
- **错误后果**：超时阻塞会导致媒体上传流程卡死
- **说明**：对齐"模拟器不因云端未回复而卡死"的健壮性要求

#### TC-MEDIA-008：媒体上传完整流程
- **给定**：航线任务完成（flight_id=FLIGHT-001）或手动触发
- **当**：执行媒体上传流程
- **那么**：时序为 `storage_config_get` → `highest_priority_upload_flighttask_media` → 逐个 `file_upload_callback`（每个等待 events_reply）
- **那么**：每个 file_upload_callback 的 `flight_task.uploaded_file_count` 递增，`expected_file_count` 为总数
- **核实依据**：[DJI 媒体管理交互时序图](https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/pilot-feature-set/pilot-media-management.html#interaction-sequence-diagram)

#### TC-MEDIA-009：三 Dock 均支持（无差异）
- **给定**：dock-type=DOCK1 或 DOCK2 或 DOCK3
- **当**：执行媒体上传流程
- **那么**：协议完全一致，无 Dock 差异（与直播的 live_camera_change 不同）
- **核实依据**：三 Dock 的 media.html/file.html 协议字段完全相同

#### TC-MEDIA-010：storage_config_get 解析完整 STS 凭证
- **给定**：storage_config_get 回复包含 `output.bucket`/`output.credentials`/`output.endpoint`/`output.provider`/`output.region`/`output.object_key_prefix`
- **当**：模拟器解析回复
- **那么**：提取 `access_key_id`/`access_key_secret`/`security_token`/`endpoint`/`bucket`/`region`/`object_key_prefix`/`provider`
- **核实依据**：[Dock3 media.html 获取上传临时凭证](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html) requests_reply output 结构

#### TC-MEDIA-011：真实文件上传到对象存储
- **给定**：`media-dir` 配置了有效目录，目录中有图片/视频文件
- **当**：模拟器执行媒体上传
- **那么**：使用 STS 凭证通过 S3 兼容协议上传文件到对象存储（bucket + object_key）
- **那么**：上传成功后发 `file_upload_callback`（object_key 指向已上传的真实文件）
- **那么**：平台可通过 object_key 从对象存储下载到真实文件
- **核实依据**：[DJI 媒体管理交互时序图](https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/pilot-feature-set/pilot-media-management.html#interaction-sequence-diagram) 机场使用临时凭证上传文件到对象存储

#### TC-MEDIA-012：media-dir 未配置时降级为仅元数据上报
- **给定**：`media-dir` 未配置或目录为空
- **当**：模拟器执行媒体上传
- **那么**：跳过文件上传，仅发 `file_upload_callback`（元数据上报，object_key 为虚构值）
- **那么**：日志记录"未配置 media-dir，跳过文件上传"
- **错误后果**：平台无法下载/预览文件，但协议层完整

#### TC-MEDIA-013：STS 凭证获取失败时降级
- **给定**：`storage_config_get` 超时或返回 `result!=0`
- **当**：模拟器执行媒体上传
- **那么**：跳过文件上传，仅发 `file_upload_callback`（使用虚构 object_key）
- **核实依据**：降级策略确保协议层不中断

#### TC-MEDIA-014：支持多云厂商（ali/aws/minio/obs）
- **给定**：`storage_config_get` 回复 `provider=ali` 或 `aws` 或 `minio` 或 `obs`
- **当**：模拟器上传文件
- **那么**：使用 S3 兼容协议，按 `endpoint` 创建 S3 客户端
- **那么**：`minio` 启用 path-style 访问；`ali`/`aws`/`obs` 使用默认 virtual-hosted style
- **核实依据**：[Dock3 media.html] provider 字段枚举值 `{"ali":"阿里云","aws":"亚马逊云","minio":"minio"}`；OBS 为 S3 兼容服务扩展支持

#### TC-MEDIA-015：从 endpoint 提取签名 region
- **给定**：`storage_config_get` 回复 `endpoint=https://oss-cn-hangzhou.aliyuncs.com`、`region=hz`
- **当**：模拟器创建 S3 客户端
- **那么**：从 endpoint 提取完整区域名 `cn-hangzhou` 作为 SigV4 签名 region（而非短格式 `hz`）
- **那么**：华为云 OBS endpoint `https://obs.cn-north-1.myhuaweicloud.com` 提取 `cn-north-1`
- **那么**：AWS S3 endpoint `https://s3.us-east-1.amazonaws.com` 提取 `us-east-1`
- **那么**：endpoint 无法识别时回退到 region 字段；均为空时默认 `us-east-1`
- **错误后果**：使用短格式 region（如 `hz`）可能导致 S3 签名校验失败，OSS 返回 403

### 2.20 航线管理（wayline.html）

> 设计背景：DJI Cloud API「航线管理」走 services（下行）+ events（上行），三 Dock 协议**存在差异**（与媒体管理不同）。
> - Service 下行：`flighttask_prepare`/`flighttask_execute`/`flighttask_pause`/`flighttask_recovery`/`flighttask_undo`/`flighttask_stop`/`return_home`/`return_home_cancel`/`return_specific_home`/`flight_setup_abort`
> - Event 上行：`flighttask_progress`/`return_home_info`/`flighttask_ready`/`device_exit_homing_notify`/`in_flight_wayline_progress`/`flight_setup_exception_notify`
>
> 核实依据：[Dock3 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) | [Dock2 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/wayline.html) | [Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)

#### TC-WAYLINE-001：flighttask_prepare 回复 + 机场状态更新
- **给定**：云端下发 `flighttask_prepare`（含 `flight_id`、`file.url`）
- **当**：模拟器收到指令
- **那么**：回 services_reply `result=0`
- **那么**：机场状态更新（dockModeCode 进入工作状态、coverOpen=true、droneChargeState 停止充电）
- **那么**：记录 `flight_id` 供后续进度上报使用
- **核实依据**：[Dock3 wayline.html 下发任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Service 结构

#### TC-WAYLINE-002：flighttask_execute 回复 + 异步进度 + track_id 生成
- **给定**：`flighttask_prepare` 已完成，云端下发 `flighttask_execute`（含 `flight_id`）
- **当**：模拟器收到指令
- **那么**：回 services_reply `result=0`（仅表示"已接收"）
- **那么**：生成唯一 `track_id`（UUID），后续 `flighttask_progress.ext.track_id` 与此一致
- **那么**：启动异步线程推进 `flighttask_progress`（不阻塞 services_reply）
- **核实依据**：[Dock3 wayline.html 执行任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Service 结构

#### TC-WAYLINE-003：flighttask_pause/recovery 回复 + 进度暂停/恢复
- **给定**：航线任务执行中（`flighttask_progress` status=in_progress）
- **当**：云端下发 `flighttask_pause`
- **那么**：回 services_reply `result=0`，暂停进度推进（不再上报 `flighttask_progress`）
- **当**：云端下发 `flighttask_recovery`
- **那么**：回 services_reply `result=0`，恢复进度推进
- **核实依据**：[Dock3 wayline.html 航线暂停/恢复](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Service 结构

#### TC-WAYLINE-004：flighttask_undo/flighttask_stop 回复 + canceled 状态上报（地面态）
- **给定**：航线任务执行中，飞行器在地面态（`mode_code ∈ {0, 1, 2}`，尚未起飞或起飞准备阶段）
- **当**：云端下发 `flighttask_stop`（Dock2/3）或 `flighttask_undo`
- **那么**：回 services_reply `result=0`
- **那么**：停止进度推进，上报 `flighttask_progress`（status=canceled, result=0）
- **那么**：完整恢复 dock 状态：
    - 无人机位置重置为机场位置（`droneLatitude=runtimeConfig.locationLatitude`、`droneLongitude=runtimeConfig.locationLongitude`、`droneHeight=0.0`）
    - `droneModeCode=0`（待机）、`droneInDock=true`、`droneChargeState=1`（充电中）
    - `coverOpen=false`、`putterExpanded=false`、`dockModeCode=0`（待机）
- **核实依据**：[Dock3 wayline.html 任务终止](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Service 结构；mode_code 取值见 [M30 设备属性 mode_code 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)

#### TC-WAYLINE-005：return_home/return_home_cancel/return_specific_home 回复
- **给定**：云端下发 `return_home`
- **那么**：回 services_reply `result=0`，更新无人机状态（进入返航模式）
- **给定**：云端下发 `return_home_cancel`
- **那么**：回 services_reply `result=0`，取消返航模式
- **给定**：云端下发 `return_specific_home`（含 `latitude`/`longitude`/`height`）
- **那么**：回 services_reply `result=0`，解析 data 中的目标坐标
- **核实依据**：[Dock3 wayline.html 一键返航/取消返航/指定home点的返航](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Service 结构

#### TC-WAYLINE-006：flighttask_progress 事件结构
- **给定**：航线任务执行中
- **当**：模拟器上报 `flighttask_progress`
- **那么**：事件含 `method=flighttask_progress`、`bid`、`tid`、`gateway`、`timestamp`
- **那么**：`data.ext` 含 `current_waypoint_index`/`wayline_mission_state`/`track_id`/`flight_id`/`wayline_id`/`media_count`
- **那么**：`data.progress` 含 `current_step`/`percent`
- **那么**：`data.status` 为枚举字符串，`data.result` 为 int（0 表示成功）
- **核实依据**：[Dock3 wayline.html 上报航线任务进度](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event Data 结构

#### TC-WAYLINE-007：flighttask_progress status 枚举
- **给定**：上报 `flighttask_progress`
- **那么**：`status` 枚举值为 `canceled`/`failed`/`in_progress`/`ok`/`partially_done`/`paused`/`rejected`/`sent`/`timeout` 之一
- **那么**：执行中 `status=in_progress`，完成 `status=ok`，终止 `status=canceled`
- **错误后果**：使用非枚举值会导致平台无法解析任务状态
- **核实依据**：[Dock3 wayline.html status 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event Data status 字段

#### TC-WAYLINE-008：flighttask_progress ext.wayline_mission_state 枚举
- **给定**：上报 `flighttask_progress`
- **那么**：`ext.wayline_mission_state` 枚举值：`6`=航线执行、`7`=航线中断（暂停）、`9`=航线停止
- **那么**：`status=in_progress` 时 `wayline_mission_state=6`，`status=ok/canceled` 时 `wayline_mission_state=9`
- **核实依据**：[Dock3 wayline.html wayline_mission_state 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event Data ext.wayline_mission_state 字段

#### TC-WAYLINE-009：return_home_info 事件结构
- **给定**：航线任务执行完成（status=ok）
- **当**：模拟器上报 `return_home_info`
- **那么**：事件含 `method=return_home_info`、`bid`、`tid`、`timestamp`
- **那么**：`data` 含 `planned_path_points`（数组，含 latitude/longitude/height）、`last_point_type`、`flight_id`
- **那么**：`planned_path_points` 每次推送为全量更新（非增量）
- **核实依据**：[Dock3 wayline.html 返航信息](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event Data 结构

#### TC-WAYLINE-010：航线任务完整时序
- **给定**：云端依次下发 `flighttask_prepare` → `flighttask_execute`
- **当**：模拟器执行任务
- **那么**：时序为 `flighttask_prepare`（reply）→ `flighttask_execute`（reply）→ `flighttask_progress`×N（in_progress）→ `flighttask_progress`（ok）→ `return_home_info` → 媒体上传流程
- **那么**：每个 `flighttask_progress` 的 `bid` 与 `flighttask_execute` 的 `bid` 一致（hivemind 据此关联任务）
- **核实依据**：[Dock3 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) 完整交互流程

#### TC-WAYLINE-011：任务完成后触发媒体上传
- **给定**：`flighttask_progress` 上报 `status=ok`
- **当**：航线任务完成
- **那么**：自动触发媒体上传流程（`storage_config_get` → `highest_priority_upload_flighttask_media` → `file_upload_callback`）
- **那么**：媒体上传的 `flight_id` 与航线任务的 `flight_id` 一致
- **核实依据**：[设计文档 §5.3 航线任务流程](superpowers/specs/2026-08-08-dji-dock-simulator-design.md) 步骤 4-5

#### TC-WAYLINE-012：current_step 步骤编号 Dock1 vs Dock2/3 不同 ⚠️
- **给定**：dock-type=DOCK1
- **当**：上报 `flighttask_progress`
- **那么**：`current_step` 使用 Dock1 编号：`7`（开机检查）→ `22`（触发执行航线）→ `23`（航线执行中）→ `25`（降落）→ `26`（关盖）→ `33`（通知任务结果）
- **给定**：dock-type=DOCK2 或 DOCK3
- **那么**：`current_step` 使用 Dock2/3 编号：`7`（开机检查）→ `24`（触发执行航线）→ `25`（航线执行中）→ `27`（降落）→ `28`（关盖）→ `35`（通知任务结果）
- **差异原因**：Dock2/3 在 step 8（图传远程对频）和 step 22（起飞机场检查降落机场准备状态）各插入一个新步骤，导致后续编号偏移 +2
- **错误后果**：Dock1 用 Dock2/3 的 step 编号会导致平台误判任务阶段
- **核实依据**：[Dock1 wayline.html current_step 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) vs [Dock3 wayline.html current_step 枚举](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)

#### TC-WAYLINE-013：flighttask_stop 仅 Dock2/3 支持
- **给定**：dock-type=DOCK1
- **当**：云端下发 `flighttask_stop`
- **那么**：Dock1 不支持此指令（Dock1 用 `flighttask_undo` + `flight_setup_abort` 替代）
- **给定**：dock-type=DOCK2 或 DOCK3
- **那么**：支持 `flighttask_stop`（终止执行中的任务）
- **核实依据**：[Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) 无 `flighttask_stop`；[Dock3 wayline.html 任务终止](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) 有

#### TC-WAYLINE-014：return_specific_home 仅 Dock2/3 支持
- **给定**：dock-type=DOCK1
- **当**：云端下发 `return_specific_home`
- **那么**：Dock1 不支持此指令
- **给定**：dock-type=DOCK2 或 DOCK3
- **那么**：支持 `return_specific_home`（指定 home 点返航，蛙跳场景）
- **核实依据**：[Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) 无 `return_specific_home`；[Dock3 wayline.html 指定home点的返航](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) 有

#### TC-WAYLINE-015：flight_setup_abort 仅 Dock1 支持
- **给定**：dock-type=DOCK1
- **当**：云端下发 `flight_setup_abort`（取消准备中的任务，在 Home 点设置阶段 current_step=21）
- **那么**：回 services_reply `result=0`，取消准备中的任务
- **给定**：dock-type=DOCK2 或 DOCK3
- **那么**：不支持 `flight_setup_abort`
- **与 flighttask_undo 的区别**：`flight_setup_abort` 在起飞命令下发后 RTK 未收敛时调用；`flighttask_undo` 仅取消未开始执行的任务
- **核实依据**：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) Service 结构

#### TC-WAYLINE-016：return_home_info 蛙跳字段仅 Dock2/3
- **给定**：dock-type=DOCK2 或 DOCK3
- **当**：上报 `return_home_info`
- **那么**：`data` 可含 `home_dock_sn`（蛙跳 home 点机场 SN）和 `multi_dock_home_info`（蛙跳机场返航信息数组）
- **那么**：`multi_dock_home_info` 元素含 `sn`/`plan_status`/`estimated_battery_consumption`/`home_distance`
- **给定**：dock-type=DOCK1
- **那么**：`data` 不含 `home_dock_sn` 和 `multi_dock_home_info`（Dock1 不支持蛙跳）
- **核实依据**：[Dock3 wayline.html return_home_info](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) 有 multi_dock_home_info 字段；[Dock1 wayline.html return_home_info](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) 无

#### TC-WAYLINE-017：flighttask_ready 事件（任务就绪通知，未实现）
- **给定**：机场准备就绪，可接受任务
- **当**：模拟器上报 `flighttask_ready`
- **那么**：事件含 `method=flighttask_ready`、`data.flight_ids`（数组，满足就绪条件的任务 ID 集合）
- **说明**：当前未实现，作为后续实现依据
- **核实依据**：[Dock3 wayline.html 任务就绪通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event 结构

#### TC-WAYLINE-018：device_exit_homing_notify 事件（返航退出状态通知，未实现）
- **给定**：机场处于返航模式，因异常退出返航
- **当**：模拟器上报 `device_exit_homing_notify`
- **那么**：事件含 `method=device_exit_homing_notify`、`need_reply=1`、`data.sn`/`data.action`（0=退出/1=进入）/`data.reason`（退出原因枚举）
- **那么**：等待云端 events_reply（tid 匹配，result=0）后才算完成
- **说明**：当前未实现，作为后续实现依据
- **核实依据**：[Dock3 wayline.html 设备返航退出状态通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event 结构

#### TC-WAYLINE-019：in_flight_wayline_progress 事件（空中航线状态，Dock2/3，未实现）
- **给定**：dock-type=DOCK2 或 DOCK3，空中航线任务执行中
- **当**：模拟器上报 `in_flight_wayline_progress`
- **那么**：事件含 `method=in_flight_wayline_progress`、`data.in_flight_wayline_id`/`data.progress.percent`/`data.status`/`data.result`/`data.way_point_index`
- **那么**：`status` 枚举：1=上传中/2=上传成功/3=执行中/4=暂停/5=取消/6=成功/7=失败/8=超时
- **说明**：当前未实现，作为后续实现依据
- **核实依据**：[Dock3 wayline.html 空中下发航线状态上报](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) Event 结构

#### TC-WAYLINE-020：flight_setup_exception_notify 事件（准备异常通知，Dock1，未实现）
- **给定**：dock-type=DOCK1，任务准备阶段异常
- **当**：模拟器上报 `flight_setup_exception_notify`
- **那么**：事件含 `method=flight_setup_exception_notify`、`need_reply=1`、`data.sn`/`data.timeout_time`（2-10 分钟）/`data.timestamp`/`data.flight_type`（1=航线/2=指令飞行）
- **那么**：等待云端 events_reply（tid 匹配，result=0）后才算完成
- **说明**：当前未实现，作为后续实现依据
- **核实依据**：[Dock1 wayline.html 机场任务准备异常通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) Event 结构

#### TC-WAYLINE-021：flighttask_undo vs flighttask_stop 语义区分
- **给定**：`flighttask_prepare` 已完成，`flighttask_execute` 未下发
- **当**：云端下发 `flighttask_undo`
- **那么**：取消未开始执行的任务，回 result=0
- **给定**：`flighttask_execute` 已下发，任务执行中
- **当**：云端下发 `flighttask_stop`（仅 Dock2/3）
- **那么**：终止执行中的任务，上报 `flighttask_progress`（status=canceled）
- **与 flight_setup_abort 的区别**：`flight_setup_abort` 在 Home 点设置阶段（RTK 未收敛）调用；`flighttask_undo` 在 `flighttask_execute` 前调用；`flighttask_stop` 在 `flighttask_execute` 后调用
- **补充**：三个方法均受 `mode_code` 约束——飞行器起飞后（`mode_code ∈ {3-12}`）调用任一取消方法均返回 326109 拒绝（见 TC-WAYLINE-022）
- **核实依据**：[Dock1 wayline.html 取消准备中的任务说明](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) flight_setup_abort 描述

#### TC-WAYLINE-022：飞行中取消任务返回 326109 拒绝 ⚠️
- **给定**：飞行器在飞行中（`mode_code ∈ {3, 4, 5, 6, 7, 8, 9, 10, 11, 12}`，已起飞/航线执行/返航/降落中）
- **当**：云端下发 `flighttask_stop` / `flighttask_undo` / `flight_setup_abort`
- **那么**：回 services_reply `result=326109`（因飞行器已经起飞，不支持取消，可通过返航按钮取消）
- **那么**：不重置无人机位置、不修改 `droneModeCode`、不上报 `flighttask_progress`（status=canceled）
- **那么**：任务进度推进保持原状（若在执行中则继续执行）
- **错误后果**：飞行中允许取消会让无人机"瞬移"回机场，违背 DJI 协议且不符合物理规律，误导平台开发者认为取消后位置立即归位
- **核实依据**：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) 返回码 326109 原文：「因飞行器已经起飞，不支持取消，可通过返航按钮取消」

#### TC-WAYLINE-023：异常态取消任务返回 326108 拒绝
- **给定**：飞行器在异常态（`mode_code=13` 升级中 / `mode_code=14` 未连接）
- **当**：云端下发 `flighttask_stop` / `flighttask_undo` / `flight_setup_abort`
- **那么**：回 services_reply `result=326108`（当前状态不支持）
- **那么**：不重置无人机位置、不修改 `droneModeCode`、不上报 `flighttask_progress`（status=canceled）
- **核实依据**：[Dock1 wayline.html 取消准备中的任务](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) 返回码 326108 原文：「当前状态不支持」

### 2.21 机场位置与无人机位置

> 设计背景：模拟器需对接第三方平台的航线任务，机场位置（起飞点/返航点）应可调整。
> 支持两种位置输入模式：地图模式（高德地图选点 + 自动获取海拔）和手动模式（直接输入经纬度+高度）。
> 机场位置作为 return_home_info 事件的返航点和无人机起飞的初始位置。

#### TC-LOC-001：机场位置配置链路
- **给定**：`application.yml` 中 `simulator.location` 配置默认值（latitude/longitude/height）
- **当**：应用启动
- **那么**：`RuntimeConfig` 从 yml 加载默认值，`LiveConfigStore` 持久化文件存在时覆盖默认值
- **配置链路**：yml → SimulatorProperties → RuntimeConfig ← LiveConfigStore（持久化覆盖）

#### TC-LOC-002：机场位置 REST API
- **给定**：模拟器运行中
- **当**：前端调用 `GET /api/location`
- **那么**：返回 `{latitude, longitude, height}` 当前机场位置
- **当**：前端调用 `PUT /api/location` 传入 `{latitude, longitude, height}`
- **那么**：更新 `RuntimeConfig` 并持久化到 `~/.hivemind-simulator/live-config.json`，返回 `{success:true, latitude, longitude, height}`

#### TC-LOC-003：机场位置参数校验
- **给定**：前端调用 `PUT /api/location`
- **当**：纬度/经度/高度任一非数字
- **那么**：返回 `success=false, message="纬度/经度/高度必须为数字"`，HTTP 仍为 200
- **当**：纬度不在 -90~90 范围
- **那么**：返回 `success=false, message="纬度范围应为 -90 ~ 90"`
- **当**：经度不在 -180~180 范围
- **那么**：返回 `success=false, message="经度范围应为 -180 ~ 180"`
- **遵循**：业务逻辑返回明确拒绝原因而非抛异常（AGENTS.md §2）

#### TC-LOC-004：return_home_info 使用机场位置
- **给定**：任务完成触发 `return_home_info` 事件
- **当**：`publishReturnHomeInfo()` 构造 `planned_path_points`
- **那么**：`latitude/longitude/height` 取自 `runtimeConfig.getLocation*()`（运行时可修改）
- **而非**：取自 `props.location()`（yml 静态配置）

#### TC-LOC-005：无人机位置随飞行步骤更新
- **给定**：`flighttask_execute` 触发任务执行
- **当**：进度推进到 `current_step=24`（起飞）
- **那么**：`droneLatitude=机场纬度, droneLongitude=机场经度, droneHeight=0`
- **当**：进度推进到 `current_step=25`（航线执行中）
- **那么**：`droneLatitude=机场纬度+0.001, droneLongitude=机场经度+0.001, droneHeight=50`（相对机场偏移约 100 米）
- **当**：进度推进到 `current_step=27`（降落机场）
- **那么**：`droneLatitude=机场纬度, droneLongitude=机场经度, droneHeight=20`
- **当**：进度推进到 `current_step=28`（关盖）
- **那么**：`droneLatitude=机场纬度, droneLongitude=机场经度, droneHeight=0`
- **height 字段语义**：相对起飞点高度（与用户期望一致）

#### TC-LOC-006：任务完成后无人机位置重置
- **给定**：任务执行完成（`completeTask()` 调用）
- **当**：无人机归舱
- **那么**：`droneLatitude=机场纬度, droneLongitude=机场经度, droneHeight=0`（避免前端显示残留的飞行中偏移位置）

#### TC-LOC-007：无人机位置 REST API
- **给定**：模拟器运行中
- **当**：前端调用 `GET /api/drone/position`
- **那么**：返回 `{latitude, longitude, height, mode_code, in_dock, activated}`
- **前端展示规则**：`activated=false` 时纬度/经度/高度显示 `-`（遵循 AGENTS.md §2 不适用值用 `-`）

#### TC-LOC-008：无人机位置状态标签映射
- **给定**：前端展示无人机状态
- **当**：`activated=false` 且 `in_dock=true`（在舱休眠）
- **那么**：状态标签显示"休眠"
- **当**：`activated=false` 且 `in_dock=false`（不在舱，状态未知）
- **那么**：状态标签显示"未知"
- **当**：`activated=true` 且 `mode_code=0`
- **那么**：状态标签显示"待机"
- **mode_code 映射**：0=待机, 1=起飞准备, 2=起飞准备完毕, 4=自动起飞, 5=航线飞行, 9=自动返航, 10=自动降落, 13=返航降落

#### TC-LOC-009：机场位置持久化向后兼容
- **给定**：旧版本配置文件 `live-config.json` 不包含 location 字段
- **当**：应用启动加载配置
- **那么**：Jackson 自动填充 0.0，`RuntimeConfig` 检测到三字段同时为 0.0 时视为未配置，回退到 yml 默认值
- **不阻断**：启动流程，仅告警日志

#### TC-LOC-010：地图模式 - 地址搜索仅定位地图视图
- **给定**：地图模式已激活（高德 Key 有效）
- **当**：用户在地址搜索框输入关键字并从下拉建议中选择一个地址
- **那么**：地图中心移动到选中地址的坐标（`setZoomAndCenter`）
- **而非**：直接修改机场坐标（`locationEdit` 不变）、不移动 Marker、不获取海拔、不触发保存
- **设计理由**：机场实际部署位置往往不在目标地址中心，用户需通过选点或拖拽 Marker 精确设置

#### TC-LOC-011：地图模式 - 选点/拖拽 Marker 自动保存
- **给定**：地图模式已激活
- **当**：用户点击「选点」按钮后在地图上点击某位置
- **那么**：更新 `locationEdit` 坐标 → 移动 Marker → 获取海拔（Open-Meteo API）→ 自动调用 `PUT /api/location` 保存
- **当**：用户拖拽 Marker 到新位置并释放
- **那么**：同上自动保存流程
- **不需要**：点击「保存」按钮（保存按钮在地图模式下隐藏）

#### TC-LOC-012：地图模式 - 保存按钮仅手动模式显示
- **给定**：位置模拟面板
- **当**：`mapMode === 'manual'`
- **那么**：显示「保存」按钮，用户编辑经纬度/高度后需手动点击保存
- **当**：`mapMode === 'map'`
- **那么**：隐藏「保存」按钮（选点/拖拽已自动保存）

#### TC-LOC-013：高德地图配置弹窗
- **给定**：位置模拟面板 Title 栏
- **当**：用户点击「配置」按钮
- **那么**：弹出配置弹窗，包含高德 JS Key 申请步骤说明 + Key/安全密钥输入框
- **当**：用户输入 Key 并点击「保存」
- **那么**：Key 持久化到 `localStorage`，加载高德 JS API 初始化地图，关闭弹窗
- **当**：用户点击「清除」（仅已配置时显示）
- **那么**：清除 `localStorage` 中的 Key，回退到手动模式，重置所有地图实例

#### TC-LOC-014：地图模式 - 自动获取海拔高度
- **给定**：地图模式下用户选点或拖拽 Marker 设置新位置
- **当**：`setAirportLocation` 被调用
- **那么**：调用 Open-Meteo Elevation API（`https://api.open-meteo.com/v1/elevation`）获取该坐标的海拔
- **那么**：将海拔值写入 `locationEdit.height`（四舍五入到 0.1m）
- **当**：API 返回无数据或请求失败
- **那么**：提示用户手动输入高度，不阻断保存流程

#### TC-LOC-015：无人机不在舱且未激活时 OSD 不上报
- **给定**：`droneInDock=false` 且 `droneActivated=false`
- **当**：`DeviceSimulator.publishOsd()` 执行
- **那么**：Dock OSD 正常上报
- **那么**：Drone OSD **不上报**（`isDroneActivated()` 为 false，跳过推送）
- **前端展示**：纬度/经度/高度显示 `-`，状态显示"未知"

### 2.22 覆盖率报告

> 设计背景：覆盖率报告用于发现"第三方平台对 DJI Cloud API 接口的访问覆盖情况"，重点是列出**未被覆盖的清单**。
> 覆盖率测试往往跨多次连接（切换平台/重连），需保留累积数据。
>
> - **基准**：`src/main/resources/dji-method-catalog.json` 维护 DJI 规范下行指令全集（按 services/drc_down/property_set 三通道 × common/dock1/dock2/dock3 四组维护）
> - **采集**：`CoverageRecorder` 按 MQTT 地址（host:port）独立累积已覆盖 method 集合
> - **覆盖方向**：仅记录平台→模拟器下行（services / property_set / drc_down）；模拟器→平台的上行 method 不纳入覆盖率
> - **生命周期**：与 JVM 一致，**断开 MQTT 不清空**（与 DiagnosticLogRecorder 不同）；切换 MQTT 地址时旧数据不清空
> - **报告格式**：HTML，含摘要 + 按通道分类的覆盖清单（绿色）和未覆盖清单（红色）+ 非规范 method（黄色）
>
> 核实依据：[DJI Cloud API 物模型](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt-dock.html) services / property / drc 通道划分

#### TC-COV-001：DJI 规范指令全集作为覆盖率基准
- **给定**：项目 `src/main/resources/dji-method-catalog.json` 维护三类下行通道（services / drc_down / property_set）的 DJI 规范 method 全集
- **当**：CoverageRecorder 启动时加载该文件
- **那么**：基准集合包含 services / drc_down / property_set 三个通道的全部 method
- **那么**：每个通道按 common（三 Dock 共有）/ dock1 / dock2 / dock3 分组维护
- **核实依据**：[DJI Cloud API 物模型](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt-dock.html) services / property / drc 通道划分

#### TC-COV-002：覆盖率按 MQTT 地址独立累积
- **给定**：模拟器连接到平台 A（127.0.0.1:1883），平台 A 下发 services `cover_open`、`drone_open`
- **当**：模拟器断开后切换到平台 B（192.168.1.10:1883），平台 B 下发 services `live_start_push`
- **那么**：CoverageRecorder 中 127.0.0.1:1883 已覆盖集合 = {cover_open, drone_open}
- **那么**：CoverageRecorder 中 192.168.1.10:1883 已覆盖集合 = {live_start_push}
- **那么**：切换 MQTT 地址时旧数据不清空（覆盖率测试往往跨多次连接累积）

#### TC-COV-003：断开 MQTT 不清空覆盖率数据
- **给定**：模拟器已采集若干 method 覆盖记录
- **当**：用户点击「断开」或关机
- **那么**：DiagnosticLogRecorder 清空（与 MQTT 生命周期绑定）
- **那么**：CoverageRecorder 不清空（与 JVM 生命周期一致，支持反复连接累积）
- **那么**：重新连接后覆盖率数据继续累积
- **核实依据**：用户约定"覆盖率测试往往不是一次就完成的"

#### TC-COV-004：覆盖率方向只统计平台→模拟器下行
- **给定**：模拟器收到平台下发的 services `flighttask_execute`
- **当**：CoverageRecorder 记录此次调用
- **那么**：在当前 MQTT 地址的已覆盖集合中添加 `flighttask_execute`
- **那么**：模拟器主动上报的 events（如 flighttask_progress）、requests（如 storage_config_get）不纳入覆盖率
- **那么**：监控器侧下发的指令不纳入覆盖率（监控器是用户主动行为，不属于平台覆盖范围）
- **核实依据**：用户约定"覆盖方向只做平台→模拟器的"

#### TC-COV-005：三个下行通道分别记录 method
- **给定**：平台下发 services `cover_open`、property/set（silent_mode=1）、drc/down `drc_force_landing`
- **当**：三个 Handler（ServiceCommandHandler / PropertySetHandler / DrcCommandHandler）分别处理
- **那么**：CoverageRecorder 记录 `cover_open`、`property_set`、`drc_force_landing` 三个 method
- **那么**：property/set 通道因无 method 字段，统一记 `property_set` 作为虚拟 method
- **核实依据**：DJI Cloud API property/set 消息结构无 method 字段

#### TC-COV-006：HTML 报告展示覆盖/未覆盖/非规范 method
- **给定**：CoverageRecorder 已采集指定 MQTT 地址的覆盖数据
- **当**：用户点击「下载 HTML 报告」按钮
- **那么**：浏览器下载 `coverage-{host}_{port}.html` 文件
- **那么**：报告含摘要（基准总数 / 已覆盖 / 未覆盖 / 覆盖率）+ 按通道分类的覆盖清单（绿色）和未覆盖清单（红色）
- **那么**：若平台下发了不在 DJI 规范内的 method，报告以黄色"非规范 method"区块展示
- **核实依据**：用户约定"报告只看覆盖率，让人感觉最清晰"

#### TC-COV-007：覆盖率报告与诊断日志的生命周期差异
- **给定**：模拟器连接 MQTT 后采集了覆盖率和诊断日志数据
- **当**：用户点击「断开 MQTT」
- **那么**：诊断日志面板立即清空（DiagnosticLogRecorder.clear() 在 offline 接口中被调用）
- **那么**：覆盖率报告数据保留（CoverageRecorder 不在 offline 中被清空）
- **那么**：用户仍可在覆盖率报告弹窗中查看历史数据并下载报告
- **核实依据**：覆盖率测试跨多次连接累积，诊断日志实时刷新

#### TC-COV-008：覆盖率报告前端入口（模拟器与监控器共享）
- **给定**：模拟器和监控器前端均连到同一 MQTT broker
- **当**：用户在 index.html 或 monitor.html 头部点击「覆盖率报告」按钮
- **那么**：弹窗显示已采集过的 MQTT 地址下拉框，默认选中当前模拟器 MQTT 地址
- **那么**：用户选择地址后预览覆盖率统计（基准总数 / 已覆盖 / 未覆盖 / 覆盖率）
- **那么**：点击「下载 HTML 报告」触发浏览器下载对应地址的 HTML 报告
- **核实依据**：用户约定"在模拟器和监控器共同的日志区域增加报告下载按钮"

## 3. 使用方式

### 新增功能时
1. 在本文档对应章节添加测试用例（Given-When-Then 格式）
2. 标注核实依据（DJI 文档 URL 或项目约定）
3. 实现代码满足测试用例
4. 编译验证

### 修复 Bug 时
1. 在本文档添加回归测试用例，描述正确行为
2. 修复代码
3. 验证修复满足测试用例

### 优化阶段
1. 以本文档为依据，确保优化不破坏已有规格行为
2. 优化后逐条核对测试用例
3. 如优化改变了行为，需更新测试用例并说明原因
