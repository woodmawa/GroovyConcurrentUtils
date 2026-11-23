package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@CompileDynamic
class ConfigLoaderPrecedenceTest {

    @Test
    void system_properties_override_external_config() {

        System.setProperty('app.hazelcast.port', '6001')

        try {
            ConfigResult result = ConfigLoader.load()

            assertEquals(
                    '6001',
                    result.config['hazelcast.port']
            )
        } finally {
            System.clearProperty('app.hazelcast.port')
        }
    }
}
