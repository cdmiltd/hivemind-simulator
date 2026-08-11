# DJI Dock 模拟器

![License](https://img.shields.io/badge/license-AGPLv3-blue.svg)
![Java](https://img.shields.io/badge/Java-21+-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)
![Platform](https://img.shields.io/badge/platform-Windows-lightgrey.svg)
![DJI Cloud API](https://img.shields.io/badge/DJI%20Cloud%20API-compatible-blue.svg)
![Coverage](https://img.shields.io/badge/coverage-JaCoCo-blue.svg)

模拟 DJI Dock（Dock1/Dock2/Dock3）机场及其配套飞行器（M30/M3D/M4D 系列）的完整云端交互流程，按 DJI Cloud API 协议经 MQTT 与巡飞平台（hivemind）通信。

## 项目价值

核心价值：**比真机更快捷地验证巡飞平台代码正确性**（状态可控、场景可复现、迭代周期短），同时让开发测试不必依赖真实机场硬件。

> 详见 [设计文档](docs/superpowers/specs/2026-08-08-dji-dock-simulator-design.md)。

## 适用场景

| 角色 | 价值 |
|---|---|
| DJI Cloud API 后端开发者 | 状态可控、场景可复现地验证平台代码，无需真实机场硬件 |
| 无人机云平台集成商 | 联调测试 DJI 协议对接，快速回归 |
| 司空私有版运维人员 | 通过监控器抓取 MQTT 消息排查线上问题，定位协议异常 |
| DJI 机场协议学习者 | 通过时序图、消息日志、诊断系统理解 Cloud API 交互细节 |

## 界面截图

<p align="center">
  <img src="assets/main-online.png" alt="主界面（设备在线）" width="720" />
</p>

<details>
<summary>更多功能截图</summary>

<br/>

<p align="center">
  <img src="assets/register-form.png" alt="注册配置弹窗" width="480" />
</p>

| 异常模拟 | 消息日志（MQTT 报文） |
|---|---|
| <img src="assets/exception.png" width="360" /> | <img src="assets/messages.png" width="360" /> |

| 位置模拟（地图模式） | 监控器（已连接） |
|---|---|
| <img src="assets/location.png" width="360" /> | <img src="assets/monitor.png" width="360" /> |

</details>

## 功能特性

- **多机型支持**：Dock1/Dock2/Dock3 + M30/M3D/M4D 系列飞行器
- **完整注册流程**：config → airport_bind_status → airport_organization_get → airport_organization_bind，注册成功后 update_topo 上线
- **OSD/State 上报**：按设备类型构造差异化字段，支持事件性属性上报
- **航线任务模拟**：接收平台下发任务，按时间推进进度并上报媒体文件
- **直播推流**：支持 FFmpeg WHIP 真实推流（WebRTC），视频循环播放持续推流
- **媒体上传**：模拟飞行后媒体文件上传流程
- **HMS 告警**：完整 HMS 错误码映射与上报
- **DRC 远程指挥**：支持 DRC 指令通道
- **飞行控制**：一键起飞/返航/降落模拟
- **属性设置**：响应平台属性设置指令
- **位置模拟**：高德地图选点（自动获取海拔）或手动输入坐标，地址搜索定位
- **诊断系统**：协议覆盖率统计、规格校验、MQTT 消息日志
- **监控器页面**：独立 MQTT 客户端，实时监听平台消息用于调试
- **桌面端打包**：Tauri 打包为 Windows 安装包，内置 JRE

## 架构概览

```mermaid
graph TB
    subgraph 模拟器
        WEB[Web 控制台<br/>Vue 3 + Element Plus]
        BACK[Spring Boot 后端<br/>REST API + MQTT Client]
        TAURI[Tauri 桌面端<br/>端口 9090→19090]
    end

    subgraph 云端
        EMQX[EMQX Broker]
        HIVE[hivemind 平台<br/>DJI Cloud API 后端]
    end

    WEB <-->|REST API| BACK
    BACK <-->|MQTT| EMQX
    EMQX <--> HIVE
    TAURI --> BACK

    style WEB fill:#0ea5e9,color:#fff
    style BACK fill:#10b981,color:#fff
    style EMQX fill:#f59e0b,color:#fff
    style HIVE fill:#ef4444,color:#fff
    style TAURI fill:#8b5cf6,color:#fff
```

## 注册流程

```mermaid
sequenceDiagram
    participant 模拟器
    participant EMQX
    participant hivemind

    模拟器->>EMQX: 建立 MQTT 连接
    模拟器->>hivemind: config（上报设备配置）
    hivemind-->>模拟器: config_reply（app_license）
    模拟器->>hivemind: airport_bind_status（查询绑定状态）
    hivemind-->>模拟器: bind_status_reply
    模拟器->>hivemind: airport_organization_get（获取组织树）
    hivemind-->>模拟器: organization_get_reply
    模拟器->>hivemind: airport_organization_bind（绑定设备到组织）
    hivemind-->>模拟器: organization_bind_reply
    Note over 模拟器,hivemind: 注册完成
    模拟器->>hivemind: update_topo（设备上线）
    Note over 模拟器,hivemind: 设备上线，开始 OSD/State 上报
```

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.x、MQTT Paho v3 |
| 前端 | Vue 3、Element Plus |
| 桌面端 | Tauri（Rust） |
| 构建 | Maven 3.8+ |
| 协议 | DJI Cloud API（[官方文档](https://developer.dji.com/doc/cloud-api-tutorial/cn/)） |

## 环境要求

- JDK 21+
- Maven 3.8+
- 运行中的 EMQX broker（与第三方巡飞平台共用）
- 运行中的第三方巡飞平台

## 快速开始

### 方式一：桌面端安装（推荐）

1. 下载最新版 [DJI Dock Simulator_x64-setup.exe](https://github.com/cdmiltd/hivemind-simulator/releases/latest) 安装包
2. 运行安装程序
3. 启动应用，自动打开控制台
4. 填写配置后点击"注册到第三方平台"

### 方式二：源码编译

```bash
# 编译
mvn compile

# 打包
mvn package -DskipTests

# 运行
java -jar target/dji-dock-simulator-1.0.0.jar

# 或直接运行
mvn spring-boot:run
```

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
# MQTT 公共配置（模拟器与监控器共享）
mqtt:
  host: 127.0.0.1
  port: 1883
  username: your-mqtt-username
  password: your-mqtt-password
  simulator-client-id-prefix: dock-sim-
  monitor-client-id-prefix: monitor-

simulator:
  location:
    latitude: 30.670815
    longitude: 104.071523
    height: 500.0

server:
  port: 9090
```

> 桌面端用户可在 Web 控制台注册时填写 DJI License、绑定码等覆盖配置，无需修改 application.yml。

## Web 控制台使用指南

启动后浏览器打开 `http://localhost:9090`。

### 注册到第三方平台

1. 点击"注册到第三方平台"打开配置弹窗
2. 选择机场类型、飞行器类型
3. 填写 DJI License（可选）、组织 ID、绑定码
4. 填写 MQTT 地址、账号、密码
5. 点击注册，模拟器自动执行上云注册流程（config → 绑定状态查询 → 组织绑定）
6. 注册成功后设备自动上线

> **重要**：首次注册时输入的 DJI License 会被锁定存储到 `localStorage['locked_app_license']`。后续注册时注册界面会**隐藏 DJI License 输入行**（显示「已锁定」标签），前端自动使用锁定的 license 提交，用户无需再次输入。DJI License 是第三方平台通过 config 回复下发给模拟器的，用户在模拟器侧再次输入不起作用。留空则跳过 License 校验，适用于调试阶段。
>
> 桌面应用中 localStorage 无法手动清除，**如果首次输入错误，必须卸载重装应用**。浏览器环境可通过 F12 开发者工具清除 localStorage 中的 `locked_app_license` 重置。
>
> 其他配置（MQTT 地址、组织 ID、绑定码等）会保存到 localStorage，下次自动填充，可随时修改。

### 设备控制

- 注册成功后自动上线
- 可手动下线
- 飞行器激活/休眠切换（飞行器在舱时可操作）
- 舱盖开合、推杆伸展状态切换

### 状态参数

调整电量/温度/湿度/风速/降雨/舱盖等，实时影响 OSD 上报。

### 任务模拟

查看当前任务进度和媒体文件列表（由第三方巡飞平台下发任务触发）。

### 位置模拟

- **地图模式**：输入高德地图 JS API Key 后启用，支持地图选点、拖拽 Marker、地址搜索定位
- **手动模式**：直接输入经纬度和高度
- 地图选点自动获取海拔高度（Open-Meteo Elevation API）
- 地图模式下选点/拖拽自动保存，手动模式下需点击保存按钮
- 机场位置作为起飞点与返航点，保存后重启依然有效
- 实时显示无人机位置（纬度/经度/高度/状态），飞行时按步骤更新
- 飞行器未激活时位置显示为 `-`

### 直播推流配置

- 支持 FFmpeg WHIP 真实推流（WebRTC）
- 一键安装 FFmpeg（通过 winget）
- 视频文件目录配置，支持循环播放持续推流
- 协议模拟模式（无 FFmpeg 时仅协议应答）

### 消息日志

实时查看 MQTT 收发报文，点击查看完整 payload。

### 诊断系统

- 协议覆盖率统计：统计已实现的 DJI 方法
- 规格校验：校验 MQTT 消息格式是否符合 DJI 协议
- 诊断日志：记录协议异常和覆盖情况

### 监控器页面

`http://localhost:9090/monitor.html` — 独立 MQTT 客户端监听平台消息，用于调试观察。

## 与第三方巡飞平台联调步骤

1. 启动 EMQX broker
2. 启动第三方巡飞平台
3. 启动本模拟器：`mvn spring-boot:run`
4. 打开 `http://localhost:9090`，填写配置后点击"注册到第三方平台"
5. 注册成功后设备自动上线，在第三方巡飞平台设备列表确认
6. 在第三方巡飞平台下发航线任务，观察模拟器自动推进进度并上报媒体文件
7. 在第三方巡飞平台下发直播命令，观察模拟器应答
8. 在第三方巡飞平台下发 DRC 指令，观察飞行控制响应

## 开发导航

### 项目结构

```
hivemind-simulator/
├── src/main/java/ltd/cdmi/hivemind/simulator/
│   ├── config/          # 配置绑定（SimulatorProperties/MqttProperties/RuntimeConfig/LiveConfigStore）
│   ├── mqtt/            # MQTT 连接（MqttClientManager/MonitorMqttClient）
│   ├── device/          # 设备状态、上云流程、OSD Builder 策略（DeviceState/DeviceSimulator/DockOnlineService/OsdBuilder）
│   ├── handler/         # 协议处理器（航线/直播/媒体/HMS/DRC/飞行指令/FFmpeg推流/属性设置）
│   └── web/             # REST API 与页面入口
├── src/main/resources/
│   ├── application.yml
│   ├── hms.json         # HMS 错误码映射
│   ├── dji-method-catalog.json  # DJI 方法目录
│   └── static/          # index.html（模拟器） + monitor.html（监控器） + vendor/（Vue/Element Plus CDN 本地化）
├── src/test/java/ltd/cdmi/hivemind/simulator/  # 单元测试（OSD/航线/直播/媒体/远程调试）
└── src-tauri/           # Tauri 桌面端打包
```

### 相关文档

| 文档 | 内容 |
|---|---|
| [设计文档](docs/superpowers/specs/2026-08-08-dji-dock-simulator-design.md) | 架构、DJI 时序图、协议覆盖、数据流、错误码体系、update_topo 核实结论 |
| [TDD 规格测试文档](docs/TDD-SPEC.md) | 容易搞错的规格陷阱、测试用例、TDD 开发模式 |

### DJI Cloud API 参考

- [DJI Cloud API 官方文档](https://developer.dji.com/doc/cloud-api-tutorial/cn/)
- 支持的协议方法详见 `src/main/resources/dji-method-catalog.json`

## 开发工作流

### 版本管理

采用[语义化版本](https://semver.org/lang/zh-CN/) `vMAJOR.MINOR.PATCH`，变更记录见 [CHANGELOG.md](CHANGELOG.md)。

### 参与贡献

贡献流程、TDD 开发模式、代码规范、提交规范详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## Roadmap

以下为暂未支持、计划演进的方向，欢迎在 Issue 中讨论或认领（标注 `good first issue` 的适合首次贡献）：

- [ ] 固件升级、远程日志、自定义飞行区
- [ ] 真实 KMZ 航线解析（当前按时间假推进进度）
- [ ] 多机模拟（当前为单机）
- [ ] Docker 化部署（docker-compose 含 EMQX）
- [ ] 英文 README 与国际化 UI
- [ ] 更多机型（如 M4E 等）

> 当前与历史变更见 [CHANGELOG.md](CHANGELOG.md)。

## 交流与支持

<p align="center">
  <img src="assets/friendCode.png" alt="微信二维码" width="220" />
</p>

- 问题反馈与功能建议：请提交 [Issue](https://github.com/cdmiltd/hivemind-simulator/issues)
- 贡献代码：请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)
- 微信沟通：扫码添加（上方二维码）

## 支持作者

如果这个项目对你的工作有帮助，欢迎打赏支持，激励持续维护与功能演进。

<p align="center">
  <img src="assets/PayCode.png" alt="收款码" width="220" />
</p>

## 开源协议

本项目采用 [GNU Affero General Public License v3.0](LICENSE) 开源协议。

- 允许自由使用、修改和分发
- 衍生作品必须以相同协议开源
- 通过网络提供服务（SaaS）也必须公开源代码
- 商业使用需遵守 AGPLv3 条款

> **为何选择 AGPLv3**：本项目定位为调试工具，希望保持开放共享；AGPLv3 确保任何通过网络提供本软件或其衍生品的服务都必须公开源代码，避免被直接商业化套壳而不回馈社区。如需商业授权，请联系维护者。
