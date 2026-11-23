package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class TraceEvent {

    enum Kind {
        SET,
        OVERRIDE
    }

    final String key
    final Object value
    final String source
    final Kind kind
    final long timestampMillis

    TraceEvent(
            String key,
            Object value,
            String source,
            Kind kind
    ) {
        this.key = key
        this.value = value
        this.source = source
        this.kind = kind
        this.timestampMillis = System.currentTimeMillis()
    }
}
