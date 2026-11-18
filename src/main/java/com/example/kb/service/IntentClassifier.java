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

    @Retry(name = "intentClassifier", fallbackMethod = "classifyFallback")
    @CircuitBreaker(name = "intentClassifier", fallbackMethod = "classifyFallback")
    @RateLimiter(name = "intentClassifier", fallbackMethod = "classifyFallback")
    //意图分类
    public QueryIntent classify(String message){
        return chatClient.prompt()
                .user(u->u.text("""
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
    public QueryIntent classifyFallback(String message, Throwable t){
        log.warn("意图识别失败，降级为 CHITCHAT：{}", t.getMessage());
        return new QueryIntent("CHITCHAT", message);
    }
}
