package com.gettyio.gim.agent.tools.image;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 面向 Agent 的图像生成工具：{@code generate_image}——调用 OpenAI 兼容生图服务生成图片，
 * 落盘 {@code workspace/generated/} 并返回绝对路径与可展示的 Markdown 图片语法。
 * <p>配置（地址/模型/Key）经 {@link ImageGenerationClient} 由装配方注入，运行时实时取用；
 * 未配置时返回明确的「未配置」提示而非抛异常。模型在回复中用 Markdown 图片语法引用返回的
 * 本地路径，气泡内即直接渲染（见 UI 侧 {@code MarkdownRenderer} 的本地图片分支），
 * 持久化与历史重放零改动。</p>
 */
public class ImageGenerationTools {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTools.class);

    private final ImageGenerationClient client;

    /** 产物目录（workspace/generated）：由装配方注入，落盘前自动创建。 */
    private final Supplier<Path> outputDirSupplier;

    public ImageGenerationTools(ImageGenerationClient client, Supplier<Path> outputDirSupplier) {
        this.client = client;
        this.outputDirSupplier = outputDirSupplier;
    }

    @Tool(name = "generate_image", readOnly = false,
            description = "根据文字提示词调用图像生成服务生成一张图片，保存到本地并返回文件路径。"
                    + "生成成功后，必须在你的回复中用 Markdown 图片语法展示该图：![](<返回的路径>)，不要只在文字里描述。")
    public String generateImage(
            @ToolParam(name = "prompt", description = "图片内容描述（尽量具体：主体、风格、构图、色调）") String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "prompt 不能为空。";
        }
        try {
            byte[] bytes = client.generate(prompt.trim());
            Path file = ImageGenerationClient.save(outputDirSupplier.get(), bytes);
            String mdPath = file.toAbsolutePath().toString().replace('\\', '/');
            return "图片已生成并保存：" + mdPath + "\n请在回复中用 Markdown 图片语法展示这张图片：![](<" + mdPath + ">)";
        } catch (IllegalStateException e) {
            // 未配置/服务报错：原文交给模型转述，不暴露堆栈
            return e.getMessage();
        } catch (Exception e) {
            log.warn("generate_image 异常", e);
            return "图片生成失败：" + e.getMessage();
        }
    }
}
