package plume.sebastix.dev.buildlogic;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Material 3 1.4.0 walks ancestors in applySemantics without checking attachment.
 * A queued snapshot notification can reach a deactivated/recycled control. Guard
 * that traversal at its source rather than swallowing an exception on the UI thread.
 * Build-time only, for debug and release; remove when the upstream implementation
 * handles detached nodes. See docs/COMPOSE_WORKAROUND.md for the mapped crash.
 */
public abstract class DetachedSemanticsGuard
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override
    public boolean isInstrumentable(ClassData data) {
        return data.getClassName().equals("androidx.compose.material3.internal.ChildSemanticsNode");
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private int guardedMethods;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("applySemantics") || !descriptor.equals(
                        "(Landroidx/compose/ui/semantics/SemanticsPropertyReceiver;)V")) return method;
                guardedMethods++;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // if (!isAttached) return; attached nodes execute the original method.
                        Label attached = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "androidx/compose/ui/Modifier$Node", "isAttached", "()Z", false);
                        super.visitJumpInsn(Opcodes.IFNE, attached);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(attached);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (guardedMethods != 1) throw new IllegalStateException(
                        "Material 3 semantics changed; review DetachedSemanticsGuard before upgrading");
                super.visitEnd();
            }
        };
    }
}
