/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.tools;

import org.apache.kafka.common.utils.AppInfoParser;
import org.apache.kafka.connect.runtime.isolation.PluginSource;
import org.apache.kafka.connect.runtime.isolation.PluginType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * An in-memory workspace for manipulating {@link java.util.ServiceLoader} manifest files.
 * <p>Use {@link #forSource(PluginSource)} to get a workspace scoped to a single plugin location, which is able
 * to accept simulated reads and writes of manifests.
 * Write the simulated changes to disk via {@link #commit(boolean)}.
 */
public class ManifestWorkspace {

    private static final Logger log = LoggerFactory.getLogger(ManifestWorkspace.class);

    private static final String MANIFEST_PREFIX = "META-INF/services/";
    private static final Path MANAGED_PATH = Paths.get("connect-plugin-path-shim-1.0.0.jar");
    private static final String MANIFEST_HEADER = "# Generated by connect-plugin-path.sh " + AppInfoParser.getVersion();
    private final PrintStream out;
    private final List<SourceWorkspace<?>> workspaces;
    private final Map<Path, Path> temporaryOverlayFiles;

    public ManifestWorkspace(PrintStream out) {
        this.out = out;
        workspaces = new ArrayList<>();
        temporaryOverlayFiles = new HashMap<>();
    }

    public SourceWorkspace<?> forSource(PluginSource source) throws IOException {
        SourceWorkspace<?> sourceWorkspace;
        switch (source.type()) {
            case CLASSPATH:
                sourceWorkspace = new ClasspathWorkspace(source);
                break;
            case MULTI_JAR:
                sourceWorkspace = new MultiJarWorkspace(source);
                break;
            case SINGLE_JAR:
                sourceWorkspace = new SingleJarWorkspace(source);
                break;
            case CLASS_HIERARCHY:
                sourceWorkspace = new ClassHierarchyWorkspace(source);
                break;
            default:
                throw new IllegalStateException("Unknown source type " + source.type());
        }
        workspaces.add(sourceWorkspace);
        return sourceWorkspace;
    }

    /**
     * Commits all queued changes to disk
     * @return true if any workspace wrote changes to disk, false if all workspaces did not have writes to apply
     * @throws IOException if an error occurs reading or writing to the filesystem
     * @throws TerseException if a path is not writable on disk and should be.
     */
    public boolean commit(boolean dryRun) throws IOException, TerseException {
        boolean changed = false;
        for (SourceWorkspace<?> workspace : workspaces) {
            changed |= workspace.commit(dryRun);
        }
        return changed;
    }

    /**
     * A workspace scoped to a single plugin source.
     * <p>Buffers simulated reads and writes to the plugin path before they can be written to disk.
     * @param <T> The data structure used by the workspace to store in-memory manifests internally.
     */
    public abstract static class SourceWorkspace<T> {
        private final Path location;
        private final PluginSource.Type type;
        protected final T initial;
        protected final T manifests;

        private SourceWorkspace(PluginSource source) throws IOException {
            this.location = source.location();
            this.type = source.type();
            this.initial = load(source);
            this.manifests = load(source);
        }

        public Path location() {
            return location;
        }

        public PluginSource.Type type() {
            return type;
        }

        protected abstract T load(PluginSource source) throws IOException;

        public abstract boolean hasManifest(PluginType type, String className);

        public abstract void forEach(BiConsumer<String, PluginType> consumer);

        public abstract void addManifest(PluginType type, String pluginClass);

        public abstract void removeManifest(PluginType type, String pluginClass);

        protected abstract boolean commit(boolean dryRun) throws TerseException, IOException;

        protected static Map<PluginType, Set<String>> loadManifest(URL baseUrl) throws MalformedURLException {
            Map<PluginType, Set<String>> manifests = new EnumMap<>(PluginType.class);
            for (PluginType type : PluginType.values()) {
                Set<String> result;
                try {
                    URL u = new URL(baseUrl, MANIFEST_PREFIX + type.superClass().getName());
                    result = parse(u);
                } catch (RuntimeException e) {
                    result = new LinkedHashSet<>();
                }
                manifests.put(type, result);
            }
            return manifests;
        }

        protected static URL jarBaseUrl(URL fileUrl) throws MalformedURLException {
            return new URL("jar", "", -1, fileUrl + "!/", null);
        }

        protected static void forEach(Map<PluginType, Set<String>> manifests, BiConsumer<String, PluginType> consumer) {
            manifests.forEach((type, classNames) -> classNames.forEach(className -> consumer.accept(className, type)));
        }
    }

    /**
     * A single jar can only contain one manifest per plugin type.
     */
    private class SingleJarWorkspace extends SourceWorkspace<Map<PluginType, Set<String>>> {

        private SingleJarWorkspace(PluginSource source) throws IOException {
            super(source);
            assert source.urls().length == 1;
        }

        @Override
        protected Map<PluginType, Set<String>> load(PluginSource source) throws IOException {
            return loadManifest(jarBaseUrl(source.urls()[0]));
        }

        @Override
        public boolean hasManifest(PluginType type, String className) {
            return manifests.get(type).contains(className);
        }

        @Override
        public void forEach(BiConsumer<String, PluginType> consumer) {
            forEach(manifests, consumer);
        }

        @Override
        public void addManifest(PluginType type, String pluginClass) {
            manifests.get(type).add(pluginClass);
        }

        @Override
        public void removeManifest(PluginType type, String pluginClass) {
            manifests.get(type).remove(pluginClass);
        }

        @Override
        protected boolean commit(boolean dryRun) throws IOException, TerseException {
            if (startSync(dryRun, location(), initial, manifests)) {
                rewriteJar(dryRun, location(), manifests);
                return true;
            }
            return false;
        }
    }

    /**
     * A classpath workspace is backed by multiple jars, and is not writable.
     * The in-memory format is a map from jar path to the manifests contained in that jar.
     * The control flow of the caller should not perform writes, so these exceptions indicate a bug in the program.
     */
    private class ClasspathWorkspace extends SourceWorkspace<Map<Path, Map<PluginType, Set<String>>>> {

        private ClasspathWorkspace(PluginSource source) throws IOException {
            super(source);
        }

        @Override
        protected Map<Path, Map<PluginType, Set<String>>> load(PluginSource source) throws IOException {
            Map<Path, Map<PluginType, Set<String>>> manifestsBySubLocation = new HashMap<>();
            for (URL url : source.urls()) {
                Path jarPath = Paths.get(url.getPath());
                manifestsBySubLocation.put(jarPath, loadManifest(jarBaseUrl(url)));
            }
            return manifestsBySubLocation;
        }

        public boolean hasManifest(PluginType type, String className) {
            return manifests.values()
                    .stream()
                    .map(m -> m.get(type))
                    .anyMatch(s -> s.contains(className));
        }

        public void forEach(BiConsumer<String, PluginType> consumer) {
            manifests.values().forEach(m -> forEach(m, consumer));
        }

        @Override
        public void addManifest(PluginType type, String pluginClass) {
            throw new UnsupportedOperationException("Cannot change the contents of the classpath");
        }

        @Override
        public void removeManifest(PluginType type, String pluginClass) {
            throw new UnsupportedOperationException("Cannot change the contents of the classpath");
        }

        @Override
        protected boolean commit(boolean dryRun) throws IOException, TerseException {
            // There is never anything to commit for the classpath
            return false;
        }
    }

    /**
     * A multi-jar workspace is similar to the classpath workspace because it has multiple jars.
     * However, the multi-jar workspace is writable, and injects a managed jar where it writes added manifests.
     */
    private class MultiJarWorkspace extends ClasspathWorkspace {

        private MultiJarWorkspace(PluginSource source) throws IOException {
            super(source);
        }

        @Override
        protected Map<Path, Map<PluginType, Set<String>>> load(PluginSource source) throws IOException {
            Map<Path, Map<PluginType, Set<String>>> manifests = super.load(source);
            // In addition to the normal multi-jar paths, inject a managed jar where we can add manifests.
            Path managedPath = source.location().resolve(MANAGED_PATH);
            URL url = managedPath.toUri().toURL();
            manifests.put(managedPath, loadManifest(jarBaseUrl(url)));
            return manifests;
        }

        @Override
        public void addManifest(PluginType type, String pluginClass) {
            // Add plugins to the managed manifest
            manifests.get(location().resolve(MANAGED_PATH)).get(type).add(pluginClass);
        }

        @Override
        public void removeManifest(PluginType type, String pluginClass) {
            // If a plugin appears in multiple manifests, remove it from all of them.
            for (Map<PluginType, Set<String>> manifestState : manifests.values()) {
                manifestState.get(type).remove(pluginClass);
            }
        }

        @Override
        public boolean commit(boolean dryRun) throws IOException, TerseException {
            boolean changed = false;
            for (Map.Entry<Path, Map<PluginType, Set<String>>> manifestSource : manifests.entrySet()) {
                Path jarPath = manifestSource.getKey();
                Map<PluginType, Set<String>> before = initial.get(jarPath);
                Map<PluginType, Set<String>> after = manifestSource.getValue();
                if (startSync(dryRun, jarPath, before, after)) {
                    rewriteJar(dryRun, jarPath, after);
                    changed = true;
                }
            }
            return changed;
        }
    }

    /**
     * The class hierarchy is similar to the single-jar because there can only be one manifest per type.
     * However, the path to that single manifest is accessed via the pluginLocation.
     */
    private class ClassHierarchyWorkspace extends SingleJarWorkspace {

        private ClassHierarchyWorkspace(PluginSource source) throws IOException {
            super(source);
        }

        @Override
        protected Map<PluginType, Set<String>> load(PluginSource source) throws IOException {
            return loadManifest(source.location().toUri().toURL());
        }

        protected boolean commit(boolean dryRun) throws IOException, TerseException {
            if (startSync(dryRun, location(), initial, manifests)) {
                rewriteClassHierarchyManifest(dryRun, location(), manifests);
                return true;
            }
            return false;
        }
    }

    private boolean startSync(boolean dryRun, Path syncLocation, Map<PluginType, Set<String>> before, Map<PluginType, Set<String>> after) {
        Objects.requireNonNull(syncLocation, "syncLocation must be non-null");
        Objects.requireNonNull(before, "before must be non-null");
        Objects.requireNonNull(after, "after must be non-null");
        if (before.equals(after)) {
            return false;
        }
        Set<String> added = new HashSet<>();
        after.values().forEach(added::addAll);
        before.values().forEach(added::removeAll);
        Set<String> removed = new HashSet<>();
        before.values().forEach(removed::addAll);
        after.values().forEach(removed::removeAll);
        out.printf("%sSync\t%s Add %s Remove %s%n", dryRun ? "Dry Run " : "", syncLocation, added.size(), removed.size());
        for (String add : added) {
            out.printf("\tAdd\t%s%n", add);
        }
        for (String rem : removed) {
            out.printf("\tRemove\t%s%n", rem);
        }
        return true;
    }

    /**
     * Rewrite a jar on disk to have manifests with the specified entries.
     * Will create the jar file if it does not exist and at least one manifest element is specified.
     * Will delete the jar file if it exists, and would otherwise remain empty.
     *
     * @param dryRun           True if the rewrite should be applied, false if it should be simulated.
     * @param jarPath          Path to a jar file for a plugin
     * @param manifestElements Map from plugin type to Class names of plugins which should appear in that manifest
     */
    private void rewriteJar(boolean dryRun, Path jarPath, Map<PluginType, Set<String>> manifestElements) throws IOException, TerseException {
        Objects.requireNonNull(jarPath, "jarPath must be non-null");
        Objects.requireNonNull(manifestElements, "manifestState must be non-null");
        Path writableJar = getWritablePath(dryRun, jarPath);
        if (nonEmpty(manifestElements) && !Files.exists(writableJar)) {
            log.debug("Create {}", jarPath);
            createJar(writableJar);
        }
        try (FileSystem jar = FileSystems.newFileSystem(
                new URI("jar", writableJar.toUri().toString(), ""),
                Collections.emptyMap()
        )) {
            Path zipRoot = jar.getRootDirectories().iterator().next();
            // Set dryRun to false because this jar file is already a writable copy.
            rewriteClassHierarchyManifest(false, zipRoot, manifestElements);
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        if (Files.exists(writableJar) && jarIsEmpty(writableJar)) {
            Files.delete(writableJar);
        }
    }

    private static boolean nonEmpty(Map<PluginType, Set<String>> manifestElements) {
        return !manifestElements.values().stream().allMatch(Collection::isEmpty);
    }

    private void createJar(Path path) throws IOException {
        Objects.requireNonNull(path, "path must be non-null");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        ))) {
            stream.closeEntry();
        }
    }

    private boolean jarIsEmpty(Path path) throws IOException {
        Objects.requireNonNull(path, "path must be non-null");
        try (ZipInputStream stream = new ZipInputStream(Files.newInputStream(
                path,
                StandardOpenOption.READ
        ))) {
            return stream.getNextEntry() == null;
        }
    }

    /**
     * Rewrite multiple manifest files contained inside a class hierarchy.
     * Will create the files and parent directories if they do not exist and at least one element is specified.
     * Will delete the files and parent directories if they exist and would otherwise remain empty.
     *
     * @param dryRun           True if the rewrite should be applied, false if it should be simulated.
     * @param pluginLocation   Path to top-level of the class hierarchy
     * @param manifestElements Map from plugin type to Class names of plugins which should appear in that manifest
     */
    private void rewriteClassHierarchyManifest(boolean dryRun, Path pluginLocation, Map<PluginType, Set<String>> manifestElements) throws IOException, TerseException {
        Objects.requireNonNull(pluginLocation, "pluginLocation must be non-null");
        Objects.requireNonNull(manifestElements, "manifestState must be non-null");
        if (!Files.exists(pluginLocation)) {
            throw new TerseException(pluginLocation + " does not exist");
        }
        if (!Files.isWritable(pluginLocation)) {
            throw new TerseException(pluginLocation + " is not writable");
        }
        Path metaInfPath = pluginLocation.resolve("META-INF");
        Path servicesPath = metaInfPath.resolve("services");
        if (nonEmpty(manifestElements) && !Files.exists(servicesPath) && !dryRun) {
            Files.createDirectories(servicesPath);
        }
        for (Map.Entry<PluginType, Set<String>> manifest : manifestElements.entrySet()) {
            PluginType type = manifest.getKey();
            Set<String> elements = manifest.getValue();
            rewriteManifestFile(dryRun, servicesPath.resolve(type.superClass().getName()), elements);
        }
        deleteDirectoryIfEmpty(dryRun, servicesPath);
        deleteDirectoryIfEmpty(dryRun, metaInfPath);
    }

    private void deleteDirectoryIfEmpty(boolean dryRun, Path path) throws IOException, TerseException {
        if (!Files.exists(path)) {
            return;
        }
        if (!Files.isWritable(path)) {
            throw new TerseException(path + " is not writable");
        }
        try (Stream<Path> list = Files.list(path)) {
            if (list.findAny().isPresent()) {
                return;
            }
        }
        log.debug("Delete {}", path);
        if (!dryRun) {
            Files.delete(path);
        }
    }

    /**
     * Rewrite a single manifest file.
     * Will create the file if it does not exist and at least one element is specified.
     * Will delete the file if it exists and no elements are specified.
     *
     * @param dryRun              True if the rewrite should be applied, false if it should be simulated.
     * @param filePath            Path to file which should be rewritten.
     * @param elements            Class names of plugins which should appear in the manifest
     */
    private void rewriteManifestFile(boolean dryRun, Path filePath, Set<String> elements) throws IOException, TerseException {
        Objects.requireNonNull(filePath, "filePath must be non-null");
        Objects.requireNonNull(elements, "elements must be non-null");
        Path writableFile = getWritablePath(dryRun, filePath);
        if (elements.isEmpty()) {
            if (Files.exists(filePath)) {
                log.debug("Delete {}", filePath);
                if (!dryRun) {
                    Files.delete(writableFile);
                }
            }
        } else {
            if (!Files.exists(filePath)) {
                log.debug("Create {}", filePath);
            }
            log.debug("Write {} with content {}", filePath, elements);
            if (!dryRun) {
                try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(
                        writableFile,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                ))) {
                    byte[] newline = System.lineSeparator().getBytes(StandardCharsets.UTF_8);
                    stream.write(MANIFEST_HEADER.getBytes(StandardCharsets.UTF_8));
                    stream.write(newline);
                    for (String element : elements) {
                        stream.write(element.getBytes(StandardCharsets.UTF_8));
                        stream.write(newline);
                    }
                }
            }
        }
    }

    /**
     * Get a path which is always writable
     * @param dryRun If true, substitute a temporary file instead of the real file on disk.
     * @param path Path which must be writable
     * @return Path which is writable, and may be different from the input path
     */
    private Path getWritablePath(boolean dryRun, Path path) throws IOException, TerseException {
        Objects.requireNonNull(path, "path must be non-null");
        for (Path parent = path; parent != null && !Files.isWritable(parent); parent = parent.getParent()) {
            if (Files.exists(parent) && !Files.isWritable(parent)) {
                throw new TerseException("Path " + path + " must be writable");
            }
        }
        if (dryRun) {
            if (!temporaryOverlayFiles.containsKey(path)) {
                Path fileName = path.getFileName();
                String suffix = fileName != null ? fileName.toString() : ".temp";
                Path temp = Files.createTempFile("connect-plugin-path-temporary-", suffix);
                if (Files.exists(path)) {
                    Files.copy(path, temp, StandardCopyOption.REPLACE_EXISTING);
                    temp.toFile().deleteOnExit();
                } else {
                    Files.delete(temp);
                }
                temporaryOverlayFiles.put(path, temp);
                return temp;
            }
            return temporaryOverlayFiles.get(path);
        }
        return path;
    }

    // Based on implementation from ServiceLoader.LazyClassPathLookupIterator from OpenJDK11
    private static Set<String> parse(URL u) {
        Set<String> names = new LinkedHashSet<>(); // preserve insertion order
        try {
            URLConnection uc = u.openConnection();
            uc.setUseCaches(false);
            try (InputStream in = uc.getInputStream();
                 BufferedReader r
                         = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                int lc = 1;
                while ((lc = parseLine(u, r, lc, names)) >= 0) {
                    // pass
                }
            }
        } catch (IOException x) {
            throw new RuntimeException("Error accessing configuration file", x);
        }
        return names;
    }

    // Based on implementation from ServiceLoader.LazyClassPathLookupIterator from OpenJDK11
    private static int parseLine(URL u, BufferedReader r, int lc, Set<String> names) throws IOException {
        String ln = r.readLine();
        if (ln == null) {
            return -1;
        }
        int ci = ln.indexOf('#');
        if (ci >= 0) ln = ln.substring(0, ci);
        ln = ln.trim();
        int n = ln.length();
        if (n != 0) {
            if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0))
                throw new IOException("Illegal configuration-file syntax in " + u);
            int cp = ln.codePointAt(0);
            if (!Character.isJavaIdentifierStart(cp))
                throw new IOException("Illegal provider-class name: " + ln + " in " + u);
            int start = Character.charCount(cp);
            for (int i = start; i < n; i += Character.charCount(cp)) {
                cp = ln.codePointAt(i);
                if (!Character.isJavaIdentifierPart(cp) && (cp != '.'))
                    throw new IOException("Illegal provider-class name: " + ln + " in " + u);
            }
            names.add(ln);
        }
        return lc + 1;
    }
}
