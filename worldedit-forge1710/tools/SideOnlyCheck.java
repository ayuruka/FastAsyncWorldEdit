import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Usage: SideOnlyCheck <mod-dev.jar (MCP names)> <recompiled_minecraft.jar (MCP names, with @SideOnly)>
 * Reports references from the mod to classes/methods/fields annotated @SideOnly(Side.CLIENT).
 */
public class SideOnlyCheck {

    static final String SIDE_ONLY = "Lcpw/mods/fml/relauncher/SideOnly;";

    static final Map<String, ClassNode> mc = new HashMap<>();

    public static void main(String[] args) throws Exception {
        try (JarFile jar = new JarFile(args[1])) {
            for (JarEntry e : Collections.list(jar.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(in).accept(cn, ClassReader.SKIP_CODE);
                    mc.put(cn.name, cn);
                }
            }
        }
        Set<String> problems = new TreeSet<>();
        try (JarFile jar = new JarFile(args[0])) {
            for (JarEntry e : Collections.list(jar.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                ClassNode cn = new ClassNode();
                try (InputStream in = jar.getInputStream(e)) {
                    new ClassReader(in).accept(cn, 0);
                }
                for (MethodNode m : cn.methods) {
                    for (AbstractInsnNode insn : m.instructions) {
                        String owner = null, name = null, desc = null;
                        boolean method = false;
                        if (insn instanceof MethodInsnNode mi) {
                            owner = mi.owner; name = mi.name; desc = mi.desc; method = true;
                        } else if (insn instanceof FieldInsnNode fi) {
                            owner = fi.owner; name = fi.name; desc = fi.desc;
                        } else if (insn instanceof TypeInsnNode ti) {
                            owner = ti.desc;
                        }
                        if (owner == null) continue;
                        if (owner.startsWith("[")) owner = Type.getType(owner).getElementType().getInternalName();
                        if (clientClass(owner)) {
                            problems.add("CLIENT CLASS " + owner + "  <- " + cn.name + "." + m.name);
                        } else if (name != null && clientMember(owner, name, desc, method)) {
                            problems.add("CLIENT MEMBER " + owner + "." + name + desc + "  <- " + cn.name + "." + m.name);
                        }
                    }
                }
            }
        }
        System.out.println("problems=" + problems.size());
        problems.forEach(System.out::println);
    }

    static boolean isClient(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (SIDE_ONLY.equals(a.desc) && a.values != null) {
                for (int i = 0; i < a.values.size(); i += 2) {
                    Object v = a.values.get(i + 1);
                    if (v instanceof String[] ev && "CLIENT".equals(ev[1])) return true;
                }
            }
        }
        return false;
    }

    static boolean clientClass(String name) {
        ClassNode cn = mc.get(name);
        return cn != null && (isClient(cn.visibleAnnotations) || isClient(cn.invisibleAnnotations));
    }

    static boolean clientMember(String owner, String name, String desc, boolean method) {
        Deque<String> todo = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String o = todo.poll();
            if (!seen.add(o)) continue;
            ClassNode cn = mc.get(o);
            if (cn == null) continue;
            if (method) {
                for (MethodNode m : cn.methods) {
                    if (m.name.equals(name) && m.desc.equals(desc)) {
                        return isClient(m.visibleAnnotations) || isClient(m.invisibleAnnotations);
                    }
                }
            } else {
                for (FieldNode f : cn.fields) {
                    if (f.name.equals(name)) {
                        return isClient(f.visibleAnnotations) || isClient(f.invisibleAnnotations);
                    }
                }
            }
            if (cn.superName != null) todo.add(cn.superName);
            if (cn.interfaces != null) todo.addAll(cn.interfaces);
        }
        return false;
    }

}
