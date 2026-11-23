package org.softwood.config

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.ConfigObject
import org.yaml.snakeyaml.Yaml

import java.nio.file.Files
import java.nio.file.Path

@CompileStatic
class ParserSupport {

    private static final JsonSlurper JSON = new JsonSlurper()
    private static final Yaml YAML = new Yaml()

    static Map<String, Object> parse(
            InputStream is,
            String sourceName,
            String profile
    ) {
        try {
            if (sourceName.endsWith('.yml') || sourceName.endsWith('.yaml')) {
                return flatten(YAML.load(is))
            }
            if (sourceName.endsWith('.json')) {
                return flatten(JSON.parse(is))
            }
            if (sourceName.endsWith('.properties')) {
                Properties p = new Properties()
                p.load(is)
                return toMap(p)
            }
            if (sourceName.endsWith('.groovy')) {
                // Use ConfigSlurper with environment in constructor (not setEnvironment)
                // This ensures imports work correctly when parsing from URL
                ConfigSlurper cs = profile != null && !profile.isEmpty() 
                    ? new ConfigSlurper(profile)
                    : new ConfigSlurper()
                
                // Parse from URL (this makes imports work)
                URL url = ParserSupport.class.getResource(sourceName)
                ConfigObject result = cs.parse(url)
                
                return flatten(result)
            }
        } finally {
            try { is.close() } catch (ignored) {}
        }
        return [:]
    }

    static Map<String, Object> parse(
            Path path,
            String profile
    ) {
        if (!Files.isRegularFile(path)) return [:]

        String name = path.fileName.toString().toLowerCase()
        InputStream is = Files.newInputStream(path)

        if (name.endsWith('.yml') || name.endsWith('.yaml')) {
            return flatten(YAML.load(is))
        }
        if (name.endsWith('.json')) {
            return flatten(JSON.parse(path.toFile()))
        }
        if (name.endsWith('.properties')) {
            Properties p = new Properties()
            p.load(is)
            return toMap(p)
        }
        if (name.endsWith('.groovy')) {
            // Use ConfigSlurper with environment in constructor (not setEnvironment)
            // This ensures imports work correctly when parsing from URL
            ConfigSlurper cs = profile != null && !profile.isEmpty()
                ? new ConfigSlurper(profile)
                : new ConfigSlurper()
            
            // Parse from URL (this makes imports work)
            ConfigObject result = cs.parse(path.toUri().toURL())
            
            return flatten(result)
        }

        return [:]
    }

    // ------------------------------------------------------------

    private static Map<String, Object> flatten(Object o) {
        Map<String, Object> out = new LinkedHashMap<>()
        if (o instanceof Map) {
            flattenRec('', (Map) o, out)
        }
        return out
    }

    private static void flattenRec(
            String prefix,
            Map m,
            Map<String, Object> out
    ) {
        for (Object k : m.keySet()) {
            Object v = m.get(k)
            String key = prefix.isEmpty()
                    ? k.toString()
                    : prefix + '.' + k.toString()

            if (v instanceof Map) {
                flattenRec(key, (Map) v, out)
            } else {
                out.put(key, v)
            }
        }
    }

    private static Map<String, Object> toMap(Properties p) {
        Map<String, Object> m = new LinkedHashMap<>()
        for (String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k))
        }
        return m
    }
}
