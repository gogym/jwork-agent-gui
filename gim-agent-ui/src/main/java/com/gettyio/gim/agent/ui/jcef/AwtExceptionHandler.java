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

package com.gettyio.gim.agent.ui.jcef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AWT 事件分发线程（EDT）未捕获异常拦截器。
 *
 * <p>通过系统属性 {@code sun.awt.exception.handler} 指定（见 {@code Launcher}），
 * AWT 每次在 EDT 上派发事件抛出未捕获异常时，会实例化本类并回调 {@link #handle(Throwable)}。
 * JCEF OSR 的 {@code GLCanvas} 在 realize/绘制阶段抛出的异常正是走 EDT，默认只打印到
 * 进程 stderr（IDEA 控制台，且被 fast-throw 优化剥掉堆栈），落入日志文件即可稳定取证。</p>
 *
 * <p>此类须为 public、带 public 无参构造、含 {@code public void handle(Throwable)}，
 * 这是 AWT 反射实例化并调用的约定签名。</p>
 *
 * @author gogym
 */
public class AwtExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger("AWT-EDT");

    public AwtExceptionHandler() {
    }

    public void handle(Throwable t) {
        log.error("AWT EDT 未捕获异常（JCEF OSR 渲染相关）", t);
    }
}
