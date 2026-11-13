# 从零搭建企业知识库问答助手 · 全过程手敲教程（程序员视角：逐类现写现长版）

> 配套完整代码已在本目录（`enterprise-kb-assistant`）。本教程**模拟一个程序员从零写这个项目的真实过程**：
> 每写一个类，我都先想清楚"这个类要干什么"，再**从空壳开始一笔一笔加代码**，
> 写到哪一步缺什么就补什么（包括 import 是 IDE 飘红后才补的），每笔都讲"此刻为什么这么写"。
>
> 你照着这个节奏敲，就等于在脑子里重放了一遍"老手写代码"的思考顺序，比看现成代码记得牢得多。
>
> **学习方法**：第 1 遍照敲跑通 → 第 2 遍关教程凭印象重写 → 第 3 遍改需求（把 HR 工具换成查订单）。能改需求才算真会。

---

## 先读：程序员写每个类的固定套路（贯穿全程）

不管写哪个类，老手的动作顺序几乎一样，记住这 5 步，后面每个类我都按它来：

1. **想职责**：这个类是"配置类 / 业务类 / 控制器 / 工具 / 数据模型"里的哪一种？它负责把哪件事干完？
2. **写壳子**：先写 `package` + 类签名 + 角色注解（比如 `@Service`）。这时候类里是空的。
3. **补依赖**：类要干活，得"用谁"？把要用的东西声明成 `private final 字段`，再用**构造器**接进来。
4. **写方法**：从最简单、最核心的那个方法写起。每写一个方法，用到的新类型就让 IDE 自动补 `import`（**import 永远是用到才加，不是预先列好的**）。
5. **编译验证**：`mvn compile`，飘红就修，绿了再往下写下一个类。

**关于包路径（写文件时目录要对）**：
```
com.example.kb
 ├─ config/     配置类（造 Bean，比如 ChatClient）
 ├─ service/    业务逻辑（编排、入库）
 ├─ tools/      工具（@Tool，给模型调用）
 ├─ controller/ REST 接口
 └─ model/      数据模型（record）
```

---

## 第 0 关：先搞懂"版本铁律"（最该刻进脑子的一关）

Spring AI 的坑 80% 来自版本不匹配。写第一行代码之前，先把这条约束链想明白：

```
Spring Boot 4.0.9+  ──►  只能用 Spring AI 2.0.x（退回 1.1.x 会报 NoSuchBeanDefinition）
        │
        └─►  Boot 4.1 最低要求 Java 17（兼容到 Java 27）→ 装 Java 17 就够了
```

**落地版本组合**（已验证可编译、可装配、本机端到端跑通）：

| 组件 | 版本 |
|---|---|
| Java | 17 |
| Spring Boot | 4.1.2 |
| Spring AI | 2.0.2（BOM 统一管理） |
| Ollama chat | `qwen2.5:7b` |
| Ollama embedding | `nomic-embed-text` |

**记忆点**：`Boot 4.x ↔ Spring AI 2.0 ↔ Java 17` 三者绑死。面试官问"为什么选这套"，就讲这层约束。
**本机前置**（只需一次）：`ollama pull qwen2.5:7b` 和 `ollama pull nomic-embed-text`。

---

## 第 1 关：建工程骨架（pom / yml / 启动类）

骨架不是"代码"，是"约定"。我先讲清楚**为什么选这些依赖**，再给文件。

### 1.1 `pom.xml` —— 先想"我要哪些能力，每个能力对应哪个起步依赖"

写 `pom.xml` 前，我在纸上列一遍本项目要的能力：
- 要能写 REST 接口、内嵌 Tomcat → `spring-boot-starter-web`
- 要连 Ollama 对话 + 向量化 → `spring-ai-starter-model-ollama`（这一个会自动配好 `ChatModel` 和 `EmbeddingModel`）
- 要多轮记忆 → `spring-ai-starter-model-chat-memory`（**不引这个就没有 `ChatMemory` Bean**）
- 要内存向量库 → `spring-ai-vector-store`（**核心模块**，提供 `SimpleVectorStore`）
- 要 RAG 检索 Advisor → `spring-ai-vector-store-advisor`（**和上一个不是同一个包**，提供 `QuestionAnswerAdvisor`）
- 要写测试 → `spring-boot-starter-test`

然后落文件（注意 AI 依赖统一交给 BOM 管版本，所以下面 AI 依赖**都不写 `<version>`**）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>enterprise-kb-assistant</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>enterprise-kb-assistant</name>
    <description>企业知识库问答助手 - Spring AI 简历项目</description>
    <properties>
        <java.version>17</java.version>
        <spring-ai.version>2.0.2</spring-ai.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-ollama</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-starter-model-chat-memory</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-vector-store</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-vector-store-advisor</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

**为什么这样写**：`<parent>` 继承 Boot 的版本与插件管理，**版本只在 parent 写一次（4.1.2）**；`<dependencyManagement>` 引入 `spring-ai-bom` 统一所有 `spring-ai-*` 版本；`<build>` 的 `spring-boot-maven-plugin` 让 `mvn package` 打出**可执行 jar**。

### 1.2 `application.yml` —— 把"模型名 / 地址 / 业务开关"写在这里

写配置前想：哪些东西以后可能改？模型名、Ollama 地址、RAG 的 topK 阈值——都放配置，别写死在代码里。

```yaml
server:
  port: 8080

spring:
  application:
    name: enterprise-kb-assistant
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen2.5:7b
          temperature: 0.7
      embedding:
        model: nomic-embed-text

app:
  knowledge:
    auto-ingest: true
    top-k: 4
    similarity-threshold: 0.55
```

**逐行决策点**：
- `spring.ai.ollama.chat.options.model`：**注意多一层 `.options`**——这是 2.0 的写法，模型名挂在这里。
- `embedding.model: nomic-embed-text`：**qwen2.5 不带 embedding 能力**，向量化必须用单独的嵌入模型。
- `app.knowledge.*`：这是**我们自定义的配置**（前缀 `app`），不是 Spring 自带的；后面用 `@Value("${app.knowledge.xxx}")` 读它。

### 1.3 启动类 —— 整个应用的入口，固定写法

**第 1 笔**：先写壳子。这个类的唯一职责就是"启动 Spring"。它要什么注解？要 `@SpringBootApplication`（它 = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` 三合一：① 本类是配置源；② 按 classpath 自动装配，比如看到 ollama starter 就建好 `ChatModel`；③ 扫描 `com.example.kb` 下所有带注解的类注册成 Bean）。

```java
package com.example.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KbAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(KbAssistantApplication.class, args);
    }
}
```

**为什么 `main` 就这么一行**：`SpringApplication.run(本类.class, args)` 是 Spring Boot 固定入口，全项目唯一。没有 `@SpringBootApplication`，你后面写的 Service/Controller/Tools 一个都不会被 Spring 发现。

---

## 第 2 关：写 AI 配置类 `AiConfig`（造两个最核心的 Bean）

**职责想清楚**：整个应用有两个"基础设施"必须先造好——① 对话入口 `ChatClient`；② 知识库 `VectorStore`。这俩是单例 Bean，放进一个 `@Configuration` 类最合适。

**第 1 笔**：写壳子 + 角色注解。
```java
package com.example.kb.config;

@Configuration
public class AiConfig {
}
```
> 为什么用 `@Configuration`？因为它专门用来"产出 Bean"——里面每个 `@Bean` 方法返回的对象会被放进 Spring 容器，别的地方就能注入使用。

**第 2 笔**：写第一个 Bean——`ChatClient`。我需要 `ChatClient.Builder`（Spring AI 自动提供的构建器），在它上面固化"系统人设"，再 `.build()`。
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
> 写道 `ChatClient` 时 IDE 飘红 → 按 `Alt+Enter` 补 `import org.springframework.ai.chat.client.ChatClient;`。
> 为什么不直接 `new ChatClient`？因为 Spring AI 给的是 `Builder`（prototype 作用域），用构建器固化默认值（这里固定系统人设），业务代码就**不用每次重复写人设**。

**第 3 笔**：写第二个 Bean——`VectorStore`。它需要嵌入模型来把文本变向量，而嵌入模型已由 ollama starter 自动装配。所以我**在方法参数里声明 `EmbeddingModel`，Spring 会按类型自动塞进来**。
```java
package com.example.kb.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
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

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```
> 新增的 import：`EmbeddingModel`、`SimpleVectorStore`、`VectorStore` —— 全是写这一步时才用到的。
> `VectorStore` 是接口，`SimpleVectorStore` 是它的**内存实现**（重启丢数据，生产可换 PGVector，接口不变）。
> 这一步体现了 Spring 最经典的"**按类型注入**"：方法参数写 `EmbeddingModel embeddingModel`，Spring 自动把 ollama starter 配好的实现类传进来，你根本不用 `new`。

---

## 第 3 关：写知识入库类 `DocumentIngestionService`（RAG 的"写"侧）

**职责想清楚**：RAG = 离线建库 + 在线检索。本类负责"写"：把 `resources/knowledge/*.txt` 读出来 → 切块 → 向量化 → 存进 `VectorStore`。并且我希望**应用一启动就自动建库一次**。

**第 1 笔**：壳子 + 角色注解 + 让它"启动后自动跑"。
```java
package com.example.kb.service;

import org.springframework.stereotype.Service;
import org.springframework.boot.CommandLineRunner;

@Service
public class DocumentIngestionService implements CommandLineRunner {
}
```
> 为什么 `@Service` 而不是 `@Component`？语义上它是"业务逻辑类"，用 `@Service` 让代码自解释，功能上二者等价。
> 为什么 `implements CommandLineRunner`？这个接口只有一个 `run()` 方法，Spring 启动完成后会自动调用它——正好拿来"启动即建库"。

**第 2 笔**：它需要 `VectorStore` 才能写库，还需要一个开关决定"是否自动入库"（读前面 yml 的 `app.knowledge.auto-ingest`）。用构造器接进来。
```java
@Service
public class DocumentIngestionService implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final boolean autoIngest;

    public DocumentIngestionService(VectorStore vectorStore,
                                    @Value("${app.knowledge.auto-ingest:true}") boolean autoIngest) {
        this.vectorStore = vectorStore;
        this.autoIngest = autoIngest;
    }
}
```
> `VectorStore` 飘红 → 补 import（来自 `org.springframework.ai.vectorstore`）。
> `@Value` 飘红 → 补 `import org.springframework.beans.factory.annotation.Value;`。
> `@Value("${app.knowledge.auto-ingest:true}")` 的意思：**去 yml 读这个键，读不到就用 `true`**。这就是为什么前面 yml 要写 `app.knowledge.*`。

**第 3 笔**：实现 `run()`——启动后若开关开着，就调 `ingest()` 建库。
```java
    @Override
    public void run(String... args) throws Exception {
        if (autoIngest) {
            int n = ingest("classpath:knowledge/*.txt");
            System.out.println("[KB] 启动自动入库完成，共写入 " + n + " 个文档片段。");
        }
    }
```
> 注意：`run` 是 `CommandLineRunner` 接口要求的方法，不实现会编译错。

**第 4 笔**：写核心方法 `ingest()`——RAG 的 ETL 三步（读 → 切 → 写）。一边写一边补 import。
```java
    public int ingest(String pattern) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(pattern);
        // ⚠️ 2.0 推荐 builder() 创建；new TokenTextSplitter() 已 deprecated
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> all = new ArrayList<>();
        for (Resource r : resources) {
            String text = FileCopyUtils.copyToString(
                    new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8));
            // 文件名可能为 null，先兜底避免 NPE
            String fileName = r.getFilename() == null ? "unknown" : r.getFilename();
            Map<String, Object> metadata = Map.of("source", fileName);
            // 2.0 推荐 Document.builder() 而非 new Document(text, metadata)
            Document doc = Document.builder().text(text).metadata(metadata).build();
            List<Document> chunks = splitter.split(doc);
            all.addAll(chunks);
        }
        vectorStore.add(all);
        return all.size();
    }
```
> 这一步会一次飘红一堆，逐个补 import：
> - `Document`、`TokenTextSplitter`（来自 `org.springframework.ai.document` / `...transformer.splitter`）——RAG 文档与切分工具
> - `Resource` / `PathMatchingResourcePatternResolver`（来自 `org.springframework.core.io`）——读 classpath 资源，`*` 通配
> - `FileCopyUtils`（来自 `org.springframework.util`）——把流读成字符串
> - `IOException`、`InputStreamReader`、`StandardCharsets`、`ArrayList`、`List`、`Map`（JDK 自带）
> **逻辑要点**：`Map.of("source", 文件名)` 给每段打 metadata（回答时可溯源"内容来自哪份文件"）；`vectorStore.add(all)` 内部会自动调用嵌入模型把文本变向量并存入内存索引。

**收尾**：别忘了类头还要补两个 import（`CommandLineRunner` 前面已加）。到此这个类完整了。

---

## 第 4 关：写工具类 `HrTools`（Agent 的"手"）

**职责想清楚**：我想让模型能"查员工信息"。做法是把一个普通 Java 类的方法用 `@Tool` 标出来——这等于给模型写了一份"说明书"，模型看说明决定何时调、传什么参。

**第 1 笔**：壳子 + 角色注解。工具类用最通用的 `@Component`（让 Spring 扫描管理，后面 `ChatService` 才能注入它）。
```java
package com.example.kb.tools;

import org.springframework.stereotype.Component;

@Component
public class HrTools {
}
```

**第 2 笔**：先准备"假数据"（真实项目这里换成调 HR 微服务）。用 `static final Map` 当内存数据库。
```java
@Component
public class HrTools {

    private static final Map<String, String> EMPLOYEES = Map.of(
            "张三", "工号 E1001，研发部，入职日期 2023-04-01",
            "李四", "工号 E1002，产品部，入职日期 2022-09-15",
            "王五", "工号 E1003，财务部，入职日期 2021-01-10");
    private static final Map<String, Integer> LEAVE_BALANCE = Map.of(
            "张三", 8, "李四", 3, "王五", 12);
}
```
> `Map` 飘红 → 补 `import java.util.Map;`。

**第 3 笔**：写第一个工具方法 `lookupEmployee`。**关键来了**——用 `@Tool(description=...)` 描述"这个工具干啥"，用 `@ToolParam(description=...)` 描述参数。
```java
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

@Component
public class HrTools {
    // ... 上面的 Map 省略 ...

    @Tool(description = "查询员工的部门、工号、入职日期等基本信息。参数使用员工姓名。")
    public String lookupEmployee(@ToolParam(description = "员工姓名，例如 张三") String name) {
        return EMPLOYEES.getOrDefault(name, "未找到员工：" + name);
    }
}
```
> 这两个注解飘红 → 补 `import org.springframework.ai.tool.annotation.Tool;` 和 `...ToolParam;`。
> **为什么 description 极其重要**：模型**只看这段描述**来决定"什么时候该调这个工具、传什么值"。写得越清楚调用越准。这是 Tool Calling 最核心的细节。

**第 4 笔**：写第二个工具 `checkLeaveBalance`，套路一模一样。
```java
    @Tool(description = "查询员工当前剩余年假天数。参数使用员工姓名。")
    public String checkLeaveBalance(@ToolParam(description = "员工姓名，例如 张三") String name) {
        int days = LEAVE_BALANCE.getOrDefault(name, 0);
        return name + " 当前剩余年假：" + days + " 天";
    }
```
> **写法要点**：工具方法内部就是普通 Java 代码，模型完全不碰你的实现，只决定"何时调、传什么参数"。把 `HrTools` 实例直接交给 `chatClient...tools(hrTools)`，Spring AI 会自动扫描 `@Tool` 生成回调，**不需要手写任何注册代码**。

---

## 第 5 关：写模型类（两个 `record`）

**职责想清楚**：① 前端 POST 来的请求体要有个载体；② 意图分类结果（结构化输出）也要有个载体。两者都用 `record`——它是 Java 16+ 的不可变数据载体，自动生成构造器、`getter`、`equals/hashCode`。

**第 1 笔**：请求体 `ChatRequest`。
```java
package com.example.kb.model;

public record ChatRequest(String sessionId, String message) {
}
```
> 没有任何 import——`record` 是 Java 17 原生语法。前端发来的 `{"sessionId":"..","message":".."}` 会被 Spring 自动反序列化成它，取值用 `request.message()`（字段名即 getter）。

**第 2 笔**：意图结果 `QueryIntent`。我额外加个 `is(name)` 方法方便路由判断。
```java
package com.example.kb.model;

public record QueryIntent(String intent, String reason) {
    public boolean is(String name) {
        return intent != null && intent.equalsIgnoreCase(name);
    }
}
```

---

## 第 6 关：写 `ChatService`（全项目大脑，最核心）

**职责想清楚**：这是把一切串起来的地方。一个 `/chat` 请求进来，它要：① 判断意图（结构化输出）；② 按意图路由——知识问题走 RAG、查人走工具、闲聊直接答；③ 带上多轮记忆；④ 流式返回。

**第 1 笔**：壳子 + 角色注解。
```java
package com.example.kb.service;

import org.springframework.stereotype.Service;

@Service
public class ChatService {
}
```

**第 2 笔**：想"它要指挥哪些部件？"——`ChatClient`（对话）、`ChatMemory`（记忆）、`VectorStore`（知识库）、`HrTools`（工具），再加两个 RAG 阈值配置。全部用构造器接进来。
```java
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final HrTools hrTools;
    private final int topK;
    private final double similarityThreshold;

    public ChatService(ChatClient chatClient, ChatMemory chatMemory, VectorStore vectorStore,
                       HrTools hrTools,
                       @Value("${app.knowledge.top-k:4}") int topK,
                       @Value("${app.knowledge.similarity-threshold:0.55}") double similarityThreshold) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.hrTools = hrTools;
        this.topK = topK;
        this.similarityThreshold = similarityThreshold;
    }
}
```
> 飘红补 import：`ChatClient`、`ChatMemory`（来自 `org.springframework.ai.chat.memory`）、`VectorStore`、`HrTools`（本项目自己的包）、`@Value`。

**第 3 笔**：先写最简单的核心方法 `classify()`——意图分类（结构化输出）。让模型把回答映射成 `QueryIntent` 这个 record。
```java
    public QueryIntent classify(String message) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        请判断下面用户问题的意图类别，并简述理由。
                        意图只能是以下三者之一：
                        - KNOWLEDGE：询问公司制度、流程、产品、政策等知识库内容
                        - HR：查询某个员工的人事信息（如工号、部门、年假）
                        - CHITCHAT：闲聊、问候或无明确业务诉求
                        问题：{question}
                        """).param("question", message))
                .call()
                .entity(QueryIntent.class);
    }
```
> 飘红补 import：`QueryIntent`（本项目 model 包）。
> **灵魂写法 ① 结构化输出**：`.call().entity(QueryIntent.class)` 让模型把自由文本回答**直接映射成强类型对象**。提示词里的 `{question}` 用 `.param("question", message)` 填值（避免字符串拼接、防注入）。

**第 4 笔**：写 `chat()`——路由 + 记忆 + 流式。这是全类最复杂的方法，我分步加。
先写骨架：建记忆 Advisor、分类、起一个 prompt 构建器（`var spec` 承接，因为 `ChatClient.RequestSpec` 不是公开类型，写死会编译错——这是前面踩过的坑）。
```java
    public Flux<String> chat(String sessionId, String message) {
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory).build();
        QueryIntent intent = classify(message);

        var spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .advisors(memoryAdvisor)
                .user(message);

        return spec.stream().content();
    }
```
> 飘红补 import：`MessageChatMemoryAdvisor`、`QuestionAnswerAdvisor`（来自 `org.springframework.ai.chat.client.advisor` 和 `...advisor.vectorstore`）、`SearchRequest`、`Flux`（来自 `reactor.core.publisher`，Spring AI 自带不用额外引）。
> **灵魂写法 ④ 流式**：`.stream().content()` 返回 `Flux<String>`，逐字流。
> **灵魂写法 ③ 记忆**：`MessageChatMemoryAdvisor` + `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`，用 sessionId 隔离不同会话上下文。

**第 5 笔**：现在加"路由"——在 `return` 之前，根据意图给 `spec` 挂不同的能力。
```java
        if (intent.is("KNOWLEDGE")) {
            QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(topK).similarityThreshold(similarityThreshold).build())
                    .build();
            spec = spec.advisors(qaAdvisor);
        } else if (intent.is("HR")) {
            spec = spec.tools(hrTools);
        }
        return spec.stream().content();
```
> **灵魂写法 ② RAG 路由**：`KNOWLEDGE` → 挂 `QuestionAnswerAdvisor`（它内部会先去 `VectorStore` 检索相关片段，再带着上下文提问）；`HR` → 挂 `tools(hrTools)` 让模型能调工具；`CHITCHAT` → 什么都不挂，纯对话。

最终这个类的完整形态就是上面 5 笔累加的结果。

---

## 第 7 关：写控制器（含流式响应）

**职责想清楚**：把 `ChatService` 暴露成 HTTP 接口。难点是 `chat()` 返回的是 `Flux<String>`（流），不能直接当普通返回值——要用 `ResponseBodyEmitter` 把流"边到边推"给前端。

### 7.1 `ChatController`

**第 1 笔**：壳子 + 四件套注解。
```java
package com.example.kb.controller;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {
}
```
> `@RestController` = `@Controller` + `@ResponseBody`（返回值直接写响应体，不用再加 `@ResponseBody`）；`@RequestMapping("/api")` 给整类加路径前缀。

**第 2 笔**：它需要 `ChatService`，构造器注入。
```java
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    public ChatController(ChatService chatService) { this.chatService = chatService; }
}
```

**第 3 笔**：写 `/chat` 接口。这里遇到"流式怎么返回"的问题——用 `ResponseBodyEmitter`。
```java
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import com.example.kb.model.ChatRequest;
import com.example.kb.service.ChatService;
import java.io.IOException;
import java.util.UUID;

@PostMapping(value = "/chat", produces = MediaType.TEXT_PLAIN_VALUE)
public ResponseBodyEmitter chat(@RequestBody ChatRequest request) {
    String sessionId = (request.sessionId() == null || request.sessionId().isBlank())
            ? UUID.randomUUID().toString() : request.sessionId();
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(120_000L);
    chatService.chat(sessionId, request.message()).subscribe(
            chunk -> {
                try { emitter.send(chunk, MediaType.TEXT_PLAIN); }
                catch (IOException e) { emitter.completeWithError(e); }
            },
            emitter::completeWithError,
            emitter::complete);
    return emitter;
}
```
> 飘红补 import：`MediaType`、`ResponseBodyEmitter`、`ChatRequest`、`ChatService`、`IOException`、`UUID`。
> **为什么用 `ResponseBodyEmitter`**：它是 Servlet 下"边生成边推"的流式响应对象。构造时给超时（120 秒），再用 `.subscribe(...)` 把 `Flux` 的每个 chunk 用 `emitter.send(...)` 推出；三个 lambda 分别是：收到数据 / 出错 / 完成。
> `@RequestBody` 把请求体 JSON 反序列化成 `ChatRequest` record；没带 sessionId 时自动 `UUID.randomUUID()` 生成（前端刷新即新会话）。

### 7.2 `IngestController` 和 `HealthController`（顺手写的两个小接口）

```java
package com.example.kb.controller;

import com.example.kb.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class IngestController {
    private final DocumentIngestionService ingestionService;
    public IngestController(DocumentIngestionService s) { this.ingestionService = s; }

    @PostMapping("/ingest")
    public Map<String, Object> ingest() throws IOException {
        int chunks = ingestionService.ingest("classpath:knowledge/*.txt");
        return Map.of("status", "ok", "chunks", chunks);
    }
}
```
```java
package com.example.kb.controller;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {
    @GetMapping("/health")
    public Map<String, String> health() { return Map.of("status", "UP"); }
}
```
> 普通 JSON 接口，返回 `Map` 会被 Spring 自动转成 JSON（`{"status":"UP"}`）。`IngestController` 让你能手动重新入库。

---

## 第 8 关：写前端聊天页 `static/index.html`

**职责想清楚**：要一个能"逐字看到回答"的页面，让项目看得见、讲得出。Spring Boot 会自动把 `static/` 下的文件映射成静态资源，根路径 `/` 即 `index.html`，启动后直接访问 `http://localhost:8080`。

核心 JS（原生，不依赖框架），专门消费后端流式文本：
```javascript
// 每个浏览器标签页一个会话；刷新即新会话（对应后端 sessionId）
const sessionId = crypto.randomUUID();
const log = document.getElementById('log');
const input = document.getElementById('q');
const sendBtn = document.getElementById('send');

async function send() {
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    sendBtn.disabled = true;
    const body = append('助手', 'bot');           // 在页面追加"助手"气泡

    // ① 发 POST 到 /api/chat，body 是 JSON
    const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, message: text })
    });
    // ② 拿到响应体的"读取器"，它是一串数据流
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    // ③ 循环读取，每读到一段就追加到页面（逐字渲染）
    while (true) {
        const { done, value } = await reader.read();
        if (done) break;                          // 流结束
        body.textContent += decoder.decode(value, { stream: true });
    }
    sendBtn.disabled = false;
    input.focus();
}
```
> 前端用 `fetch` + `ReadableStream` 消费流式响应，和后端 `ResponseBodyEmitter` 配对。

---

## 第 9 关：编译、运行与验证

```bash
mvn test                          # 只验证骨架/装配（不连 Ollama，CI 友好）
mvn spring-boot:run               # 启动（需本机 Ollama 已运行并 pull 好两个模型）
```

接口验证（流式加 `-N`，让 curl 不缓冲、逐字打印）：
```bash
curl http://localhost:8080/api/health

curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo","message":"年假是怎么规定的？"}'      # → 走 RAG，基于知识库回答

curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo","message":"查一下张三的工号"}'        # → 走工具调用，返回 E1001

curl -N -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"demo","message":"那他的年假还有几天？"}'    # → 走记忆，接住"他=张三"
```

**验证标准**：`mvn test` 绿；`/chat` 三类问题分别走通 RAG / 工具 / 记忆；页面能逐字显示。

**排错速查**：
- 启动报连不上 `11434` → Ollama 没启动或没 `pull` 模型。
- RAG 答非所问 → 调 `similarity-threshold`（调低更容易命中）或检查知识文档。
- 工具没被调用 → 看 `@Tool` 的 description 是否清楚，或问题是否明确到"查某人"。

---

## 第 10 关：Docker 部署（简历加分项）

`docker-compose.yml` 把 Ollama 与应用一起编排：
```yaml
services:
  ollama:
    image: ollama/ollama:latest
    ports: ["11434:11434"]
    volumes: ["ollama-data:/root/.ollama"]
    command: serve
  app:
    build: .
    ports: ["8080:8080"]
    environment:
      - SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434   # 容器内用服务名互访
    depends_on: [ollama]
volumes:
  ollama-data:
```
```bash
docker compose up --build
docker compose exec ollama ollama pull qwen2.5:7b
docker compose exec ollama ollama pull nomic-embed-text
```
> 容器内 `localhost` 是容器自己，所以应用要用服务名 `ollama` 访问 Ollama；`SPRING_AI_OLLAMA_BASE_URL` 覆盖 yml 里的 `localhost`。

---

## 简历话术 & 面试准备

能流畅说出下面几句，项目就"立得住"：
1. "用 Spring AI 2.0 做了企业知识库问答助手，把 **RAG、Agent 工具调用、多轮记忆、流式输出** 集成在一个服务里。"
2. "RAG 用 `SimpleVectorStore` + `QuestionAnswerAdvisor`；工具用 `@Tool` 暴露 HR 查询，由模型自主决策调用。"
3. "用**意图分类（结构化输出）** 做轻量级 Agent 编排：先分类，再路由到 RAG / 工具 / 纯对话。"
4. "记忆用 `MessageChatMemoryAdvisor` 按 `sessionId` 隔离；前端用 `fetch + ReadableStream` 消费流式响应。"
5. "版本上，Boot 4.x 只能配 Spring AI 2.0，Java 17 即可，是硬约束。"

**可能被追问**：为什么不用 1.1.x（不兼容 Boot 4.x）？`SimpleVectorStore` 内存缺点（重启丢数据，生产换 PGVector/Milvus，接口不变）？意图分错怎么办（fallback 到 RAG / 置信度阈值）？工具调用安全（只读安全，写操作加权限校验）？

---

## 你的敲写练习计划（三遍法）

| 遍数 | 做法 | 目标 |
|---|---|---|
| 第 1 遍 | 照本教程逐笔敲（含"飘红补 import"的过程），跑通每个接口 | 熟悉 API 与项目结构、适应"用到才引"的节奏 |
| 第 2 遍 | 关掉教程，凭印象重写整个项目 | 把"套路"变成长时记忆 |
| 第 3 遍 | 改需求：把 `HrTools` 换成"查订单/查物流"，新增知识文档验证 RAG | 证明能独立调，形成作品 |

完成第三遍后推到 GitHub，README 写清架构图与上面的话术——这就是一份能写进简历的 Spring AI 作品。
