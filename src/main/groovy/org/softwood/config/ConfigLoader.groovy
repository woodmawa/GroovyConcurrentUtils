package org.softwood.config

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.ConfigSlurper
import groovy.util.logging.Slf4j
import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Slf4j
@CompileStatic
class ConfigLoader {

    static ConfigResult load(Map options = [:]) {

        ConfigSpec spec = options.spec instanceof ConfigSpec
                ? (ConfigSpec) options.spec
                : ConfigSpec.defaultSpec()

        String profile = options.profile instanceof String
                ? (String) options.profile
                : resolveProfile(spec)

        Trace trace = new Trace()
        Map<String, Object> merged = new LinkedHashMap<>()

        // ============================================================
        // 1) Classpath configs (library → user)
        // ============================================================

        merged = mergeAll(
                merged,
                trace,
                ClasspathConfigSource.load(
                        spec.libraryDefaultsPath,
                        spec.libraryBaseNames,
                        profile
                )
        )

        merged = mergeAll(
                merged,
                trace,
                ClasspathConfigSource.load(
                        '',
                        spec.userBaseNames,
                        profile
                )
        )

        // ============================================================
        // 2) External config files
        // ============================================================

        List<Path> externalFiles = collectExternalFiles(options, spec, profile)
        for (Path p : externalFiles) {
            Map<String, Object> ext = loadExternalFile(p, profile, trace)
            merged = deepMergeWithTrace(
                    merged,
                    ext,
                    trace,
                    "external:" + p.toAbsolutePath()
            )
        }

        // ============================================================
        // 3) System properties
        // ============================================================

        merged = deepMergeWithTrace(
                merged,
                loadSystemProperties(spec),
                trace,
                "system-properties"
        )

        // ============================================================
        // 4) Environment variables
        // ============================================================

        merged = deepMergeWithTrace(
                merged,
                loadEnvironmentVariables(spec),
                trace,
                "environment"
        )

        // ============================================================
        // 5) Profile marker
        // ============================================================

        merged.put(spec.profileKey, profile)
        trace.record(
                spec.profileKey,
                profile,
                "computed:profile",
                TraceEvent.Kind.SET
        )

        // ------------------------------------------------------------
        // 6) Explicit overrides (applied last)
        // ------------------------------------------------------------
        if (options.overrides instanceof Map) {
            Map<String, Object> o = (Map<String, Object>) options.overrides
            merged = deepMergeWithTrace(
                    merged,
                    o,
                    trace,
                    "overrides"
            )
        }

        // ============================================================
        // 7) Schema validation + coercion
        // ============================================================

        List<String> errors = []
        List<String> warnings = []

        if (spec.schema != null) {
            spec.schema.validate(merged, errors, warnings)
        }

        // ============================================================
        // 8) Validators
        // ============================================================

        TraceSnapshot snapshot = trace.freeze()

        for (ConfigValidator v : spec.validators) {
            try {
                v.validate(merged, errors, warnings, profile, snapshot)
            } catch (Exception e) {
                errors.add(
                        ("Validator " + v.getClass().getName() +
                                " threw: " + e.getMessage()).toString()
                )
            }
        }

        return new ConfigResult(
                Collections.unmodifiableMap(merged),
                snapshot,
                errors,
                warnings,
                profile
        )
    }

    // ============================================================
    // Convenience (backward compatibility)
    // ============================================================

    static Map<String, Object> loadConfig() {
        return load([:]).config
    }

    // ============================================================
    // Profile resolution
    // ============================================================

    private static String resolveProfile(ConfigSpec spec) {
        for (String k : spec.profileEnvKeys) {
            String v = System.getenv(k)
            if (v != null && !v.isEmpty()) return v
        }
        for (String k : spec.profileSysKeys) {
            String v = System.getProperty(k)
            if (v != null && !v.isEmpty()) return v
        }
        return spec.defaultProfile
    }

    // ============================================================
    // External config helpers
    // ============================================================

    private static List<Path> collectExternalFiles(
            Map options,
            ConfigSpec spec,
            String profile
    ) {
        LinkedHashSet<Path> out = []

        if (options.externalFiles instanceof List) {
            options.externalFiles.each {
                out.add(Paths.get(it.toString()))
            }
        }

        return out.toList()
    }

    private static Map<String, Object> loadExternalFile(
            Path p,
            String profile,
            Trace trace
    ) {
        try {
            if (!Files.isRegularFile(p)) return [:]
            String n = p.fileName.toString().toLowerCase()

            if (n.endsWith(".yml") || n.endsWith(".yaml")) {
                return flatten(new Yaml().load(Files.newInputStream(p)))
            }
            if (n.endsWith(".json")) {
                return flatten(new JsonSlurper().parse(p.toFile()))
            }
            if (n.endsWith(".properties")) {
                Properties props = new Properties()
                props.load(Files.newInputStream(p))
                return toMap(props)
            }
            if (n.endsWith(".groovy")) {
                ConfigSlurper cs = new ConfigSlurper(profile)
                return (Map<String, Object>) cs.parse(p.toUri().toURL()).flatten()
            }
        } catch (Exception e) {
            trace.note("Failed to load external config " + p + ": " + e.message)
        }
        return [:]
    }

    // ============================================================
    // System + env
    // ============================================================

    private static Map<String, Object> loadSystemProperties(ConfigSpec spec) {
        Map<String, Object> out = [:]
        Properties p = System.getProperties()
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith(spec.sysPropPrefix)
                    && !spec.profileSysKeys.contains(k)) {
                out.put(
                        k.substring(spec.sysPropPrefix.length()),
                        p.getProperty(k)
                )
            }
        }
        return out
    }

    private static Map<String, Object> loadEnvironmentVariables(ConfigSpec spec) {
        Map<String, Object> out = [:]
        Map<String, String> env = System.getenv()
        for (EnvMapping m : spec.envMappings) {
            if (env.containsKey(m.envKey)) {
                out.put(m.configKey, m.coerce(env.get(m.envKey)))
            }
        }
        return out
    }

    // ============================================================
    // Merge helpers
    // ============================================================

    private static Map<String, Object> mergeAll(
            Map<String, Object> base,
            Trace trace,
            List<NamedMap> maps
    ) {
        Map<String, Object> out = base
        for (NamedMap nm : maps) {
            out = deepMergeWithTrace(out, nm.map, trace, nm.name)
        }
        return out
    }

    private static Map<String, Object> deepMergeWithTrace(
            Map<String, Object> base,
            Map<String, Object> override,
            Trace trace,
            String source
    ) {
        Map<String, Object> result = new LinkedHashMap<>(base)
        for (Map.Entry<String, Object> e : override.entrySet()) {
            result.put(e.key, e.value)
            trace.record(
                    e.key,
                    e.value,
                    source,
                    base.containsKey(e.key)
                            ? TraceEvent.Kind.OVERRIDE
                            : TraceEvent.Kind.SET
            )
        }
        return result
    }

    // ============================================================
    // Flatten helpers
    // ============================================================

    private static Map<String, Object> flatten(Object o) {
        Map<String, Object> out = [:]
        if (o instanceof Map) flattenRec("", (Map) o, out)
        return out
    }

    private static void flattenRec(String p, Map m, Map<String, Object> out) {
        for (Object k : m.keySet()) {
            Object v = m.get(k)
            String key = p.isEmpty() ? k.toString() : p + "." + k.toString()
            if (v instanceof Map) {
                flattenRec(key, (Map) v, out)
            } else {
                out.put(key, v)
            }
        }
    }

    private static Map<String, Object> toMap(Properties p) {
        Map<String, Object> m = [:]
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k))
        }
        return m
    }

    // ============================================================
    // Typed accessors (backward compatible helpers)
    // ============================================================

    static String getString(Map<String, Object> config, String key) {
        Object v = config.get(key)
        return v != null ? v.toString() : null
    }

    static String getString(Map<String, Object> config, String key, String defaultValue) {
        Object v = config.get(key)
        return v != null ? v.toString() : defaultValue
    }

    static Boolean getBoolean(Map<String, Object> config, String key) {
        Object v = config.get(key)
        if (v instanceof Boolean) return (Boolean) v
        if (v != null) return v.toString().toBoolean()
        return null
    }

    static Boolean getBoolean(Map<String, Object> config, String key, Boolean defaultValue) {
        Boolean v = getBoolean(config, key)
        return v != null ? v : defaultValue
    }

    static Integer getInt(Map<String, Object> config, String key) {
        Object v = config.get(key)
        if (v instanceof Integer) return (Integer) v
        if (v != null) return Integer.parseInt(v.toString())
        return null
    }

    static Integer getInt(Map<String, Object> config, String key, Integer defaultValue) {
        Integer v = getInt(config, key)
        return v != null ? v : defaultValue
    }

}
