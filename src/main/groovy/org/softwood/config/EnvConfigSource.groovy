package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class EnvConfigSource {

    static Map<String, Object> load(ConfigSpec spec) {

        Map<String, Object> out = new LinkedHashMap<>()
        Map<String, String> env = System.getenv()

        for (EnvMapping mapping : spec.envMappings) {
            String raw = env.get(mapping.envKey)
            if (raw != null) {
                out.put(mapping.configKey, mapping.coerce(raw))
            }
        }

        return out
    }
}
