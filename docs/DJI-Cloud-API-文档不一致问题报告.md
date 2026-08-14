# DJI Cloud API 文档不一致问题报告

- 报告日期：2026-08-13
- 报告来源：DJI Dock 模拟器（hivemind-simulator）开发过程中的协议核实
- 核实范围：Dock1 / Dock2 / Dock3 三代机场及其配套飞行器（M30/M3D/M4D 系列）的 Cloud API 协议
- 核实方法：对照 DJI Cloud API 官方文档（属性列表、字段定义、示例、时序图）与真机 OSD 示例交叉验证
- 报告目的：提交给 DJI Cloud API 文档团队，协助完善文档准确性

> **问题分级说明**
> - **P0（严重）**：字段定义与示例矛盾、字段缺失等会导致平台/设备解析失败的问题
> - **P1（重要）**：协议联动行为未明确，需开发者推断实现，存在多套实现方案风险
> - **P2（一般）**：Dock 版本能力差异未集中说明，需开发者跨多个文档交叉对比

---

## 一、字段定义与示例矛盾（P0）

### 1.1 device_exit_homing_notify.reason 字段类型矛盾

- **涉及协议**：[Dock1/Dock2/Dock3 wayline.html - 设备返航退出状态通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
- **问题描述**：
  - 字段定义表中 `reason` 字段 type 标注为 `enum_int`
  - 但 Example 中 `reason` 的值为字符串 `"0"`
- **期望修复**：明确 `reason` 字段实际类型（int 或 string），统一字段定义表与示例
- **当前模拟器实现**：按字段定义用 int 类型（`reason=0`），待真机验证平台是否接受 int 类型

### 1.2 Dock1 OSD air_conditioner vs air_conditioner_mode 字段名矛盾

- **涉及协议**：
  - [Dock1 properties.html - air_conditioner](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)
  - [topic-definition.html - Dock1 OSD 示例](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/topic-definition.html)
- **问题描述**：
  - Dock1 properties 属性列表中字段名为 `air_conditioner`（struct 类型，含子字段）
  - Dock1 OSD 结构示例中字段名为 `air_conditioner_mode`（标量类型）
  - Dock2/Dock3 文档统一使用 `air_conditioner`（struct）
- **期望修复**：修正 Dock1 OSD 示例，统一为 `air_conditioner`（struct）
- **当前模拟器实现**：以属性列表为准，三版均上报 struct `air_conditioner`

### 1.3 Dock1 sub_device 子字段名 product_type vs device_model_key 矛盾

- **涉及协议**：
  - [Dock1 properties.html - sub_device](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)
  - [topic-definition.html - Dock1 OSD 示例](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/topic-definition.html)
- **问题描述**：
  - Dock1 properties 属性列表中 `sub_device` 子设备型号字段名为 `product_type`
  - Dock1 OSD 示例中子设备型号字段名为 `device_model_key`
  - Dock2/Dock3 文档统一使用 `device_model_key`
- **期望修复**：修正 Dock1 属性列表，统一为 `device_model_key`
- **当前模拟器实现**：三版统一使用 `device_model_key`

---

## 二、字段在属性列表与示例之间不一致（P0）

### 2.1 putter_state 在 Dock2/Dock3 属性列表缺失

- **涉及协议**：
  - [Dock1 properties.html - putter_state](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)（明确列出）
  - [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)（属性列表无）
  - [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html)（属性列表无）
- **问题描述**：
  - Dock1 properties 属性列表明确列出 `putter_state`（pushMode=0）
  - Dock2/Dock3 properties 属性列表中均无此字段
  - 但 DJI OSD 结构示例和 Dock2 真机推送示例中均包含 `putter_state` 字段
- **期望修复**：在 Dock2/Dock3 properties 属性列表中补充 `putter_state` 字段定义
- **当前模拟器实现**：三版均上报 `putter_state=0`（关闭），Dock2/Dock3 待真机验证

### 2.2 electric_supply_voltage 在 Dock2/Dock3 属性列表缺失

- **涉及协议**：
  - [topic-definition.html - OSD 示例](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/topic-definition.html)（包含此字段）
  - [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)（属性列表无）
  - [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html)（属性列表无）
- **问题描述**：
  - OSD 封面示例（topic-definition.html）包含 `electric_supply_voltage` 字段
  - Dock2/Dock3 properties 属性列表中无此字段
  - Dock1 properties 是否包含此字段也需核实
- **期望修复**：在 Dock2/Dock3 properties 属性列表中补充 `electric_supply_voltage` 字段定义
- **当前模拟器实现**：三版均上报 `electric_supply_voltage`（从 DeviceState 读取），Dock2/Dock3 待真机验证

### 2.3 track_id 在飞行器 OSD 文档未明确

- **涉及协议**：
  - [M4D properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html)
  - [M30 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)
- **问题描述**：
  - M4D/M30 properties 文档字段列表中未明确显示 `track_id` 字段
  - 但真机 M30 OSD 推送示例中包含 `track_id` 字段（值为空字符串）
- **期望修复**：在飞行器 properties 文档中明确 `track_id` 字段定义（type、pushMode、是否必填等）
- **当前模拟器实现**：按真机 OSD 示例上报 `track_id=""`，待真机验证

### 2.4 flight_setup_exception_notify.flight_id 字段在 Data 表未列出

- **涉及协议**：[Dock1 wayline.html - 机场任务准备异常通知](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
- **问题描述**：
  - Data 字段定义表中未列出 `flight_id` 字段
  - 但 Example 中包含 `flight_id` 字段
- **期望修复**：在 Data 字段定义表中补充 `flight_id` 字段（type、是否必填、语义说明）
- **当前模拟器实现**：按 Example 包含 `flight_id`（优先取传入值，否则 fallback 当前任务 ID，再否则空串），待真机验证平台是否需要该字段关联任务

### 2.5 flight_areas_drone_location.area_id 在 Dock3 表格遗漏

- **涉及协议**：
  - [Dock1 wayline.html - 自定义飞行区](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock1/wayline.html)（表格明确列出 `area_id`）
  - [Dock2 wayline.html - 自定义飞行区](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/wayline.html)（表格明确列出 `area_id`）
  - [Dock3 wayline.html - 自定义飞行区](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)（表格遗漏 `area_id`）
- **问题描述**：
  - Dock1/Dock2 wayline.html 表格均明确列出 `area_id`（区域唯一 ID）
  - Dock3 wayline.html 表格遗漏 `area_id`
  - 但 Dock3 wayline.html Example 中包含 `area_id`
- **期望修复**：在 Dock3 wayline.html 表格中补充 `area_id` 字段定义，与 Dock1/Dock2 保持一致
- **当前模拟器实现**：按 Example 包含 `area_id`，已通过 Dock1/Dock2/Dock3 三版本交叉验证

---

## 三、协议联动行为文档未明确（P1）

### 3.1 flight_areas_update → flight_areas_get 联动

- **涉及协议**：[Dock2/Dock3 wayline.html - 自定义飞行区](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)
- **问题描述**：DJI 文档未明确 `flight_areas_update`（平台通知更新）与 `flight_areas_get`（设备主动拉取）的联动关系
- **期望补充**：明确设备收到 `flight_areas_update` 后是否应主动发起 `flight_areas_get` 请求
- **当前模拟器实现**：推断为"平台通知更新→设备主动拉取"，自动联动 `flight_areas_get`，待真机验证

### 3.2 自定义飞行区文件名校验失败后的自动上报行为

- **涉及协议**：[Dock2 wayline.html - 自定义飞行区](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/wayline.html)
- **问题描述**：
  - Dock2 文档明确要求文件名格式 `geofence_{fileMD5}.json`（fileMD5 为 32 位十六进制 MD5 值）
  - Dock2 文档额外说明真机会验证 fileMD5，若平台未按此传输可能执行失败
  - 但 DJI 文档未明确文件名不合规时设备的自动上报行为（是否上报 sync_progress、reason 值等）
- **期望补充**：明确文件名校验失败时设备应上报的 `sync_progress` 字段值（status=fail, reason=?）
- **当前模拟器实现**：自动上报 `sync_progress(status=fail, reason=1 "解析云端返回的文件信息失败")`，待真机验证

### 3.3 return_home 后续行为未明确

- **涉及协议**：[Dock1/Dock2/Dock3 wayline.html - 一键返航](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)
- **问题描述**：DJI 文档未明确 `return_home` 命令的后续事件/进度机制：
  - 是否发送 `return_home_info` 事件？
  - 是否上报进度事件？
  - 返航完成后的状态转换流程？
- **期望补充**：明确 `return_home` 后续事件序列和状态转换流程
- **当前模拟器实现**：不发 `return_home_info` + 无进度上报，延迟归舱（mode_code=9→5s→位置=机场, inDock=true），待真机验证

### 3.4 rc_lost_action=1/2 后续行为未明确

- **涉及协议**：[Dock3 drc.html - 一键起飞](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞)
- **问题描述**：DJI 文档未明确 rc_lost_action 触发后的后续行为：
  - rc_lost_action=1（降落）：降落位置是原地还是机场？后续状态？
  - rc_lost_action=2（返航）：是否发 `return_home_info`？返航后续行为？
- **期望补充**：明确 rc_lost_action 各枚举值触发的具体行为序列
- **当前模拟器实现**：
  - rc_lost_action=1：原地降落（保持经纬度, height=0, mode_code=0, droneInDock=false）
  - rc_lost_action=2：不发 return_home_info + 延迟归舱（mode_code=9→5s→位置=机场, inDock=true）
  - 待真机验证

### 3.5 update_topo 后续行为未规定

- **涉及协议**：
  - [设备管理时序图](https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html)
  - [update_topo 接口文档](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/device.html)
- **问题描述**：
  - DJI 文档未规定「设备必须等待 status_reply 才算上线成功」
  - DJI 文档未规定「超时未收到 status_reply 要停止流程」
  - 时序图将 update_topo 后直接进入 osd 属性推送，未将"等待 status_reply"画为独立步骤
- **期望补充**：明确 update_topo 后设备是否需要等待 status_reply，以及超时处理策略
- **当前模拟器实现**：发送 update_topo 后等待 status_reply 仅用于日志确认；超时或 result 非 0 不停止上线流程，直接继续 `state.setOnline(true)` + `publishLiveCapacity()`

### 3.6 takeoff_to_point rth_mode=0 真机反应未明确

- **涉及协议**：[Dock2/Dock3 drc.html - 一键起飞](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞)
- **问题描述**：
  - DJI 文档原文："大疆机场当前不支持设置返航高度模式，只能选择'设定高度'模式"
  - 但未明确真机收到 `rth_mode=0`（智能高度）的具体反应（错误码/行为）
- **期望补充**：明确 rth_mode=0 时 services_reply 的 result 值，以及设备是否调度 takeoff_to_point_progress 事件
- **当前模拟器实现**：按拒绝执行返回 result=1，不调度进度事件，待真机验证

### 3.7 airport_organization_bind err_infos 判定逻辑未明确

- **涉及协议**：[Dock2 device.html - airport_organization_bind](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/device.html)
- **问题描述**：
  - DJI 文档未明确 `err_code=0` 是否会出现（即"err_infos 是否可能包含成功设备"）
  - DJI 示例中 result=0 且 err_infos 含 210231（部分设备绑定失败的错误码）
  - err_infos 字面意为"错误信息"，但判定逻辑未明确
- **期望补充**：明确 err_infos 的判定逻辑：
  - err_infos 非空即整体失败？还是 err_infos 仅含失败设备，整体 result 仍可为 0？
  - err_code=0 是否会出现？
- **当前模拟器实现**：推断 err_infos 非空即失败（err_infos 只含失败设备），待真机验证

### 3.8 标量属性 set_reply 格式未明确

- **涉及协议**：[Dock1/Dock2/Dock3 properties.html - property/set_reply](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)
- **问题描述**：
  - DJI 文档示例仅展示 struct 属性的 set_reply 格式（如 `distance_limit_status.state → {"result": 0}`）
  - 未明确标量属性（如 `silent_mode`）的 set_reply 格式
- **期望补充**：明确标量属性 set_reply 的格式（是否为 `{"属性名": {"result": 0}}`）
- **当前模拟器实现**：按 struct 属性的叶子字段包 result 的逻辑推断，标量属性同样包 result（`{"silent_mode": {"result": 0}}`），待真机验证

### 3.9 Dock OSD 分多条推送字段分组方案未提供 Dock2/Dock3 专属方案

- **涉及协议**：
  - [Dock1 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)（提供分组示例）
  - [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)
  - [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html)
- **问题描述**：
  - DJI 文档明确"机场的设备属性推送是分多条推送的"，并提供 Dock1 示例
  - 但未提供 Dock2/Dock3 专属分组方案
- **期望补充**：提供 Dock2/Dock3 的 OSD 分组方案示例
- **当前模拟器实现**：按 Dock1 示例的分组模式推断 Dock3 分组（Group1=电源/电池/保养/统计, Group2=任务/图传/媒体, Group3=位置/环境/机械/子设备），待真机验证

### 3.10 live_start_push url_type 不匹配时的容错行为未规定

- **涉及协议**：[Dock3 live.html - live_start_push](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html)
- **问题描述**：DJI 文档未规定 `url_type` 与实际 `url` 协议不匹配时的行为
- **期望补充**：明确 url_type 不匹配时设备的处理策略（容错处理 / 拒绝 / 报错）
- **当前模拟器实现**：容错处理（按 url 实际协议处理），待真机验证

### 3.11 live_start_push WHIP 不支持时的降级行为未规定

- **涉及协议**：[Dock3 live.html - live_start_push](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html)
- **问题描述**：DJI 文档未规定真机不支持 WHIP 时的行为
- **期望补充**：明确真机不支持 WHIP 时的处理策略（降级为 RTMP / 直接报错）
- **当前模拟器实现**：本机 ffmpeg 不支持 WHIP 时降级为协议模拟（仅回 result=0，不真实推流），待真机验证

---

## 四、Dock 版本能力差异未集中说明（P2）

> DJI Cloud API 文档将 Dock1/Dock2/Dock3 分别放在不同路径下，开发者需跨多个文档交叉对比才能确定某指令/事件/字段的版本支持情况。建议在 [产品支持](https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html) 页面集中补充"指令/事件/字段 Dock 版本支持矩阵"。

### 4.1 事件 Dock 版本支持差异

| 事件 method | Dock1 | Dock2 | Dock3 | 文档链接 |
|---|---|---|---|---|
| `flight_setup_exception_notify` | ✓ | ✗ | ✗ | [Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) |
| `flight_setup_abort`（service） | ✓ | ✗ | ✗ | [Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html) |
| `obstacle_avoidance_notify` | ✗ | ✗ | ✓ | [Dock3 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html) |
| `poi_status_notify` | ✓ | ✗ | ✗ | [Dock1 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html) |
| `flighttask_stop`（service） | ✗ | ✓ | ✓ | [Dock3 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html) |

### 4.2 远程调试指令 Dock 版本支持差异

| 指令 method | Dock1 | Dock2 | Dock3 | 文档链接 |
|---|---|---|---|---|
| `putter_open` / `putter_close` | ✓ | ✗ | ✗ | [Dock1 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html) |
| `rtk_calibration` | ✗ | ✗ | ✓ | [Dock3 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html) |
| `esim_activate` / `esim_operator_switch` | ✗ | ✓ | ✓ | [Dock2 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html) |
| `sim_slot_switch` | ✗ | ✓ | ✓ | [Dock2 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html) |

### 4.3 直播指令 Dock 版本支持差异

| 指令 method | Dock1 | Dock2 | Dock3 | 文档链接 |
|---|---|---|---|---|
| `live_camera_change` | ✗ | ✓ | ✓ | [Dock3 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html) |

### 4.4 OSD 字段 Dock 版本支持差异

| 字段 | Dock1 | Dock2 | Dock3 | 文档链接 |
|---|---|---|---|---|
| `air_transfer_enable` | ✗ | ✓ | ✓ | [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html) |
| `home_position_is_valid` / `heading` | ✗ | ✓ | ✓ | [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html) |
| `self_converge_coordinate` | ✗ | ✗ | ✓ | [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html) |
| `drone_authority_info` | ✓ | ✗ | ✗ | [Dock1 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html) |

### 4.5 takeoff_to_point 字段 Dock 版本差异

- **涉及协议**：
  - [Dock1 drc.html - 一键起飞](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html)
  - [Dock2/Dock3 drc.html - 一键起飞](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html#一键起飞)
- **问题描述**：
  - Dock1：无 `rth_mode`/`commander_flight_mode`/`flight_safety_advance_check` 字段
  - Dock2/Dock3：`rth_mode`/`commander_flight_mode` 为必填，`flight_safety_advance_check` 为可选
- **期望补充**：在产品支持页面明确各 Dock 版本 takeoff_to_point 的字段差异

### 4.6 break_reason 枚举值型号差异

- **涉及协议**：[Dock1/Dock2/Dock3 wayline.html - flighttask_progress](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
- **问题描述**：`break_reason` 枚举值 528/529 在不同 Dock 版本中支持情况不同：

| break_reason | 语义 | Dock1 | Dock2 | Dock3 |
|---|---|---|---|---|
| 528 | 接近用户自定义飞行区边界 | ✓ | ✗ | ✗ |
| 529 | 有障碍物或者禁飞区域，导致航线无法到达 | ✗ | ✓ | ✗ |
| 1565 | 航线避障紧急刹停 | ✓ | ✓ | ✓ |

- **期望补充**：在 break_reason 枚举值表格中标注各值的 Dock 版本支持情况

### 4.7 current_step 枚举值版本偏移未说明原因

- **涉及协议**：[Dock1/Dock2/Dock3 wayline.html - flighttask_progress](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
- **问题描述**：
  - Dock1 step 序列：7, 22, 24, 25, 27, 33
  - Dock2/3 step 序列：7, 24, 26, 27, 29, 35
  - Dock2/3 比 Dock1 整体偏移 +2（多 step 8 图传远程对频、step 22 起飞机场检查降落机场准备状态）
  - **Dock2 跳过 step 25（航线执行中），Dock3 有此值**，但 Dock2 文档未明确说明跳过原因
- **期望补充**：在 current_step 枚举值表格中标注 Dock2 跳过 step 25 的原因

### 4.8 OSD 字段命名风格版本差异未集中说明

- **涉及协议**：
  - [Dock1 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)
  - [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)
  - [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html)
- **问题描述**：
  - Dock1 OSD：snake_case → camelCase（如 `mode_code` → `modeCode`）
  - Dock2 OSD：未明确（与 Dock1 一致？）
  - Dock3 OSD：snake_case（原样）
- **期望补充**：在产品支持页面明确各 Dock 版本 OSD 字段命名风格

---

## 五、其他文档问题（P2）

### 5.1 蛙跳任务参数说明不完整

- **涉及协议**：[Dock3 wayline.html - flighttask_execute](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)
- **问题描述**：
  - DJI 文档解析了蛙跳任务参数（`center_node`、`leaf_nodes`、`secret_code`）
  - 但未明确蛙跳任务的执行逻辑（设备如何处理这些参数、需要上报哪些进度事件等）
- **期望补充**：补充蛙跳任务的执行流程说明和事件序列

### 5.2 飞行器负载 measure_target_* 字段无测距场景下的值未明确

- **涉及协议**：
  - [M30 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)
  - [M4D properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html)
- **问题描述**：
  - `measure_target_error_state` 枚举：0=NORMAL, 1=TOO_CLOSE, 2=TOO_FAR, 3=NO_SIGNAL
  - 但 DJI 文档未明确无测距场景下（如机场停机状态）各 `measure_target_*` 字段的默认值
- **期望补充**：明确无测距场景下 `measure_target_*` 字段的默认值和 `measure_target_error_state` 应取的枚举值

---

## 附录：相关 DJI Cloud API 文档链接

### 通用
- [产品支持](https://developer.dji.com/doc/cloud-api-tutorial/cn/overview/product-support.html)
- [Topic 定义](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/topic-definition.html)
- [设备管理时序图](https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/dock-feature-set/dock-device-management.html)

### Dock1
- [Dock1 device.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/device.html)
- [Dock1 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/wayline.html)
- [Dock1 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/drc.html)
- [Dock1 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/cmd.html)
- [Dock1 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/live.html)
- [Dock1 file.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/file.html)
- [Dock1 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock1/properties.html)

### Dock2
- [Dock2 device.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/device.html)
- [Dock2 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/wayline.html)
- [Dock2 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/drc.html)
- [Dock2 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/cmd.html)
- [Dock2 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/live.html)
- [Dock2 file.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/file.html)
- [Dock2 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock2/properties.html)

### Dock3
- [Dock3 device.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/device.html)
- [Dock3 wayline.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/wayline.html)
- [Dock3 drc.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/drc.html)
- [Dock3 cmd.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/cmd.html)
- [Dock3 live.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/live.html)
- [Dock3 media.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/media.html)
- [Dock3 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/dock/dock3/properties.html)

### 飞行器
- [M30 properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m30-properties.html)
- [M4D properties.html](https://developer.dji.com/doc/cloud-api-tutorial/cn/api-reference/dock-to-cloud/mqtt/aircraft/m4d-properties.html)

---

## 问题统计

| 类别 | P0（严重） | P1（重要） | P2（一般） | 小计 |
|---|---|---|---|---|
| 一、字段定义与示例矛盾 | 3 | - | - | 3 |
| 二、字段在属性列表与示例之间不一致 | 5 | - | - | 5 |
| 三、协议联动行为文档未明确 | - | 11 | - | 11 |
| 四、Dock 版本能力差异未集中说明 | - | - | 8 | 8 |
| 五、其他文档问题 | - | - | 2 | 2 |
| **合计** | **8** | **11** | **10** | **29** |

---

## 修复建议优先级

### P0（建议优先修复）
- 字段定义与示例矛盾会导致平台/设备 Jackson 反序列化失败，影响协议可用性
- 字段在属性列表与示例之间不一致会让开发者无法判断字段是否合法

### P1（建议次优先修复）
- 协议联动行为未明确会导致不同厂商实现方案不一致，影响互联互通
- 建议在文档中补充完整的时序图和事件序列说明

### P2（建议长期完善）
- Dock 版本能力差异建议在产品支持页面集中补充"指令/事件/字段 Dock 版本支持矩阵"
- 减少开发者跨文档交叉对比的成本

---

## 联系方式

如需进一步核实本文档中的问题，请联系 hivemind-simulator 项目组。本报告所有问题均已在模拟器项目中通过 M-2 诊断日志（`MONITOR_SIMULATOR_INFERENCE`）标记，并在 [TDD-SPEC.md](TDD-SPEC.md) 和 [设计文档](superpowers/specs/2026-08-08-dji-dock-simulator-design.md) 中记录推断逻辑和待真机验证点。
