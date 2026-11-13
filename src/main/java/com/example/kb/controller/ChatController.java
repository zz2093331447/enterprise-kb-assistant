package com.example.kb.controller;

import com.example.kb.model.ChatRequest;
import com.example.kb.service.ChatService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.awt.*;
import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {
    //它需要 ChatService，构造器注入
    private final ChatService chatService;
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    //写 /chat 接口。这里遇到"流式怎么返回"的问题——用 ResponseBodyEmitter。
    @RequestMapping(value = "/chat",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseBodyEmitter chat(@RequestBody ChatRequest request) {
        String sessionId =(request.sessionId()==null || request.sessionId().isBlank())
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
}
