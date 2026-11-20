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

package com.gettyio.jwork.agent.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据根目录解析：jwork.home 属性 &gt; 安装器注册表值 &gt; ~/.jwork。
 * 安装向导（scripts/wix）把用户选择写入注册表，应用侧经 {@link JworkPaths#defaultRoot()} 承接；
 * 优先级或空白值处理出错，都会让打包版把数据落错目录。
 *
 * @author gogym
 */
class JworkPathsTest {

    private static final String HOME = Paths.get(System.getProperty("user.home")).toString();

    @Test
    @DisplayName("jwork.home 属性优先级最高，压过安装器注册表值")
    void propertyOverrideWins() {
        Path root = JworkPaths.resolveRoot("D:\\prop", "D:\\installer", HOME);
        assertEquals(Paths.get("D:\\prop"), root);
    }

    @Test
    @DisplayName("无属性覆盖时取安装器注册表值")
    void installerValueWinsOverDefault() {
        assertEquals(Paths.get("D:\\installer"), JworkPaths.resolveRoot(null, "D:\\installer", HOME));
        assertEquals(Paths.get("D:\\installer"), JworkPaths.resolveRoot("  ", "D:\\installer", HOME));
    }

    @Test
    @DisplayName("两级来源皆空/空白时回落 ~/.jwork")
    void blankSourcesFallBackToDefault() {
        Path expected = Paths.get(HOME, JworkPaths.DEFAULT_ROOT_NAME);
        assertEquals(expected, JworkPaths.resolveRoot(null, null, HOME));
        assertEquals(expected, JworkPaths.resolveRoot("", "   ", HOME));
    }

    @Test
    @DisplayName("来源值两端空白被修剪（注册表/命令行常见尾随空格）")
    void valuesAreTrimmed() {
        assertEquals(Paths.get("D:\\data"), JworkPaths.resolveRoot(null, "  D:\\data  ", HOME));
        assertEquals(Paths.get("D:\\prop"), JworkPaths.resolveRoot(" D:\\prop ", null, HOME));
    }

    @Test
    @DisplayName("defaultRoot 承接 jwork.home 系统属性")
    void defaultRootHonoursSystemProperty() {
        String old = System.getProperty(JworkPaths.HOME_PROPERTY);
        try {
            System.setProperty(JworkPaths.HOME_PROPERTY, "D:\\jwork-test-home");
            assertEquals(Paths.get("D:\\jwork-test-home"), JworkPaths.defaultRoot());
            assertEquals(Paths.get("D:\\jwork-test-home"), new JworkPaths().root());
        } finally {
            if (old == null) {
                System.clearProperty(JworkPaths.HOME_PROPERTY);
            } else {
                System.setProperty(JworkPaths.HOME_PROPERTY, old);
            }
        }
    }

    @Test
    @DisplayName("构造参数为相对路径时规范化为绝对路径（既有行为不回归）")
    void relativeRootBecomesAbsolute() {
        Path root = new JworkPaths(Paths.get("relative-dir")).root();
        assertTrue(root.isAbsolute());
    }
}