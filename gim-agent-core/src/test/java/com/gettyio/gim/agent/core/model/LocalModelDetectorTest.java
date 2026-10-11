/*
 * Copyright 2026 gogym
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gettyio.gim.agent.core.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalModelDetector} 单测：用内嵌 HttpServer 模拟 Ollama / LM Studio 端点，
 * 不依赖真实 11434/1234 端口与外网。
 *
 * @author gogym
 */
class LocalModelDetectorTest {

    private HttpServer server;
    private String base;
    /** 由各用例设置的响应体；为 null 时返回 404。 */
    private volatile String ollamaBody;
    private volatile String lmStudioBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        base = "http://localhost:" + server.getAddress().getPort();
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = ollamaBody == null ? new byte[0] : ollamaBody.getBytes(StandardCharsets.UTF_8);
            if (ollamaBody == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] bytes = lmStudioBody == null ? new byte[0] : lmStudioBody.getBytes(StandardCharsets.UTF_8);
            if (lmStudioBody == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void detectsOllamaAndListsModels() {
        ollamaBody = "{\"models\":[{\"name\":\"qwen2.5:7b\"},{\"name\":\"llama3.1:8b\"}]}";
        Optional<LocalModelDetector.LocalService> svc = new LocalModelDetector(base, base + ":1").detect();
        assertTrue(svc.isPresent());
        assertEquals("OLLAMA", svc.get().kind());
        assertEquals(2, svc.get().models().size());
        assertEquals("qwen2.5:7b", svc.get().models().get(0));
    }

    @Test
    void fallsBackToLmStudioWhenOllamaAbsent() {
        lmStudioBody = "{\"data\":[{\"id\":\"qwen3-8b\"}]}";
        Optional<LocalModelDetector.LocalService> svc = new LocalModelDetector(base, base).detect();
        assertTrue(svc.isPresent());
        assertEquals("LM_STUDIO", svc.get().kind());
        assertEquals("qwen3-8b", svc.get().models().get(0));
    }

    @Test
    void returnsEmptyWhenBothAbsent() {
        Optional<LocalModelDetector.LocalService> svc = new LocalModelDetector(base, base).detect();
        assertTrue(svc.isEmpty());
    }

    @Test
    void malformedJsonCountsAsAbsent() {
        ollamaBody = "not-json";
        Optional<LocalModelDetector.LocalService> svc = new LocalModelDetector(base, base).detect();
        assertTrue(svc.isEmpty());
    }
}
