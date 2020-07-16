package gadgetinspector;

import com.google.common.reflect.ClassPath;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ClassResourceEnumerator {
    private final Path[] jarPaths;
    private final boolean loadRuntimeClasses;

    public ClassResourceEnumerator(Path[] jarPaths) {
        this(jarPaths, true);
    }

    public ClassResourceEnumerator(Path[] jarPaths, boolean loadRuntimeClasses) {
        this.jarPaths = jarPaths;
        this.loadRuntimeClasses = loadRuntimeClasses;
    }

    public Collection<ClassResource> getAllClasses() throws IOException {
        List<ClassResource> result = new ArrayList<>();
        if (loadRuntimeClasses) {
            result.addAll(getRuntimeClasses());
        }
        for (Path path : jarPaths) {
            if (path.toString().endsWith(".war")) {
                try (JarFile jarFile = new JarFile(path.toFile())) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while(entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory())
                            continue;
                        if (entry.getName().startsWith("WEB-INF/lib") && entry.getName().endsWith(".jar")) {
                            File file = File.createTempFile("gadget-chain", "jar");
                            copy(jarFile.getInputStream(entry), new FileOutputStream(file));
                            processJarFile(result, new JarFile(file));
                            file.delete();
                        } else if (entry.getName().startsWith("WEB-INF/classes") && entry.getName().endsWith(".class")) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            copy(jarFile.getInputStream(entry), baos);
                            result.add(new ByteArrayClassResource(entry.getName(), baos.toByteArray()));
                        } else {
                            continue;
                        }
                    }
                }
            } else {
                try (JarFile jarFile = new JarFile(path.toFile())) {
                    processJarFile(result, jarFile);
                }
            }
        }
        return result;
    }

    private void processJarFile(Collection<ClassResource> result, JarFile jarFile) throws IOException {
        Enumeration<JarEntry> entries = jarFile.entries();
        while(entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            if (!entry.getName().endsWith(".class")) {
                continue;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            copy(jarFile.getInputStream(entry), baos);
            result.add(new ByteArrayClassResource(entry.getName(), baos.toByteArray()));
        }
    }

    private Collection<ClassResource> getRuntimeClasses() throws IOException {
        // A hacky way to get the current JRE's rt.jar. Depending on the class loader, rt.jar may be in the
        // bootstrap classloader so all the JDK classes will be excluded from classpath scanning with this!
        // However, this only works up to Java 8, since after that Java uses some crazy module magic.
        URL stringClassUrl = Object.class.getResource("String.class");
        URLConnection connection = stringClassUrl.openConnection();
        Collection<ClassResource> result = new ArrayList<>();
        if (connection instanceof JarURLConnection) {
            URL runtimeUrl = ((JarURLConnection) connection).getJarFileURL();
            URLClassLoader classLoader = new URLClassLoader(new URL[]{runtimeUrl});

            for (ClassPath.ClassInfo classInfo : ClassPath.from(classLoader).getAllClasses()) {
                result.add(new ClassLoaderClassResource(classLoader, classInfo.getResourceName()));
            }
            return result;
        }

        // Try finding all the JDK classes using the Java9+ modules method:
        try {
            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Files.walk(fs.getPath("/")).forEach(p -> {
                if (p.toString().toLowerCase().endsWith(".class")) {
                    result.add(new PathClassResource(p));
                }
            });
        } catch (ProviderNotFoundException e) {
            // Do nothing; this is expected on versions below Java9
        }

        return result;
    }

    public static interface ClassResource {
        public InputStream getInputStream() throws IOException;
        public String getName();
    }


    private static class ByteArrayClassResource implements ClassResource {

        private final String name;
        private final byte[] buffer;

        private ByteArrayClassResource(String name, byte[] buffer) {
            this.name = name;
            this.buffer = buffer;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(this.buffer);
        }

        @Override
        public String getName() {
            return this.name;
        }
    }

    private static class PathClassResource implements ClassResource {
        private final Path path;

        private PathClassResource(Path path) {
            this.path = path;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public String getName() {
            return path.toString();
        }
    }

    private static class ClassLoaderClassResource implements ClassResource {
        private final ClassLoader classLoader;
        private final String resourceName;

        private ClassLoaderClassResource(ClassLoader classLoader, String resourceName) {
            this.classLoader = classLoader;
            this.resourceName = resourceName;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return classLoader.getResourceAsStream(resourceName);
        }

        @Override
        public String getName() {
            return resourceName;
        }
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws IOException {
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, n);
        }
    }
}
