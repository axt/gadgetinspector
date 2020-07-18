package gadgetinspector;

import com.google.common.collect.ImmutableMap;
import gadgetinspector.data.MethodReference;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import gadgetinspector.GadgetChainDiscovery.GadgetChain;
import gadgetinspector.GadgetChainDiscovery.GadgetChainLink;

public class DotUtil {

    private static Map<String, String> normalizeMap = ImmutableMap.<String, String>builder()
        .put("/", "_")
        .put(".", "_")
        .put("(", "_")
        .put(")", "_")
        .put("[", "_")
        .put("]", "_")
        .put(">", "_")
        .put("<", "_")
        .put(";", "_")
        .put("@", "_")
        .put("$", "_")
        .build();

    private static String escape(String name) {
        return name;
    }

    private static String normalize(String name) {
        for (Map.Entry<String, String> entry : normalizeMap.entrySet()) {
            name = name.replace(entry.getKey(), entry.getValue());
        }
        return name;
    }

    public static void dumpGadgetChains(String fileName, Set<GadgetChainDiscovery.GadgetChain> discoveredGadgets) throws  IOException {
        Set<GadgetChainLink> nodes = new HashSet<>();
        for (GadgetChain gc : discoveredGadgets) {
            for (GadgetChainLink gl : gc.getLinks()) {
                nodes.add(gl);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph G{\n");
        sb.append("rankdir=LR\n");
        sb.append("newrank=true\n");
        for (GadgetChainLink gl : nodes) {
            sb.append(String.format("%s [label=\"%s\"]\n", normalize(gl.toString()), escape(gl.toString())));
        }

        Map<GadgetChainLink, Set<GadgetChainLink>> edges = new HashMap<>();

        for (GadgetChain gc : discoveredGadgets) {
            GadgetChainLink prev = null;
            for (GadgetChainLink gl : gc.getLinks()) {
                if (prev != null) {
                    Set<GadgetChainLink> targets = edges.get(prev);
                    if (targets == null) {
                        targets = new HashSet<>();
                        edges.put(prev, targets);
                    }
                    if (!targets.contains(gl)) {
                        sb.append(String.format("%s -> %s\n", normalize(prev.toString()), normalize(gl.toString())));
                        targets.add(gl);
                    }
                }
                prev = gl;
            }
        }
        sb.append("}\n");

        try(FileWriter fw = new FileWriter(fileName)) {
            fw.write(sb.toString());
        }
    }
}
