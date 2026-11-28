import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

public class VerifyHooks {
    private static void check(boolean value, String error) {
        if (!value) throw new AssertionError(error);
    }
    public static void main(String[] args) throws Exception {
        int methods = 0, classes = 0;
        for (Path path : Files.walk(Path.of(args[0])).filter(p -> p.toString().endsWith(".class")).toList()) {
            ClassNode root = new ClassNode();
            new ClassReader(Files.readAllBytes(path)).accept(root, ClassReader.EXPAND_FRAMES);
            classes++;
            for (MethodNode method : root.methods) {
                new Analyzer<>(new BasicVerifier()).analyze(root.name, method);
                methods++;
                if (root.name.equals("dev/bustling/CommonInit") && method.name.equals("handleServer")) {
                    int dispatches = 0;
                    for (var insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call && call.owner.equals("dev/bustling/ServerCrowdStore")) {
                            check(call.name.equals("handleClientEdit")
                                    && call.desc.equals("(Ljava/lang/String;Lnet/minecraft/server/level/ServerPlayer;)V"),
                                  "server dispatcher missing sender or doing unconditional save");
                            dispatches++;
                        }
                    }
                    check(dispatches == 1, "exactly one server edit dispatch");
                }
                if (root.name.equals("dev/bustling/CopyTool") && method.name.equals("copy")) {
                    boolean ready = false;
                    int plays = 0;
                    for (var insn : method.instructions) {
                        if (insn instanceof MethodInsnNode call) {
                            if (call.owner.equals("dev/bustling/ToolUi") && call.name.equals("flash")) ready = true;
                            if (call.owner.equals("dev/bustling/ToolSounds") && call.name.equals("copied")) {
                                plays++;
                                check(ready, "copy sound queued before the successful state");
                            }
                        }
                    }
                    check(plays == 1, "exactly one sound after copy");
                }
            }
        }
        System.out.printf("PASS ASM verification for %d loose classes / %d methods and both renderer integration hooks%n",
                          classes, methods);
    }
}
