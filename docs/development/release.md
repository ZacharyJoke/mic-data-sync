# 发布流程

## 版本策略

- 当前版本：`0.1.0-SNAPSHOT`（MVP-I1 候选版本，持续迭代中）；
- 发布前将 `SNAPSHOT` 替换为正式版本号，并同步更新：
  - `pom.xml` / `server/pom.xml`；
  - `web/package.json`；
  - `CHANGELOG.md`；
  - 文档中的版本与链接。

## 构建分发包

```bash
./scripts/build-distribution.sh
```

该命令会：

1. 安装前端依赖（`npm ci`）；
2. 构建前端；
3. 拷贝前端产物到后端静态资源目录；
4. 打包可执行 JAR；
5. 组装 `distribution-build/` 并生成 `mic-data-sync-<version>-linux.tar.gz`。

发布包结构：

```text
bin/                 start.sh / stop.sh
config/              application-example.yml
systemd/             mic-data-sync.service
docs/                安装、账号、Docker 部署与首次同步手册
nginx/               nginx 反向代理样例
drivers/README.txt   JDBC 驱动说明（默认内置 openGauss/PostgreSQL）
*.jar                可执行 JAR
README.md
```

## 发布前检查清单

- [ ] `./scripts/verify.sh` 全部通过；
- [ ] `./scripts/smoke-test.sh` 通过；
- [ ] `CHANGELOG.md` 已记录本次变更；
- [ ] 公开文档不包含真实服务器地址、密码、Token、密钥或生产实例 ID；
- [ ] `data/`、`.env`、`distribution-build/`、tar.gz 未进入版本库；
- [ ] 商业授权驱动（KingbaseES）JAR 未打包进发布包，由用户自行获取；
- [ ] LICENSE、CONTRIBUTING、SECURITY 等社区文件随仓库发布。

## 发布后

- 在 GitHub 创建 Tag 与 Release，附件上传 Linux x86_64 tar.gz；
- 在 Release Notes 中引用 CHANGELOG 对应条目；
- 如涉及安全修复，遵循 [SECURITY.md](../../SECURITY.md) 的披露流程。
