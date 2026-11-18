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

package com.gettyio.gim.agent.core.model;

import com.gettyio.gim.agent.domain.util.HttpMedia;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 模型连通性探测：按给定规格发一条最小对话，验证 baseUrl / apiKey / 模型名这一组配置是否真的可用。
 * <p>配置页在保存前测一次，比让用户到会话里撞上一条 401 更早暴露问题。探测走独立实例、
 * 不进 {@link AgentScopeModelFactory} 缓存，也永不抛异常——探测失败本身就是要回显的结果。</p>
 *
 * @author gogym
 */
public class ModelProbe {

    private static final Logger log = LoggerFactory.getLogger(ModelProbe.class);

    /** 探测超时：覆盖 DNS + TLS 握手 + 首字返回，海外提供商也来得及给出结论。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 只要一个回包就够，限制输出长度，避免真的生成一段话白烧 token。 */
    private static final int MAX_TOKENS = 16;
    private static final String PING = "ping";
    /** 多模态探测：发一张纯色小图问颜色，端点若不支持图片输入会直接报错，支持则给出回答。 */
    private static final String VISION_PROMPT = "这张图片是什么颜色？只回答一个词。";
    private static final String VISION_MEDIA_TYPE = HttpMedia.IMAGE_PNG;

    private final AgentScopeModelFactory modelFactory;

    public ModelProbe(AgentScopeModelFactory modelFactory) {
        this.modelFactory = modelFactory;
    }

    /**
     * 探测结果。
     *
     * @param ok     是否连通并拿到回包
     * @param detail 可直接展示的细节：成功为耗时，失败为归一化后的错误原因
     */
    public record Result(boolean ok, String detail) {
    }

    /** 发一条 ping 探测；任何异常（含超时、鉴权失败、规格非法）都收敛成失败结果。 */
    public Result probe(ModelSpec spec) {
        long start = System.nanoTime();
        try {
            Model model = modelFactory.create(spec);
            Msg ping = Msg.builder().role(MsgRole.USER).textContent(PING).build();
            GenerateOptions options = GenerateOptions.builder()
                    .modelName(spec.modelName())
                    .maxTokens(MAX_TOKENS)
                    .build();
            ChatResponse first = model.stream(List.of(ping), List.of(), options).next().block(TIMEOUT);
            if (first == null) {
                return failure(spec, "连接已建立，但模型没有返回任何内容");
            }
            return new Result(true, "已连通 · " + elapsedMs(start) + "ms");
        } catch (Exception e) {
            return failure(spec, describe(e));
        }
    }

    /**
     * 多模态探测：随一条最小对话附一张程序生成的纯色小图，验证模型是否真能吃图片。
     * <p>与 {@link #probe} 一样走独立实例、永不抛异常。文本模型收到 image 块通常直接返回 4xx，
     * 归一化成失败；能正常回包的视为支持多模态，并把模型的简短回答作为佐证一并回显。</p>
     */
    public Result probeVision(ModelSpec spec) {
        long start = System.nanoTime();
        try {
            Model model = modelFactory.create(spec);
            Msg msg = Msg.builder().role(MsgRole.USER).content(List.of(
                    TextBlock.builder().text(VISION_PROMPT).build(),
                    ImageBlock.builder()
                            .source(new Base64Source(VISION_MEDIA_TYPE, redSquarePngBase64()))
                            .build()
            )).build();
            GenerateOptions options = GenerateOptions.builder()
                    .modelName(spec.modelName())
                    .maxTokens(MAX_TOKENS)
                    .build();
            List<ChatResponse> chunks = model.stream(List.of(msg), List.of(), options)
                    .collectList().block(TIMEOUT);
            if (chunks == null || chunks.isEmpty()) {
                return failure(spec, "已接受图片输入，但模型没有返回任何内容");
            }
            String answer = assembleText(chunks);
            String evidence = answer.isBlank() ? "" : "（回答：" + trimAnswer(answer) + "）";
            return new Result(true, "支持多模态" + evidence + " · " + elapsedMs(start) + "ms");
        } catch (Exception e) {
            return failure(spec, describe(e));
        }
    }

    /** 生成 64×64 纯红 PNG 并转 Base64（不含 data URI 前缀），作为多模态探测的固定输入。 */
    private static String redSquarePngBase64() throws IOException {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, 64, 64);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /** 拼接流式回包里所有文本块（增量或非增量都适用），得到模型完整回答。 */
    private static String assembleText(List<ChatResponse> chunks) {
        StringBuilder sb = new StringBuilder();
        for (ChatResponse chunk : chunks) {
            if (chunk == null || chunk.getContent() == null) {
                continue;
            }
            for (ContentBlock block : chunk.getContent()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    sb.append(text.getText());
                }
            }
        }
        return sb.toString().trim();
    }

    private static String trimAnswer(String answer) {
        String oneLine = answer.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    private Result failure(ModelSpec spec, String reason) {
        log.warn("模型连通性探测失败: provider={}, model={}, 原因={}",
                spec.providerCode(), spec.modelName(), reason);
        return new Result(false, reason);
    }

    /**
     * 把异常收敛成一句人话：阻塞超时抛的是 IllegalStateException（文案是 Reactor 的英文串），
     * 其余情形剥到最内层 cause，拿到 SDK 包装的 HTTP 状态与 provider 返回的错误消息。
     */
    private static String describe(Exception e) {
        String raw = e.getMessage();
        if (e instanceof IllegalStateException && raw != null && raw.startsWith("Timeout")) {
            return "请求超时（" + TIMEOUT.toSeconds() + "s），请检查 BaseUrl 与网络";
        }
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage();
        if (text == null || text.isBlank()) {
            text = root.getClass().getSimpleName();
        }
        return text;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
