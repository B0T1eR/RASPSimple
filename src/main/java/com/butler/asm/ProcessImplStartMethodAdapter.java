package com.butler.asm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 在 ProcessImpl.start() 方法开头插入检测逻辑
 */
public class ProcessImplStartMethodAdapter extends AdviceAdapter {
    public ProcessImplStartMethodAdapter(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc);
    }

    @Override
    protected void onMethodEnter() {

        mv.visitVarInsn(ALOAD, 0); // 加载第一个参数
        mv.visitMethodInsn(INVOKESTATIC,
                "com/butler/util/ProcessCheckUtil",
                "checkArgs",
                "(Ljava/lang/Object;)V",
                false);
    }
}
