package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@CompileDynamic
class ConfigLoaderClasspathTest {

    @Test
    void loads_library_defaults_and_profile_overrides() {
        // default profile is 'dev'
        ConfigResult result = ConfigLoader.load()

        assertTrue(result.isValid())

        // value from defaults.groovy / defaults.yml
        assertEquals(
                'rsocket',
                result.config['actor.remote.transport']
        )

        // dev override should apply
        assertEquals(
                'dev-cluster',
                result.config['hazelcast.cluster.name']
        )
    }

    @Test
    void profile_override_wins_over_base_defaults() {
        ConfigResult result = ConfigLoader.load(profile: 'production')

        assertTrue(result.isValid())

        assertEquals(
                'production-cluster',
                result.config['hazelcast.cluster.name']
        )

        assertEquals(
                true,
                result.config['distributed']
        )
    }
}
