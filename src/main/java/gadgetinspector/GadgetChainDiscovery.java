package gadgetinspector;

import gadgetinspector.config.GIConfig;
import gadgetinspector.config.JavaDeserializationConfig;
import gadgetinspector.data.ClassReference;
import gadgetinspector.data.DataLoader;
import gadgetinspector.data.GraphCall;
import gadgetinspector.data.InheritanceDeriver;
import gadgetinspector.data.InheritanceMap;
import gadgetinspector.data.MethodReference;
import gadgetinspector.data.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GadgetChainDiscovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(GadgetChainDiscovery.class);

    private final GIConfig config;
    private final Set<String> blacklist;

    public GadgetChainDiscovery(GIConfig config, Set<String> blacklist) {
        this.config = config;
        this.blacklist = blacklist;
    }

    public void discover() throws Exception {
        Map<MethodReference.Handle, MethodReference> methodMap = DataLoader.loadMethods();
        InheritanceMap inheritanceMap = InheritanceMap.load();
        Map<MethodReference.Handle, Set<MethodReference.Handle>> methodImplMap = InheritanceDeriver.getAllMethodImplementations(
                inheritanceMap, methodMap);

        final ImplementationFinder implementationFinder = config.getImplementationFinder(
                methodMap, methodImplMap, inheritanceMap);

        try (Writer writer = Files.newBufferedWriter(Paths.get("methodimpl.dat"))) {
            for (Map.Entry<MethodReference.Handle, Set<MethodReference.Handle>> entry : methodImplMap.entrySet()) {
                writer.write(entry.getKey().getClassReference().getName());
                writer.write("\t");
                writer.write(entry.getKey().getName());
                writer.write("\t");
                writer.write(entry.getKey().getDesc());
                writer.write("\n");
                for (MethodReference.Handle method : entry.getValue()) {
                    writer.write("\t");
                    writer.write(method.getClassReference().getName());
                    writer.write("\t");
                    writer.write(method.getName());
                    writer.write("\t");
                    writer.write(method.getDesc());
                    writer.write("\n");
                }
            }
        }

        Map<MethodReference.Handle, Set<GraphCall>> graphCallMap = new HashMap<>();
        for (GraphCall graphCall : DataLoader.loadData(Paths.get("callgraph.dat"), new GraphCall.Factory())) {
            MethodReference.Handle caller = graphCall.getCallerMethod();
            if (!graphCallMap.containsKey(caller)) {
                Set<GraphCall> graphCalls = new HashSet<>();
                graphCalls.add(graphCall);
                graphCallMap.put(caller, graphCalls);
            } else {
                graphCallMap.get(caller).add(graphCall);
            }
        }

        List<Source> sources = DataLoader.loadData(Paths.get("sources.dat"), new Source.Factory());

        GadgetChainDiscoveryStrategy gadgetChainDiscoveryStrategy = new DefaultGadgetChainDiscoveryStrategy(sources, inheritanceMap, implementationFinder, graphCallMap);
        Set<GadgetChain> discoveredGadgets = gadgetChainDiscoveryStrategy.findGadgetChains();

        try (OutputStream outputStream = Files.newOutputStream(Paths.get("gadget-chains.txt"));
             Writer writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
            for (GadgetChain chain : discoveredGadgets) {
                printGadgetChain(writer, chain);
            }
        }

        DotUtil.dumpGadgetChains("gadget-chains.dot", discoveredGadgets);

        LOGGER.info("Found {} gadget chains.", discoveredGadgets.size());
    }

    private boolean isBlacklisted(MethodReference.Handle method) {
        for (String blacklistItem : blacklist) {
            if (method.toString().contains(blacklistItem)) {
                return true;
            }
        }
        return false;
    }

    private static void printGadgetChain(Writer writer, GadgetChain chain) throws IOException {
        writer.write(String.format("%s.%s%s (%d)%n",
                chain.links.get(0).method.getClassReference().getName(),
                chain.links.get(0).method.getName(),
                chain.links.get(0).method.getDesc(),
                chain.links.get(0).taintedArgIndex));
        for (int i = 1; i < chain.links.size(); i++) {
            writer.write(String.format("  %s.%s%s (%d)%n",
                    chain.links.get(i).method.getClassReference().getName(),
                    chain.links.get(i).method.getName(),
                    chain.links.get(i).method.getDesc(),
                    chain.links.get(i).taintedArgIndex));
        }
        writer.write("\n");
    }

    static class GadgetChain {
        private final List<GadgetChainLink> links;

        private GadgetChain(List<GadgetChainLink> links) {
            this.links = links;
        }

        private GadgetChain(GadgetChain gadgetChain, GadgetChainLink link) {
            List<GadgetChainLink> links = new ArrayList<GadgetChainLink>(gadgetChain.links);
            links.add(link);
            this.links = links;
        }

        public List<GadgetChainLink> getLinks() {
            return Collections.unmodifiableList(links);
        }
    }

    static class GadgetChainLink {
        private final MethodReference.Handle method;
        private final int taintedArgIndex;

        private GadgetChainLink(MethodReference.Handle method, int taintedArgIndex) {
            this.method = method;
            this.taintedArgIndex = taintedArgIndex;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            GadgetChainLink that = (GadgetChainLink) o;

            if (taintedArgIndex != that.taintedArgIndex) return false;
            return method != null ? method.equals(that.method) : that.method == null;
        }

        @Override
        public int hashCode() {
            int result = method != null ? method.hashCode() : 0;
            result = 31 * result + taintedArgIndex;
            return result;
        }

        public MethodReference.Handle getMethod() {
            return method;
        }

        public int getTaintedArgIndex() {
            return taintedArgIndex;
        }

        @Override
        public String toString() {
            return method.toString() + "@" + taintedArgIndex;
        }
    }

    /*
    private Set<GadgetChain> getSources(Map<Long, String> classNameMap, Map<Long, MethodReferenceOld> methodIdMap, Map<Long, Set<Long>> inheritanceMap) {
        Long serializableClassId = null;
        for (Map.Entry<Long, String> entry : classNameMap.entrySet()) {
            if (entry.getValue().equals("java/io/Serializable")) {
                serializableClassId = entry.getKey();
                break;
            }
        }
        if (serializableClassId == null) {
            throw new IllegalStateException("No class ID found for java.io.Serializable");
        }

        Set<GadgetChain> sources = new HashSet<>();
        for (Map.Entry<Long, MethodReferenceOld> entry : methodIdMap.entrySet()) {
            MethodReferenceOld method = entry.getValue();
            if (inheritanceMap.get(method.getClassId()).contains(serializableClassId)
                    && method.getName().equals("readObject")
                    && method.getDesc().equals("(Ljava/io/ObjectInputStream;)V")) {
                sources.add(new GadgetChain(Arrays.asList(new GadgetChainLink(entry.getKey(), 0))));
            }
        }

        return sources;
    }
    */

    /**
     * Represents a collection of methods in the JDK that we consider to be "interesting". If a gadget chain can
     * successfully exercise one of these, it could represent anything as mundade as causing the target to make a DNS
     * query to full blown RCE.
     * @param method
     * @param argIndex
     * @param inheritanceMap
     * @return
     */
    // TODO: Parameterize this as a configuration option
    private boolean isSink(MethodReference.Handle method, int argIndex, InheritanceMap inheritanceMap) {
        if (method.getClassReference().getName().equals("java/io/FileInputStream")
                && method.getName().equals("<init>")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/io/FileOutputStream")
                && method.getName().equals("<init>")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/nio/file/Files")
            && (method.getName().equals("newInputStream")
                || method.getName().equals("newOutputStream")
                || method.getName().equals("newBufferedReader")
                || method.getName().equals("newBufferedWriter"))) {
            return true;
        }

        if (method.getClassReference().getName().equals("java/lang/Runtime")
                && method.getName().equals("exec")) {
            return true;
        }
        /*
        if (method.getClassReference().getName().equals("java/lang/Class")
                && method.getName().equals("forName")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/lang/Class")
                && method.getName().equals("getMethod")) {
            return true;
        }
        */
        // If we can invoke an arbitrary method, that's probably interesting (though this doesn't assert that we
        // can control its arguments). Conversely, if we can control the arguments to an invocation but not what
        // method is being invoked, we don't mark that as interesting.
        if (method.getClassReference().getName().equals("java/lang/reflect/Method")
                && method.getName().equals("invoke") && argIndex == 0) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/net/URLClassLoader")
                && method.getName().equals("newInstance")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/lang/System")
                && method.getName().equals("exit")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/lang/Shutdown")
                && method.getName().equals("exit")) {
            return true;
        }
        if (method.getClassReference().getName().equals("java/lang/Runtime")
                && method.getName().equals("exit")) {
            return true;
        }

        if (method.getClassReference().getName().equals("java/nio/file/Files")
                && method.getName().equals("newOutputStream")) {
            return true;
        }

        if (method.getClassReference().getName().equals("java/lang/ProcessBuilder")
                && method.getName().equals("<init>") && argIndex > 0) {
            return true;
        }

        if (inheritanceMap.isSubclassOf(method.getClassReference(), new ClassReference.Handle("java/lang/ClassLoader"))
                && method.getName().equals("<init>")) {
            return true;
        }

        if (method.getClassReference().getName().equals("java/net/URL") && method.getName().equals("openStream")) {
            return true;
        }

        // Some groovy-specific sinks
        if (method.getClassReference().getName().equals("org/codehaus/groovy/runtime/InvokerHelper")
                && method.getName().equals("invokeMethod") && argIndex == 1) {
            return true;
        }

        if (inheritanceMap.isSubclassOf(method.getClassReference(), new ClassReference.Handle("groovy/lang/MetaClass"))
                && Arrays.asList("invokeMethod", "invokeConstructor", "invokeStaticMethod").contains(method.getName())) {
            return true;
        }

        // This jython-specific sink effectively results in RCE
        if (method.getClassReference().getName().equals("org/python/core/PyCode") && method.getName().equals("call")) {
            return true;
        }

        return false;
    }

    public static void main(String[] args) throws Exception {
        GadgetChainDiscovery gadgetChainDiscovery = new GadgetChainDiscovery(new JavaDeserializationConfig(), new HashSet<>());
        gadgetChainDiscovery.discover();
    }


    public interface GadgetChainDiscoveryStrategy {
        Set<GadgetChain> findGadgetChains();
    }

    public abstract class AbstractGadgetChainDiscoveryStrategy implements GadgetChainDiscoveryStrategy {
        final List<Source> sources;
        final InheritanceMap inheritanceMap;
        final ImplementationFinder implementationFinder;
        final Map<MethodReference.Handle, Set<GraphCall>> graphCallMap;

        public AbstractGadgetChainDiscoveryStrategy(List<Source> sources, InheritanceMap inheritanceMap, ImplementationFinder implementationFinder, Map<MethodReference.Handle, Set<GraphCall>> graphCallMap) {
            this.sources = sources;
            this.inheritanceMap = inheritanceMap;
            this.implementationFinder = implementationFinder;
            this.graphCallMap = graphCallMap;
        }
    }

    public class DefaultGadgetChainDiscoveryStrategy extends AbstractGadgetChainDiscoveryStrategy {

        public DefaultGadgetChainDiscoveryStrategy(List<Source> sources, InheritanceMap inheritanceMap, ImplementationFinder implementationFinder, Map<MethodReference.Handle, Set<GraphCall>> graphCallMap) {
            super(sources, inheritanceMap, implementationFinder, graphCallMap);
        }

        public Set<GadgetChain> findGadgetChains() {
            Set<GadgetChainLink> exploredMethods = new HashSet<>();
            LinkedList<GadgetChain> methodsToExplore = new LinkedList<>();

            for (Source source : this.sources) {
                GadgetChainLink srcLink = new GadgetChainLink(source.getSourceMethod(), source.getTaintedArgIndex());

                if (isBlacklisted(srcLink.method))
                    continue;
                if (exploredMethods.contains(srcLink))
                    continue;

                methodsToExplore.add(new GadgetChain(Arrays.asList(srcLink)));
                exploredMethods.add(srcLink);
            }

            Set<GadgetChain> discoveredGadgets = new HashSet<>();

            long iteration = 0;
            while (methodsToExplore.size() > 0) {
                if ((iteration % 1000) == 0) {
                    LOGGER.info("Iteration " + iteration + ", Search space: " + methodsToExplore.size());
                }
                iteration += 1;

                GadgetChain chain = methodsToExplore.pop();
                GadgetChainLink lastLink = chain.links.get(chain.links.size()-1);

                Set<GraphCall> methodCalls = graphCallMap.get(lastLink.method);
                if (methodCalls != null) {
                    for (GraphCall graphCall : methodCalls) {

                        if (graphCall.getCallerArgIndex() != lastLink.taintedArgIndex)
                            continue;

                        Set<MethodReference.Handle> allImpls = implementationFinder.getImplementations(graphCall.getTargetMethod());

                        for (MethodReference.Handle methodImpl : allImpls) {
                            GadgetChainLink newLink = new GadgetChainLink(methodImpl, graphCall.getTargetArgIndex());

                            if (isBlacklisted(newLink.method))
                                exploredMethods.add(newLink);

                            if (exploredMethods.contains(newLink))
                                continue;

                            GadgetChain newChain = new GadgetChain(chain, newLink);
                            if (isSink(methodImpl, graphCall.getTargetArgIndex(), inheritanceMap)) {
                                discoveredGadgets.add(newChain);
                            } else {
                                methodsToExplore.add(newChain);
                                exploredMethods.add(newLink);
                            }
                        }
                    }
                }
            }
            return discoveredGadgets;
        }
    }
}
