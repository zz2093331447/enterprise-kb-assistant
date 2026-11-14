package com.example.kb.service;

import com.example.kb.model.QueryIntent;
import com.example.kb.tools.HrTools;
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
    private final HrTools hrTools;
    private final int topk;
    private final double similarityThreshold;
    public ChatService(ChatClient chatClient, ChatMemory chatMemory, VectorStore vectorStore, HrTools hrTools, @Value("${app.knowledge.top-k:4}") int topk, @Value("${app.knowledge.similarity-threshold:0.55}") double similarityThreshold) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.vectorStore = vectorStore;
        this.hrTools = hrTools;
        this.topk = topk;
        this.similarityThreshold = similarityThreshold;
    }
    //意图分类
    public QueryIntent classify(String message){
        return chatClient.prompt()
                .user(u->u.text("""
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
    //路由 + 记忆 + 流式

    public Flux<String> chat(String sessionId, String message){
        MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
        QueryIntent intent=classify(message);
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
        }

        return spec.stream().content();

    }

}
