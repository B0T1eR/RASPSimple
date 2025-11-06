package com.butler.asm;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.AdviceAdapter;

public class ProcessImplConstructorAdapter extends AdviceAdapter {
    private final String methodDesc;

    protected ProcessImplConstructorAdapter(int api, MethodVisitor mv, int access, String name, String desc) {
        super(api, mv, access, name, desc);
        this.methodDesc = desc;
    }

    @Override
    protected void onMethodEnter() {
        // 加载第一个参数 String[] var1
        mv.visitVarInsn(ALOAD, 1);

        // 调用静态方法 ProcessCheckUtil.checkArgs(Object)
        mv.visitMethodInsn(INVOKESTATIC,
                "com/butler/util/ProcessCheckUtil",
                "checkArgs",
                "(Ljava/lang/Object;)V",
                false);
    }
}
