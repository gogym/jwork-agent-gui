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

/**
 * UI 层通用纯函数工具：不依赖场景图与窗口状态，供各页面共享的展示格式化与轻量计算。
 * <p>{@link UiFormat} 提供字节大小等格式化，统一多语言环境下的数值形态；
 * 与 {@code ui.control} 的自绘控件不同，本包只含静态纯函数，无 JavaFX 控件语义。</p>
 */
package com.gettyio.gim.agent.ui.util;
