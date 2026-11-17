# 第四周 · 生产化改造实现手册（照着敲）

> 目标：把内存向量库 `SimpleVectorStore` 换成持久化的 **PGVector**（Postgres + pgvector 扩展），做到"应用重启后知识库不丢"。
> 完成标准：重启应用后，知识库问答依然能命中，且数据确实落在 Postgres 里。
> 技术栈：Spring Boot 4.1.2 + Spring AI 2.0.2 + Java 17 + Ollama（qwen2.5:7b / nomic-embed-text）。

---

## 0. 先想明白：为什么要做这一步（面试直接能讲）

你现在的 `AiConfig` 里是这么建的库：

```java
return SimpleVectorStore.builder(embeddingModel).build();
```

`SimpleVectorStore` 把向量存在 **JVM 内存**里。这意味着三件事，件件都是生产事故：

1. **重启即丢** —— 应用一停，几万条文档的向量全没了，下次启动得重新入库。
2. **无法多实例共享** —— 你跑两个副本，各存各的，用户问 A 命中、问 B 落空。
3. **无索引、无检索优化** —— 全表线性扫描，数据一多就慢。

PGVector 把向量存进 Postgres，天然具备持久化、事务、备份、以及 HNSW/IVFFlat 近似最近邻索引。**换它只需要动配置和一个 Bean，业务代码一行不改**——这正是"好架构"的体现，也是本项目最值钱的面试卖点。

⚠️ **动手前先备份**：把 `pom.xml`、`application.yml`、`AiConfig.java`、`docker-compose.yml` 各复制一份 `.bak`，改崩了能退回来。

---

## 1. 改动地图（本周只碰这 4 个文件）

| 文件 | 改什么 | 为什么 |
|---|---|---|
| `docker-compose.yml` | 加一个 `pgvector` 服务 | 起数据库 |
| `pom.xml` | 加 pgvector starter 依赖 | 拿到自动装配的 `PgVectorStore` |
| `application.yml` | 加 datasource + pgvector 配置 | 连库、定维度/索引 |
| `AiConfig.java` | **删掉**手搓的 `vectorStore()` Bean | 否则和自动装配的 Bean 冲突 |

`ChatService` / `DocumentIngestionService` / 各种 Controller —— **全部不动**。

---

## 2. Step 1：docker-compose 加 pgvector 服务

打开工程根目录的 `docker-compose.yml`，在 `services:` 下加一段 `pgvector`，并让 `app` 等它、把数据库连接地址传进容器。改完长这样（可直接对照覆盖）：

```yaml
services:
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
    command: serve
    healthcheck:
      test: ["CMD", "ollama", "list"]
      interval: 10s
      timeout: 5s
      retries: 10

  pgvector:
    image: pgvector/pgvector:pg16        # 已预装 vector 扩展的 Postgres 16
    environment:
      POSTGRES_DB: kbdb
      POSTGRES_USER: kbuser
      POSTGRES_PASSWORD: kbpass
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:                          # 等数据库真能连上再放行
      test: ["CMD-SHELL", "pg_isready -U kbuser -d kbdb"]
      interval: 10s
      timeout: 5s
      retries: 10

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
      - SPRING_DATASOURCE_URL=jdbc:postgresql://pgvector:5432/kbdb   # ← 容器里用服务名
    depends_on:
      ollama:
        condition: service_healthy
      pgvector:
        condition: service_healthy

volumes:
  ollama-data:
  pgdata:                                 # ← 数据卷：数据库重启/重建容器都不丢
```

**要点（面试能讲）**：
- 容器之间访问要用 **compose 服务名 `pgvector`**，不是 `localhost`（`localhost` 是容器自己）。这和之前 ollama 的道理完全一样。
- `pgdata` 是**命名数据卷**——就算你 `docker compose down` 再 up，数据库文件还在。这就是持久化的物理载体。
- `pg_isready` 健康检查确保数据库真正可用后 app 才启动，避免 app 启动时连不上库直接崩。

---

## 3. Step 2：pom 换依赖

打开 `pom.xml`，在 `<dependencies>` 里**新增**这一段（放在原 `spring-ai-vector-store` 依赖附近）：

```xml
<!-- PGVector 向量库：自动装配 PgVectorStore，替换内存版 SimpleVectorStore -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
```

**注意**：
- **不要写 `<version>`** —— Spring AI BOM（你 pom 里的 `${spring-ai.version}` = 2.0.2）统一管理版本。手写版本是新手常犯的错。
- 原有的 `spring-ai-vector-store` 依赖**可以留着**（pgvector starter 会传递依赖它，留着无害），先别删，减少变量。
- 如果启动时报 `ClassNotFoundException: org.postgresql.Driver`，再显式补两个依赖（一般 starter 会带，报了再加）：
  ```xml
  <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
  </dependency>
  <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
  </dependency>
  ```

---

## 4. Step 3：application.yml 加配置（⚠️ 有一个必踩的坑）

打开 `src/main/resources/application.yml`，在 `spring:` 下补 `datasource`，在 `spring.ai` 下补 `vectorstore.pgvector`。改完关键部分如下：

```yaml
spring:
  application:
    name: enterprise-kb-assistant
  datasource:                            # ← 新增：数据库连接
    url: jdbc:postgresql://localhost:5432/kbdb   # 本机跑用 localhost；容器里由环境变量覆盖成 pgvector
    username: kbuser
    password: kbpass
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:7b
          temperature: 0.7
      embedding:
        model: nomic-embed-text
    vectorstore:                         # ← 新增：PGVector 配置
      pgvector:
        initialize-schema: true          # 开发期自动建表；生产改 false，交给 Flyway/Liquibase
        index-type: HNSW                 # 近似最近邻索引：HNSW 查询快、IVFFlat 省内存
        distance-type: COSINE_DISTANCE   # 余弦距离
        dimensions: 768                  # ★ 关键！必须等于 embedding 模型维度

app:
  knowledge:
    auto-ingest: true
    top-k: 4
    similarity-threshold: 0.55
```

🚨 **最大的坑：`dimensions: 768`**
- 你的 embedding 模型是 **nomic-embed-text，输出 768 维向量**。
- 网上/官方文档示例常写 `1536`（那是 OpenAI `text-embedding-3-small` 的维度），照抄必炸。
- **维度不匹配不会在启动时报错，而是文档入库写入时才抛异常**，报错信息还很绕，极难排查。
- 结论：**索引维度永远等于你实际用的 embedding 模型维度**。换个 embedding 模型，这里就要跟着改。

> `initialize-schema: true` 只在开发环境用。生产环境表结构应由 Flyway/Liquibase 管理，避免应用启动时有建表权限这种安全隐患——这点记下来，面试会问。

---

## 5. Step 4：AiConfig 删掉手搓的 VectorStore Bean

打开 `src/main/java/com/example/kb/config/AiConfig.java`。

**为什么必须删**：加了 pgvector starter 后，Spring AI 会自动装配一个 `PgVectorStore` Bean（类型就是 `VectorStore`）。如果你原来的手搓 Bean 还在，容器里就有**两个 `VectorStore` Bean**，`DocumentIngestionService` 和 `ChatService` 构造器注入时会报 `NoUniqueBeanDefinitionException`（容器不知道该给你哪个）——这是"编译通过但启动失败"的典型。

把这段**删掉**：
```java
    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();   // ← 整个方法删除
    }
```

同时把头部这两个不再用到的 import 删掉（IDE 会标灰）：
```java
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
```

改完后 `AiConfig.java` 只剩 `chatClient` 一个 Bean：
```java
package com.example.kb.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("你是企业智能知识库助手，服务于公司内部员工。"
                        + "回答应当专业、简洁、基于已知信息；"
                        + "如果知识库中没有相关内容，请如实说明，不要编造。")
                .build();
    }
}
```

`VectorStore` 现在由 starter 自动提供，注入照旧生效——**业务代码果然一行没改**。

---

## 6. Step 5：理解并绕过"重启重复入库"陷阱

这是个**新的、真实的问题**，务必搞懂（面试加分）：

- 内存版 `SimpleVectorStore` 重启就空了，所以 `auto-ingest: true` 每次启动重新入库没问题。
- PGVector 是持久化的，`auto-ingest: true` 每次启动**又 add 一遍**，数据会**不断累积重复**。

所以验证"重启不丢"要用**正确的姿势**，别一上来就踩这个坑：

1. **第一次启动**：保持 `auto-ingest: true`，应用启动会自动把 `knowledge/*.txt` 入库。
2. 确认能问答命中后，**把 `auto-ingest` 改成 `false`**（或删掉自动入库）。
3. **重启应用**：此时不会重新入库。
4. 再问同样的问题——**还能答出来** → 说明数据真的从 Postgres 读出来了，而不是内存重建的。这就证明了"重启不丢"。

> 进阶（想做得更规范）：在 `DocumentIngestionService.ingest()` 里，写入前先按 `source` 删除旧数据再 add，做成幂等。可用 `vectorStore.delete(FilterExpression)`。这个留作完成主流程后的加分练习。

---

## 7. 验证清单

### 7.1 编译（先把飘红清掉）
```bash
cd D:/Java/enterprise-kb-assistant
mvn -o -q -DskipTests compile
```

### 7.2 起数据库
```bash
docker compose up -d pgvector
docker ps                      # 确认 pgvector 容器 Up (healthy)
```

### 7.3 本机启动应用（第一次，auto-ingest=true）
```bash
mvn spring-boot:run
```
启动日志应看到 `[KB] 启动自动入库完成，共写入 N 个文档片段`，且**没有** `NoUniqueBeanDefinitionException`、没有维度相关异常。

### 7.4 直接查库，确认数据真的落进 Postgres
```bash
docker exec -it <pgvector容器名> psql -U kbuser -d kbdb -c "select count(*) from vector_store;"
```
返回条数 > 0，说明向量已落库。（容器名用 `docker ps` 查，一般是 `enterprise-kb-assistant-pgvector-1`）

### 7.5 问答命中
```bash
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w4","message":"年假是怎么规定的？"}'
```
应基于 `hr-policy.txt` 回答，不是瞎编。

### 7.6 验证"重启不丢"（关键）
```bash
# 1) 停应用，把 application.yml 的 auto-ingest 改成 false
# 2) 重新启动
mvn spring-boot:run
# 3) 再问同样问题
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w4","message":"年假是怎么规定的？"}'
```
依然能基于文档回答 → ✅ 持久化成功。此时内存是空的，数据只可能来自 Postgres。

---

## 8. 面试怎么讲这个项目（背下来）

> "我这个项目一开始用的是 `SimpleVectorStore`，向量存在内存里，重启就丢、多实例不能共享。生产化改造时我换成了 PGVector——底层是 Postgres 加 pgvector 扩展，用 HNSW 索引做近似最近邻检索。整个改造只换了依赖、配置和 `AiConfig` 里的一个 Bean，业务代码一行没动，体现了接口抽象的价值。踩过的坑是**向量维度必须和 embedding 模型对齐**：我用 nomic-embed-text 是 768 维，照抄示例的 1536 会在写入时才报错，很难查。另外持久化后要注意入库幂等，否则重启会重复写数据。"

三个必答题：
- **内存向量库为何不能上生产**：重启丢、不能共享、无索引性能差、无备份。
- **HNSW vs IVFFlat**：HNSW 查询快、内存占用高、无需训练数据；IVFFlat 内存省但需先用数据训练、召回略低。小数据量差别不大。
- **为什么维度必须匹配**：向量距离计算要求两向量同维，embedding 模型输出维度固定，索引维度必须与之一致，否则写入/检索报错。

---

## 9. 完成标准（对照 LEARNING_PLAN 打勾）

- [ ] docker-compose 加了 pgvector 服务，`docker ps` 显示 healthy
- [ ] pom 加了 pgvector starter（未手写 version）
- [ ] application.yml 配好 datasource + pgvector（`dimensions: 768`）
- [ ] AiConfig 删掉了手搓的 VectorStore Bean，启动无 Bean 冲突
- [ ] 查库 `vector_store` 表有数据
- [ ] 关掉 auto-ingest 重启后，问答依然命中 → 持久化验证通过
- [ ] 能脱稿讲清"为何换 PGVector + 维度坑 + 索引选型"

---

## 10. 做完这周，下一步预告

- **W5a 可观测**：加 actuator + Micrometer，看每次调用的 token / 耗时 / 命中哪个 advisor。
- **W5b 容错**：Resilience4j 做重试、降级兜底话术、限流。
- 这两步会单独出手册，先把 W4 的持久化彻底跑通、讲清楚再往下走。
