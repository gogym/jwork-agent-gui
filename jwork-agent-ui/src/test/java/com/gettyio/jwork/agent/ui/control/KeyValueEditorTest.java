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

package com.gettyio.jwork.agent.ui.control;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyValueEditor} 交互单测：真实 JavaFX 工具包上跑增删行 / 收集口径 / 掩码显形切换。
 * <p>工具包起不来（无显示会话等）时整类跳过而非失败——控件逻辑仍由编译与服务层测试兜底。</p>
 *
 * @author gogym
 */
class KeyValueEditorTest {

    private static boolean fxReady;

    @BeforeAll
    static void bootFx() {
        try {
            CountDownLatch latch = new CountDownLatch(1);
            Platform.startup(latch::countDown);
            fxReady = latch.await(15, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            fxReady = true;
        } catch (Throwable t) {
            fxReady = false;
        }
    }

    @AfterAll
    static void shutdownFx() {
        if (fxReady) {
            Platform.exit();
        }
    }

    /** 在 FX 线程执行并等待完成；断言失败原样抛回测试线程。 */
    private static void fx(ThrowingRunnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 任务超时");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static KeyValueEditor newEditor() throws Exception {
        KeyValueEditor[] holder = new KeyValueEditor[1];
        fx(() -> holder[0] = new KeyValueEditor("API_KEY", "sk-…"));
        return holder[0];
    }

    /** 行内节点取用：HBox = [键名框, 值 StackPane(密码框/明文框/眼睛), 删行按钮]。 */
    private static HBox rowBox(KeyValueEditor editor, int index) {
        VBox root = (VBox) editor.getNode();
        VBox rows = (VBox) root.getChildren().get(0);
        return (HBox) rows.getChildren().get(index);
    }

    private static StackPane valueBox(HBox row) {
        return (StackPane) row.getChildren().get(1);
    }

    @Test
    @DisplayName("回填往返一致；空键名行忽略、键名 trim、同名后行覆盖")
    void collectRules() throws Exception {
        Assumptions.assumeTrue(fxReady, "JavaFX 工具包不可用，跳过");
        KeyValueEditor editor = newEditor();
        fx(() -> {
            Map<String, String> entries = new LinkedHashMap<>();
            entries.put("Authorization", "Bearer sk-1");
            entries.put("X-Api-Key", "k2");
            editor.setEntries(entries);
            assertEquals(2, editor.rowCount());
            assertEquals(entries, editor.collect());

            editor.addRow();
            assertEquals(3, editor.rowCount());
            assertEquals(2, editor.collect().size(), "待填空行不进映射");

            HBox extra = rowBox(editor, 2);
            ((TextField) extra.getChildren().get(0)).setText("  Authorization  ");
            ((PasswordField) valueBox(extra).getChildren().get(0)).setText("Bearer sk-9");
            assertEquals("Bearer sk-9", editor.collect().get("Authorization"), "同名后行覆盖且键名 trim");
        });
    }

    @Test
    @DisplayName("添加一行 / 删除该行：行数与收集同步")
    void addAndRemoveRows() throws Exception {
        Assumptions.assumeTrue(fxReady, "JavaFX 工具包不可用，跳过");
        KeyValueEditor editor = newEditor();
        fx(() -> {
            editor.setEntries(Map.of("A", "1"));
            editor.addRow();
            assertEquals(2, editor.rowCount());
            Button remove = (Button) rowBox(editor, 1).getChildren().get(2);
            remove.fire();
            assertEquals(1, editor.rowCount());
            assertEquals(Map.of("A", "1"), editor.collect());
            // 删唯一行：映射清空，添加按钮仍在
            ((Button) rowBox(editor, 0).getChildren().get(2)).fire();
            assertEquals(0, editor.rowCount());
            assertTrue(editor.collect().isEmpty());
        });
    }

    @Test
    @DisplayName("掩码默认开；眼睛切换明/密文不丢输入，收集始终读密码框")
    void revealToggleKeepsText() throws Exception {
        Assumptions.assumeTrue(fxReady, "JavaFX 工具包不可用，跳过");
        KeyValueEditor editor = newEditor();
        fx(() -> {
            editor.setEntries(Map.of("A", "secret-value"));
            StackPane box = valueBox(rowBox(editor, 0));
            PasswordField secret = (PasswordField) box.getChildren().get(0);
            TextField plain = (TextField) box.getChildren().get(1);
            Button reveal = (Button) box.getChildren().get(2);
            assertTrue(secret.isVisible());
            assertFalse(plain.isVisible(), "默认掩码");

            reveal.fire();
            assertTrue(plain.isVisible());
            assertFalse(secret.isVisible());
            assertEquals("secret-value", plain.getText(), "切换不丢内容");
            plain.setText("typed-plain");
            assertEquals("typed-plain", secret.getText(), "双向绑定同步");

            reveal.fire();
            assertTrue(secret.isVisible());
            assertFalse(plain.isVisible());
            assertEquals("typed-plain", editor.collect().get("A"));
        });
    }
}