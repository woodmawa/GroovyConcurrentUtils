package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class ConfigSchema {

    private final Map<String, SchemaRule> rules =
            new LinkedHashMap<>()

    ConfigSchema require(String key, Class type = null) {
        rules.put(key, new SchemaRule(true, type))
        return this
    }

    ConfigSchema optional(String key, Class type = null) {
        rules.put(key, new SchemaRule(false, type))
        return this
    }

    void validate(
            Map<String, Object> config,
            List<String> errors,
            List<String> warnings
    ) {
        for (Map.Entry<String, SchemaRule> e : rules.entrySet()) {

            String key = e.key
            SchemaRule rule = e.value

            if (!config.containsKey(key)) {
                if (rule.required) {
                    errors.add(
                            ("Missing required config key: " + key).toString()
                    )
                }
                continue
            }

            Object raw = config.get(key)

            if (rule.type != null && raw != null) {
                Object coerced = coerce(raw, rule.type, key, errors)
                if (coerced != null) {
                    config.put(key, coerced)
                }
            }
        }
    }

    // ------------------------------------------------------------

    private static Object coerce(
            Object raw,
            Class target,
            String key,
            List<String> errors
    ) {
        try {
            if (target == Boolean) {
                if (raw instanceof Boolean) return raw
                return raw.toString().toBoolean()
            }

            if (target == Integer) {
                if (raw instanceof Integer) return raw
                return Integer.parseInt(raw.toString())
            }

            if (target == Long) {
                if (raw instanceof Long) return raw
                return Long.parseLong(raw.toString())
            }

            if (target == String) {
                return raw.toString()
            }

            // enums
            if (target.isEnum()) {
                return Enum.valueOf(
                        (Class<? extends Enum>) target,
                        raw.toString()
                )
            }

            // fallback: already assignable?
            if (target.isInstance(raw)) {
                return raw
            }

            errors.add(
                    ("Config key '" + key +
                            "' could not be coerced to " +
                            target.getSimpleName()).toString()
            )
            return null

        } catch (Exception e) {
            errors.add(
                    ("Config key '" + key +
                            "' coercion failed: " + e.getMessage()).toString()
            )
            return null
        }
    }

    // ------------------------------------------------------------

    @CompileStatic
    private static class SchemaRule {
        final boolean required
        final Class type

        SchemaRule(boolean required, Class type) {
            this.required = required
            this.type = type
        }
    }
}
