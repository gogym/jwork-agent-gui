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
import com.sun.jna.platform.win32.Kernel32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 保持设备唤醒服务（仅 Windows）：运行期间阻止系统因空闲进入睡眠，避免错过计划或中断任务。
 * <p>基于 JNA {@code Kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)}：
 * 连续标志的时效绑定「调用线程」，故用专用守护线程持续保持；关闭时在同一线程以
 * {@code ES_CONTINUOUS} 单独复位后退出。开关持久化在 AppSetting {@code automation.keep_awake}（默认关），
 * 非 Windows 平台或调用失败只告警降级，不影响主程序。</p>
 *
 * @author gogym
 */
public class KeepAwakeService {

    private static final Logger log = LoggerFactory.getLogger(KeepAwakeService.class);

    /** 开关持久化键（默认关） */
    public static final String KEY_KEEP_AWAKE = "automation.keep_awake";

    /** ES_CONTINUOUS：状态持续生效直至显式清除 */
    private static final int ES_CONTINUOUS = 0x80000000;
    /** ES_SYSTEM_REQUIRED：阻止系统睡眠 */
    private static final int ES_SYSTEM_REQUIRED = 0x00000001;
    /** 保持线程的循环间隔（毫秒）：周期重申状态，抵御系统策略抖动 */
    private static final long REASSERT_INTERVAL_MS = 30_000;

    private final GimDatabase db;

    private volatile boolean running;
    private Thread worker;

    public KeepAwakeService(GimDatabase db) {
        this.db = db;
    }

    /** 当前平台能否使用（仅 Windows）。 */
    public boolean isSupported() {
        return OsPlatform.current().isWindows();
    }

    /** 是否启用（读持久化开关）。 */
    public boolean isEnabled() {
        return readSetting();
    }

    /** 开启/关闭并持久化：启用时以专用线程保持唤醒，关闭时复位并停线程。 */
    public void setEnabled(boolean on) {
        db.settingDao().upsert(KEY_KEEP_AWAKE, on ? "true" : "false", "自动化：运行期间保持设备唤醒");
        if (on) {
            start();
        } else {
            stop();
        }
    }

    /** 应用启动时按持久化开关启动保持线程（幂等）。 */
    public synchronized void start() {
        if (!isSupported() || running || !readSetting()) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "gim-keep-awake");
        worker.setDaemon(true);
        worker.start();
    }

    /** 保持循环：置位 → 周期重申 → 退出前复位（置位/复位必须在同一线程）。 */
    private void loop() {
        try {
            apply(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
            log.info("保持设备唤醒已开启（阻止系统休眠）");
            while (running) {
                Thread.sleep(REASSERT_INTERVAL_MS);
                if (running) {
                    apply(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            apply(ES_CONTINUOUS);
            log.info("保持设备唤醒已关闭（恢复系统休眠策略）");
        }
    }

    private static void apply(int flags) {
        try {
            Kernel32.INSTANCE.SetThreadExecutionState(flags);
        } catch (RuntimeException | Error e) {
            log.warn("SetThreadExecutionState 调用失败: flags={}", flags, e);
        }
    }

    private boolean readSetting() {
        try {
            return db.settingDao().getValue(KEY_KEEP_AWAKE)
                    .map(v -> "true".equalsIgnoreCase(v.trim()))
                    .orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 停止保活：置运行标志并中断工作线程（幂等）。 */
    public synchronized void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
