
# LavShard
<p align="center">
  <img src="docs/images/logo.svg" alt="LavShard Logo" width="200"/>
</p>

> 轻量级数据库分库分表路由组件 —— 让海量数据像熔岩般奔流，但分而有道，路由有方。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Maven Central](https://img.shields.io/badge/maven%20central-0.1.0--SNAPSHOT-yellow.svg)](https://search.maven.org/)
[![Build Status](https://github.com/lavyoung/lavshard/actions/workflows/ci.yml/badge.svg)](https://github.com/lavyoung/lavshard/actions)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/lavyoung/lavshard/pulls)

---

## 📖 简介

**LavShard** 是一个轻量级、高性能的数据库分库分表路由组件，为 Spring Boot 应用提供开箱即用的分片能力。

核心设计理念：

- **轻量** —— 核心模块零 Spring 依赖，可独立用于任意 Java 项目
- **无感** —— 业务代码不需要关心分片逻辑，SQL 自动路由到正确的库表
- **可扩展** —— 分片算法、规则加载、数据源均提供 SPI 扩展点
- **高性能** —— SQL 解析 + 路由计算全内存完成，单次路由耗时 < 1μs

---

## ✨ 特性

| 特性 | 说明 | 状态 |
| :--- | :--- | :---: |
| 分库分表 | 支持仅分表、仅分库、分库 + 分表 | ✅ |
| 分片算法 | Hash / Range / 自定义 SPI | ✅ |
| 动态数据源 | 基于 HikariCP 的多数据源管理 | ✅ |
| Spring Boot Starter | `@EnableLavShard` 一键开启 | ✅ |
| SQL 解析 | 基于 JSQLParser，支持 SELECT / INSERT / UPDATE / DELETE | ✅ |
| 路由上下文 | ThreadLocal 隔离，支持嵌套 | ✅ |
| 读写分离 | 主从自动路由 | 🚧 规划中 |
| 分布式事务 | 弱 XA / TCC | 🚧 规划中 |
| 数据脱敏 | 敏感字段自动加密 | 🚧 规划中 |
| Actuator 端点 | `/actuator/lavshard` 查看分片状态 | ✅ |

---

## 🏗️ 架构

```text
┌─────────────────────────────────────────────────────────┐
│                     Business Code                        │
│              (Mapper / Service / Controller)             │
└──────────────────────────┬──────────────────────────────┘
                           │ SQL
                           ▼
┌─────────────────────────────────────────────────────────┐
│                    LavShard Core                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐   │
│  │ SQL 解析 │→│  路由计算 │→│ 数据源路由│→│ 执行器 │   │
│  └──────────┘  └──────────┘  └──────────┘  └────────┘   │
│         ▲              ▲            ▲                    │
│         │              │            │                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐               │
│  │ 规则仓库 │  │ 分片算法 │  │ 路由上下文│               │
│  └──────────┘  └──────────┘  └──────────┘               │
└──────────────────────────┬──────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌─────────┐       ┌─────────┐        ┌─────────┐
   │  ds0    │       │  ds1    │        │  dsN    │
   │ ┌─────┐ │       │ ┌─────┐ │        │ ┌─────┐ │
   │ │t_0  │ │       │ │t_0  │ │        │ │t_0  │ │
   │ │t_1  │ │       │ │t_1  │ │        │ │t_1  │ │
   │ │t_N  │ │       │ │t_N  │ │        │ │t_N  │ │
   │ └─────┘ │       │ └─────┘ │        │ └─────┘ │
   └─────────┘       └─────────┘        └─────────┘
```

---

## 🚀 快速开始

### 1. 引入依赖

**Maven**

```xml
<dependency>
    <groupId>io.github.lavyoung</groupId>
    <artifactId>lavshard-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Gradle**

```groovy
implementation 'io.github.lavyoung:lavshard-spring-boot-starter:0.1.0-SNAPSHOT'
```

### 2. 配置数据源与分片规则

```yaml
# application.yml
lavshard:
  enabled: true

  # 数据源配置
  datasources:
    ds0:
      url: jdbc:mysql://localhost:3306/db0?useSSL=false
      username: root
      password: 123456
      driver-class-name: com.mysql.cj.jdbc.Driver
    ds1:
      url: jdbc:mysql://localhost:3306/db1?useSSL=false
      username: root
      password: 123456
      driver-class-name: com.mysql.cj.jdbc.Driver

  # 分片规则
  sharding-rules:
    - table: order
      sharding-column: user_id
      database-count: 2
      table-count: 4
      algorithm: HASH
    - table: user
      sharding-column: id
      database-count: 2
      table-count: 2
      algorithm: RANGE
      range-rule: "0-10000:0,10000-20000:1"
```

### 3. 开启 LavShard

```java
@SpringBootApplication
@EnableLavShard
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 4. 编写业务代码（无感知）

```java
@Mapper
public interface OrderMapper {

    @Insert("INSERT INTO order(user_id, amount) VALUES(#{userId}, #{amount})")
    int insert(Order order);

    @Select("SELECT * FROM order WHERE user_id = #{userId} AND id = #{id}")
    Order selectByUserIdAndId(@Param("userId") Long userId, @Param("id") Long id);
}
```

**完成！** 所有 SQL 会自动路由到正确的库表：

- `user_id = 100`，`database-count = 2`，`table-count = 4`  
  → 路由到 `ds0.order_0`（Hash 后取模）
- `id = 15000`，`range-rule = "0-10000:0,10000-20000:1"`  
  → 路由到 `ds1.user_0`（范围匹配）

---

## 📚 使用文档

### 分片算法

LavShard 内置三种分片算法，也可以通过 SPI 自定义。

#### Hash 分片（默认）

```yaml
sharding-rules:
  - table: order
    sharding-column: user_id
    database-count: 2
    table-count: 4
    algorithm: HASH
```

路由公式：

```text
dbIndex  = hash(user_id) % database-count
tableIndex = hash(user_id) % table-count
```

#### Range 分片

```yaml
sharding-rules:
  - table: user
    sharding-column: id
    database-count: 2
    table-count: 2
    algorithm: RANGE
    range-rule: "0-10000:0,10000-20000:1"
```

#### 自定义算法

实现 `ShardAlgorithm` 接口：

```java
public class MyShardAlgorithm implements ShardAlgorithm {

    @Override
    public ShardResult shard(ShardKey key, ShardRule rule) {
        long value = Long.parseLong(key.getValue().toString());
        int dbIndex = (int) (value % rule.getDatabaseCount());
        int tableIndex = (int) (value % rule.getTableCount());
        return ShardResult.of(dbIndex, tableIndex);
    }

    @Override
    public String name() {
        return "MY_ALGO";
    }
}
```

注册到 Spring 容器即可：

```java
@Bean
public ShardAlgorithm myShardAlgorithm() {
    return new MyShardAlgorithm();
}
```

---

### 编程式路由

除了注解驱动，也支持编程式 API：

```java
// 绑定分片键到当前线程
LavShardContext.bind("user_id", 12345L);

try {
    Order order = orderMapper.selectById(1001);
    // 自动路由
} finally {
    LavShardContext.clear();
}
```

**⚠️ 注意：** 编程式绑定后，务必在 `finally` 中调用 `clear()`，避免线程污染。

---

### Actuator 端点

引入 `spring-boot-starter-actuator` 后，可访问：

```text
GET /actuator/lavshard
```

返回当前所有分片规则和状态：

```json
{
  "enabled": true,
  "rules": [
    {
      "table": "order",
      "shardingColumn": "user_id",
      "databaseCount": 2,
      "tableCount": 4,
      "algorithm": "HASH"
    }
  ],
  "datasources": ["ds0", "ds1"]
}
```

---

## 🧩 模块结构

```text
lavshard/
├── lavshard-core/                  # 核心路由引擎（无 Spring 依赖）
├── lavshard-spring-boot-starter/   # Spring Boot 自动配置
├── lavshard-example/               # 可运行示例
│   ├── lavshard-example-simple/
│   └── lavshard-example-multi-datasource/
├── lavshard-test/                  # 集成测试 + JMH 压测
└── docs/                           # 中英文文档
```

| 模块 | 说明 |
| :--- | :--- |
| `lavshard-core` | 分片算法、SQL 解析、路由计算、动态数据源 |
| `lavshard-spring-boot-starter` | `@EnableLavShard`、配置绑定、自动装配 |
| `lavshard-example-*` | 直接可运行的示例工程 |
| `lavshard-test` | Testcontainers 集成测试、JMH 性能基准 |

---

## 🔧 构建

### 环境要求

- JDK 17+
- Maven 3.8+
- MySQL 8.0+（示例用，可选 H2）

### 命令

```bash
# 克隆仓库
git clone https://github.com/lavyoung/lavshard.git
cd lavshard

# 全量构建（含测试）
mvn clean install

# 快速构建（跳过测试）
mvn clean install -Pfast

# 只构建 core
mvn clean install -pl lavshard-core -am

# 运行示例
cd lavshard-example/lavshard-example-simple
mvn spring-boot:run

# 发布到 Maven 中央仓库
mvn clean deploy -Prelease
```

---

## 🗺️ 路线图

- [x] **v0.1.0** — 基础路由能力（Hash 分片 + 动态数据源 + Starter）
- [ ] **v0.2.0** — Range 分片 + 自定义分片算法 SPI
- [ ] **v0.3.0** — 读写分离 + 弱 XA 事务
- [ ] **v0.4.0** — 数据脱敏 + 加解密
- [ ] **v1.0.0** — 生产级 GA，完整文档 + 压测报告

详见 [CHANGELOG.md](./CHANGELOG.md)

---

## 🤝 贡献

欢迎任何形式的贡献！请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md)。

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/amazing-feature`
3. 提交代码：`git commit -m 'feat: add amazing feature'`
4. 推送分支：`git push origin feature/amazing-feature`
5. 提交 Pull Request

**提交规范**（遵循 [Conventional Commits](https://www.conventionalcommits.org/)）：

- `feat:` 新功能
- `fix:` 修复 bug
- `docs:` 文档变更
- `refactor:` 重构
- `test:` 测试
- `chore:` 构建/工具变更

---

## 📄 许可证

本项目基于 [Apache License 2.0](./LICENSE) 发布。

```text
Copyright 2026 lavyoung

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

## 📮 联系

- **Author:** lavyoung
- **GitHub:** [@lavyoung](https://github.com/lavyoung)
- **Issues:** [github.com/lavyoung/lavshard/issues](https://github.com/lavyoung/lavshard/issues)
- **Discussions:** [github.com/lavyoung/lavshard/discussions](https://github.com/lavyoung/lavshard/discussions)

---

## 🙏 致谢

灵感来源于以下优秀开源项目：

- [Apache ShardingSphere](https://shardingsphere.apache.org/)
- [MyBatis-Plus](https://baomidou.com/)
- [Dynamic-Datasource](https://github.com/baomidou/dynamic-datasource)

---

<div align="center">

**⭐ 如果 LavShard 对你有帮助，欢迎给个 Star！**

Made with ❤️ by [lavyoung](https://github.com/lavyoung)

</div>