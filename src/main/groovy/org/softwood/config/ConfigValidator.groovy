package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
interface ConfigValidator {

    /**
     * Perform validation against the fully merged config.
     *
     * @param config   flattened config map
     * @param errors   add fatal validation errors here
     * @param warnings add non-fatal warnings here
     * @param profile  active profile
     * @param trace    immutable trace snapshot
     */
    void validate(
            Map<String, Object> config,
            List<String> errors,
            List<String> warnings,
            String profile,
            TraceSnapshot trace
    )
}
