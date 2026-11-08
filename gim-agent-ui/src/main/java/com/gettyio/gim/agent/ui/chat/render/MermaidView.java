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

package com.gettyio.gim.agent.ui.chat.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.gim.agent.ui.theme.UiTheme;
import com.gettyio.gim.agent.ui.jcef.JcefOsrView;
import com.gettyio.gim.agent.ui.jcef.JcefResources;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mermaid 图表视图：以 JCEF(Chromium OSR) 加载打包进应用资源的 {@code mermaid.min.js} 离线渲染。
 * <p>页面由 {@link JcefResources#mermaidShell} 落地到临时目录（{@code mermaid.min.js} 与图表 HTML 同目录，
 * 相对脚本引用为同源）；图表源码放入 {@code <pre class="mermaid">}，mermaid 初始化时自动渲染。渲染/重排后
 * JS 经 {@code reportHeight} 垫片回抛文档高度，本类据此把宿主节点高度对齐到刚好包住图形
 * （外层滚动由消息区/阅读区统一处理，图内不出现双层滚动条）。主题跟随 {@link UiTheme}。</p>
 * <p>资源缺失或页面生成失败时 {@link #tryCreate} 仍按旧约定工作：资源不在 classpath 直接返回 {@code null}
 * （调用方回退普通代码块渲染）。</p>
 *
 * @author gogym
 */
public class MermaidView {

    private static final Logger log = LoggerFactory.getLogger(MermaidView.class);

    /** 打包在资源目录里的 mermaid 离线脚本。 */
    public static final String MERMAID_JS = "/js/mermaid.min.js";

    private static final String QUERY_FN = "gimQuery";
    private static final String CANCEL_FN = "gimCancel";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 宿主节点默认宽度（CSS 像素）：容器未定宽前的初始视口宽度。 */
    private static final double DEFAULT_WIDTH = 640;

    private final JcefOsrView view;
    private final StackPane host;
    /** 渲染完成后由 JS 回调写入的文档高度（像素）。 */
    private volatile double docHeight = 120;

    private MermaidView(String code) {
        this.view = new JcefOsrView(QUERY_FN, CANCEL_FN, this::onRequest);
        this.host = view.node();
        this.host.setPrefSize(DEFAULT_WIDTH, 120);
        this.view.syncSize(DEFAULT_WIDTH, 120);
        this.view.loadUrl(mermaidUrl(code));
        // 一张图 = 一个 Chromium 浏览器实例，而每次重渲染都新建视图、旧节点不再复用
        // （MarkdownRenderer.render 总返回全新节点列表）。离开场景即释放，否则预览过的每个
        // 文档都把浏览器与帧缓冲留在进程里，直到退出。
        this.host.sceneProperty().addListener((obs, was, now) -> {
            if (was != null && now == null) {
                view.dispose();
            }
        });
    }

    private static String mermaidUrl(String code) {
        try {
            boolean dark = UiTheme.mode() == UiTheme.Mode.DARK;
            return JcefResources.mermaidShell(code, dark, QUERY_FN);
        } catch (Exception e) {
            log.warn("Mermaid 页面生成失败，回退空白页", e);
            return "about:blank";
        }
    }

    /** 尝试渲染；mermaid 资源不在 classpath 时返回 {@code null} 交由调用方回退。 */
    public static MermaidView tryCreate(String code) {
        if (MermaidView.class.getResource(MERMAID_JS) == null) {
            log.warn("缺少 {}，Mermaid 回退为代码块渲染", MERMAID_JS);
            return null;
        }
        return new MermaidView(code);
    }

    /** 渲染出的节点（宿主已按内容高度自适应）。 */
    public Parent node() {
        return host;
    }

    /**
     * JS→Java 高度回调：渲染/重排完成时把宿主高度对齐到文档实际高度。
     * <p>已在 FX 线程触发（{@link JcefOsrView} 的 {@code onQuery} 经 {@code Platform.runLater} 派发）。</p>
     */
    private void onRequest(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!"reportHeight".equals(root.path("fn").asText(""))) {
                return;
            }
            JsonNode a = root.path("args");
            double h = a.isArray() && a.size() > 0 ? a.get(0).asDouble(0) : 0;
            if (h > 0 && h < 4096) {
                docHeight = h + 8;
                host.setPrefHeight(docHeight);
                double w = host.getWidth() > 0 ? host.getWidth() : DEFAULT_WIDTH;
                view.syncSize(w, docHeight);
            }
        } catch (Throwable t) {
            log.warn("Mermaid 高度回调解析失败", t);
        }
    }
}
