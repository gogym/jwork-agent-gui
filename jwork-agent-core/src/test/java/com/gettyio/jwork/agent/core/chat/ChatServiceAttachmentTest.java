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

package com.gettyio.jwork.agent.core.chat;

import com.gettyio.jwork.agent.core.JworkPaths;
import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.persist.SqliteConnectionProvider;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatService} 附件注入单测：表格只注入「结构 + 本机路径」并保留原件名，
 * 文本类按摘要/全文两档处理，落盘名可辨识且同名不覆盖。
 * <p>用捕获请求的假网关断言真正发给模型的文本，真实 SQLite 与临时目录验落盘。</p>
 *
 * @author gogym
 */
class ChatServiceAttachmentTest {

    @TempDir
    Path tempDir;

    private JworkDatabase db;
    private JworkPaths paths;
    private ChatService chatService;
    private final AtomicReference<ChatRequest> captured = new AtomicReference<>();

    /** 假网关：记下收到的请求，回一个空流（落盘语义由别的用例覆盖）。 */
    private final class CapturingGateway implements AgentGateway {
        @Override
        public Mono<String> ask(String agentCode, String sessionId, String userText) {
            return Mono.empty();
        }

        @Override
        public Flux<StreamEvent> stream(ChatRequest request) {
            captured.set(request);
            return Flux.empty();
        }
    }

    @BeforeEach
    void setUp() {
        db = new JworkDatabase(new SqliteConnectionProvider(tempDir.resolve("jwork.db")));
        db.initSchema();
        paths = new JworkPaths(tempDir.resolve("home"));
        chatService = new ChatService(new CapturingGateway(), db, paths, null);
    }

    private void send(FileAttachment... files) {
        ChatRequest req = ChatRequest.builder()
                .agentCode("jwork-agent").sessionId("s1").text("帮我统计一下")
                .files(List.of(files))
                .build();
        chatService.chat(req).blockLast(java.time.Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("表格附件：注入结构与本机路径，不塞整表正文")
    void tabularAttachmentInjectsStructureAndPath() throws IOException {
        byte[] xlsx = ordersXlsx(50);
        send(new FileAttachment("文件1 推广平台.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Base64.getEncoder().encodeToString(xlsx), xlsx.length));

        String prompt = captured.get().getText();
        assertTrue(prompt.contains("文件1 推广平台.xlsx"), prompt);
        assertTrue(prompt.contains("本机路径："), prompt);
        assertTrue(prompt.contains("退款明细"), prompt);
        assertTrue(prompt.contains("商家订单号"), prompt);
        assertTrue(prompt.contains("inspect_file"), prompt);
        // 只给样例：50 行不可能全部出现在注入块里
        assertFalse(prompt.contains("r40"), prompt);
        assertTrue(prompt.length() < 4_000, "注入块应远小于整表，实际 " + prompt.length());
    }

    @Test
    @DisplayName("落盘名保留原件名（带序号前缀），路径指向真实存在的文件")
    void storedFileNameKeepsOriginalName() throws IOException {
        byte[] xlsx = ordersXlsx(3);
        send(new FileAttachment("文件2 利郎退款.xlsx", "application/octet-stream",
                Base64.getEncoder().encodeToString(xlsx), xlsx.length));

        List<Path> stored = listAttachments();
        assertEquals(1, stored.size(), "应只落一份附件，实际 " + stored);
        String name = stored.get(0).getFileName().toString();
        assertTrue(name.startsWith("1-"), name);
        assertTrue(name.contains("文件2 利郎退款"), name);
        assertTrue(name.endsWith(".xlsx"), name);
        assertTrue(captured.get().getText().contains(name), captured.get().getText());
    }

    @Test
    @DisplayName("原件名里的路径与非法字符被净化，存储名不会越出附件目录")
    void illegalCharsInOriginalNameAreSanitized() throws IOException {
        byte[] xlsx = ordersXlsx(2);
        send(new FileAttachment("../逃逸/x:<明细>.xlsx", "application/octet-stream",
                Base64.getEncoder().encodeToString(xlsx), xlsx.length));

        String name = listAttachments().get(0).getFileName().toString();
        for (char bad : new char[] {'/', '\\', ':', '*', '?', '"', '<', '>', '|'}) {
            assertFalse(name.indexOf(bad) >= 0, name + " 含非法字符 " + bad);
        }
        assertFalse(name.contains(".."), name);
        assertTrue(name.contains("明细"), name);
    }

    @Test
    @DisplayName("摘要模式：长文本只给头部摘录并指路 read_file")
    void longTextSummarizedByDefault() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            sb.append("第").append(i).append("行说明，用于验证摘要模式只截取开头。\n");
        }
        byte[] txt = sb.toString().getBytes(StandardCharsets.UTF_8);
        send(new FileAttachment("说明.txt", "text/plain",
                Base64.getEncoder().encodeToString(txt), txt.length));

        String prompt = captured.get().getText();
        assertTrue(prompt.contains("第0行说明"), prompt);
        assertTrue(prompt.contains("未全部展开"), prompt);
        assertTrue(prompt.contains("read_file"), prompt);
        assertFalse(prompt.contains("第1999行说明"), prompt);
    }

    @Test
    @DisplayName("全文模式：正文整份注入（改设置即时生效）")
    void fullModeInjectsWholeBody() {
        db.settingDao().upsert(ChatService.SETTING_ATTACHMENT_MODE, ChatService.ATTACHMENT_MODE_FULL, "测试");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            sb.append("第").append(i).append("行说明，用于验证摘要模式只截取开头。\n");
        }
        byte[] txt = sb.toString().getBytes(StandardCharsets.UTF_8);
        send(new FileAttachment("说明.txt", "text/plain",
                Base64.getEncoder().encodeToString(txt), txt.length));

        String prompt = captured.get().getText();
        assertTrue(prompt.contains("第1999行说明"), prompt);
        assertFalse(prompt.contains("未全部展开"), prompt);
    }

    @Test
    @DisplayName("同名附件不互相覆盖：第二个落点带后缀")
    void sameNameDoesNotOverwrite() throws IOException {
        byte[] a = ordersXlsx(2);
        send(new FileAttachment("订单.xlsx", "application/octet-stream",
                Base64.getEncoder().encodeToString(a), a.length));
        String firstText = captured.get().getText();
        send(new FileAttachment("订单.xlsx", "application/octet-stream",
                Base64.getEncoder().encodeToString(a), a.length));

        long stored = listAttachments().size();
        assertEquals(2, stored, "同名附件应各留一份");
        assertTrue(firstText.contains("1-订单.xlsx"), firstText);
        assertTrue(captured.get().getText().contains("1-订单-"), captured.get().getText());
    }

    /** 会话附件目录里的文件清单。 */
    private List<Path> listAttachments() throws IOException {
        Path dir = paths.attachmentsDir().resolve("s1");
        try (Stream<Path> walk = Files.list(dir)) {
            return walk.toList();
        }
    }

    /** 生成含表头 + rows 行的 xlsx 字节。 */
    private static byte[] ordersXlsx(int rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("退款明细");
            XSSFRow head = sheet.createRow(0);
            head.createCell(0).setCellValue("商家订单号");
            head.createCell(1).setCellValue("付款时间");
            head.createCell(2).setCellValue("实付金额");
            for (int i = 1; i <= rows; i++) {
                XSSFRow r = sheet.createRow(i);
                r.createCell(0).setCellValue("T200P" + (88000 + i));
                r.createCell(1).setCellValue("2026-10-0" + (i % 9 + 1));
                r.createCell(2).setCellValue(10.0 + i);
            }
            wb.write(out);
        }
        return out.toByteArray();
    }
}
