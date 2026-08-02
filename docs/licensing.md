# 许可证与第三方声明

## 项目许可证

mic-data-sync 以 [MIT License](../LICENSE) 发布。

## 第三方依赖

项目使用大量开源依赖，包括但不限于：

| 组件 | 用途 | 许可证（以官方发布为准） |
|---|---|---|
| Spring Boot / Spring Security | 后端框架与安全 | Apache-2.0 |
| SQLite JDBC | 本地状态库 | Apache-2.0 |
| Flyway | 数据库迁移 | Apache-2.0 |
| JSqlParser | SQL 安全解析 | Apache-2.0 |
| Vue 3 | 前端框架 | MIT |
| Element Plus | UI 组件库 | MIT |
| Vue Router / Pinia | 路由与状态管理 | MIT |
| Vite / Vitest | 构建与测试 | MIT |
| Axios | HTTP 客户端 | MIT |
| Playwright | 端到端测试 | Apache-2.0 |

完整依赖清单以各项目的 `package.json`、`pom.xml` 和官方 LICENSE 为准。

## 数据库驱动

- **openGauss JDBC**：openGauss 是开源数据库，驱动许可证以其官方仓库为准；本项目不内置驱动 JAR，由用户放入 `${dataDir}/drivers`；
- **KingbaseES JDBC**：人大金仓 KingbaseES 驱动为商业授权组件，本项目不内置、不随分发包分发，使用者需从人大金仓官方渠道获取并遵守其授权条款。

## 商标与免责

- 产品名称、品牌与商标归各自所有者所有；
- 本项目按 MIT 许可证“按现状”提供，不附带任何明示或默示担保；
- 医疗或生产场景使用前，请由部署方独立完成安全与合规评估。
