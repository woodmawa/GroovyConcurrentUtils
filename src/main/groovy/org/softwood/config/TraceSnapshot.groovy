package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class TraceSnapshot {

    final Map<String, String> lastSourceByKey
    final List<TraceEvent> events
    final List<String> notes

    TraceSnapshot(
            Map<String, String> lastSourceByKey,
            List<TraceEvent> events,
            List<String> notes
    ) {
        this.lastSourceByKey = lastSourceByKey
        this.events = events
        this.notes = notes
    }

    String sourceOf(String key) {
        return lastSourceByKey.get(key)
    }

    List<TraceEvent> historyOf(String key) {
        List<TraceEvent> out = new ArrayList<>()
        for (TraceEvent e : events) {
            if (e.key == key) {
                out.add(e)
            }
        }
        return out
    }
}
