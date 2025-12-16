package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class MergeSupport {

    static Map<String, Object> mergeAll(
            Map<String, Object> base,
            Trace trace,
            List<NamedMap> maps
    ) {
        Map<String, Object> out = base
        for (NamedMap nm : maps) {
            out = merge(out, nm.map, trace, nm.name)
        }
        return out
    }

    static Map<String, Object> merge(
            Map<String, Object> base,
            Map<String, Object> override,
            Trace trace,
            String source
    ) {
        Map<String, Object> result = new LinkedHashMap<>(base)

        for (Map.Entry<String, Object> e : override.entrySet()) {
            String key = e.getKey()
            Object value = e.getValue()

            TraceEvent.Kind kind =
                    base.containsKey(key)
                            ? TraceEvent.Kind.OVERRIDE
                            : TraceEvent.Kind.SET

            trace.record(key, value, source, kind)
            result.put(key, value)
        }

        return result
    }
}
