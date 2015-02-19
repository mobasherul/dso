/*
 * All content copyright (c) 2003-2008 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tc.object.bytecode;

import com.tc.asm.ClassVisitor;
import com.tc.asm.MethodVisitor;
import com.tc.asm.Opcodes;

public class JavaUtilConcurrentCyclicBarrierClassAdapter extends ClassVisitor implements Opcodes {

  public JavaUtilConcurrentCyclicBarrierClassAdapter(ClassVisitor cv) {
    super(Opcodes.ASM4, cv);
  }

  @Override
  public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
    if ("nextGeneration".equals(name) && "()V".equals(desc)) {
      mv = new NextGenerationMethodAdapter(mv);
    }

    return mv;
  }

  private static class NextGenerationMethodAdapter extends MethodVisitor implements Opcodes {
    public NextGenerationMethodAdapter(MethodVisitor mv) {
      super(Opcodes.ASM4, mv);
    }

    @Override
    public void visitMethodInsn(final int opcode, final String owner, final String name, final String desc)
    {
      if ((!"java/util/concurrent/locks/Condition".equals(owner))
          || (!"signalAll".equals(name)) || (!desc.equals("()V"))) {
        super.visitMethodInsn(opcode, owner, name, desc);
      }
    }

    @Override
    public void visitInsn(int opcode) {
      if (opcode == RETURN) {
        super.visitMethodInsn(INVOKEINTERFACE, "java/util/concurrent/locks/Condition", "signalAll", "()V");
      }
      super.visitInsn(opcode);
    }
  }
}
