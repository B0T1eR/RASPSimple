package com.butler;

import com.butler.helper.JarFileHelper;
import com.butler.Transformer.CommandExecTransformer;
import com.butler.helper.TargetClassHelper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import static com.butler.helper.JarFileHelper.getLocalJarPath;

public class AgentMain {
    public static final String ClassName = "java.lang.ProcessImpl";

    private static Class virtualMachineClass;
    private static Class virtualMachineDescriptorClass;
    private static List<Object> vms;
    public static boolean LLM_ANALYZE_START;
    public static String QWEN_API_KEY = "";


    static {
        try {
            StringBuilder toolsJarPath = new StringBuilder();
            toolsJarPath.append(System.getProperty("java.home"))
                    .append(File.separator).append("..")
                    .append(File.separator).append("lib")
                    .append(File.separator).append("tools.jar");

            File toolsJarFile = new File(toolsJarPath.toString());

            if (!toolsJarFile.exists() || !toolsJarFile.isFile()) {
                InputStream jarStream = AgentMain.class.getClassLoader().getResourceAsStream("tools.jar");
                toolsJarFile = File.createTempFile("tools", ".jar");

                FileOutputStream out = null;

                try {
                    out = new FileOutputStream(toolsJarFile);

                    byte[] buffer = new byte[1024];
                    int bytesRead;

                    while ((bytesRead = jarStream.read(buffer)) != -1)
                        out.write(buffer, 0, bytesRead);
                } finally {
                    if (out != null) {
                        out.close();
                    }
                }
            }

            URL url = toolsJarFile.toURI().toURL();
            URLClassLoader urlClassLoader = new URLClassLoader(new URL[] { url });
            virtualMachineClass = urlClassLoader.loadClass(
                    "com.sun.tools.attach.VirtualMachine");
            virtualMachineDescriptorClass = urlClassLoader.loadClass(
                    "com.sun.tools.attach.VirtualMachineDescriptor");
            vms = (List<Object>) virtualMachineClass.getMethod("list",
                    new Class[0]).invoke(virtualMachineClass, new Object[0]);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        // 没有参数：列出所有 JVM
        if (args.length == 0) {
            listAllJvmPids();
            return;
        }

        // 超过两个参数则报错
        if (args.length > 2) {
            throw new IllegalArgumentException("Too many arguments. Expected up to 2: [JVM_PID] [optional: Qwen_API_KEY]");
        }
        String targetPid = args[0];
        // 如果提供了第二个参数，则识别为 Qwen API Key
        if (args.length == 2) {
            QWEN_API_KEY = args[1];
            if (!QWEN_API_KEY.startsWith("sk-")) {
                throw new IllegalArgumentException("Warning：Qwen API Key is startwith 'sk-'");
            }
            LLM_ANALYZE_START = true;
            infoLog("已设置通义大模型 API Key: sk-***********" );
        }
        // 主逻辑分支：PID 或 displayName
        if (targetPid.equalsIgnoreCase("all")) {
            for (String jvmProcessId : getAllJvmPids())
                attachAgentToTargetJvm(jvmProcessId);
        } else {
            try {
                Integer.parseInt(targetPid);
                attachAgentToTargetJvm(targetPid);
            } catch (NumberFormatException e) {
                for (String jvmProcessId : getJvmPidsByDisplayName(targetPid))
                    attachAgentToTargetJvm(jvmProcessId);
            }
        }
    }

    public static void agentmain(String agentArgs, Instrumentation ins) {
        //路径追加到了启动类加载器的classpath中。此时启动类加载器收到类加载委派任务时，就能通过该classpath加载到rasp.jar的所有类了
        try {
            JarFileHelper.addJarToBootstrap(ins);
        } catch (IOException e) {
            failLog("Failed to initialize, will continue without security protection.");
            throw new RuntimeException(e);
        }

        // 注册ProcessBuilderTransformer
        ins.addTransformer(new CommandExecTransformer(ins),true);
        // 获取所有已加载的类
        Class[] classes = ins.getAllLoadedClasses();
        for (Class clas:classes){
            if (TargetClassHelper.isTargetClass(clas.getName())){
                try{
                    // 对类进行重新定义
                    ins.retransformClasses(new Class[]{clas});
                } catch (Exception e){
                    e.printStackTrace();
                }
            }
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        try {
            JarFileHelper.addJarToBootstrap(inst);

            inst.addTransformer(new CommandExecTransformer(inst), true);

            Class[] loadedClasses = inst.getAllLoadedClasses();
            for (Class cls : loadedClasses) {
                if (TargetClassHelper.isTargetClass(cls.getName())) {
                    try {
                        inst.retransformClasses(cls);
                        infoLog("Retransformed class: " + cls.getName());
                    } catch (Exception e) {
                        failLog("Failed to retransform " + cls.getName());
                        e.printStackTrace();
                    }
                }
            }

            successLog("Premain: Java layer protection active");
        } catch (Exception e) {
            failLog("Premain: initialization failed");
            e.printStackTrace();
        }
    }


    public static List<String> getAllJvmPids() throws Exception {
        List<String> pids = new ArrayList<String>();

        for (Object vm : vms) {
            Method getId = virtualMachineDescriptorClass.getDeclaredMethod("id",
                    new Class[0]);
            String id = (String) getId.invoke(vm, new Object[0]);
            pids.add(id);
        }

        return pids;
    }

    public static void listAllJvmPids() throws Exception {
        for (Object vm : vms) {
            Method displayNameMethod = virtualMachineDescriptorClass.getMethod("displayName",
                    new Class[0]);
            String displayName = (String) displayNameMethod.invoke(vm,
                    new Object[0]);
            Method getId = virtualMachineDescriptorClass.getDeclaredMethod("id",
                    new Class[0]);
            String id = (String) getId.invoke(vm, new Object[0]);
            infoLog(String.format("Found pid %s [%s]",
                    new Object[] { id, displayName }));
        }
    }

    public static List<String> getJvmPidsByDisplayName(String displayName)
            throws Exception {
        List<String> pids = new ArrayList<String>();

        for (Object vm : vms) {
            Method displayNameMethod = virtualMachineDescriptorClass.getMethod("displayName",
                    new Class[0]);
            String currentDisplayName = (String) displayNameMethod.invoke(vm,
                    new Object[0]);
            System.out.println(currentDisplayName);
            System.out.println(displayName);
            System.out.println();

            if (currentDisplayName.toLowerCase()
                    .contains(displayName.toLowerCase())) {
                Method getId = virtualMachineDescriptorClass.getDeclaredMethod("id",
                        new Class[0]);
                String id = (String) getId.invoke(vm, new Object[0]);
                pids.add(id);
            }
        }

        return pids;
    }

    private static void attachAgentToTargetJvm(String targetPID)
            throws Exception {
        String agentFilePath = (new File(AgentMain.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .getPath())).getCanonicalPath();
        infoLog("Current agent path: " + agentFilePath);

        File agentFile = new File(agentFilePath);
        String currentPid = getCurrentPID();

        if (targetPID.equals(currentPid)) {
            infoLog("Skipping attaching to self");
        } else {
            try {
                infoLog("Attaching to target JVM with PID: " + targetPID);

                Object jvm = virtualMachineClass.getMethod("attach",new Class[] { String.class }).invoke(null,new Object[] { targetPID });
                Method loadAgent = virtualMachineClass.getDeclaredMethod("loadAgent",new Class[] { String.class });
                loadAgent.invoke(jvm,new Object[] { agentFile.getAbsolutePath() });

                Method detach = virtualMachineClass.getDeclaredMethod("detach",new Class[0]);
                detach.invoke(jvm, new Object[0]);
                successLog("[JavaAgent] Attached to target JVM and loaded agent successfully");

                // ========== Native Agent attach ==========
                String os = System.getProperty("os.name").toLowerCase();
                String ext = os.contains("win") ? ".dll" : ".so";
                // 获取 jar 包所在路径，并拼接 native 目录
                String localJarPath = getLocalJarPath();
                String nativePath = new File(localJarPath).getParentFile().getAbsolutePath();
                File nativeAgentFile = new File(nativePath + File.separator + "native" + File.separator + "librasp_agent" + ext);
                // 开始attach
                if (!nativeAgentFile.exists()) {
                    failLog("[NativeAgent] Native agent file not found: " + nativeAgentFile.getAbsolutePath());
                } else {
                    // 与 Java Agent 相同方式进行 attach + loadAgentPath
                    Object jvm2 = virtualMachineClass.getMethod("attach", String.class).invoke(null, targetPID);
                    Method loadAgentPath = virtualMachineClass.getDeclaredMethod("loadAgentPath", String.class);
                    loadAgentPath.invoke(jvm2, nativeAgentFile.getAbsolutePath());
                    detach.invoke(jvm2);
                    successLog("[NativeAgent] Attached to target JVM and loaded native agent successfully");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String getCurrentPID() {
        return ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
    }

    public static void infoLog(String message) {
        System.out.println(("[*] [RASPSimple] " + message));
    }

    public static void failLog(String message) {
        System.out.println(("[-] [RASPSimple] " + message));
    }

    public static void successLog(String message) {
        System.out.println(("[+] [RASPSimple] " + message));
    }
}
