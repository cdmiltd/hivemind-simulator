# 贡献指南

感谢你对 DJI Dock 模拟器项目的关注！本文档介绍如何参与开发贡献。

## 1. 开发环境

### 前置要求

- JDK 21+
- Maven 3.8+
- Rust 工具链（桌面端打包，可选）
- 一个运行中的 EMQX（或兼容的 MQTT Broker）
- 一个 DJI Cloud API 后端（如 hivemind）

### 启动

```bash
mvn spring-boot:run
```

启动后访问 http://localhost:9090 打开模拟器控制台，http://localhost:9090/monitor.html 打开监控器。

前端为零构建：直接编辑 `src/main/resources/static/index.html` / `monitor.html`（Vue 3 + Element Plus 通过 CDN 本地化引入），无需 npm install。

## 2. TDD 开发模式

本项目采用 TDD（测试驱动开发）作为标准开发方式：

1. **先写测试**：在 [TDD-SPEC.md](docs/TDD-SPEC.md) 编写测试用例（基于 DJI 文档规格，非实现代码）
2. **再实现代码**：满足测试用例
3. **优化阶段**：以 TDD 文档为依据，确保不破坏已有行为

新增功能或修复 Bug 时，必须先更新 TDD-SPEC.md 测试用例。

## 3. 代码规范

- **无硬编码值**：可配参数走 `application.yml` → `SimulatorProperties` → `RuntimeConfig`
- **业务逻辑返回明确拒绝原因**而非抛异常（HTTP 200 + success=false + message）
- **Vue DOM 模板**：自定义元素（`<el-xxx>`）必须用显式闭合标签，禁止自闭合
- **不适用的值**用 `-` 显示，不用 `0` 或 `--`
- **配置无硬编码**：新增可配参数走 yml → Properties → RuntimeConfig 链路
- **DJI 协议**：涉及 DJI Cloud API 的改动必须核对[官方文档](https://developer.dji.com/doc/cloud-api-tutorial/cn/)

## 4. 提交规范

采用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：

- `feat:` 新功能
- `fix:` Bug 修复
- `docs:` 文档
- `refactor:` 重构
- `test:` 测试
- `chore:` 构建/工具

## 5. PR 流程

1. Fork 仓库并创建分支
2. 遵循 TDD：先更新 TDD-SPEC.md，再实现代码
3. 确保 `mvn test` 通过
4. 提交 PR，填写模板中的自检清单
5. 等待维护者 review

## 6. 测试

```bash
mvn test
```

新增功能必须同步补充对应的 Java 单元测试（TDD-SPEC.md 中定义的后端可测用例），前端仅可测用例除外。

## 7. Review 标准

维护者 review 时关注：

- 是否遵循 TDD 流程（TDD-SPEC.md 是否同步更新）
- 是否破坏既有流程
- 命名与文档描述是否仍准确反映职责

## 8. PR 流程与发布节奏

- 你提交的 PR 会被维护者 review，通过后合并
- 改动会随**下一个 release 版本**发布，而非立即生效
- 这样保证仓库始终是经过完整测试的稳定版

感谢你的耐心与贡献！

## 9. 文档更新

改动涉及以下内容时，同步更新对应文档：

| 场景 | 更新文档 |
|---|---|
| 新增功能 | TDD-SPEC.md → 设计文档 → 代码 |
| 修复 Bug | TDD-SPEC.md → 代码 |
| 协议变更 | 设计文档 → TDD-SPEC.md → 代码 |
| 配置变更 | 设计文档 → README.md |
| 项目结构变更 | 设计文档 → README.md |

详细文档策略见 [AGENTS.md](AGENTS.md)。
