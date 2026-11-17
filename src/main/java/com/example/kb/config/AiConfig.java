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
