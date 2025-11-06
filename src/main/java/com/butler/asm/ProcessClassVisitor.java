package com.butler.asm;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * 扫描 ProcessImpl 类中的所有方法
 * 找到 create() 与 start() 并交给对应的 Adapter 处理
 */
public class ProcessClassVisitor extends ClassVisitor {
    private static final String CONSTRUCTOR = "<init>";
    private static final String START_METHOD = "start";

    public ProcessClassVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // 匹配 ProcessImpl.<init>
        if (CONSTRUCTOR.equals(name)) {
            return new ProcessImplConstructorAdapter(api, mv, access, name, descriptor);
        }

        // 匹配 ProcessImpl.start()
        if (START_METHOD.equals(name)) {
            return new ProcessImplStartMethodAdapter(api, mv, access, name, descriptor);
        }

        return mv;
    }
}
