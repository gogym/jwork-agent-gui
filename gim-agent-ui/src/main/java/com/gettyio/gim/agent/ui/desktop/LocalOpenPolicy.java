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

package com.gettyio.gim.agent.ui.desktop;

import com.gettyio.gim.agent.core.fs.CanonicalPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 本机文件与外链的「交系统处置」边界：聊天正文里的路径、产物卡、图片缩略、工作区右键菜单，
 * 最终都经这里出去，因为它们的文本由模型产出。
 *
 * <p>两件事必须钉住：</p>
 * <ol>
 *   <li><b>路径要在授权目录内，且要看规范形。</b>字符串归一挡不住符号链接：模型写
 *       {@code workspace/link/备注.txt}（link 指向 {@code C:\Users\me\Desktop}），
 *       前缀比较一路放行，双击却把桌面上的文件打开了。{@link CanonicalPath} 逐级跟随链接后再判。</li>
 *   <li><b>可执行与脚本类扩展名一律不交系统「打开」。</b>Windows 上双击 {@code .bat/.cmd/.vbs/.js/
 *       .ps1/.jar/.lnk} 乃至 {@code .py}（Python 启动器接管关联时）就是<b>运行</b>它，
 *       而「打开产物」这个动作在用户心里是「看一眼」，不是「执行一次」。脚本文本请用应用内预览阅读。</li>
 * </ol>
 *
 * <p>未装配授权根（{@link #configure}）时一律拒绝：安全边界缺配置时不能默认放开。</p>
 *
 * @author gogym
 */
public final class LocalOpenPolicy {

    private static final Logger log = LoggerFactory.getLogger(LocalOpenPolicy.class);

    /**
     * 交系统打开等于执行（或挂载、导入注册表）的扩展名。
     * <p>含 {@code py}：python.org 安装器把 {@code .py} 关联到启动器并直接运行，双击一个模型刚写好的
     * 脚本就是执行它。{@code iso}/{@code img} 也在此列——Windows 双击即挂载，历史上是常见的载荷载体。</p>
     */
    private static final Set<String> EXECUTABLE_EXTENSIONS = Set.of(
            "exe", "com", "scr", "msi", "msp", "app", "jar", "bat", "cmd", "vbs", "vbe",
            "js", "jse", "ws", "wsf", "wsh", "ps1", "psm1", "ps1xml", "msc", "cpl", "hta",
            "lnk", "reg", "sh", "py", "pyw", "iso", "img", "xdm");

    /** 可以直接交给浏览器的协议；其余 scheme 可能被本机注册的处理器拿去干别的。 */
    private static final Set<String> BROWSABLE_SCHEMES = Set.of("http", "https");

    /** 授权根快照；未装配即空集，任何本机路径都判为越界。 */
    private static volatile Supplier<Collection<Path>> roots = List::of;

    private LocalOpenPolicy() {
    }

    /** 装配授权根（工作区 + 各智能体项目目录 + 设置页新增目录），实时取快照，改设置不必重启。 */
    public static void configure(Supplier<Collection<Path>> allowedRoots) {
        roots = allowedRoots == null ? List::of : allowedRoots;
    }

    /** 文件名是否属于「打开即执行」那一类（扩展名大小写不敏感）。 */
    public static boolean isExecutableLike(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        // ".tar.gz" 这类复合后缀：末段是 gz，不在名单里；"exe." 结尾无扩展名
        return EXECUTABLE_EXTENSIONS.contains(ext);
    }

    /**
     * 落在授权根内的规范形路径（越界返回 {@code null}）；不判断类型，也不看扩展名。
     * <p>供「先归一、再决定去哪个界面」的调用方复用：应用内<b>阅读</b>一个 {@code .py} 产物是安全的，
     * 只有交回操作系统那一步才需要叠加可执行扩展名门槛。</p>
     */
    public static Path allowedPath(Path candidate) {
        return CanonicalPath.withinAny(candidate, roots.get());
    }

    /** 可在系统程序里打开的普通文件：授权根内的规范形；越界、目录、脚本类一律返回 null。 */
    public static Path openableFile(Path candidate) {
        Path canonical = allowedPath(candidate);
        if (canonical == null || !Files.isRegularFile(canonical) || isExecutableLike(canonical.getFileName().toString())) {
            return null;
        }
        return canonical;
    }

    /** 可在文件管理器里定位的目录：授权根内的规范形目录，否则 null。 */
    public static Path openableDirectory(Path candidate) {
        Path canonical = allowedPath(candidate);
        if (canonical == null || !Files.isDirectory(canonical)) {
            return null;
        }
        return canonical;
    }

    /**
     * 交系统默认程序打开文件；不合格返回 {@code false} 并记 WARN（路径来自模型文本，静默拒绝不好排查）。
     * <p>调用方不需要自己再做边界检查——重复检查就会有人漏检查。</p>
     */
    public static boolean openFile(Path file, String what) {
        Path target = openableFile(file);
        if (target == null) {
            log.warn("拒绝以系统程序打开: {}（越出授权目录、不存在或属可执行类）: {}", what, file);
            return false;
        }
        return dispatch(target, "openFile");
    }

    /** 在系统文件管理器里打开目录；越界或不是目录返回 {@code false}。 */
    public static boolean revealDirectory(Path dir, String what) {
        Path target = openableDirectory(dir);
        if (target == null) {
            log.warn("拒绝在文件管理器里打开: {}（越出授权目录或不是目录）: {}", what, dir);
            return false;
        }
        return dispatch(target, "revealDirectory");
    }

    private static boolean dispatch(Path target, String action) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(target.toFile());
                return true;
            }
            log.debug("当前环境不支持 Desktop.open，{} 未执行: {}", action, target);
        } catch (Exception e) {
            // 未关联程序是常态，路径已在提示里给过用户
            log.warn("{} 失败: {}", action, target, e);
        }
        return false;
    }

    /**
     * 外链是否可直接交浏览器打开。
     * <p>{@code file:} 由本地分支处理，{@code javascript:}/{@code data:}/{@code search:} 之类
     * 交给 {@code Desktop.browse} 的结果取决于本机注册了什么处理器，不在放开范围里。</p>
     */
    public static boolean isBrowsableLink(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return BROWSABLE_SCHEMES.contains(scheme) && uri.getHost() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 交浏览器打开外链；scheme 不合规或环境不支持时返回 {@code false}。 */
    public static boolean browse(String raw) {
        if (!isBrowsableLink(raw)) {
            log.warn("拒绝打开非 http(s) 外链: {}", raw);
            return false;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(raw.trim()));
                return true;
            }
        } catch (Exception e) {
            log.warn("打开外链失败: {}", raw, e);
        }
        return false;
    }
}
