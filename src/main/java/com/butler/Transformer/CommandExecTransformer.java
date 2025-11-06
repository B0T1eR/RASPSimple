package com.butler.Transformer;

import com.butler.asm.UNIXProcessClassVisitor;
import com.butler.helper.RaspLoggerHelper;
import com.butler.asm.ProcessClassVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import java.io.ByteArrayInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import static com.butler.helper.TargetClassHelper.PROCESSIMPL;
import static com.butler.helper.TargetClassHelper.UNIXPROCESS;

/**
 * 负责在加载 java.lang.ProcessImpl / java.lang.UNIXProcess 时修改字节码
 */
public class CommandExecTransformer implements ClassFileTransformer {

    private final Instrumentation inst;

    public CommandExecTransformer(Instrumentation inst) {
        this.inst = inst;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) throws IllegalClassFormatException {

        // 如果不是我们关注的目标类则直接返回原字节码
        if (!PROCESSIMPL.equals(className) && !UNIXPROCESS.equals(className)) {
            return classfileBuffer;
        }

        RaspLoggerHelper.info("Using ASM to patch class: " + className);

        try {
            ClassReader cr = new ClassReader(new ByteArrayInputStream(classfileBuffer));
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            // 根据类名创建合适的Visitor
            ClassVisitor cv = createVisitorFor(className, Opcodes.ASM9, cw, loader);
            if (cv == null) {
                RaspLoggerHelper.warn("No visitor found for class: " + className);
                return classfileBuffer;
            }

            cr.accept(cv, ClassReader.SKIP_FRAMES);

            byte[] modified = cw.toByteArray();
            RaspLoggerHelper.info("ASM patch success for " + className);
            return modified;

        } catch (Throwable t) {
            RaspLoggerHelper.error("ASM transform failed for " + className,t);
            t.printStackTrace();
        }

        return classfileBuffer;
    }

    /**
     * 根据类名返回对应的 ClassVisitor。
     * - ProcessImpl → 注入 create/start 检测逻辑
     * - UNIXProcess → 注入构造函数检测逻辑
     */
    private ClassVisitor createVisitorFor(String className, int api, ClassVisitor cv, ClassLoader loader) {
        if (PROCESSIMPL.equals(className)) {
            return new ProcessClassVisitor(api, cv);
        }
        if (UNIXPROCESS.equals(className)) {
            return new UNIXProcessClassVisitor(api, cv);
        }
        return null;
    }
}
