package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class EnvMapping {

    final String envKey
    final String configKey

    EnvMapping(String envKey, String configKey) {
        this.envKey = envKey
        this.configKey = configKey
    }

    /**
     * Override if you want custom coercion
     * (e.g. booleans, ints, enums)
     */
    Object coerce(String rawValue) {
        return rawValue
    }
}
