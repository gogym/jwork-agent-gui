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

package com.gettyio.jwork.agent.ui.chat.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gettyio.jwork.agent.ui.theme.UiTheme;
import com.gettyio.jwork.agent.ui.jcef.JcefOsrView;
import com.gettyio.jwork.agent.ui.jcef.JcefResources;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mermaid 图表视图：以 JCEF(Chromium OSR) 加载打包进应用资源的 {@code mermaid.min.js} 离线渲染。
 * <p>页面由 {@link JcefResources#mermaidShell} 落地到临时目录（{@code mermaid.min.js} 与图表 HTML 同目录，
 * 相对脚本引用为同源）；图表源码放入 {@code <pre class="mermaid">}，mermaid 初始化时自动渲染。渲染/重排后
 * JS 经 {@code reportHeight} 垫片回抛文档高度，本类据此把宿主节点高度对齐到刚好包住图形
 * （外层滚动由消息区/阅读区统一处理，图内不出现双层滚动条）。主题跟随 {@link UiTheme}。</p>
 * <p>资源缺失或页面生成失败时 {@link #tryCreate} 仍按旧约定工作：资源不在 classpath 直接返回 {@code null}
 * （调用方回退普通代码块渲染）。</p>
 * <p>同主题同源码的图表按 {@link #POOL} 复用节点：{@link MarkdownRenderer#render(String)} 每次都给全新
 * 节点列表，反复打开同一份文档就要重来一遍「建 Chromium 浏览器 + 载入页面 + 渲染图形」，
 * 而这一下比渲染整页 Markdown 本身贵得多。</p>
 *
 * @author gogym
 */
public class MermaidView {

    private static final Logger log = LoggerFactory.getLogger(MermaidView.class);

    /** 打包在资源目录里的 mermaid 离线脚本。 */
    public static final String MERMAID_JS = "/js/mermaid.min.js";

    private static final String QUERY_FN = "jworkQuery";
    private static final String CANCEL_FN = "jworkCancel";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 宿主节点默认宽度（CSS 像素）：容器未定宽前的初始视口宽度。 */
    private static final double DEFAULT_WIDTH = 640;
    /** 池内最多留几张图：每张占一个浏览器与帧缓冲，够用即可（一份文档通常只有一两张图）。 */
    private static final int POOL_SIZE = 4;
    /** 复用池（键 = 主题 + 源码，最近使用优先淘汰）。 */
    private static final LinkedHashMap<String, MermaidView> POOL =
            new LinkedHashMap<String, MermaidView>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MermaidView> eldest) {
                    if (size() <= POOL_SIZE) {
                        return false;
                    }
                    eldest.getValue().releaseIfIdle();
                    return true;
                }
            };

    private final String poolKey;
    private final JcefOsrView view;
    private final StackPane host;
    /** 渲染完成后由 JS 回调写入的文档高度（像素）。 */
    private volatile double docHeight = 120;
    /** 已交出但还没真正挂进场景图：同一次渲染里的第二张同图不能抢同一个节点。 */
    private volatile boolean handedOut;
    /** 浏览器是否已释放（释放过一次就到底，池里的这条条目随之作废）。 */
    private volatile boolean released;

    private MermaidView(String code, String poolKey) {
        this.poolKey = poolKey;
        this.view = new JcefOsrView(QUERY_FN, CANCEL_FN, this::onRequest);
        this.host = view.node();
        this.host.setPrefSize(DEFAULT_WIDTH, 120);
        this.view.syncSize(DEFAULT_WIDTH, 120);
        this.view.loadUrl(mermaidUrl(code));
        this.host.parentProperty().addListener((obs, was, now) -> {
            if (now != null) {
                handedOut = false;
            }
        });
        // 一张图 = 一个 Chromium 浏览器实例。离开场景时：仍被池记录的留着复用，不再记录的当场释放，
        // 否则预览过的每个文档都把浏览器与帧缓冲留在进程里，直到退出。
        this.host.sceneProperty().addListener((obs, was, now) -> {
            if (was != null && now == null && !isPooled()) {
                release();
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

    /**
     * 尝试渲染；mermaid 资源不在 classpath 时返回 {@code null} 交由调用方回退。
     * <p>命中的旧图必须已经真正显示过又离了屏才复用——页面还渲染在那里，连载页都不必再发一次。</p>
     */
    public static MermaidView tryCreate(String code) {
        if (MermaidView.class.getResource(MERMAID_JS) == null) {
            log.warn("缺少 {}，Mermaid 回退为代码块渲染", MERMAID_JS);
            return null;
        }
        String key = poolKey(code);
        synchronized (POOL) {
            MermaidView pooled = POOL.get(key);
            if (pooled != null) {
                if (pooled.reusable()) {
                    pooled.handedOut = true;
                    return pooled;
                }
                if (pooled.released) {
                    POOL.remove(key);
                }
            }
            MermaidView fresh = new MermaidView(code, key);
            fresh.handedOut = true;
            POOL.put(key, fresh);
            return fresh;
        }
    }

    /** 池键：主题 + 源码。明暗两套壳渲染结果不同，换主题不能拿旧图顶替。 */
    private static String poolKey(String code) {
        return (UiTheme.mode() == UiTheme.Mode.DARK ? "d" : "l") + '\n' + (code == null ? "" : code);
    }

    /** 可复用：浏览器还活着、已真正显示过一次、此刻不在任何容器里。 */
    private boolean reusable() {
        return !released && !handedOut && host.getParent() == null;
    }

    private boolean isPooled() {
        synchronized (POOL) {
            return POOL.get(poolKey) == this;
        }
    }

    /** 淘汰或让位时的回收：没挂在任何容器上才释放，正被看着的那张不打断（它下次离屏时自行释放）。 */
    private void releaseIfIdle() {
        if (host.getParent() == null) {
            release();
        }
    }

    private void release() {
        if (released) {
            return;
        }
        released = true;
        view.dispose();
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
