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

package com.gettyio.jwork.agent.ui.config;

import com.gettyio.jwork.agent.domain.JworkDatabase;
import com.gettyio.jwork.agent.domain.security.SecretStore;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.model.transport.ProxyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全局网络代理（HTTP/HTTPS）：设置持久化于 settings 表（密码经 {@link SecretStore} 加密），
 * 保存即应用到进程，覆盖应用内全部出网路径：
 * <ul>
 *   <li><b>系统属性</b>（http.proxyHost 等）：所有未显式指定代理的 JDK HttpClient 建连时实时读取
 *       （模型探测、知识嵌入、市场、更新检查、语音、图像生成、MCP 客户端等）；</li>
 *   <li><b>AgentScope 默认传输</b>（{@link HttpTransportFactory#setDefault}）：LLM 对话调用，
 *       支持代理认证；</li>
 *   <li><b>WebDAV 云同步</b>：Sardine 建连时取 JVM 默认 ProxySelector（实时读系统属性），随系统属性生效。</li>
 * </ul>
 * <p>本机地址（localhost / 127.* / [::1]）恒定绕过，避免本地模型（Ollama）与内嵌服务被代理劫持。
 * 代理变更后调用方需失效模型缓存与运行体，使新出口于下一轮对话生效。</p>
 *
 * @author gogym
 */
public class ProxyManager {

    private static final Logger log = LoggerFactory.getLogger(ProxyManager.class);

    /** 代理开关：{@code true} 启用 */
    public static final String KEY_ENABLED = "net.proxy.enabled";
    /** 代理主机 */
    public static final String KEY_HOST = "net.proxy.host";
    /** 代理端口 */
    public static final String KEY_PORT = "net.proxy.port";
    /** 认证用户名（明文存储） */
    public static final String KEY_USERNAME = "net.proxy.username";
    /** 认证密码（SecretStore 加密存储） */
    public static final String KEY_PASSWORD = "net.proxy.password";
    /** 绕过列表：逗号/分号分隔，支持 * 通配 */
    public static final String KEY_BYPASS = "net.proxy.bypass";

    /** JDK nonProxyHosts 语法下的恒定本机绕过项（含通配） */
    private static final String LOCAL_BYPASS = "localhost|127.*|[::1]";
    /** ProxyConfig 绕过集合下的恒定本机项（与 {@link #LOCAL_BYPASS} 同口径） */
    private static final List<String> LOCAL_BYPASS_ITEMS = List.of("localhost", "127.*", "[::1]");
    /** 应用管理的系统属性：应用与清理同源，避免开关切换残留 */
    private static final String[] MANAGED_PROPS = {
            "http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "http.nonProxyHosts"};
    /** settings.remark 文案：与既有入库行保持一致。 */
    private static final String REMARK_PASSWORD = "网络代理认证密码（加密）";

    private final JworkDatabase db;
    private final SecretStore secrets;

    public ProxyManager(JworkDatabase db, SecretStore secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    /** 代理设置快照：{@code port <= 0} 表示未填；{@code password} 为解密后的明文。 */
    public record Settings(boolean enabled, String host, int port, String username, String password, String bypass) {
    }

    // ---- 读写 ----

    /** 读取当前设置（密码本机解不开时返回空串，界面用 {@link #passwordState()} 区分「未配置」与「解不开」）。 */
    public Settings settings() {
        boolean enabled = db.settingDao().getValue(KEY_ENABLED)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(false);
        String host = db.settingDao().getValue(KEY_HOST).orElse("").trim();
        int port = parsePort(db.settingDao().getValue(KEY_PORT).orElse(""));
        String username = db.settingDao().getValue(KEY_USERNAME).orElse("").trim();
        String password = secrets.open(stored(KEY_PASSWORD), "网络代理密码");
        String bypass = db.settingDao().getValue(KEY_BYPASS).orElse("").trim();
        return new Settings(enabled, host, port, username, password, bypass);
    }

    /**
     * 保存设置并立即应用。
     * <p>{@code password} 留空表示<b>沿用已存密码</b>，不是清除：界面上的密码框不回显明文，
     * 把空串当清除会让一次瞬时解密失败加失焦永久抹掉认证凭据。撤销认证请调
     * {@link #clearPassword()}。</p>
     */
    public void save(Settings s) {
        db.settingDao().upsert(KEY_ENABLED, String.valueOf(s.enabled()), "网络代理开关（true/false）");
        db.settingDao().upsert(KEY_HOST, s.host(), "网络代理主机（HTTP/HTTPS）");
        db.settingDao().upsert(KEY_PORT, s.port() > 0 ? String.valueOf(s.port()) : "", "网络代理端口");
        db.settingDao().upsert(KEY_USERNAME, s.username(), "网络代理认证用户名（明文）");
        if (s.password() != null && !s.password().isBlank()) {
            db.settingDao().upsert(KEY_PASSWORD, secrets.seal(s.password()), REMARK_PASSWORD);
        }
        db.settingDao().upsert(KEY_BYPASS, s.bypass(), "网络代理绕过列表：逗号/分号分隔，支持 * 通配");
        apply();
    }

    /** 显式清除已存认证密码（改为无认证）。 */
    public void clearPassword() {
        db.settingDao().upsert(KEY_PASSWORD, "", REMARK_PASSWORD);
        apply();
    }

    /** 密码项存储状态。 */
    public SecretStore.State passwordState() {
        return secrets.state(stored(KEY_PASSWORD));
    }

    private String stored(String key) {
        return db.settingDao().getValue(key).orElse("");
    }

    // ---- 应用 ----

    /** 依据当前设置应用到进程：启用且主机/端口齐备则设系统属性 + AgentScope 默认传输；否则全部清除。 */
    public synchronized void apply() {
        Settings s = settings();
        if (s.enabled() && !s.host().isEmpty() && s.port() > 0) {
            applySystemProperties(s);
            applyDefaultTransport(s);
            log.info("网络代理已应用: {}:{}{}", s.host(), s.port(),
                    s.bypass().isEmpty() ? "" : "（绕过 " + s.bypass() + "）");
        } else {
            clearSystemProperties();
            HttpTransportFactory.setDefault(null);
            if (s.enabled()) {
                log.warn("网络代理已启用但主机/端口不完整，按未启用处理");
            }
        }
    }

    /** 系统属性通道：JDK HttpClient / MCP / Sardine 等默认出网路径建连时实时读取。 */
    private static void applySystemProperties(Settings s) {
        clearSystemProperties();
        System.setProperty("http.proxyHost", s.host());
        System.setProperty("http.proxyPort", String.valueOf(s.port()));
        System.setProperty("https.proxyHost", s.host());
        System.setProperty("https.proxyPort", String.valueOf(s.port()));
        System.setProperty("http.nonProxyHosts", nonProxyHosts(s.bypass()));
    }

    private static void clearSystemProperties() {
        for (String key : MANAGED_PROPS) {
            System.clearProperty(key);
        }
    }

    /** AgentScope 传输通道：LLM 对话调用走独立传输实例（可携带 HTTP 代理认证）。 */
    private static void applyDefaultTransport(Settings s) {
        ProxyConfig.Builder proxy = ProxyConfig.builder()
                .type(ProxyType.HTTP)
                .host(s.host())
                .port(s.port())
                .nonProxyHosts(bypassSet(s.bypass()));
        if (!s.username().isEmpty()) {
            proxy.username(s.username()).password(s.password());
        }
        HttpTransportConfig transport = HttpTransportConfig.builder().proxy(proxy.build()).build();
        HttpTransportFactory.setDefault(JdkHttpTransport.builder().config(transport).build());
    }

    /** 绕过集合（ProxyConfig 语法）：本机项 + 用户项。 */
    private static Set<String> bypassSet(String userBypass) {
        Set<String> set = new LinkedHashSet<>(LOCAL_BYPASS_ITEMS);
        set.addAll(splitBypass(userBypass));
        return set;
    }

    /** 绕过串（JDK nonProxyHosts 语法）：本机项 + 用户项以 {@code |} 串接。 */
    private static String nonProxyHosts(String userBypass) {
        StringBuilder sb = new StringBuilder(LOCAL_BYPASS);
        for (String item : splitBypass(userBypass)) {
            sb.append('|').append(item);
        }
        return sb.toString();
    }

    /** 拆分绕过列表：逗号/分号/竖线/空白均可作分隔符。 */
    private static List<String> splitBypass(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return items;
        }
        for (String item : raw.split("[;,|\\s]+")) {
            if (!item.isBlank()) {
                items.add(item.trim());
            }
        }
        return items;
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
