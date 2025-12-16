package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class Trace {

    private final Map<String, String> lastSourceByKey =
            new LinkedHashMap<>()

    private final List<TraceEvent> events =
            new ArrayList<>()

    private final List<String> notes =
            new ArrayList<>()

    void record(
            String key,
            Object value,
            String source,
            TraceEvent.Kind kind
    ) {
        lastSourceByKey.put(key, source)
        events.add(new TraceEvent(key, value, source, kind))
    }

    void note(String message) {
        if (message != null) {
            notes.add(message)
        }
    }

    TraceSnapshot freeze() {
        return new TraceSnapshot(
                new LinkedHashMap<>(lastSourceByKey),
                new ArrayList<>(events),
                new ArrayList<>(notes)
        )
    }
}
