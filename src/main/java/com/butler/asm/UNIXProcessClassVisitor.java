package com.butler.asm;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

/**
 * 扫描 UNIXProcess 类，找到 init 方法并用适配器处理
 */
public class UNIXProcessClassVisitor extends ClassVisitor {
    private static final String INIT_METHOD = "<init>";

    public UNIXProcessClassVisitor(int api, ClassVisitor classVisitor) {
        super(api, classVisitor);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        // 匹配 init 方法（如果要匹配构造器，把 name 改为 "<init>"）
        if (INIT_METHOD.equals(name)) {
            return new UNIXProcessCtorAdapter(api, mv, access, name, descriptor);
        }

        return mv;
    }
}
