# 第五周 · 可观测 + 容错实现手册（照着敲）

> 目标：给项目补上生产化的最后半截——**出问题能看见（可观测）**、**模型挂了不崩（容错）**。
> 完成标准：`/actuator/metrics` 能看到 token / 耗时指标；故意停掉 Ollama 后问问题，返回兜底话术而不是报错页。
> 技术栈：Spring Boot 4.1.2 + Spring AI 2.0.2 + Java 17 + Ollama + PGVector（承接 W4）。
> 前置：W4 的 PGVector 已跑通（起库 → 启动 → 入库 → 重启不丢）。

---

## 0. 先想明白：为什么这两件事值钱（面试直接能讲）

**没有可观测**：用户抱怨"回答慢"，你只能翻聊天记录猜——是模型慢？RAG 检索慢？还是工具调用卡住？token 花在哪也不知道。Spring AI 2.0 用 Micrometer 在每次模型调用、向量检索、Advisor 上都埋了点，加一个 actuator 就能看到 `gen_ai.client.*` 指标。

**没有容错**：Ollama 一停、`classify()` 抛异常，异常直接甩给前端，整个对话 500。生产系统必须做到"依赖挂了，我能降级"——重试、熔断、兜底话术。

⚠️ **动手前备份**：`pom.xml`、`application.yml`、`ChatService.java` 各复制一份 `.bak`。

---

## 1. 改动地图（分两个阶段，先做完 A 再做 B）

| 阶段 | 文件 | 改什么 |
|---|---|---|
| **W5a 可观测** | `pom.xml` | 加 `spring-boot-starter-actuator` |
| | `application.yml` | 暴露 metrics 端点 + 开观测 + **关掉 prompt 日志** |
| **W5b 容错** | `pom.xml` | 加 `spring-boot-starter-aspectj` + `resilience4j-spring-boot4:2.4.0` |
| | `IntentClassifier.java`（**新建**） | 把意图识别抽成独立 Bean，加 `@Retry`/`@CircuitBreaker`/`@RateLimiter` |
| | `ChatService.java` | 注入新 Bean、删掉旧 `classify()`、给 Flux 加 `onErrorResume` 兜底 |
| | `application.yml` | 加 `resilience4j` 容错参数 |

---

# 第一部分 · W5a 可观测

## 2. Step 1：pom 加 actuator

打开 `pom.xml`，在 `<dependencies>` 里加（**不写 version**，由 Spring Boot parent 管理）：

```xml
<!-- 可观测：暴露 health / metrics 端点，Spring AI 的 gen_ai.* 指标靠它可见 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

> 可选：如果你后面要接 Prometheus + Grafana，再加 `io.micrometer:micrometer-registry-prometheus`（Boot BOM 管理，同样不写 version）。本周只看 actuator 的 JSON 端点就够，**先别加，减少变量**。

## 3. Step 2：application.yml 配观测（含一个合规开关）

在 `application.yml` 顶层加 `management:`，并在 `spring.ai.chat.client` 下加观测开关。改完关键部分：

```yaml
spring:
  ai:
    # ... 原有的 ollama / vectorstore 配置不动 ...
    chat:
      client:
        observations:
          log-prompt: false        # ★ 合规：不要把用户原文写进日志/链路
          log-completion: false    # 同理，回答原文也不记

management:
  observations:
    enabled: true                  # 开启 Micrometer 观测（生成 gen_ai.* 指标）
  endpoints:
    web:
      exposure:
        include: health,metrics    # 只暴露这两个端点，别用 '*'
  health:
    circuitbreakers:
      enabled: true                # 为 W5b 的熔断健康指示提前开好
```

🚨 **`log-prompt: false` 别漏**：Spring AI 默认可能把 prompt 记进观测。你是企业知识库助手，用户问题可能含敏感信息，生产规范明确要求 prompt 日志关闭或脱敏。这一行就是上线清单里的"日志脱敏"项。

> **属性名提醒**：观测相关的属性在 2.0.x 不同 minor 版本间可能微调。如果启动报未知属性，用 `--debug` 看配置报告，或在 IDE 里搜 `ChatClientProperties`，不要照抄别处的老配置。

## 4. Step 3：验证指标（先问问题，再看指标）

```bash
# 1) 编译
cd D:/Java/enterprise-kb-assistant
mvn -o -q -DskipTests compile

# 2) 启动（确保 Ollama 和 pgvector 都在跑）
mvn spring-boot:run

# 3) 先发一个问题，指标是"调用后才生成"的
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w5","message":"年假是怎么规定的？"}'

# 4) 列出所有指标，找 gen_ai.* 和 spring.ai.*
curl -s http://localhost:8080/actuator/metrics

# 5) 单独看 token 用量
curl -s http://localhost:8080/actuator/metrics/gen_ai.client.token.usage

# 6) 看模型调用耗时
curl -s http://localhost:8080/actuator/metrics/gen_ai.client.operation.duration

# 7) 健康检查
curl -s http://localhost:8080/actuator/health
```

**应该看到的关键指标**（名字记下来，面试能讲）：

| 指标名 | 含义 |
|---|---|
| `gen_ai.client.token.usage` | token 用量，带 `gen_ai_token_type=input/output/total` 标签 |
| `gen_ai.client.operation.duration` | 模型调用耗时（Histogram，可看 P95） |
| `spring.ai.chat.client` | ChatClient 端到端耗时（含 Advisor、RAG、工具链开销） |
| `spring.ai.advisor` | 每个 Advisor 的耗时 |
| `db.vector.client.operation` | 向量检索耗时 |

> **两个耗时不一样是正常的**：`gen_ai.client.operation` 只算模型调用；`spring.ai.chat.client` 还包含 RAG 检索、工具调用、Advisor 链的开销。面试官问"怎么定位慢在哪"，这就是答案——对比这两个值。

> **看不到指标？** ① 先发请求再查（很多 meter 调用后才注册）；② 确认 `management.observations.enabled=true`；③ 你的 `ChatClient` 是通过 `AiConfig` 里自动装配的 Builder 构建的，观测会生效——**千万别改成 `ChatClient.create(chatModel)`**，那样会绕过自动装配、丢掉全部观测。

## 5. 加分项（可选）：链路追踪看单次请求

如果想看"这一次请求，每一步各花了多久"，加 Micrometer Tracing + Zipkin：

```xml
<!-- 可选：链路追踪 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>  <!-- 已加，不用重复 -->
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>io.zipkin.reporter2</groupId>
    <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```

```yaml
management:
  tracing:
    sampling:
      probability: 1.0    # 开发期全采样，生产调低
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

配 Zipkin（`docker run -d -p 9411:9411 openzipkin/zipkin`）后，发个请求去 `http://localhost:9411` 就能看到一次问答里"检索 → 模型 → 工具"的时间线。**时间紧可以先跳过，本周核心是指标 + 容错。**

---

# 第二部分 · W5b 容错

## 6. Step 4：pom 加 AOP 和 Resilience4j（⚠️ 全程最大的版本坑）

```xml
<!-- AOP：Resilience4j 的 @Retry/@CircuitBreaker 注解靠切面实现，没有它注解静默失效 -->
<!-- ⚠️ Boot 4.x 已把 starter-aop 改名为 starter-aspectj，写 aop 会报缺 version -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aspectj</artifactId>
</dependency>

<!-- 容错核心：Boot 4.x 专用模块，必须手写 version -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot4</artifactId>
    <version>2.4.0</version>
</dependency>
```

🚨 **三个必须讲清的点**：

1. **模块名是 `resilience4j-spring-boot4`，不是 `-spring-boot3`**。Resilience4j 从 2.4.0 起为 Boot 4.x 单独拆了 `boot4` 模块；`boot3` 模块在 Boot 4 上会**启动即失败**，明确提示你换模块。网上 90% 的教程写的都是 `boot3`，照抄必踩。
2. **这里必须写 `<version>2.4.0</version>`**，和 Spring AI starter 的规则相反。原因：你的版本由 `spring-ai-bom`（管 AI 依赖）和 Spring Boot parent（管大部分库）管理，但我核对过 **Boot 4.1.2 的 BOM 里没有 Resilience4j 条目**，所以只能手写。写完后首次编译要联网下载一次。
3. **`spring-boot-starter-aspectj` 不能省**。Resilience4j 注解是 AOP 切面，没有 AOP 依赖时注解**不报错但也不生效**——这是最难查的一类问题。⚠️ 注意 Boot 4.x 已把这个 starter 从 `spring-boot-starter-aop` 改名为 `spring-boot-starter-aspectj`，照抄老教程写 `aop` 会直接报 `dependencies.dependency.version is missing`。

> 想把熔断/限流指标也送进 `/actuator/metrics`：Boot4 starter 通常已带 Micrometer 集成，先验证；看不到 `resilience4j.*` 指标再补 `io.github.resilience4j:resilience4j-micrometer:2.4.0`。

## 7. Step 5：关键设计——注解为什么不能直接加在 `classify()` 上

这一节是本周最值钱的思考，**面试能讲出"AOP 代理"就赢了**。

先看你现在 `ChatService` 里的调用方式：

```java
public Flux<String> chat(String sessionId, String message) {
    QueryIntent intent = classify(message);   // ← 自己调自己的方法
    ...
}
public QueryIntent classify(String message) { ... }
```

如果你兴冲冲地在 `classify()` 上贴 `@Retry`，结果发现**完全不生效**。两个原因：

1. **AOP 自调用陷阱**：Spring 的声明式容错靠**代理对象**实现。Controller 调 `chatService.chat()` 时经过代理，注解生效；但 `chat()` 内部用 `this.classify()` 调同类方法，走的是目标对象本身，**绕过代理**，切面根本没机会介入。
2. **响应式陷阱**：`chat()` 返回的是 `Flux<String>`，是**惰性流**——方法调用那一刻只是"组装管道"，真正的模型请求发生在 Controller `.subscribe()` 时。同步注解只能包裹"返回 Flux"这一瞬间，管不到后面 Flux 里发射的错误。

**解法**（两手都要）：

- 把 `classify()` 抽成**独立的 Bean** `IntentClassifier`，由 `ChatService` 注入调用 → 跨 Bean 调用经过代理，注解生效；
- 流式主链路的降级用响应式的 `onErrorResume` 处理，而不是注解。

## 8. Step 6：新建 `IntentClassifier.java`

在 `src/main/java/com/example/kb/service/` 下新建文件，逐段敲：

**① 先写包、import、类骨架**——Think：为什么要 `@Component`？因为要让 Spring 管理它、生成代理。

```java
package com.example.kb.service;

import com.example.kb.model.QueryIntent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class IntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);
    private final ChatClient chatClient;

    public IntentClassifier(ChatClient chatClient) {
        this.chatClient = chatClient;
    }
```

**② 把原来 `ChatService.classify()` 的逻辑搬过来**，加上三个容错注解：

```java
    @Retry(name = "intentClassifier", fallbackMethod = "classifyFallback")
    @CircuitBreaker(name = "intentClassifier", fallbackMethod = "classifyFallback")
    @RateLimiter(name = "intentClassifier", fallbackMethod = "classifyFallback")
    public QueryIntent classify(String message) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        请判断下面用户问题的意图类别，并简述理由。
                        意图只能是以下四者之一：
                        - KNOWLEDGE：询问公司制度、流程、产品、政策等知识库内容
                        - HR：查询某个员工的人事信息（如工号、部门、年假）
                        - ORDER：根据订单号查询某个订单的状态、金额、物流等实时订单信息
                        - CHITCHAT：闲聊、问候或无明确业务诉求
                        问题：{question}
                        """).param("question", message))
                .call()
                .entity(QueryIntent.class);
    }
```

- `@Retry`：模型偶发抖动时重试（配在 yml，最多 3 次、指数退避）。
- `@CircuitBreaker`：连续失败达到阈值就"跳闸"，短时间内直接走兜底，不再打爆已经挂掉的 Ollama。
- `@RateLimiter`：限制意图识别的调用频率，防刷（教学演示用；生产限流一般放网关）。
- 三个注解用**同一个实例名 `intentClassifier`**，共用下面 yml 里的一组配置。

**③ 写兜底方法**——签名 = 原方法参数 + 末尾一个 `Throwable`，一个字都不能错：

```java
    // 兜底：意图识别失败就降级为闲聊，让对话流程继续走下去
    public QueryIntent classifyFallback(String message, Throwable t) {
        log.warn("意图识别失败，降级为 CHITCHAT：{}", t.getMessage());
        return new QueryIntent("CHITCHAT", message);
    }
}
```

> **为什么兜底成 CHITCHAT 而不是直接报错**？意图识别只是"决定走哪条路"，失败后降级为纯对话，至少用户还能聊；真正模型不可用时，下一步的 `onErrorResume` 会给最终兜底话术。这是两层防护。

## 9. Step 7：改 `ChatService.java`（三处）

**① 加 import**（Slf4j 日志 + 新 Bean）：

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

**② 加字段 + 构造器注入**：把 `IntentClassifier` 加进构造参数并赋值；同时删掉旧的 `public QueryIntent classify(...)` 整个方法。

```java
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final HrTools hrTools;
    private final OrderTools orderTools;
    private final IntentClassifier intentClassifier;   // ← 新增
    private final int topk;
    private final double similarityThreshold;

    public ChatService(ChatClient chatClient, ChatMemory chatMemory, VectorStore vectorStore,
                       HrTools hrTools, OrderTools orderTools, IntentClassifier intentClassifier,
                       @Value("${app.knowledge.top-k:4}") int topk,
                       @Value("${app.knowledge.similarity-threshold:0.55}") double similarityThreshold) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.hrTools = hrTools;
        this.orderTools = orderTools;
        this.intentClassifier = intentClassifier;      // ← 新增
        this.topk = topk;
        this.similarityThreshold = similarityThreshold;
    }
```

**③ `chat()` 里改调用 + 给流式链路加兜底**：

```java
    public Flux<String> chat(String sessionId, String message) {
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        QueryIntent intent = intentClassifier.classify(message);   // ← 改成调用 Bean（经过代理）
        var spec = chatClient.prompt()
                .advisors(a -> a.param(chatMemory.CONVERSATION_ID, sessionId))
                .advisors(memoryAdvisor)
                .user(message);

        if (intent.is("KNOWLEDGE")) {
            QuestionAnswerAdvisor qsAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder().topK(topk)
                            .similarityThreshold(similarityThreshold).build())
                    .build();
            spec = spec.advisors(qsAdvisor);
        } else if (intent.is("HR")) {
            spec = spec.tools(hrTools);
        } else if (intent.is("ORDER")) {
            spec = spec.tools(orderTools);
        }

        // ★ 流式链路的兜底：模型报错时返回固定话术，而不是把异常甩给前端
        return spec.stream().content()
                .onErrorResume(e -> {
                    log.warn("对话生成失败，返回兜底话术：{}", e.getMessage());
                    return Flux.just("抱歉，服务暂时不可用，请稍后再试。");
                });
    }
}
```

> `onErrorResume` 是 Reactor 的标准降级姿势——**流式中止时切换到一条备用流**。注意别把 `e.getMessage()` 里的用户原文记进日志（配合 W5a 的合规要求）。

## 10. Step 8：application.yml 加容错参数

在文件末尾追加（和 `spring:`、`management:` 平级）：

```yaml
resilience4j:
  retry:
    instances:
      intentClassifier:
        max-attempts: 3                  # 含首次，共 3 次
        wait-duration: 500ms
        enable-exponential-backoff: true
        exponential-backoff-multiplier: 2
        retry-exceptions:                # 只对网络/超时类异常重试，业务异常不重试
          - java.io.IOException
          - java.net.SocketTimeoutException
          - org.springframework.web.client.ResourceAccessException
  circuitbreaker:
    instances:
      intentClassifier:
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10          # 看最近 10 次
        minimum-number-of-calls: 5       # 至少 5 次才开始算失败率
        failure-rate-threshold: 50       # 失败率 ≥50% 就跳闸
        wait-duration-in-open-state: 10s # 跳闸后 10 秒进半开
        permitted-number-of-calls-in-half-open-state: 3
        register-health-indicator: true  # 配合 management.health.circuitbreakers
  ratelimiter:
    instances:
      intentClassifier:
        limit-for-period: 10             # 每 1 分钟最多 10 次
        limit-refresh-period: 1m
        timeout-duration: 0              # 超限立即失败（走 fallback），不排队
```

> `retry-exceptions` 的列法：只对"网络抖动、超时"这类**瞬态故障**重试；参数错误之类的业务异常重试也没用，还会放大流量。具体异常类名以你实际日志为准，上面三个是 Ollama 连不上时常见的。

---

## 11. 验证清单

### 11.1 编译与启动
```bash
mvn -o -q -DskipTests compile       # 首次需联网下载 resilience4j，失败就去掉 -o
mvn spring-boot:run
```
无 `APPLICATION FAILED TO START`、无属性绑定错误即通过。

### 11.2 正常路径回归（先确认没改坏）
```bash
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w5","message":"年假是怎么规定的？"}'
```
仍能基于知识库回答 → 路由没被容错改造破坏。

### 11.3 可观测验证
```bash
curl -s http://localhost:8080/actuator/metrics | grep -o '"name":"[^"]*"' | grep -E 'gen_ai|spring.ai|resilience4j'
```
能看到 `gen_ai.client.token.usage`、`gen_ai.client.operation.duration` 等即通过。

### 11.4 容错验证（关键）——故意让 Ollama 挂掉

**不要真去停服务**，改配置最安全：

1. 停应用，把 `application.yml` 里 `spring.ai.ollama.base-url` 临时改成 `http://localhost:11435`（一个不存在的端口）；
2. 重启应用；
3. 问一个问题：
   ```bash
   curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"sessionId":"w5-fail","message":"年假怎么规定的？"}'
   ```
4. **预期结果**：
   - 日志里看到重试 warn（最多 3 次）→ `意图识别失败，降级为 CHITCHAT` → `对话生成失败，返回兜底话术`；
   - 前端收到 `抱歉，服务暂时不可用，请稍后再试。`，**而不是报错或空白**；
   - 连续问十几次触发熔断后，`curl -s http://localhost:8080/actuator/health` 里能看到 circuitBreakers 状态变化。
5. 验证完把 `base-url` 改回 `http://localhost:11434`。

> 这个"故意注入故障 → 观察降级"的演练，就是面试里"你怎么验证容错生效"的标准答案。

---

## 12. 面试怎么讲（背下来）

> "生产化我先解决了可观测：加 actuator，利用 Spring AI 2.0 内置的 Micrometer 埋点，拿到 token 用量、模型耗时和 ChatClient 端到端耗时。对比这两个耗时就能定位慢在模型还是 RAG 链路，并且我按规范关掉了 prompt 日志防敏感信息泄露。
>
> 容错这块踩了个印象最深的坑：我一开始把 `@Retry` 直接加在 `classify()` 上，发现不生效。原因是 Spring AOP 靠代理，而 `chat()` 内部 `this.classify()` 是自调用，绕过了代理；而且 `chat()` 返回 Flux，是惰性流，同步注解管不到订阅后才发生的错误。于是我把意图识别抽成独立 Bean 让注解生效，流式链路用 `onErrorResume` 做降级。熔断器在依赖连续失败时跳闸，避免打爆已经挂掉的模型，半开状态会放少量试探请求自动恢复。我通过把 Ollama 地址改错来注入故障，验证了重试、降级和熔断的完整链路。"

三个必答题：
- **为什么 scope 要抽 Bean**：AOP 自调用绕过代理，注解失效；跨 Bean 调用才经过代理。
- **Retry / CircuitBreaker / RateLimiter 各管什么**：重试治偶发抖动；熔断防止雪崩、给下游恢复时间；限流防刷。
- **为什么流式不能用注解**：Flux 惰性执行，错误发生在订阅后，要用 `onErrorResume` / `retryWhen` 这类响应式算子。

---

## 13. 完成标准（对照打勾）

- [ ] pom 加了 actuator + aop + `resilience4j-spring-boot4:2.4.0`（手写 version）
- [ ] `log-prompt: false` 已配（prompt 日志关闭）
- [ ] `/actuator/metrics` 能看到 `gen_ai.client.token.usage` 和 `gen_ai.client.operation.duration`
- [ ] 新建了 `IntentClassifier` Bean，三个容错注解都在、fallback 签名正确
- [ ] `ChatService` 删掉旧 `classify()`、改为注入 Bean、Flux 加了 `onErrorResume`
- [ ] 故意改错 Ollama 地址后，前端收到兜底话术而不是报错
- [ ] 能脱稿讲清"AOP 自调用陷阱 + 流式注解失效 + 熔断三态"

---

## 14. 做完这周，下一步预告

到这里，阶段三（生产化改造：PGVector + 可观测 + 容错）就**全部完成**了。之后两条路：

- **面试冲刺**：把 W2（改需求）+ W4（持久化迁移）+ W5（可观测/容错）整理成 STAR 讲稿，配一份 README 架构图。
- **加分实验**：做一次"同一份代码切 Milvus"——业务代码不动，只换 starter + yml，凑出"两种向量库都接过"的讲法。

这两块我会单独出材料。先把 W5 的故障注入验证跑通、讲清楚，再往下走。
