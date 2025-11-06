package com.butler.helper;

import com.butler.AgentMain;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.logging.*;

import static com.butler.service.LLMAnalysis.LLM_LOG_FILE;

/**
 * JUL 日志工具类，安全用于 Agent attach
 */
public class RaspLoggerHelper {

    private static final Logger logger;

    static {
        logger = Logger.getLogger("com.butler.helper.RaspLogger");
        logger.setUseParentHandlers(false); // 不输出到默认控制台

        try {
            // 获取应用目录
            String appDir = System.getProperty("user.dir");
            if (appDir == null || appDir.isEmpty()) {
                appDir = new File(".").getCanonicalPath();
            }

            // 在文件名中加上时间戳
            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String logPath = appDir + File.separator + "RASPSimple_Agent_" + timestamp + ".log";

            if(AgentMain.LLM_ANALYZE_START){
                // 在文件名中加上时间戳
                LLM_LOG_FILE = appDir + File.separator + "RASPSimple_LLMAnalyze_" + timestamp + ".log";
                RaspLoggerHelper.info("LLM Log initialized at: " + LLM_LOG_FILE);
            }

            // 自定义 Handler，直接写文件，不使用 FileHandler
            Handler fileHandler = new StreamHandler(Files.newOutputStream(Paths.get(logPath), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    new SimpleFormatter() {
                        private static final String format = "[%1$tF %1$tT] [%4$-5s] %5$s %n";
                        @Override
                        public synchronized String format(LogRecord lr) {
                            return String.format(format,
                                    new Date(lr.getMillis()),
                                    lr.getSourceClassName(),
                                    lr.getLoggerName(),
                                    lr.getLevel().getLocalizedName(),
                                    lr.getMessage());
                        }
                    }) {
                @Override
                public synchronized void publish(LogRecord record) {
                    super.publish(record);
                    flush(); // 每次写完就 flush
                }
            };

            fileHandler.setLevel(Level.INFO);
            logger.addHandler(fileHandler);

            // 控制台输出
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.INFO);
            consoleHandler.setFormatter(fileHandler.getFormatter());
            logger.addHandler(consoleHandler);

            logger.info("Log initialized at: " + logPath);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** INFO 级别日志 */
    public static void info(String msg) {
        logger.info("[*] [RASPSimple] " + msg);
    }

    /** WARN 级别日志 */
    public static void warn(String msg) {
        logger.warning("[!] [RASPSimple] " + msg);
    }

    /** DEBUG 级别日志（对应 JUL FINE） */
    public static void debug(String msg) {
        logger.fine("[&] [RASPSimple] " + msg);
    }

    /** ERROR 级别日志 */
    public static void error(String msg, Throwable t) {
        logger.log(Level.SEVERE,"[x] [RASPSimple] " +  msg, t);
    }
}
