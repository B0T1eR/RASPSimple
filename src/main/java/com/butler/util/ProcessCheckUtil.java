package com.butler.util;

import com.butler.helper.RaspLoggerHelper;
import com.butler.service.LLMAnalysis;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.butler.AgentMain.QWEN_API_KEY;
import static com.butler.service.LLMAnalysis.LLM_LOG_FILE;

/**
 * 检查是否存在对 /etc/passwd 的访问行为
 * 可扩展为更复杂的命令检测逻辑
 */
public class ProcessCheckUtil {

    /**
     * 黑名单正则列表 —— 针对常见命令注入/反弹手法做简单匹配。
     * 可替换为从配置文件动态加载以支持热更新。
     */
    private static final List<Pattern> BLACKLIST = Arrays.asList(
            // Linux reads
            Pattern.compile("(?i)\\bcat\\b\\s+/etc/passwd"),                        // cat /etc/passwd
            Pattern.compile("(?i)\\bcat\\b\\s+[^\\r\\n]*\\b/dev/tcp\\b"),           // bash reverse via /dev/tcp
            // Windows reads
            Pattern.compile("(?i)\\btype\\b\\s+(?:[A-Za-z]:\\\\|\\\\\\\\)[^\\r\\n\\s]+"), // type C:\path\file or type \\server\share\file
            Pattern.compile("(?i)\\btype\\b\\s+[A-Za-z]:\\\\Windows\\\\win\\.ini\\b"),     // specific example: type C:\Windows\win.ini
            // Common reverse-shell / remote-exec patterns (Linux & Windows)
            Pattern.compile("(?i)\\b(?:nc|ncat|netcat)\\b[^\\r\\n]{0,80}-e\\b"),     // nc -e ...
            Pattern.compile("(?i)\\b(?:bash|sh)\\b[^\\r\\n]{0,120}/dev/tcp\\b"),     // bash ... /dev/tcp (reverse shell)
            Pattern.compile("(?i)\\bpython(?:3)?\\b[^\\r\\n]{0,200}\\b(?:socket|subprocess|Popen)\\b"), // python -c "import socket/ subprocess..."
            Pattern.compile("(?i)\\bpowershell(?:\\.exe)?[^\\r\\n]{0,200}\\b(?:IEX|Invoke-Expression|Invoke-WebRequest|DownloadString)\\b"), // powershell IEX / download & exec
            Pattern.compile("(?i)\\bperl\\b[^\\r\\n]{0,200}-e\\b"),                  // perl -e ...
            Pattern.compile("(?i)\\b(?:curl|wget)\\b[^\\r\\n]{0,120}\\|\\s*(?:sh|bash)\\b") // curl ... | sh
    );

    /**
     * checkArgs
     * 统一入口：传入任意类型的参数（String, String[], byte[], Object[] 等），如果能被串化为命令字符串则进行黑名单匹配。
     *
     * 设计原则：
     * - 只检测命令执行相关的可疑模式（黑名单）。
     * - 不要误杀非命令场景：若无法串化或为空则直接返回（不阻断）。
     *
     * @param arg 可能是命令字符串、命令数组、byte[]（如底层 C-style blocks），或 Object[]（反射传参）
     */
    public static void checkArgs(Object arg) {

        RaspLoggerHelper.info("======================= Check Command Start =======================");
        String s = stringifyArg(arg);
        if (s == null) return; // 无可检测内容，直接放行

        RaspLoggerHelper.info("Find Command execute: " + s);
        for (Pattern p : BLACKLIST) {
            if (p.matcher(s).find()) {
                // 命中黑名单，记录并阻断
                logAndBlock(s, p.pattern());
            }
        }

        //LLM大模型分析
//        System.out.println(QWEN_API_KEY);
        if(QWEN_API_KEY!= null && !QWEN_API_KEY.isEmpty()){
            // 获取应用目录
            if(LLM_LOG_FILE == null || LLM_LOG_FILE.isEmpty()){
                String appDir = System.getProperty("user.dir");
                if (appDir == null || appDir.isEmpty()) {
                    try {
                        appDir = new File(".").getCanonicalPath();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
                // 在文件名中加上时间戳
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                // 在文件名中加上时间戳
                LLM_LOG_FILE = appDir + File.separator + "RASPSimple_LLMAnalyze_" + timestamp + ".log";
                RaspLoggerHelper.info("LLM Log initialized at: " + LLM_LOG_FILE);
            }

            RaspLoggerHelper.info("========= LLM Analyze Start:");
            String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                    .map(StackTraceElement::toString)
                    .limit(20) // 限制栈深避免日志过长
                    .collect(Collectors.joining("\n"));
            LLMAnalysis.analyze(s,stack);
            RaspLoggerHelper.info("========= LLM Analyze Ending:");
        }

        RaspLoggerHelper.info("======================= Check Command Ending =======================");
    }

    /**
     * stringifyArg
     *
     * 将各种可能的入参类型规范化为可搜索的字符串：
     * - String      -> 直接返回
     * - String[]    -> join 空格（常见高层 API 传参）
     * - byte[]      -> 按 UTF-8 解码（UNIXProcess/底层构造器常用 C-style byte blocks）
     * - Object[]    -> 对每个元素调用 toString 并 join（反射时常见）
     * - 其它       -> 调用 String.valueOf 作为兜底（尽量提供可读字符串，便于匹配与审计）
     *
     * 如果无法或不合理地串化，返回 null（则不做检测）。
     */
    private static String stringifyArg(Object arg) {
        if (arg == null) return null;

        if (arg instanceof String) {
            return (String) arg;
        }
        if (arg instanceof String[]) {
            try {
                return String.join(" ", (String[]) arg);
            } catch (Throwable ignored) {
                // 若 join 失败则降级为逐项 toString
                return Arrays.stream((String[]) arg).filter(x -> x != null).collect(Collectors.joining(" "));
            }
        }
        if (arg instanceof byte[]) {
            try {
                return new String((byte[]) arg, StandardCharsets.UTF_8);
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (arg instanceof Object[]) {
            try {
                return Arrays.stream((Object[]) arg)
                        .filter(x -> x != null)
                        .map(String::valueOf)
                        .collect(Collectors.joining(" "));
            } catch (Throwable ignored) {
                return null;
            }
        }
        // 其它常见情况（例如某些容器类型），兜底 toString
        try {
            return String.valueOf(arg);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * logAndBlock
     *
     * 记录触发信息并抛出 SecurityException 阻断执行。
     *
     * - 记录信息包含被检测到的命令字符串、匹配原因（正则）以及简要调用栈（最多 20 条）。
     * - 抛出 SecurityException，用于在调用链上回退并阻断命令执行。
     *
     * 注意：如果你希望在生产环境降低误杀风险，可把这里改为先告警同时返回一个受控异常或调用上报链路（本工具默认阻断）。
     */
    private static void logAndBlock(String cmd, String reason) {
        String stack = Arrays.stream(Thread.currentThread().getStackTrace())
                .map(StackTraceElement::toString)
                .limit(20) // 限制栈深避免日志过长
                .collect(Collectors.joining("\n"));

        // 输出到你的 RASP 日志系统
        RaspLoggerHelper.warn("Blocked suspicious command: " + cmd + " reason:" + reason + "\nStack:\n" + stack);

        // 直接阻断当前执行（调用者应捕获或允许传播）
        throw new SecurityException("[RASPSimple blocked dangerous command]");
    }
}
