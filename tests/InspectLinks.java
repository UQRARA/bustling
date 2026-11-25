import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class InspectLinks {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            ClassNode c = new ClassNode();
            new ClassReader(Files.readAllBytes(Path.of(path))).accept(c, 0);
            System.out.println(c.name);
            for (MethodNode m : c.methods) for (AbstractInsnNode n : m.instructions) {
                if (n instanceof MethodInsnNode i && (i.owner.startsWith("net/") || i.owner.startsWith("dev/bustling/")))
                    System.out.println(i.owner + "." + i.name + i.desc);
                if (n instanceof FieldInsnNode i && (i.owner.startsWith("net/") || i.owner.startsWith("dev/bustling/")))
                    System.out.println(i.owner + "." + i.name + " " + i.desc);
            }
        }
    }
}
