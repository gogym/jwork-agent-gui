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

package com.gettyio.jwork.agent.ui.desktop;

import com.gettyio.jwork.agent.domain.util.OsPlatform;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.Structure.FieldOrder;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Windows 原生文件拖放接管（OLE {@code IDropTarget} + {@code WM_DROPFILES} 双通道）。
 *
 * <p>主窗使用 {@code StageStyle.TRANSPARENT}（四角圆角/磨砂需要逐像素透明），此形态下
 * JavaFX Glass 自己的 OLE 放置目标收不到有效拖放（Scene 级 {@code DragEvent} 不派发），且
 * 只要窗口上存在已注册的 OLE 放置目标，系统就不会投递 {@code WM_DROPFILES}——两条路都被堵死。
 * 这里直接接管：{@code RevokeDragDrop} 撤销 Glass 的注册，再 {@code RegisterDragDrop} 挂上
 * 自己的 {@code IDropTarget}（只认 {@code CF_HDROP}），拖放释放时枚举文件清单回调宿主
 * （最终走 {@code AttachmentBar.addDroppedFiles}，与回形针菜单同一分流口径）。</p>
 *
 * <p>兜底：OLE 注册失败时（或从未成功），保留经典 {@code DragAcceptFiles + WM_DROPFILES}
 * 通道——撤销 Glass 目标后该通道即恢复可用。两通道在系统层互斥，不会双份入列。
 * 仅 64 位 Windows 下生效（签名按 8 字节 LONG_PTR 处理）；其余平台静默跳过。
 * 另：应用若以管理员运行，资源管理器（非提升进程）的拖放会被系统 UIPI 拦截，届时任何
 * 通道都收不到——挂接时检测并给出明确日志。</p>
 *
 * @author gogym
 */
public final class NativeFileDrop {

    private static final Logger log = LoggerFactory.getLogger(NativeFileDrop.class);

    /** WM_DROPFILES：拖放释放消息，wParam 为 HDROP 句柄 */
    private static final int WM_DROPFILES = 0x0233;
    /** SetWindowLongPtr 的窗口过程槽位 */
    private static final int GWLP_WNDPROC = -4;
    /** JavaFX Glass 窗口的 Win32 类名前缀（实际形如 GlassWndClass-GlassWindow-N） */
    private static final String CLASS_PREFIX = "Glass";
    /** HWND 定位失败后的重试次数与间隔：窗口刚 show 时可能尚在创建过程中 */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 300;
    /** 尺寸匹配套容差（物理像素）：DPI 缩放取整与窗口阴影边框的余量 */
    private static final double SIZE_TOLERANCE_PX = 64;
    /** 单个文件路径缓冲的字符数（覆盖 Windows 最长路径） */
    private static final int PATH_CHARS = 4096;

    /** 剪贴板格式：文件列表 */
    private static final short CF_HDROP = 15;
    private static final int TYMED_HGLOBAL = 1;
    private static final int DVASPECT_CONTENT = 1;
    private static final int DROPEFFECT_COPY = 1;
    private static final int S_OK = 0;
    private static final int E_FAIL = 0x80004005;
    private static final int RPC_E_CHANGED_MODE = 0x80010106;

    /** ChangeWindowMessageFilterEx 的 action：显式放行低完整性进程发来的消息 */
    private static final int MSGFLT_ALLOW = 1;
    /** 提权窗口接收拖放须放行的消息：WM_DROPFILES + OLE 跨进程握手私有消息（0x0049/0x004A=WM_COPYDATA） */
    private static final int[] DROP_MESSAGES = {0x0233, 0x0049, 0x004A};

    /** 已挂接的窗口：防同一窗口重复挂接 */
    private static final List<Window> INSTALLED = new CopyOnWriteArrayList<>();
    /** 挂接产物的强引用：JNA 回调/COM vtable 一旦被 GC，再有消息进来自动崩溃 */
    private static final List<Object> PINNED = new CopyOnWriteArrayList<>();

    /** user32 平台库未暴露的窗口过程操作；签名按 x64 的 8 字节指针位宽声明 */
    private interface User32Ex extends Library {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        /** 子类化窗口过程，返回原过程地址（LONG_PTR） */
        long SetWindowLongPtrW(WinDef.HWND hWnd, int nIndex, WndProc proc);

        /** 转发到原窗口过程（返回值同为 LONG_PTR） */
        long CallWindowProcW(long prevProc, Pointer hWnd, int msg, long wParam, long lParam);

        /** 修改窗口的 UIPI 消息过滤器（提权窗口接收低权限进程拖放的官方机制） */
        boolean ChangeWindowMessageFilterEx(WinDef.HWND hWnd, int msg, int action,
                                            Pointer pChangeFilterStruct);

        /** 进程级 UIPI 消息过滤器（旧 API，与窗口级互补；MSGFLT_ADD 与 MSGFLT_ALLOW 同值） */
        boolean ChangeWindowMessageFilter(int msg, int action);
    }

    /** shell32 经典拖放 API 与提权检测（平台库未收录） */
    private interface Shell32Ex extends Library {
        Shell32Ex INSTANCE = Native.load("shell32", Shell32Ex.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        /** 打开/关闭窗口的 WS_EX_ACCEPTFILES 样式 */
        void DragAcceptFiles(WinDef.HWND hWnd, boolean accept);

        /** 枚举 HDROP 中的文件名：index=0xFFFFFFFF 取总数，否则取第 index 条 */
        int DragQueryFileW(Pointer hDrop, int index, Pointer buffer, int cch);

        /** 释放系统为 HDROP 分配的内存 */
        void DragFinish(Pointer hDrop);

        /** 当前进程是否以管理员令牌运行（已弃用但仅作诊断用途） */
        boolean IsUserAnAdmin();
    }

    /** OLE 拖放注册与存储介质释放 */
    private interface Ole32Ex extends Library {
        Ole32Ex INSTANCE = Native.load("ole32", Ole32Ex.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        /** 当前线程初始化 OLE（已初始化返回 S_FALSE，无害） */
        int OleInitialize(Pointer pvReserved);

        /** 撤销窗口上已注册的放置目标（Glass 的对透明窗口无效，须让位） */
        int RevokeDragDrop(WinDef.HWND hWnd);

        /** 注册放置目标 */
        int RegisterDragDrop(WinDef.HWND hWnd, Pointer pDropTarget);

        /** 释放 GetData 填充的 STGMEDIUM */
        void ReleaseStgMedium(Pointer pStgMedium);
    }

    private interface Kernel32Ex extends Library {
        Kernel32Ex INSTANCE = Native.load("kernel32", Kernel32Ex.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);

        Pointer GlobalLock(Pointer hMem);

        boolean GlobalUnlock(Pointer hMem);
    }

    /** 替换后的窗口过程：仅拦截 WM_DROPFILES，其余一律转发原过程 */
    private interface WndProc extends StdCallLibrary.StdCallCallback {
        long callback(Pointer hWnd, int msg, long wParam, long lParam);
    }

    /** IDropTarget 成员回调（stdcall）：签名与 vtable 槽位一一对应 */
    private interface QueryCb extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer riid, Pointer ppvOut);
    }

    private interface SelfCb extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self);
    }

    private interface DataCb extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, Pointer dataObj, int keyState, long pt, Pointer effect);
    }

    private interface HoverCb extends StdCallLibrary.StdCallCallback {
        int invoke(Pointer self, int keyState, long pt, Pointer effect);
    }

    /** FORMATETC：GetData 的请求格式（CF_HDROP / HGLOBAL） */
    @FieldOrder({"cfFormat", "ptd", "dwAspect", "lindex", "tymed"})
    static final class FormatEtc extends Structure {
        public short cfFormat;
        public Pointer ptd;
        public int dwAspect;
        public int lindex;
        public int tymed;
    }

    /** STGMEDIUM：GetData 的输出存储介质 */
    @FieldOrder({"tymed", "data", "pUnkForRelease"})
    static final class StgMedium extends Structure {
        public int tymed;
        public Pointer data;
        public Pointer pUnkForRelease;
    }

    private NativeFileDrop() {
    }

    /**
     * 给窗口挂原生拖放接收：普通权限下撤销 Glass 放置目标并注册自己的 OLE
     * {@code IDropTarget}；管理员权限下改走 WM_DROPFILES 兼容通道（见 oleRevokeOnly）。
     * {@code onDrop} 保证在 FX 线程回调；重复对同一 Window 调用无副作用。
     */
    public static void install(Window window, Consumer<List<File>> onDrop) {
        if (window == null || onDrop == null || INSTALLED.contains(window)) {
            return;
        }
        if (!OsPlatform.current().isWindows()) {
            return;
        }
        INSTALLED.add(window);
        if (Shell32Ex.INSTANCE.IsUserAnAdmin()) {
            log.warn("应用以管理员运行：将放行资源管理器（非提升进程）的拖放消息，"
                    + "否则系统 UIPI 会拦截文件拖入");
        }
        log.info("原生文件拖放接管启动：等待窗口显示");
        if (window.isShowing()) {
            tryAttach(window, onDrop, MAX_RETRIES);
        } else {
            window.showingProperty().addListener((obs, was, showing) -> {
                if (showing) {
                    tryAttach(window, onDrop, MAX_RETRIES);
                }
            });
        }
    }

    /** 定位主窗 HWND；刚 show 时窗口可能尚在创建，按重试节奏再找。 */
    private static void tryAttach(Window window, Consumer<List<File>> onDrop, int attemptsLeft) {
        WinDef.HWND hwnd = findMainHwnd(window);
        if (hwnd != null) {
            attach(hwnd, onDrop);
            return;
        }
        if (attemptsLeft <= 0) {
            log.warn("未定位到主窗 HWND，原生文件拖放接管未启用");
            return;
        }
        PauseTransition retry = new PauseTransition(Duration.millis(RETRY_DELAY_MS));
        retry.setOnFinished(e -> tryAttach(window, onDrop, attemptsLeft - 1));
        retry.play();
    }

    /**
     * 在同进程可见窗口中找 JavaFX Glass 窗口：优先取物理尺寸与 Stage 缩放后尺寸最接近者
     * （同进程还有登录门等其它 Glass 窗口，靠尺寸区分）；找不到匹配则回退面积最大的 Glass 窗口。
     */
    private static WinDef.HWND findMainHwnd(Window window) {
        long pid = ProcessHandle.current().pid();
        double scaleX = window.getOutputScaleX() > 0 ? window.getOutputScaleX() : 1;
        double scaleY = window.getOutputScaleY() > 0 ? window.getOutputScaleY() : 1;
        double expectW = window.getWidth() * scaleX;
        double expectH = window.getHeight() * scaleY;

        List<WinDef.HWND> glassWindows = new ArrayList<>();
        List<String> glassClasses = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            try {
                IntByReference pidRef = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hWnd, pidRef);
                if (pidRef.getValue() != pid) {
                    return true;
                }
                if (!User32.INSTANCE.IsWindowVisible(hWnd)) {
                    return true;
                }
                char[] cls = new char[64];
                int len = User32.INSTANCE.GetClassName(hWnd, cls, cls.length);
                if (len <= 0 || !new String(cls, 0, len).startsWith(CLASS_PREFIX)) {
                    return true;
                }
                glassWindows.add(hWnd);
                glassClasses.add(new String(cls, 0, len));
            } catch (Throwable t) {
                log.warn("枚举窗口失败", t);
            }
            return true;
        }, null);
        if (glassWindows.isEmpty()) {
            log.warn("同进程未发现 Glass 窗口");
            return null;
        }
        log.info("Glass 窗口候选: {}（期望物理尺寸 {}x{}）", glassClasses,
                (int) expectW, (int) expectH);

        WinDef.HWND best = null;
        double bestGap = Double.MAX_VALUE;
        WinDef.HWND fallback = null;
        double fallbackArea = -1;
        for (WinDef.HWND hWnd : glassWindows) {
            WinDef.RECT rect = new WinDef.RECT();
            if (!User32.INSTANCE.GetWindowRect(hWnd, rect)) {
                continue;
            }
            double w = rect.right - rect.left;
            double h = rect.bottom - rect.top;
            double area = w * h;
            if (area > fallbackArea) {
                fallbackArea = area;
                fallback = hWnd;
            }
            double gap = Math.abs(w - expectW) + Math.abs(h - expectH);
            if (gap < bestGap && gap <= SIZE_TOLERANCE_PX * 2) {
                bestGap = gap;
                best = hWnd;
            }
        }
        return best != null ? best : fallback;
    }

    /** 挂接：DragAcceptFiles + 子类化窗口过程 + 放置目标（提权时改走 WM_DROPFILES 兼容通道）。 */
    private static void attach(WinDef.HWND hwnd, Consumer<List<File>> onDrop) {
        boolean elevated = Shell32Ex.INSTANCE.IsUserAnAdmin();
        try {
            Shell32Ex.INSTANCE.DragAcceptFiles(hwnd, true);
            DropWndProc handler = new DropWndProc(onDrop);
            long prev = User32Ex.INSTANCE.SetWindowLongPtrW(hwnd, GWLP_WNDPROC, handler);
            if (prev == 0) {
                log.warn("子类化窗口过程失败，WM_DROPFILES 备用通道未启用");
            } else {
                // 先补 prevProc 再入强引用表：消息派发与本赋值同在 FX 线程，无并发窗口
                handler.prevProc = prev;
                PINNED.add(handler);
            }
        } catch (Throwable t) {
            log.warn("WM_DROPFILES 备用通道挂接失败", t);
        }
        if (elevated) {
            allowCrossIntegrityMessages(hwnd);
            oleRevokeOnly(hwnd);
            return;
        }
        oleRegister(hwnd, onDrop);
    }

    /**
     * 提权窗口接收拖放：UIPI 默认拦截资源管理器（中等完整性）发来的拖放消息，
     * 官方机制是逐消息放行。同时做窗口级与进程级放行（验证过的组合：WM_DROPFILES +
     * 0x0049 私有消息 + WM_COPYDATA）。
     */
    private static void allowCrossIntegrityMessages(WinDef.HWND hwnd) {
        try {
            for (int msg : DROP_MESSAGES) {
                boolean ok = User32Ex.INSTANCE.ChangeWindowMessageFilterEx(hwnd, msg, MSGFLT_ALLOW, null);
                boolean procOk = User32Ex.INSTANCE.ChangeWindowMessageFilter(msg, MSGFLT_ALLOW);
                log.info("放行跨完整性拖放消息 0x{}：窗口级 {} / 进程级 {}",
                        Integer.toHexString(msg), ok, procOk);
            }
        } catch (Throwable t) {
            log.warn("放行跨完整性消息失败，提权运行时拖放可能仍不可用", t);
        }
    }

    /**
     * 提权专用：只撤销 OLE 放置目标、不注册自己的。OLE 放置目标的跨进程调用被 UIPI
     * 拦死且无法放行（Electron/Chromium 同款结论）；而 OLE 目标存在时 shell 又不会把
     * 资源管理器拖放转换成 WM_DROPFILES。撤销后窗口只剩 WS_EX_ACCEPTFILES，shell 自动
     * 投递 WM_DROPFILES，配合消息放行即可工作。
     */
    private static void oleRevokeOnly(WinDef.HWND hwnd) {
        try {
            Ole32Ex.INSTANCE.OleInitialize(null);
            int revoked = Ole32Ex.INSTANCE.RevokeDragDrop(hwnd);
            log.info("管理员运行：已撤销 OLE 放置目标（hr=0x{}），改用 WM_DROPFILES 兼容通道",
                    Integer.toHexString(revoked));
        } catch (Throwable t) {
            log.warn("撤销 OLE 放置目标失败，拖放可能仍不可用", t);
        }
    }

    /**
     * OLE 主通道：撤销 Glass 的放置目标后注册自己的 IDropTarget。
     * Glass 的目标对 TRANSPARENT 窗口收不到有效拖放，且会压制 WM_DROPFILES，必须让位。
     */
    private static void oleRegister(WinDef.HWND hwnd, Consumer<List<File>> onDrop) {
        try {
            int ole = Ole32Ex.INSTANCE.OleInitialize(null);
            if (ole == RPC_E_CHANGED_MODE) {
                log.warn("OLE 已按其它并发模式初始化，注册可能受限");
            }
            int revoked = Ole32Ex.INSTANCE.RevokeDragDrop(hwnd);
            DropHandler handler = new DropHandler(onDrop);
            Pointer target = buildDropTarget(handler);
            int hr = Ole32Ex.INSTANCE.RegisterDragDrop(hwnd, target);
            if (hr == S_OK) {
                PINNED.add(handler);
                PINNED.add(target);
                log.info("OLE 文件拖放已接管（RevokeDragDrop hr=0x{}）",
                        Integer.toHexString(revoked));
            } else {
                log.warn("RegisterDragDrop 失败 hr=0x{}，仅剩 WM_DROPFILES 备用通道",
                        Integer.toHexString(hr));
            }
        } catch (Throwable t) {
            log.warn("OLE 拖放注册异常，仅剩 WM_DROPFILES 备用通道", t);
        }
    }

    /**
     * 组装 IDropTarget COM 对象：8 字节对象指针 + 7 槽 vtable
     * （QI / AddRef / Release / DragEnter / DragOver / DragLeave / Drop）。
     */
    private static Pointer buildDropTarget(DropHandler h) {
        Memory vtbl = new Memory(7 * 8);
        vtbl.setPointer(0, CallbackReference.getFunctionPointer(
                (QueryCb) h::queryInterface));
        vtbl.setPointer(8, CallbackReference.getFunctionPointer(
                (SelfCb) h::addRef));
        vtbl.setPointer(16, CallbackReference.getFunctionPointer(
                (SelfCb) h::addRef));
        vtbl.setPointer(24, CallbackReference.getFunctionPointer(
                (DataCb) h::dragEnter));
        vtbl.setPointer(32, CallbackReference.getFunctionPointer(
                (HoverCb) h::dragOver));
        vtbl.setPointer(40, CallbackReference.getFunctionPointer(
                (SelfCb) h::dragLeave));
        vtbl.setPointer(48, CallbackReference.getFunctionPointer(
                (DataCb) h::drop));
        Memory obj = new Memory(8);
        obj.setPointer(0, vtbl);
        return obj;
    }

    /** IDropTarget 实现：始终接受拖入（COPY），释放时只认 CF_HDROP，其余静默忽略。 */
    private static final class DropHandler {

        private final Consumer<List<File>> onDrop;
        /** 悬停诊断只打一次：确认 OLE 通道收到了拖动 */
        private volatile boolean enterLogged;

        DropHandler(Consumer<List<File>> onDrop) {
            this.onDrop = onDrop;
        }

        int queryInterface(Pointer self, Pointer riid, Pointer ppvOut) {
            if (ppvOut != null) {
                ppvOut.setPointer(0, Pointer.NULL);
            }
            return E_FAIL;
        }

        int addRef(Pointer self) {
            return 1;
        }

        int dragLeave(Pointer self) {
            return S_OK;
        }

        int dragEnter(Pointer self, Pointer dataObj, int keyState, long pt, Pointer effect) {
            if (!enterLogged) {
                enterLogged = true;
                log.info("OLE 通道收到 DragEnter（拖动已进入窗口）");
            }
            return hover(effect);
        }

        int dragOver(Pointer self, int keyState, long pt, Pointer effect) {
            return hover(effect);
        }

        int drop(Pointer self, Pointer dataObj, int keyState, long pt, Pointer effect) {
            try {
                setEffect(effect, DROPEFFECT_COPY);
                List<File> files = readHdrop(dataObj);
                if (files != null && !files.isEmpty()) {
                    log.info("收到 OLE 拖放 {} 个条目", files.size());
                    Platform.runLater(() -> onDrop.accept(files));
                }
                return S_OK;
            } catch (Throwable t) {
                log.warn("处理拖放失败", t);
                return E_FAIL;
            }
        }

        private int hover(Pointer effect) {
            try {
                setEffect(effect, DROPEFFECT_COPY);
                return S_OK;
            } catch (Throwable t) {
                return E_FAIL;
            }
        }

        private static void setEffect(Pointer effect, int value) {
            if (effect != null) {
                effect.setInt(0, value);
            }
        }

        /**
         * 经 IDataObject::GetData 取 CF_HDROP 并枚举文件清单。
         * IDataObject vtable：QI(0)/AddRef(1)/Release(2)/GetData(3)/…，GetData 固定在槽 3。
         */
        private List<File> readHdrop(Pointer dataObj) {
            if (dataObj == null) {
                return null;
            }
            FormatEtc fmt = new FormatEtc();
            fmt.cfFormat = CF_HDROP;
            fmt.ptd = null;
            fmt.dwAspect = DVASPECT_CONTENT;
            fmt.lindex = -1;
            fmt.tymed = TYMED_HGLOBAL;
            fmt.write();

            StgMedium stg = new StgMedium();
            stg.write();

            Pointer vtbl = dataObj.getPointer(0);
            Function getData = Function.getFunction(vtbl.getPointer(3 * 8));
            int hr = getData.invokeInt(new Object[]{dataObj, fmt.getPointer(), stg.getPointer()});
            if (hr != S_OK) {
                log.debug("GetData(CF_HDROP) hr=0x{}：非文件拖放，忽略", Integer.toHexString(hr));
                return null;
            }
            stg.read();
            try {
                if (stg.tymed != TYMED_HGLOBAL || stg.data == null) {
                    return null;
                }
                Pointer p = Kernel32Ex.INSTANCE.GlobalLock(stg.data);
                if (p == null) {
                    return null;
                }
                try {
                    // DROPFILES 头：pFiles（文件区偏移）+ POINT + fNC + fWide
                    int offset = p.getInt(0);
                    boolean wide = p.getInt(16) != 0;
                    List<File> files = new ArrayList<>();
                    while (true) {
                        String s = wide ? p.getWideString(offset) : p.getString(offset);
                        offset += wide ? (s.length() + 1) * 2 : (s.length() + 1);
                        if (s.isEmpty()) {
                            break;
                        }
                        files.add(new File(s));
                    }
                    return files;
                } finally {
                    Kernel32Ex.INSTANCE.GlobalUnlock(stg.data);
                }
            } finally {
                Ole32Ex.INSTANCE.ReleaseStgMedium(stg.getPointer());
            }
        }
    }

    /** 窗口过程实现（备用通道）：拦截拖放释放并枚举文件，其余消息原样转发。 */
    private static final class DropWndProc implements WndProc {

        private final Consumer<List<File>> onDrop;
        private long prevProc;

        DropWndProc(Consumer<List<File>> onDrop) {
            this.onDrop = onDrop;
        }

        @Override
        public long callback(Pointer hWnd, int msg, long wParam, long lParam) {
            try {
                if (msg == WM_DROPFILES) {
                    log.info("收到 WM_DROPFILES（兼容通道）");
                    handleDrop(new Pointer(wParam));
                    return 0;
                }
            } catch (Throwable t) {
                log.warn("处理 WM_DROPFILES 失败", t);
            }
            return User32Ex.INSTANCE.CallWindowProcW(prevProc, hWnd, msg, wParam, lParam);
        }

        /** 枚举 HDROP 文件清单并回调宿主；无论成败都须 DragFinish 释放系统内存。 */
        private void handleDrop(Pointer hDrop) {
            List<File> files = new ArrayList<>();
            try {
                int count = Shell32Ex.INSTANCE.DragQueryFileW(hDrop, 0xFFFFFFFF, Pointer.NULL, 0);
                Memory buf = new Memory(PATH_CHARS * 2L);
                for (int i = 0; i < count; i++) {
                    int len = Shell32Ex.INSTANCE.DragQueryFileW(hDrop, i, buf, PATH_CHARS);
                    if (len > 0) {
                        files.add(new File(new String(buf.getCharArray(0, len))));
                    }
                }
            } finally {
                Shell32Ex.INSTANCE.DragFinish(hDrop);
            }
            if (!files.isEmpty()) {
                log.info("收到 WM_DROPFILES 拖放 {} 个条目", files.size());
                Platform.runLater(() -> onDrop.accept(files));
            }
        }
    }
}
