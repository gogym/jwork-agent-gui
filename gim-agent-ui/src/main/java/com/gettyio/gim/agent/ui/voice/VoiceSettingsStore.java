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

package com.gettyio.gim.agent.ui.voice;

import com.gettyio.gim.agent.domain.GimDatabase;
import com.gettyio.gim.agent.domain.security.SecretCipher;

/**
 * 语音服务配置存取器：朗读（{@link TtsService}）与转写（{@link VoiceInputService}）
 * 共用的 settings 键值读写——普通值 trim 存取、API Key 加解密存取。
 * <p>两个服务的设置键与中文描述文案不同，由调用方以参数传入；
 * 「空值安全的读写」「密文识别回退明文」「空白清除」这段同一逻辑收敛在本类，
 * 避免两侧实现漂移。</p>
 *
 * @author gogym
 */
final class VoiceSettingsStore {

    private final GimDatabase db;
    private final SecretCipher cipher;

    VoiceSettingsStore(GimDatabase db, SecretCipher cipher) {
        this.db = db;
        this.cipher = cipher;
    }

    /** 读取配置值（trim 后返回；未配置返回空串）。 */
    String get(String key) {
        return db.settingDao().getValue(key).orElse("").trim();
    }

    /** 写入配置值（{@code null} 按空串处理；空串表示清除）。 */
    void put(String key, String value, String remark) {
        db.settingDao().upsert(key, value == null ? "" : value.trim(), remark);
    }

    /** 明文 API Key（密文存储，取出即解密）；未配置或解密失败返回空串。 */
    String apiKeyPlain(String key) {
        String stored = get(key);
        if (stored.isEmpty()) {
            return "";
        }
        try {
            return cipher.canHandle(stored) ? cipher.decrypt(stored) : stored;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** 保存 API Key：空白清除（存空串）；非空 trim 后加密入库。 */
    void saveApiKey(String key, String plain, String remark) {
        if (plain == null || plain.isBlank()) {
            db.settingDao().upsert(key, "", remark);
            return;
        }
        db.settingDao().upsert(key, cipher.encrypt(plain.trim()), remark);
    }
}
