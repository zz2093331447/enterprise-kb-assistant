package com.example.kb.service;

import com.example.kb.model.QueryIntent;
import com.example.kb.tools.HrTools;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final VectorStore vectorStore;
    private final HrTools hrTools;
    private final OrderTools orderTools;
    private final IntentClassifier intentClassifier;
    private final int topk;
    private final double similarityThreshold;
    public ChatService(ChatClient chatClient, ChatMemory chatMemory,
                       VectorStore vectorStore, HrTools hrTools,
                       OrderTools orderTools, IntentClassifier intentClassifier,
                       @Value("${app.knowledge.top-k:4}") int topk, @Value("${app.knowledge.similarity-threshold:0.55}") double similarityThreshold) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.hrTools = hrTools;
        this.orderTools = orderTools;
        this.intentClassifier = intentClassifier;
        this.topk = topk;
        this.similarityThreshold = similarityThreshold;
    }

    //路由 + 记忆 + 流式

    public Flux<String> chat(String sessionId, String message){
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        //意图识别方法，根据message返回意图
        QueryIntent intent=intentClassifier.classify(message);
        var spec=chatClient.prompt()
                .advisors(a ->a.param(chatMemory.CONVERSATION_ID,sessionId))
                .advisors(memoryAdvisor)
                .user(message);

        //现在加"路由"——在 return 之前，根据意图给 spec 挂不同的能力
        if(intent.is("KNOWLEDGE")){
            QuestionAnswerAdvisor qsAdvisor=QuestionAnswerAdvisor.builder(vectorStore)
                    .searchRequest(SearchRequest.builder()
                            .topK(topk)
                            .similarityThreshold(similarityThreshold)
                            .build())
                    .build();
            spec = spec.advisors(qsAdvisor);
        } else if (intent.is("HR")) {
            spec = spec.tools(hrTools);
        } else if (intent.is("ORDER")) {
            spec = spec.tools(orderTools);
        }

        return spec.stream().content()
                .onErrorResume(e -> {
                    log.warn("对话生成失败，返回兜底话术：{}", e.getMessage());
                    return Flux.just("抱歉，服务暂时不可用，请稍后再试。");
                });
    }
}
