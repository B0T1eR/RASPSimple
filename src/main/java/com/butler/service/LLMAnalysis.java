package com.butler.service;

import com.butler.helper.RaspLoggerHelper;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.butler.util.PromptUtil;

import static com.butler.AgentMain.QWEN_API_KEY;

public class LLMAnalysis {

    public static String LLM_LOG_FILE;

    /** 系统提示与分析模板 */
    private static final Message sysMsg =
            Message.builder().role(Role.SYSTEM.getValue()).content(PromptUtil.SYS_PROMPT).build();
    private static final Message guideMsg =
            Message.builder().role(Role.SYSTEM.getValue()).content(PromptUtil.GUIDELINES_TEMPLATE).build();

    /**
     * 执行大模型分析
     * @param command 被执行命令（或完整参数）
     * @param callstack 调用栈字符串
     * @return 返回模型评分（0-10）
     */
    public static int analyze(String command, String callstack) {
        File logFile = new File(LLM_LOG_FILE);
        try {
            if (!logFile.exists()) {
                logFile.getParentFile().mkdirs();
                logFile.createNewFile();
            }
        } catch (IOException e) {
            RaspLoggerHelper.error("创建RASPSimple LLM大模型分析日志文件错误",e);
            throw new RuntimeException(e);
        }

        Generation gen = new Generation();
        int score = 0;

        try {
            // 准备消息列表
            List<Message> messages = new ArrayList<>(Arrays.asList(sysMsg, guideMsg));

            // 拼接输入上下文
            StringBuilder sb = new StringBuilder();
            sb.append("命令参数如下：\n").append(command)
                    .append("\n\n调用栈如下：\n").append(callstack);

            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content("请根据以下命令参数与调用栈信息判断安全风险：\n" + sb)
                    .build());

            // 第一步：让模型输出完整分析
            GenerationResult result;
            try {
                result = gen.call(createGenerationParam(messages, QWEN_API_KEY));
            } catch (NoApiKeyException | InputRequiredException e) {
                RaspLoggerHelper.error("LLM大模型调用出错",e);
                throw new RuntimeException(e);
            }

            String fullAnalysis = result.getOutput().getChoices().get(0).getMessage().getContent();
            RaspLoggerHelper.info(command + "LLM大模型分析结果已出!\n");
            writeToFile(logFile,"命令：" + command +
                    "\nLLM大模型分析结果：\n" + fullAnalysis);

            // 第二步：追加“只输出评分数字”提示
            messages.add(Message.builder()
                    .role(Role.USER.getValue())
                    .content("请只输出风险评分数字（0-10）").build());
            GenerationResult scoreResult = gen.call(createGenerationParam(messages, QWEN_API_KEY));
            String scoreStr = scoreResult.getOutput().getChoices().get(0).getMessage().getContent();

            Pattern p = Pattern.compile("\\d+");
            Matcher m = p.matcher(scoreStr);
            if (m.find()) {
                score = Integer.parseInt(m.group());
            }

            // 日志输出
            writeToFile(logFile,
                    "命令：" + command +
                    "\n调用栈：\n" + callstack +
                    "\n分析结果：\n" + fullAnalysis +
                    "\n评分：" + score + "\n\n");

        } catch (Exception e) {
            RaspLoggerHelper.error("LLM分析失败", e);
        }
        return score;
    }

    /** 构造模型参数 */
    private static GenerationParam createGenerationParam(List<Message> messages, String apiKey) {
        GenerationParam param = GenerationParam.builder()
                .model("qwen-plus")
                .messages(messages)
                .resultFormat("message")
                .build();

        if (apiKey != null && !apiKey.isEmpty()) {
            param.setApiKey(apiKey);
        } else {
            throw new RuntimeException("未识别到通义 API Key，请设置 DASHSCOPE_API_KEY");
        }

        return param;
    }

    /** 写入文件 */
    private static void writeToFile(File logFile,String content) {
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(content);
            writer.flush();
        } catch (IOException e) {
            RaspLoggerHelper.error("写入RASPSimple LLM大模型分析日志文件错误",e);
            throw new RuntimeException(e);
        }
    }
}
