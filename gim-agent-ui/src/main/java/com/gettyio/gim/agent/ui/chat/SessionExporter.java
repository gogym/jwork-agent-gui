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

package com.gettyio.gim.agent.ui.chat;

import com.gettyio.gim.agent.domain.entity.ChatSession;
import com.gettyio.gim.agent.ui.i18n.I18n;
import com.gettyio.gim.agent.ui.export.ExportService;
import com.gettyio.gim.agent.ui.export.SessionImageExporter;
import com.gettyio.gim.agent.ui.window.dialog.GimDialog;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 会话导出命令：长图（PNG）与独立网页（HTML）。
 * <p>从 {@code MainController} 拆出的协作类：「另存为」选点、后台渲染、成功/失败弹窗内聚于此；
 * 控制器只保留 {@code SessionSidebarHost} 的薄转发。</p>
 *
 * @author gogym
 */
public final class SessionExporter {

    private static final Logger log = LoggerFactory.getLogger(SessionExporter.class);

    private final ExportService exportService;
    private final Supplier<Window> ownerWindow;

    public SessionExporter(ExportService exportService, Supplier<Window> ownerWindow) {
        this.exportService = exportService;
        this.ownerWindow = ownerWindow;
    }

    /** 导出会话长图：后台备好 HTML，回 FX 线程交给导出器（其内部再异步写盘）。 */
    public void exportImage(String sessionId) {
        ChatSession session = exportService.findSession(sessionId).orElse(null);
        if (session == null) {
            GimDialog.message(ownerWindow.get(), I18n.t("session.export.image"), null, I18n.t("settings.export.noSession"));
            return;
        }
        File file = chooseExportTarget(I18n.t("session.export.image"), session, ".png", "PNG (*.png)");
        if (file == null) {
            return;
        }
        Path target = withExtension(file.toPath(), ".png");
        // 长图须在 FX 线程快照：先在后台备好 HTML，回 FX 线程交给导出器（其内部再异步写盘）
        Thread t = new Thread(() -> {
            String html;
            try {
                html = exportService.renderSessionHtml(sessionId);
            } catch (RuntimeException e) {
                log.warn("导出会话长图：HTML 渲染失败", e);
                Platform.runLater(() -> GimDialog.message(ownerWindow.get(), I18n.t("session.export.image"), null,
                        I18n.t("settings.data.fail", String.valueOf(e.getMessage()))));
                return;
            }
            Platform.runLater(() -> SessionImageExporter.export(ownerWindow.get(), html, target,
                    saved -> GimDialog.message(ownerWindow.get(), I18n.t("session.export.image"), null,
                            I18n.t("settings.export.done", saved.getFileName())),
                    err -> GimDialog.message(ownerWindow.get(), I18n.t("session.export.image"), null, err)));
        }, "session-image-render");
        t.setDaemon(true);
        t.start();
    }

    /** 导出独立 HTML（话题卡片式、样式与本地图片内嵌，单文件可分享）；渲染在后台线程。 */
    public void exportHtml(String sessionId) {
        ChatSession session = exportService.findSession(sessionId).orElse(null);
        if (session == null) {
            GimDialog.message(ownerWindow.get(), I18n.t("session.export.html"), null, I18n.t("settings.export.noSession"));
            return;
        }
        File file = chooseExportTarget(I18n.t("session.export.html"), session, ".html", "HTML (*.html)");
        if (file == null) {
            return;
        }
        Path target = withExtension(file.toPath(), ".html");
        Thread t = new Thread(() -> {
            try {
                Path saved = exportService.exportSessionHtml(sessionId, target);
                Platform.runLater(() -> GimDialog.message(ownerWindow.get(), I18n.t("session.export.html"), null,
                        I18n.t("settings.export.done", saved.getFileName())));
            } catch (RuntimeException e) {
                log.warn("导出会话网页失败", e);
                Platform.runLater(() -> GimDialog.message(ownerWindow.get(), I18n.t("session.export.html"), null,
                        I18n.t("settings.data.fail", String.valueOf(e.getMessage()))));
            }
        }, "session-html-export");
        t.setDaemon(true);
        t.start();
    }

    /** 弹「另存为」对话框（标题、建议文件名与扩展名过滤器按导出格式定制）；取消返回 null。 */
    private File chooseExportTarget(String title, ChatSession session, String ext, String filterText) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(exportService.suggestedFileName(
                session.getTitle() == null || session.getTitle().isBlank() ? session.getSessionId() : session.getTitle(),
                ext));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(filterText, "*" + ext));
        return chooser.showSaveDialog(ownerWindow.get());
    }

    /** 用户未按选中格式补后缀时自动补上（与设置页导出同规则）。 */
    private static Path withExtension(Path p, String ext) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(ext) ? p : Path.of(p.toString() + ext);
    }
}
