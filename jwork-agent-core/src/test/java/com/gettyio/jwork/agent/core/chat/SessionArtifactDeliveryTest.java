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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产物交付落盘：{@link SessionArtifactDelivery} 的会话目录归位、同名冲突与文件名清洗。
 * <p>交付路径口径必须与 UI 侧产物卡反查（{@code dirOf}）一致，故两处共用同一方法在此校验。</p>
 *
 * @author gogym
 */
class SessionArtifactDeliveryTest {

    @TempDir
    Path root;

    private static RuntimeContext ctx(String sessionId) {
        return new RuntimeContext.Builder().sessionId(sessionId).build();
    }

    private static ArtifactDeliveryRequest req(String fileName, String body, boolean force) {
        return new ArtifactDeliveryRequest("workspace/" + fileName,
                body.getBytes(StandardCharsets.UTF_8), fileName, "报表", force);
    }

    @Test
    @DisplayName("交付落到会话目录并把宿主绝对路径回给模型")
    void deliversIntoSessionDir() throws IOException {
        SessionArtifactDelivery target = new SessionArtifactDelivery(root);
        ArtifactDeliveryResult r = target.deliver(ctx("s-1"), req("退款差异.xlsx", "A1", false));

        assertTrue(r.successful(), () -> "交付应成功，实际: " + r.error());
        Path file = root.resolve("s-1").resolve("退款差异.xlsx");
        assertEquals(file.toString(), r.message());
        assertEquals("A1", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("同名不覆盖：force=false 返回冲突，force=true 才改写")
    void conflictUnlessForced() throws IOException {
        SessionArtifactDelivery target = new SessionArtifactDelivery(root);
        assertTrue(target.deliver(ctx("s-2"), req("report.md", "第一版", false)).successful());

        ArtifactDeliveryResult again = target.deliver(ctx("s-2"), req("report.md", "第二版", false));
        assertFalse(again.successful());
        assertTrue(again.conflict(), "同名应报冲突而非普通失败");

        Path file = root.resolve("s-2").resolve("report.md");
        assertEquals("第一版", Files.readString(file, StandardCharsets.UTF_8));

        assertTrue(target.deliver(ctx("s-2"), req("report.md", "第二版", true)).successful());
        assertEquals("第二版", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("交付名里的路径分隔与控制字符被清洗，落点始终在会话目录内")
    void sanitizesDeliveryName() {
        SessionArtifactDelivery target = new SessionArtifactDelivery(root);
        ArtifactDeliveryResult r = target.deliver(ctx("s-3"), req("..\\..\\escape.xlsx", "x", false));

        assertTrue(r.successful(), () -> "非法名应清洗后交付，实际: " + r.error());
        Path dir = root.toAbsolutePath().normalize().resolve("s-3");
        Path landed = Path.of(r.message());
        assertTrue(landed.startsWith(dir), () -> "落点越界: " + landed);
        assertEquals("escape.xlsx", landed.getFileName().toString());
    }

    @Test
    @DisplayName("空名与空内容直接失败，不抛异常也不建目录")
    void rejectsEmptyRequest() {
        SessionArtifactDelivery target = new SessionArtifactDelivery(root);

        assertFalse(target.deliver(ctx("s-4"), req("   ", "x", false)).successful());
        assertFalse(target.deliver(ctx("s-4"), new ArtifactDeliveryRequest("a", null, "a.txt", "", false)).successful());
        assertFalse(Files.exists(root.resolve("s-4")));
    }

    @Test
    @DisplayName("无会话上下文落 _no-session；会话 ID 里的分隔符不逃逸出交付根")
    void mapsSessionIdToSafeSegment() {
        SessionArtifactDelivery target = new SessionArtifactDelivery(root);
        assertTrue(target.deliver(null, req("a.txt", "x", false)).successful());
        assertTrue(Files.exists(root.resolve(SessionArtifactDelivery.NO_SESSION).resolve("a.txt")));

        Path escaped = SessionArtifactDelivery.dirOf(root, "../../secrets");
        assertTrue(escaped.startsWith(root.toAbsolutePath().normalize()));
        assertFalse(escaped.equals(root.toAbsolutePath().normalize()), "清洗后不得等于交付根");
    }
}
