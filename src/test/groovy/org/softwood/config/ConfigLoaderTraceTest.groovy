package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@CompileDynamic
class ConfigLoaderTraceTest {

    @Test
    void trace_records_source_of_config_value() {

        System.setProperty('app.hazelcast.port', '6100')

        try {
            ConfigResult result = ConfigLoader.load()

            TraceSnapshot trace = result.trace

            assertEquals(
                    'system-properties',
                    trace.sourceOf('hazelcast.port')
            )

            assertTrue(
                    trace.historyOf('hazelcast.port').size() >= 1
            )
        } finally {
            System.clearProperty('app.hazelcast.port')
        }
    }
}
