# LavShard v0.1.3 Release Notes

> 发布状态： **Draft**
> 发布日期：待定
> Git Tag：待创建 `v0.1.3`
> 当前代码版本：`0.1.3`

## 发布摘要

v0.1.3 是面向 Maven Central 消费体验的补充版本。该版本新增独立的
`lavshard-bom`，并收紧发布边界，确保示例工程与测试模块不会进入公开仓库。

业务功能与 v0.1.2 保持兼容，不包含计划中的破坏性 API 变更。

## Added

- 新增 `io.github.lavyoung:lavshard-bom:0.1.3`。
- BOM 统一管理以下公开 Java 模块：
    - `lavshard-core`；
    - `lavshard-mybatis`；
    - `lavshard-spring-boot-starter`。

## Fixed

- Central Publisher 明确排除 `lavshard-example`、两个可运行示例和 `lavshard-test`。
- 发布 bundle 仅包含 Parent、BOM、Core、MyBatis 适配器和 Spring Boot Starter。

## Upgrade Guide

推荐通过 BOM 统一管理 LavShard 版本：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.lavyoung</groupId>
            <artifactId>lavshard-bom</artifactId>
            <version>0.1.3</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>io.github.lavyoung</groupId>
        <artifactId>lavshard-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

未使用 BOM 的项目可以继续直接为依赖声明 `0.1.3`，无需调整现有配置。

## Published Coordinates

- `io.github.lavyoung:lavshard-parent:0.1.3`
- `io.github.lavyoung:lavshard-bom:0.1.3`
- `io.github.lavyoung:lavshard-core:0.1.3`
- `io.github.lavyoung:lavshard-mybatis:0.1.3`
- `io.github.lavyoung:lavshard-spring-boot-starter:0.1.3`

## Verification

- 已通过：`mvn clean verify -DskipITs`，共执行 668 个测试，0 失败、0 错误、0 跳过。
- 已通过：`mvn clean deploy -Pcentral-release -DskipPublishing=true -Dgpg.skip=true -DskipTests`
  本地打包演练；bundle 仅包含上述 5 个公开坐标，示例与测试条目为 0。
- 待执行：在可用的 GPG 会话中完成带签名的发布演练；本次本机签名因 `gpg: signing
  failed: Timeout` 中止。
- 发布前仍需在具备 Docker 的环境中执行 MySQL Testcontainers 验收。

## Links

- [Changelog](../../../CHANGELOG.md)
- [README](../../../README.md)
- [v0.1.2 Release Notes](../v0.1.2/release-notes.md)
