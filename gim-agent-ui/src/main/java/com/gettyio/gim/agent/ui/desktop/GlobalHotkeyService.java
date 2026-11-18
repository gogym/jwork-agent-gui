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

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.util.OsPlatform;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 全局热键服务（仅 Windows）：基于 JNA {@code User32.RegisterHotKey} 注册系统级快捷键，
 * 即使应用未获焦点也能唤起「快速问答小窗」「截屏提问」与「划词助手」。
 * <p>实现要点：以 {@code hWnd=NULL} 注册，WM_HOTKEY 投递到注册所在线程的消息队列；故注册、消息泵、
 * 反注册全部收敛在同一条守护线程上完成（{@link #loop()}）。注册/反注册不直接调用，而是把动作
 * {@code offer} 到 {@link #commands} 队列由该线程串行消费，杜绝跨线程调用 Win32 热键 API。</p>
 * <p>非 Windows 平台或注册失败（组合键被占用等）只告警降级，不影响主程序；开关持久化在
 * AppSetting {@code app.hotkey.enabled}（默认开）。</p>
 *
 * @author gogym
 */
public class GlobalHotkeyService {

    private static final Logger log = LoggerFactory.getLogger(GlobalHotkeyService.class);

    /** WinUser 未内置 WM_HOTKEY，本地定义（0x0312） */
    private static final int WM_HOTKEY = 0x0312;
    /** PeekMessage 移除消息标志 */
    private static final int PM_REMOVE = 1;

    private static final int ID_QUICK_ASK = 1;
    private static final int ID_SCREENSHOT = 2;
    private static final int ID_SELECTION = 3;
    /** Ctrl+Alt 组合 */
    private static final int MOD_CTRL_ALT = WinUser.MOD_CONTROL | WinUser.MOD_ALT;
    private static final int VK_Q = 0x51;
    private static final int VK_A = 0x41;
    private static final int VK_D = 0x44;

    /** 热键总开关的持久化键（默认开） */
    public static final String KEY_ENABLED = "app.hotkey.enabled";

    private final GimDatabase db;

    public GlobalHotkeyService(GimDatabase db) {
        this.db = db;
    }

    private volatile Runnable onQuickAsk;
    private volatile Runnable onScreenshot;
    private volatile Runnable onSelection;
    private volatile boolean enabled;
    private volatile boolean running;
    private Thread worker;
    /** 注册/反注册命令：由消息泵线程串行消费，保证 Win32 调用单线程 */
    private final LinkedBlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();

    public void setOnQuickAsk(Runnable onQuickAsk) {
        this.onQuickAsk = onQuickAsk;
    }

    public void setOnScreenshot(Runnable onScreenshot) {
        this.onScreenshot = onScreenshot;
    }

    public void setOnSelection(Runnable onSelection) {
        this.onSelection = onSelection;
    }

    /** 当前平台能否使用全局热键（仅 Windows）。 */
    public boolean isSupported() {
        return OsPlatform.current().isWindows();
    }

    /** 热键当前是否启用（读持久化开关）。 */
    public boolean isEnabled() {
        return readEnabledSetting();
    }

    /**
     * 启动消息泵线程（幂等）：仅 Windows 生效；起始按持久化开关决定是否注册。
     * 回调尚未设置也可先启动，回调随时可替换。
     */
    public synchronized void start() {
        if (!isSupported() || running) {
            return;
        }
        this.enabled = readEnabledSetting();
        this.running = true;
        worker = new Thread(this::loop, "gim-global-hotkey");
        worker.setDaemon(true);
        worker.start();
        log.info("全局热键消息泵已启动（quickAsk=Ctrl+Alt+Q, screenshot=Ctrl+Alt+A, selection=Ctrl+Alt+D, enabled={}）", enabled);
    }

    /**
     * 开启/关闭热键并持久化开关；命令投递到消息泵线程执行注册/反注册。
     *
     * @return 持久化是否成功
     */
    public boolean setEnabled(boolean on) {
        this.enabled = on;
        db.settingDao().upsert(KEY_ENABLED, on ? "true" : "false", "全局热键开关：唤起快速问答与截屏提问");
        if (!isSupported() || !running) {
            return true;
        }
        commands.offer(new HotkeyCommand(on));
        return true;
    }

    /** 消息泵主循环：注册（若启用）→ 轮询命令与消息 → WM_HOTKEY 分派回调 → 退出前反注册。 */
    private void loop() {
        Set<Integer> registered = new HashSet<>();
        try {
            if (enabled) {
                registerAllInto(registered);
            }
            WinUser.MSG msg = new WinUser.MSG();
            while (running) {
                Runnable cmd;
                while ((cmd = commands.poll()) != null) {
                    try {
                        if (cmd instanceof HotkeyCommand hc) {
                            if (hc.enable()) {
                                registerAllInto(registered);
                            } else {
                                unregisterAllFrom(registered);
                            }
                        }
                    } catch (RuntimeException e) {
                        log.warn("热键命令执行失败", e);
                    }
                }
                boolean got = false;
                try {
                    got = User32.INSTANCE.PeekMessage(msg, null, 0, 0, PM_REMOVE);
                } catch (RuntimeException e) {
                    log.warn("PeekMessage 失败，热键轮询暂停本轮", e);
                }
                if (got && msg.message == WM_HOTKEY) {
                    dispatch(msg.wParam.intValue());
                }
                if (!got) {
                    Thread.sleep(40);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("全局热键消息泵异常退出", e);
        } finally {
            unregisterAllFrom(registered);
        }
    }

    /** WM_HOTKEY 按 id 分派到对应回调；回调本身负责切回 FX 线程。 */
    private void dispatch(int id) {
        if (id == ID_QUICK_ASK && onQuickAsk != null) {
            safeRun(onQuickAsk);
        } else if (id == ID_SCREENSHOT && onScreenshot != null) {
            safeRun(onScreenshot);
        } else if (id == ID_SELECTION && onSelection != null) {
            safeRun(onSelection);
        }
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("热键回调执行失败", e);
        }
    }

    private void registerAllInto(Set<Integer> registered) {
        registerOne(registered, ID_QUICK_ASK, VK_Q);
        registerOne(registered, ID_SCREENSHOT, VK_A);
        registerOne(registered, ID_SELECTION, VK_D);
    }

    private void registerOne(Set<Integer> registered, int id, int vk) {
        if (registered.contains(id)) {
            return;
        }
        try {
            if (User32.INSTANCE.RegisterHotKey(null, id, MOD_CTRL_ALT, vk)) {
                registered.add(id);
            } else {
                log.warn("热键注册失败（组合键可能被占用）: Ctrl+Alt+{}", (char) vk);
            }
        } catch (RuntimeException | Error e) {
            log.warn("热键注册异常: Ctrl+Alt+{}", (char) vk, e);
        }
    }

    private void unregisterAllFrom(Set<Integer> registered) {
        for (int id : new HashSet<>(registered)) {
            try {
                User32.INSTANCE.UnregisterHotKey(null, id);
            } catch (RuntimeException e) {
                log.warn("热键反注册失败: id={}", id, e);
            }
        }
        registered.clear();
    }

    private boolean readEnabledSetting() {
        try {
            return db.settingDao().getValue(KEY_ENABLED)
                    .map(v -> !"false".equalsIgnoreCase(v.trim()))
                    .orElse(true);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** 注册/反注册命令：由消息泵线程串行消费，保证 Win32 热键调用单线程。 */
    private record HotkeyCommand(boolean enable) implements Runnable {
        @Override
        public void run() {
            // 实际动作在 loop 中按 enable 判别执行（需访问 registered 集合）
        }
    }

    /** 停止消息泵（幂等）：中断循环，loop 的 finally 负责反注册全部热键。 */
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
