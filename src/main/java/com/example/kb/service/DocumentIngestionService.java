package com.example.kb.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识入库服务（RAG 的“写”侧）。
 *
 * 职责：
 * 1. 启动时（auto-ingest=true）自动把 resources/knowledge 下的文档切片、向量化、写入 VectorStore；
 * 2. 暴露 reIngest() 供管理接口手动重新入库。
 *
 * 这演示了 RAG 流程中“离线建库”这一步——生产里通常独立成定时任务或管理后台。
 */
@Service
public class DocumentIngestionService implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final boolean autoIngest;

    public DocumentIngestionService(VectorStore vectorStore,
                                    @Value("${app.knowledge.auto-ingest:true}") boolean autoIngest) {
        this.vectorStore = vectorStore;
        this.autoIngest = autoIngest;
    }

    /** Spring Boot 启动完成后自动执行 */
    @Override
    public void run(String... args) throws Exception {
        if (autoIngest) {
            int n = ingest("classpath:knowledge/*.txt");
            System.out.println("[KB] 启动自动入库完成，共写入 " + n + " 个文档片段。");
        }
    }

    /** 扫描并入库指定模式的文档，返回写入的片段数量 */
    public int ingest(String pattern) throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(pattern);

        // TokenTextSplitter：按 token 数切片，避免单段过长超出模型上下文
        // 2.0 推荐用 builder() 创建；new TokenTextSplitter() 已 deprecated
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> all = new ArrayList<>();

        for (Resource r : resources) {
            String text = FileCopyUtils.copyToString(
                    new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8));
            // metadata 用于溯源：回答时可知道内容来自哪个文件
            String fileName = r.getFilename() == null ? "unknown" : r.getFilename();
            Map<String, Object> metadata = Map.of("source", fileName);
            Document doc = Document.builder().text(text).metadata(metadata).build();
            List<Document> chunks = splitter.split(doc);
            all.addAll(chunks);
        }

        vectorStore.add(all);
        return all.size();
    }
}
