package com.butler.asm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

public class UNIXProcessCtorAdapter extends AdviceAdapter {
    protected UNIXProcessCtorAdapter(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc);
    }

    @Override
    protected void onMethodEnter() {
        // 构造器（<init>）为非 static，参数从 index 1 开始（0是this）
        // 比如 prog 在 index 1，argBlock 在 index 2
        mv.visitVarInsn(ALOAD, 2); // 加载 argBlock
        mv.visitMethodInsn(INVOKESTATIC,
                "com/butler/util/ProcessCheckUtil",
                "checkArgs",
                "(Ljava/lang/Object;)V",
                false);

        int[] byteParamIndexes = new int[]{0, 1};

        for (int idx : byteParamIndexes) {
            // 加载 byte[] 参数
            mv.visitVarInsn(ALOAD, idx);

            // 调用 ProcessCheckUtil.checkArgs(Object)
            mv.visitMethodInsn(INVOKESTATIC,
                    "com/butler/util/ProcessCheckUtil",
                    "checkArgs",
                    "(Ljava/lang/Object;)V",
                    false);
        }
    }
}
