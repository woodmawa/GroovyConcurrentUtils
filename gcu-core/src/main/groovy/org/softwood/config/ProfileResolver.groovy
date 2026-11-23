package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class ProfileResolver {

    static String resolve(ConfigSpec spec) {

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
}
