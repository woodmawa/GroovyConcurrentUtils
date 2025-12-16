package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class ConfigResult {

    final Map<String, Object> config
    final TraceSnapshot trace
    final List<String> errors
    final List<String> warnings
    final String profile

    ConfigResult(
            Map<String, Object> config,
            TraceSnapshot trace,
            List<String> errors,
            List<String> warnings,
            String profile
    ) {
        this.config = Collections.unmodifiableMap(config)
        this.trace = trace
        this.errors = Collections.unmodifiableList(errors)
        this.warnings = Collections.unmodifiableList(warnings)
        this.profile = profile
    }

    boolean isValid() {
        return errors.isEmpty()
    }
}
