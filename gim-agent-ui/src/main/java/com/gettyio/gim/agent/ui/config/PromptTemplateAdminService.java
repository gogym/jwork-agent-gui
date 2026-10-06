package com.gettyio.gim.agent.ui.config;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.entity.PromptTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 提示词模板管理：prompt_templates 表增删改查与启停。
 * <p>模板供设置页维护、聊天输入区「模板」下拉插入。{@code template_code} 为稳定定位键，
 * 新建时若未提供则生成一个。</p>
 */
public class PromptTemplateAdminService {

    private final GimDatabase db;

    public PromptTemplateAdminService(GimDatabase db) {
        this.db = db;
    }

    public List<PromptTemplate> list() {
        return db.promptTemplateDao().findAll();
    }

    /** 启用中的模板，供聊天输入区下拉。 */
    public List<PromptTemplate> listEnabled() {
        return db.promptTemplateDao().findEnabled();
    }

    /** 保存：按 template_code 存在与否决定更新或插入；新建补编码。 */
    public void save(PromptTemplate t) {
        if (t.getTemplateCode() == null || t.getTemplateCode().isBlank()) {
            t.setTemplateCode("tpl-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (t.getEnabled() == null) {
            t.setEnabled(1);
        }
        boolean exists = db.promptTemplateDao().findByCode(t.getTemplateCode()).isPresent();
        if (exists) {
            db.promptTemplateDao().update(t);
        } else {
            db.promptTemplateDao().insert(t);
        }
    }

    public void delete(String templateCode) {
        db.promptTemplateDao().deleteByCode(templateCode);
    }

    public void setEnabled(String templateCode, boolean enabled) {
        db.promptTemplateDao().setEnabled(templateCode, enabled);
    }
}
