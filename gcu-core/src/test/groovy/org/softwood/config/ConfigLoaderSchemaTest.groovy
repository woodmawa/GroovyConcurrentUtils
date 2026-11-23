package org.softwood.config

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

@CompileDynamic
class ConfigLoaderSchemaTest {

    private ConfigValidator distributedNotAllowedValidator() {
        return { config, errors, warnings, profile, trace ->
            if (config['distributed'] == true) {
                errors.add('distributed mode not allowed in this test')
            }
        } as ConfigValidator
    }

    private ConfigSchema distributedSchema() {
        return new ConfigSchema()
                .optional('distributed', Boolean)
    }

    @Test
    void validator_does_not_trigger_when_condition_not_met() {

        ConfigSpec spec = ConfigSpec.defaultSpec()
        spec.schema = distributedSchema()
        spec.validators.add(distributedNotAllowedValidator())

        ConfigResult result = ConfigLoader.load(spec: spec)

        assertTrue(result.isValid())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    void validator_triggers_when_test_override_sets_distributed_true() {

        ConfigSpec spec = ConfigSpec.defaultSpec()
        spec.schema = distributedSchema()
        spec.validators.add(distributedNotAllowedValidator())

        ConfigResult result = ConfigLoader.load(
                spec: spec,
                overrides: ['distributed': true]
        )

        assertFalse(result.isValid())
        assertTrue(
                result.errors.any { it.contains('distributed mode not allowed') }
        )
    }
}
