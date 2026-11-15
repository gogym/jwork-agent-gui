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

package com.gettyio.gim.agent.ui.market;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MarketService} 纯逻辑单测：可更新判定、依赖获取指引映射、依赖 PATH 探测
 * 与安装入参校验。不触网、不依赖 Spring（协作服务保持 null，被测路径在触碰它们之前完成校验）。
 *
 * @author gogym
 */
class MarketServiceTest {

    /** 不注入协作服务：被测方法（版本比较/指引映射/探测/入参校验）均不需要它们。 */
    private final MarketService service = new MarketService(null, null, null, null);

    @Test
    @DisplayName("可更新判定：市场版本为空不可更新，本地未装或版本为空视为可安装")
    void hasNewerVersionEdgeCases() {
        assertFalse(MarketService.hasNewerVersion("1.0.0", null), "市场无版本信息");
        assertFalse(MarketService.hasNewerVersion("1.0.0", ""), "市场版本为空");
        assertFalse(MarketService.hasNewerVersion("1.0.0", "  "), "市场版本为空白");
        assertTrue(MarketService.hasNewerVersion(null, "1.0.0"), "本地未装");
        assertTrue(MarketService.hasNewerVersion("", "1.0.0"), "本地版本为空");
    }

    @Test
    @DisplayName("可更新判定：数值化比较版本高低，v 前缀与预发布后缀归一化后不影响")
    void hasNewerVersionCompare() {
        assertTrue(MarketService.hasNewerVersion("1.0.0", "1.1.0"));
        assertTrue(MarketService.hasNewerVersion("1.9.9", "1.10.0"), "数值比较而非字典序");
        assertFalse(MarketService.hasNewerVersion("1.1.0", "1.0.0"), "本地更新不提示");
        assertFalse(MarketService.hasNewerVersion("1.0.0", "1.0.0"));
        assertFalse(MarketService.hasNewerVersion("1.0.0", "v1.0.0"), "归一化后相等");
        assertFalse(MarketService.hasNewerVersion("1.0.0", "1.0.0-SNAPSHOT"), "预发布后缀归一到同版本");
    }

    @Test
    @DisplayName("依赖获取指引：已知依赖大小写不敏感，未知/空值返回空串")
    void dependencyHomepageMapping() {
        assertEquals("https://nodejs.org/", MarketService.dependencyHomepage("npx"));
        assertEquals("https://nodejs.org/", MarketService.dependencyHomepage(" NPX "), "大小写与空白不敏感");
        assertTrue(MarketService.dependencyHomepage("uvx").contains("astral.sh"));
        assertTrue(MarketService.dependencyHomepage("docker").contains("docker.com"));
        assertEquals("", MarketService.dependencyHomepage("jwork-no-such-dep"));
        assertEquals("", MarketService.dependencyHomepage(null));
        assertEquals("", MarketService.dependencyHomepage(""));
    }

    @Test
    @DisplayName("依赖探测：null/空列表返回空表，空白与 null 项跳过")
    void probeDependenciesEdgeCases() {
        assertTrue(service.probeDependencies(null).isEmpty());
        assertTrue(service.probeDependencies(List.of()).isEmpty());
        Map<String, Boolean> r = service.probeDependencies(Arrays.asList("  ", null));
        assertTrue(r.isEmpty(), "空白与 null 依赖项应跳过");
    }

    @Test
    @DisplayName("依赖探测：键归一化为小写，不存在的命令探测为缺失且二次结果稳定")
    void probeDependenciesMissing() {
        Map<String, Boolean> first = service.probeDependencies(List.of("JWORK-NONEXISTENT-DEP-9F3D"));
        assertEquals(Boolean.FALSE, first.get("jwork-nonexistent-dep-9f3d"), "键应归一化为小写且探测为缺失");

        Map<String, Boolean> second = service.probeDependencies(List.of("jwork-nonexistent-dep-9f3d"));
        assertEquals(first, second, "重复探测结果应稳定（命中缓存）");

        Map<String, Boolean> npx = service.probeDependencies(List.of("NPX"));
        assertTrue(npx.containsKey("npx"), "真实依赖同样只做键归一化，不断言本机是否安装");
    }

    @Test
    @DisplayName("技能安装入参校验：空条目/缺下载地址抛 IOException 且不触网")
    void installSkillValidation() {
        IOException empty = assertThrows(IOException.class, () -> service.installSkill(null, null));
        assertTrue(empty.getMessage().contains("下载地址"), "异常应提示缺少下载地址");

        MarketService.MarketSkill noUrl = new MarketService.MarketSkill(
                "demo-skill", "演示技能", "desc", "1.0.0", "tester",
                List.of(), "", "", -1, "", 0, "");
        IOException noDownload = assertThrows(IOException.class, () -> service.installSkill(noUrl, null));
        assertTrue(noDownload.getMessage().contains("下载地址"));
    }

    @Test
    @DisplayName("技能包下载地址落在内网时拒掉：市场条目是服务端返回的数据，不是用户填的地址")
    void installSkillRefusesInternalDownloadUrl() {
        MarketService.MarketSkill viaInternal = new MarketService.MarketSkill(
                "demo-skill", "演示技能", "desc", "1.0.0", "tester",
                List.of(), "http://127.0.0.1:8080/skill.zip", "", -1, "", 0, "");

        IOException ex = assertThrows(IOException.class, () -> service.installSkill(viaInternal, null));
        assertTrue(ex.getMessage().contains("内网"), ex.getMessage());
    }
}
