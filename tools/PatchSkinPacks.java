import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Connects the skin-pack source classes to the released renderer and initializer. */
public final class PatchSkinPacks implements Opcodes {
    private static final String MOD = "dev/bustling/";
    private static final String PACKS = MOD + "ClientSkinPacks";
    private static final String PRESENCE = MOD + "CrowdPresence";
    private static final String EMITTER = "L" + MOD + "CrowdData$Emitter;";
    private static final String GHOST = "L" + MOD + "CrowdSampler$Ghost;";
    private static final String ROW = "L" + MOD + "CrowdData$Emitter$MobWeight;";
    private static final String SKIN = "L" + MOD + "SkinLibrary$Skin;";
    private static final String PLAYER_MODEL = "net/minecraft/client/model/PlayerModel";

    private static void common(ClassNode owner) {
        MethodNode init = method(owner, "init");
        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(ALOAD, 0));
        hook.add(new MethodInsnNode(INVOKESTATIC, MOD + "SkinPackNetwork", "init", init.desc, false));
        init.instructions.insert(hook);
    }

    private static void renderer(ClassNode owner) {
        MethodNode pick = method(owner, "pickSkin");
        if (!pick.desc.equals("(" + EMITTER + "ID)" + SKIN)) {
            throw new IllegalStateException("Unexpected skin selection signature");
        }
        pick.instructions.clear();
        pick.localVariables = null;
        pick.instructions.add(new VarInsnNode(ALOAD, 0));
        pick.instructions.add(new VarInsnNode(ILOAD, 1));
        pick.instructions.add(new MethodInsnNode(INVOKESTATIC, PACKS, "selectSkin", "(" + EMITTER + "I)" + SKIN, false));
        pick.instructions.add(new InsnNode(ARETURN));

        int factories = 0;
        for (MethodNode candidate : owner.methods) {
            if (!candidate.name.equals("mobFor")) continue;
            for (AbstractInsnNode insn : candidate.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                        && call.owner.equals(MOD + "MobMotion") && call.name.equals("instance")) {
                    call.owner = MOD + "YsmClientModels";
                    factories++;
                }
            }
        }
        if (factories != 1) throw new IllegalStateException("Expected one crowd entity factory");

        MethodNode render = method(owner, "onRenderLevel");
        int rowSlot = -1;
        int models = 0;
        int ids = 0;
        int animations = 0;
        for (AbstractInsnNode insn : render.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner.name) && call.name.equals("pickMobEntry")
                    && call.getNext() instanceof VarInsnNode store && store.getOpcode() == ASTORE) {
                rowSlot = store.var;
            } else if (insn instanceof FieldInsnNode field && field.getOpcode() == GETFIELD
                    && field.owner.equals(MOD + "CrowdData$Emitter$MobWeight") && field.name.equals("id")) {
                render.instructions.insert(field, new MethodInsnNode(INVOKESTATIC, PACKS, "entityId",
                        "(Ljava/lang/String;)Ljava/lang/String;", false));
                ids++;
            } else if (insn instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                    && call.owner.equals(MOD + "MobMotion") && call.name.equals("animate")) {
                call.owner = MOD + "YsmClientModels";
                animations++;
            } else if (insn instanceof MethodInsnNode call && call.owner.equals(PLAYER_MODEL)
                    && call.name.equals("renderToBuffer") && rowSlot >= 0) {
                InsnList overlay = new InsnList();
                overlay.add(new VarInsnNode(ALOAD, rowSlot));
                overlay.add(new MethodInsnNode(INVOKESTATIC, PACKS, "hasPackSkin", "(" + ROW + ")Z", false));
                render.instructions.insertBefore(call, overlay);
                render.instructions.set(call, new MethodInsnNode(INVOKESTATIC, PACKS, "renderPlayer",
                        "(L" + PLAYER_MODEL + ";" + call.desc.substring(1, call.desc.length() - 2) + "Z)V", false));
                models++;
            }
        }
        if (models != 1 || ids != 1 || animations != 1) {
            throw new IllegalStateException("Expected one crowd model call, one entity ID and one animation call");
        }
        presence(render);
    }

    private static void presence(MethodNode render) {
        int groupReads = 0, groupSlot = -1, ghostSlot = -1, distances = 0, views = 0, navCalls = 0;
        for (AbstractInsnNode insn : render.instructions.toArray()) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(MOD + "CrowdData")
                    && field.name.equals("EMITTERS") && field.getOpcode() == GETSTATIC) {
                String args = "()";
                String handler = "renderOrder";
                if (++groupReads == 1) {
                    args = "(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent;)";
                    handler = "begin";
                    render.instructions.insertBefore(field, new VarInsnNode(ALOAD, 0));
                }
                render.instructions.set(field, new MethodInsnNode(INVOKESTATIC, PRESENCE, handler, args + field.desc, false));
            } else if (insn instanceof TypeInsnNode type && type.getOpcode() == CHECKCAST
                    && type.getNext() instanceof VarInsnNode store && store.getOpcode() == ASTORE) {
                if (("L" + type.desc + ";").equals(EMITTER)) groupSlot = store.var;
                if (("L" + type.desc + ";").equals(GHOST)) ghostSlot = store.var;
            } else if (insn instanceof FieldInsnNode field && field.owner.equals(MOD + "CrowdData$Emitter")
                    && field.name.equals("maxDist") && field.getOpcode() == GETFIELD && ++distances == 2) {
                AbstractInsnNode store = field.getNext();
                while (store != null && store.getOpcode() != DSTORE) store = store.getNext();
                if (store == null || groupSlot < 0) throw new IllegalStateException("Missing crowd render distance");
                render.instructions.insertBefore(store, new VarInsnNode(ALOAD, groupSlot));
                render.instructions.insertBefore(store, new MethodInsnNode(INVOKESTATIC, PRESENCE, "distance",
                        "(D" + EMITTER + ")D", false));
            } else if (insn instanceof MethodInsnNode call && call.owner.equals(MOD + "CrowdNavigation")
                    && call.getOpcode() == INVOKESTATIC) {
                if (switch (call.name) {
                    case "sample", "adjust", "hasActive", "resolve", "animated" -> true;
                    default -> false;
                }) {
                    call.owner = PRESENCE;
                    navCalls++;
                }
            } else if (insn instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/client/renderer/culling/Frustum") && call.name.equals("isVisible")) {
                if (groupSlot < 0 || ghostSlot < 0) throw new IllegalStateException("Missing crowd visibility locals");
                render.instructions.insertBefore(insn, new VarInsnNode(ALOAD, groupSlot));
                render.instructions.insertBefore(insn, new VarInsnNode(ALOAD, ghostSlot));
                render.instructions.set(insn, new MethodInsnNode(INVOKESTATIC, PRESENCE, "visible",
                        "(L" + call.owner + ";Lnet/minecraft/world/phys/AABB;" + EMITTER + GHOST + ")Z", false));
                views++;
            }
        }
        if (groupReads != 2 || views != 1 || distances != 2 || navCalls != 5) {
            throw new IllegalStateException("Unexpected crowd render structure for visibility retention");
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(method -> method.name.equals(name)).findFirst().orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        try (ZipFile base = new ZipFile(args[0])) {
            for (String simpleName : new String[]{"CommonInit", "CrowdRenderer"}) {
                String name = MOD + simpleName + ".class";
                ClassNode node = new ClassNode();
                try (var input = base.getInputStream(base.getEntry(name))) {
                    new ClassReader(input).accept(node, 0);
                }
                if (simpleName.equals("CommonInit")) common(node);
                else renderer(node);
                var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                Path target = output.resolve(name);
                Files.createDirectories(target.getParent());
                Files.write(target, writer.toByteArray());
            }
        }
    }
}
