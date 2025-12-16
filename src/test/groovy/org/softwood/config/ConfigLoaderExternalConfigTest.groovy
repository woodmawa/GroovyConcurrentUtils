package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

@CompileDynamic
class ConfigLoaderExternalConfigTest {

    @Test
    void external_config_overrides_classpath_config() {

        Path temp = Files.createTempFile('config', '.properties')
        temp.toFile().text = '''
            hazelcast.cluster.name=external-cluster
            hazelcast.port=5999
        '''

        try {
            ConfigResult result = ConfigLoader.load(
                    externalFiles: [temp]
            )

            assertEquals(
                    'external-cluster',
                    result.config['hazelcast.cluster.name']
            )

            assertEquals(
                    '5999',
                    result.config['hazelcast.port']
            )

        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Test
    void later_external_files_override_earlier_ones() {

        Path base = Files.createTempFile('base', '.properties')
        Path override = Files.createTempFile('override', '.properties')

        base.toFile().text = 'hazelcast.port=5701'
        override.toFile().text = 'hazelcast.port=5709'

        try {
            ConfigResult result = ConfigLoader.load(
                    externalFiles: [base, override]
            )

            assertEquals(
                    '5709',
                    result.config['hazelcast.port']
            )
        } finally {
            Files.deleteIfExists(base)
            Files.deleteIfExists(override)
        }
    }
}
