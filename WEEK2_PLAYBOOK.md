# 第二周 · 改需求练习实现手册（照着敲）

> 目标：把通用"HR 工具"换成你自己的"查订单"业务 —— 新建 `OrderTools` + 新增订单知识文档 + 调整意图路由。
> 完成标准：新增一个业务工具 + 一份文档，且「查订单走工具、订单政策走 RAG」路由正确。

⚠️ **先读 0 节**：你当前练习工程里的 `ChatService` / `QueryIntent` 有两个真实 bug，不修的话 RAG 和工具分支都进不去，改需求也没意义。

---

## 0. 前置修复：两个路由 bug（必做）

### 0.1 `QueryIntent` —— `is()` 比较错字段

你现在的文件：
```java
public record QueryIntent(String intent, String message) {
    public boolean is(String name){
        return message !=null && message.equalsIgnoreCase(name);  // ❌ 比的是 message
    }
}
```
模型把意图类别填进 `intent` 字段（`KNOWLEDGE`/`HR`/`CHITCHAT`），`message` 只是理由。所以 `is()` 永远 false。

**改成**（直接用这份完整覆盖 `src/main/java/com/example/kb/model/QueryIntent.java`）：
```java
package com.example.kb.model;

public record QueryIntent(
        String intent,  // 只能是 KNOWLEDGE / ORDER / CHITCHAT 之一
        String reason   // 模型给出该分类的理由（便于排查）
) {
    public boolean is(String name) {
        // 忽略空指针和大小写，拿 intent 字段去比
        return intent != null && intent.equalsIgnoreCase(name);
    }
}
```

### 0.2 `ChatService` —— 路由判断写错意图名

你现在的文件第 56 行：
```java
if(intent.is("knownError")){   // ❌ 应该是 KNOWLEDGE（拼写错+大小写错）
```
而且你 prompt 里定义的是 `KNOWLEDGE`，这里却写 `knownError`，永远匹配不上 → RAG 分支废了。

**整份覆盖 `src/main/java/com/example/kb/service/ChatService.java`**（已经把 HR 改成第二周的 ORDER 语义，见第 1 节说明）：
```java
package com.example.kb.service;

import com.example.kb.model.QueryIntent;
import com.example.kb.tools.OrderTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final OrderTools orderTools;          // ← 原来是 HrTools

    private final int topk;
    private final double similarityThreshold;

    public ChatService(ChatClient chatClient,
                       ChatMemory chatMemory,
                       VectorStore vectorStore,
                       OrderTools orderTools,      // ← 注入 OrderTools
                       @Value("${app.knowledge.top-k:4}") int topk,
                       @Value("${app.knowledge.similarity-threshold:0.55}") double similarityThreshold) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.orderTools = orderTools;
        this.topk = topk;
        this.similarityThreshold = similarityThreshold;
    }

    /** 第 1 步：意图识别（结构化输出能力） */
    public QueryIntent classify(String message) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        请判断下面用户问题的意图类别，并简述理由。
                        意图只能是以下三者之一：
                        - KNOWLEDGE：询问公司制度、流程、产品、政策、订单退换货规则等知识库内容
                        - ORDER：查询某个订单的状态、金额、物流、收货人等业务数据（需要订单号）
                        - CHITCHAT：闲聊、问候或无明确业务诉求
                        问题：{question}
                        """).param("question", message))
                .call()
                .entity(QueryIntent.class);
    }

    /** 第 2~4 步：按意图路由 + 记忆 + 流式 */
    public Flux<String> chat(String sessionId, String message) {
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory).build();

        QueryIntent intent = classify(message);

        var spec = chatClient.prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .advisors(memoryAdvisor)
                .user(message);

        // 路由：不同意图挂不同能力
        if (intent.is("KNOWLEDGE")) {
            QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(topk)
                            .similarityThreshold(similarityThreshold)
                            .build())
                    .build();
            spec = spec.advisors(qaAdvisor);
        } else if (intent.is("ORDER")) {       // ← 原来是 HR
            spec = spec.tools(orderTools);      // ← 挂订单工具
        }
        // CHITCHAT：纯对话

        return spec.stream().content();
    }
}
```

> 修完后 `HrTools` 不再被引用，第 1 节直接把它替换成 `OrderTools`。

---

## 1. 任务一：新建「查订单」工具 `OrderTools`

**操作**：删除 `src/main/java/com/example/kb/tools/HrTools.java`，新建 `OrderTools.java`（同目录）。

```java
package com.example.kb.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OrderTools {
    // 用内存 Map 模拟"订单数据库"。
    // 真实项目这里会注入 JpaRepository 或 RestTemplate 去调订单服务，写法结构完全不变。
    private static final Map<String, String> ORDERS = Map.of(
            "NO2026001", "订单 NO2026001：状态=已发货，金额=299.00元，收货人=张三，物流=顺丰已揽收",
            "NO2026002", "订单 NO2026002：状态=待付款，金额=1599.00元，收货人=李四，下单时间=2026-11-10",
            "NO2026003", "订单 NO2026003：状态=已签收，金额=89.90元，收货人=王五，签收时间=2026-11-12");

    @Tool(description = "根据订单号查询订单的状态、金额、收货人、物流等详情。参数使用订单号，例如 NO2026001。")
    public String queryOrder(@ToolParam(description = "订单号，例如 NO2026001") String orderId) {
        return ORDERS.getOrDefault(orderId, "未找到订单" + orderId + "，请确认订单号是否正确");
    }
}
```

**要点（面试能讲）**：
- `@Component` 让 Spring 接管，`ChatService` 构造器就能直接收到它。
- `@Tool` 的 `description` 是**写给模型看的说明书**——模型靠它判断"何时该调这个工具、传什么参数"。描述越清楚，工具调用越准。
- `@ToolParam(description=...)` 同理，指导模型填对参数。
- 工具内部是"硬编码 Map"，但**结构**（查 DB / 调 API）和真实项目一致，第二周的练习目的就是"换业务逻辑不换框架"。

---

## 2. 任务二：新增订单知识文档 `order-faq.txt`

**操作**：在 `src/main/resources/knowledge/` 下新建 `order-faq.txt`（和 `hr-policy.txt` 同级）。

```text
# 订单与退换货政策 FAQ

问：订单支持七天无理由退货吗？
答：支持。商品签收后 7 天内，在不影响二次销售的前提下，可申请无理由退货，退款将在审核通过后 3 个工作日内原路返回。

问：怎么申请退款？
答：进入"我的订单"→ 选择对应订单 → 点击"申请售后" → 选择"仅退款"或"退货退款"，填写原因并提交。

问：物流信息多久更新一次？
答：订单发货后，物流轨迹通常每 2-4 小时同步一次。若长时间未更新，可联系在线客服核实。

问：下单后多久发货？
答：现货商品在付款后 48 小时内发出；预售商品以商品页标注的发货时间为准。

问：可以修改收货地址吗？
答：订单在"待发货"状态前可自助修改收货地址；已进入发货流程后无法修改，需联系客服拦截。

问：发票怎么开？
答：在订单完成后，于"订单详情 → 申请开票"中提交抬头与税号，电子发票将于 1-3 个工作日发送至预留邮箱。
```

**要点**：
- `DocumentIngestionService` 会自动扫描 `classpath:knowledge/*.txt` 全部入库（启动时或调 `/api/ingest`），**你不用改入库代码**，加文件即可生效。
- 这份文档用来验证「订单政策类问题走 RAG」——模型不该瞎编，而应从这里检索回答。

---

## 3. 任务三：意图路由调整（已在 0.2 的 ChatService 里完成）

三处联动改动，确认一致即可：
- `classify` 的 prompt：把原 `HR：查询员工...` 改成 `ORDER：查询订单状态/金额/物流...`。
- `QueryIntent` 的可能值：概念上从 `KNOWLEDGE/HR/CHITCHAT` 变成 `KNOWLEDGE/ORDER/CHITCHAT`。
- 路由：`intent.is("ORDER")` 时 `spec.tools(orderTools)`。

> 为什么"查订单"走**工具**、"订单政策"走 **RAG**？
> - 订单状态是**实时/结构化数据**（查数据库），用工具精确返回，不该让模型"编"。
> - 退换货规则是**非结构化知识**（文档），用 RAG 检索后让模型组织语言回答，不该让它死记。
> 这正是"什么时候该用工具、什么时候该用 RAG"的核心区分，面试高频题。

---

## 4. 验证清单

```bash
# 1) 编译（先确保 0 节的修复 + 1/2/3 节改动无飘红）
cd D:/Java/enterprise-kb-assistant
mvn -o -q -DskipTests compile

# 2) 本机启动（需 Ollama 已 pull qwen2.5:7b 和 nomic-embed-text）
mvn spring-boot:run
```

逐条验证（另开终端）：

```bash
# (a) 订单工具：应命中 OrderTools，返回"已发货/金额"等真实字段
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w2","message":"查一下订单 NO2026001 的状态"}'

# (b) 订单政策 RAG：应基于 order-faq.txt 回答"七天无理由"
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w2","message":"订单支持七天无理由退货吗？"}'

# (c) 记忆：接着问，应接住"它"指代上一句订单/用户上下文
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w2","message":"那它什么时候发货的？"}'

# (d) 闲聊：纯对话，不触发工具也不检索
curl -N -X POST http://localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"sessionId":"w2","message":"你好呀"}'
```

**判断通过的标准**：
- (a) 回答里出现 `NO2026001` 和 `已发货`/`金额` → 工具命中 ✅
- (b) 回答提到"七天/签收后/原路返回"等文档原话 → RAG 命中 ✅
- (d) 回答是客套话、没调工具也没检索 → 路由正确 ✅

---

## 5. 完成标准（对照 LEARNING_PLAN 第二周打勾）

- [ ] 已删除 `HrTools`，新建 `OrderTools`（查订单工具跑通）
- [ ] 已新增 `order-faq.txt`，且 RAG 能检索到（验证 b 通过）
- [ ] 意图路由正确：查订单→工具、订单政策→RAG、闲聊→纯对话
- [ ] 四个 curl 验证全部符合预期

---

## 6. 练完这一步，你可以接着做（第三遍法延伸）

把 `OrderTools` 再换一次业务，比如"查物流"或"查库存"，体会"**换业务逻辑、框架代码一行不动**"——这就是 Spring AI 抽象的价值，也是你以后接手任何 AI 项目的通用套路。
