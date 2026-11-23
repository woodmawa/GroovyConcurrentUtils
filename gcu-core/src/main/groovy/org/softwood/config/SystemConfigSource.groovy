package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class SystemConfigSource {

    /**
     * Load system properties using the configured prefix.
     *
     * Example:
     *   -Dapp.hazelcast.port=5701
     *   -> hazelcast.port = 5701
     */
    static Map<String, Object> load(ConfigSpec spec) {

        Map<String, Object> out = new LinkedHashMap<>()
        Properties props = System.getProperties()

        for (String key : props.stringPropertyNames()) {

            if (!key.startsWith(spec.sysPropPrefix)) {
                continue
            }

            // skip profile keys
            if (spec.profileSysKeys.contains(key)) {
                continue
            }

            String normalizedKey =
                    key.substring(spec.sysPropPrefix.length())

            out.put(normalizedKey, props.getProperty(key))
        }

        return out
    }
}
