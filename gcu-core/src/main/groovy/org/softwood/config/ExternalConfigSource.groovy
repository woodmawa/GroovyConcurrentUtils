package org.softwood.config

import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@CompileStatic
class ExternalConfigSource {

    static List<Path> collect(
            Map options,
            ConfigSpec spec,
            String profile
    ) {
        LinkedHashSet<Path> out = []

        addPaths(out, System.getProperty(spec.externalFilesSysProp))
        addPaths(out, options.externalFiles)

        List<Path> dirs = []
        addPaths(dirs, System.getProperty(spec.externalDirsSysProp))
        addPaths(dirs, options.externalDirs)

        for (Path d : dirs) {
            if (!Files.isDirectory(d)) continue
            for (String base : spec.externalBaseNames) {
                for (String ext : ConfigSpec.SUPPORTED_EXTENSIONS) {
                    Path p = d.resolve(base + '.' + ext)
                    if (Files.isRegularFile(p)) out << p
                    Path pp = d.resolve(base + '-' + profile + '.' + ext)
                    if (Files.isRegularFile(pp)) out << pp
                }
            }
        }

        return out.toList()
    }

    static Map<String, Object> loadFile(
            Path p,
            String profile,
            Trace trace
    ) {
        try {
            return ParserSupport.parse(p, profile)
        } catch (Exception e) {
            trace.note("Failed to load " + p + ": " + e.getMessage())
            return [:]
        }
    }

    private static void addPaths(Collection<Path> out, Object value) {
        if (value == null) return
        if (value instanceof String) {
            value.split(',').each {
                String val = it as String
                out << Paths.get(val.trim()) }
        } else if (value instanceof List) {
            value.each { out << Paths.get(it.toString()) }
        }
    }
}
