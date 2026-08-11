# DJI Dock 模拟器

模拟一台 DJI Dock + 以及配套的飞行机，按 DJI Cloud API 协议经 MQTT 与巡飞平台（hivemind）通信。

## 项目价值

核心价值：**比真机更快捷地验证巡飞平台代码正确性**（状态可控、场景可复现、迭代周期短），同时让开发测试不必依赖真实机场硬件。

> 详见 [设计文档](docs/superpowers/specs/2026-08-08-dji-dock-simulator-design.md)。

## 环境要求

- JDK 21+
- Maven 3.8+
- 运行中的 EMQX broker（与 hivemind 共用）
- 运行中的 hivemind 平台

## 快速开始

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
# MQTT 公共配置（模拟器与监控器共享）
mqtt:
  host: 127.0.0.1
  port: 1883
  username: dji_uas_admin
  password: Dji@Mqtt2024!Secure
  simulator-client-id-prefix: dock-sim-
  monitor-client-id-prefix: monitor-

simulator:
  device:
    dock-sn: 7UUXN1Q00A008W
    drone-sn: 1081F8HGD25110010059
    dock-type: DOCK3             # Dock1/Dock2/Dock3
    drone-type: M4TD             # M30/M30T(Dock1), M3D/M3TD(Dock2), M4D/M4TD(Dock3)
    organization-id: MJDQELED
    device-binding-code: IxEYnFLHPFyIicrn
    app-license: ""             # 桌面端用户在注册时填写覆盖
  location:
    latitude: 30.670815
    longitude: 104.071523
    height: 500.0

server:
  port: 9090
```

> 桌面端用户可在 Web 控制台注册时填写 App License、绑定码等覆盖配置。

### 构建与运行

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

## Web 控制台使用指南

启动后浏览器打开 `http://localhost:9090`。

### 注册到第三方平台

1. 点击"注册到第三方平台"打开配置弹窗
2. 填写 MQTT 地址、App License、组织 ID、绑定码
3. 点击注册，模拟器自动执行上云注册流程（config → 绑定状态查询 → 组织绑定）
4. 注册成功后设备自动上线

> **重要**：首次注册时输入的 App License 会被锁定存储到 `localStorage['locked_app_license']`。后续注册时注册界面会**隐藏 App License 输入行**（显示「已锁定」标签），前端自动使用锁定的 license 提交，用户无需再次输入。App License 是第三方平台通过 config 回复下发给模拟器的，用户在模拟器侧再次输入不起作用。
>
> 桌面应用中 localStorage 无法手动清除，**如果首次输入错误，必须卸载重装应用**。浏览器环境可通过 F12 开发者工具清除 localStorage 中的 `locked_app_license` 重置。
>
> 其他配置（MQTT 地址、组织 ID、绑定码等）会保存到 localStorage，下次自动填充，可随时修改。

### 设备控制

- 注册成功后自动上线
- 可手动下线

### 状态参数

调整电量/温度/湿度/风速/舱盖等，实时影响 OSD 上报。

### 任务模拟

查看当前任务进度和媒体文件列表（由 hivemind 下发任务触发）。

### 消息日志

实时查看 MQTT 收发报文。

### 监控器页面

`http://localhost:9090/monitor.html` — 独立 MQTT 客户端监听平台消息，用于调试观察。

## 与 hivemind 联调步骤

1. 启动 EMQX broker
2. 启动 hivemind 平台
3. 启动本模拟器：`mvn spring-boot:run`
4. 打开 `http://localhost:9090`，填写配置后点击"注册到第三方平台"
5. 注册成功后设备自动上线，在 hivemind 平台设备列表确认
6. 在 hivemind 平台下发航线任务，观察模拟器自动推进进度并上报媒体文件
7. 在 hivemind 平台下发直播命令，观察模拟器应答

## 开发导航

### 项目结构

```
Simulator/
├── src/main/java/ltd/cdmi/simulator/
│   ├── config/          # 配置绑定（SimulatorProperties/MqttProperties/RuntimeConfig/LiveConfigStore）
│   ├── mqtt/            # MQTT 连接（MqttClientManager/MonitorMqttClient）
│   ├── device/          # 设备状态、上云流程、OSD Builder 策略（DeviceState/DeviceSimulator/DockOnlineService/OsdBuilder）
│   ├── handler/         # 协议处理器（航线/直播/媒体/HMS/DRC/飞行指令/FFmpeg推流/属性设置）
│   └── web/             # REST API 与页面入口
├── src/main/resources/
│   ├── application.yml
│   ├── hms.json         # HMS 错误码映射
│   └── static/          # index.html（模拟器） + monitor.html（监控器）
└── src-tauri/           # Tauri 桌面端打包
```

### 相关文档

| 文档 | 内容 |
|---|---|
| [设计文档](docs/superpowers/specs/2026-08-08-dji-dock-simulator-design.md) | 架构、DJI 时序图、协议覆盖、数据流、错误码体系、update_topo 核实结论 |
| [TDD 规格测试文档](docs/TDD-SPEC.md) | 容易搞错的规格陷阱、测试用例、TDD 开发模式 |
| [AGENTS.md](AGENTS.md) | AI 编程约定（修改前置流程、文档对齐、汇报要求） |

## 不支持的功能

- 固件升级、远程日志、自定义飞行区
- 真实 KMZ 航线解析（进度按时间假推进）
- 多机模拟（单机，后续可扩展）

## 开源协议

本项目采用 [GNU Affero General Public License v3.0](LICENSE) 开源协议。

- 允许自由使用、修改和分发
- 衍生作品必须以相同协议开源
- 通过网络提供服务（SaaS）也必须公开源代码
- 商业使用需遵守 AGPLv3 条款
