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

package com.gettyio.gim.agent.tools.skill;

import com.gettyio.gim.agent.core.GimPaths;
import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.persist.SqliteConnectionProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置技能播种的逐文件语义：缺才补、有则不动。
 * <p>老工作区已有 SKILL.md（用户可能改过），新版随技能附带的脚本资产必须还能落进去；
 * 反过来已存在的文件一律不覆盖，否则用户的定制技能每启动被打回原版。</p>
 *
 * @author gogym
 */
class SkillServiceSeedTest {

    @TempDir
    Path root;

    private GimPaths paths;
    private GimDatabase db;
    private SkillService service;

    @BeforeEach
    void setUp() {
        paths = new GimPaths(root);
        db = new GimDatabase(new SqliteConnectionProvider(paths.dbFile()));
        db.initSchema();
        service = new SkillService(paths, db);
    }

    private Path office() {
        return paths.skillsDir().resolve("office-documents");
    }

    @Test
    @DisplayName("空工作区：SKILL.md 与脚本资产一并播种，技能登记为 BUILTIN")
    void seedsFilesAndRegistersRows() {
        service.seedBuiltinSkills();
        assertTrue(Files.isRegularFile(office().resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(office().resolve("scripts").resolve("xlsx_peek.py")));
        assertTrue(Files.isRegularFile(office().resolve("scripts").resolve("xlsx_report.py")));
        assertEquals("BUILTIN", db.skillDao().findByCode("office-documents").orElseThrow().getSource());
        assertEquals(4, db.skillDao().findAll().size());
    }

    @Test
    @DisplayName("用户改过的 SKILL.md 不覆盖，缺的脚本照样补齐")
    void keepsUserEditsAndAddsMissingScripts() throws IOException {
        Path skillMd = office().resolve("SKILL.md");
        Files.createDirectories(office());
        Files.writeString(skillMd, "我自己写的办公技能\n", StandardCharsets.UTF_8);

        service.seedBuiltinSkills();

        assertEquals("我自己写的办公技能\n", Files.readString(skillMd, StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(office().resolve("scripts").resolve("xlsx_peek.py")),
                "老工作区缺的脚本资产应补齐");
        assertEquals("BUILTIN", db.skillDao().findByCode("office-documents").orElseThrow().getSource(),
                "文件在但库里没行，也要补登记");
    }

    @Test
    @DisplayName("禁用态技能整体跳过，不在启用目录里复活")
    void skipsDisabledSkill() {
        Path parked = paths.workspaceDir().resolve("skills-disabled").resolve("translate");
        try {
            Files.createDirectories(parked);
            Files.writeString(parked.resolve("SKILL.md"), "停用中\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        service.seedBuiltinSkills();
        assertFalse(Files.exists(paths.skillsDir().resolve("translate")));
    }

    @Test
    @DisplayName("重复播种幂等：文件与技能行都不翻倍")
    void idempotentSecondRun() throws IOException {
        service.seedBuiltinSkills();
        Path skillMd = office().resolve("SKILL.md");
        Files.writeString(skillMd, "别动我\n", StandardCharsets.UTF_8);

        service.seedBuiltinSkills();

        assertEquals("别动我\n", Files.readString(skillMd, StandardCharsets.UTF_8));
        assertEquals(4, db.skillDao().findAll().size());
    }
}
