import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * NewPipeExtractor is compiled against JDK 11 and calls URLEncoder.encode(String, Charset) and
 * URLDecoder.decode(String, Charset), which only exist on Android 13+ (API 33). This rewrites those two
 * calls to com.claptune.compat.JdkCompat, which works on every Android version.
 */
abstract class JdkCompatTransform : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData) =
        classData.className.startsWith("org.schabi.newpipe.extractor.")

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(
                        opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
                    ) {
                        val rewritten = opcode == Opcodes.INVOKESTATIC && descriptor == CHARSET_DESC &&
                            ((owner == "java/net/URLEncoder" && name == "encode") ||
                                (owner == "java/net/URLDecoder" && name == "decode"))
                        if (rewritten) {
                            super.visitMethodInsn(opcode, COMPAT, name, descriptor, false)
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            }
        }

    private companion object {
        const val COMPAT = "com/claptune/compat/JdkCompat"
        const val CHARSET_DESC = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
    }
}
