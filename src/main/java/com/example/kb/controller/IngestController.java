package com.example.kb.controller;

import com.example.kb.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class IngestController {
    private final DocumentIngestionService ingestionService;
    public IngestController(DocumentIngestionService s) {
        this.ingestionService = s;
    }
    @PostMapping("/ingest")
    public Map<String, Object> ingest() throws IOException {
        int chunks=ingestionService.ingest("classpath:knowledge/*.txt");
        return Map.of("status","ok","chunks", chunks);
    }

}
