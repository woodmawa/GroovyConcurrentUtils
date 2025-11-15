package org.softwood.config

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.yaml.snakeyaml.Yaml

@Slf4j
class ConfigLoader {

    private static final String DEFAULT_PROFILE = 'dev'

    /**
     * Loads and merges config from multiple sources with profile support.
     *
     * Profile resolution order:
     * 1. Environment variable: APP_PROFILE or PROFILE
     * 2. System property: -Dapp.profile=xxx
     * 3. Default: 'dev'
     *
     * Config loading order (later overrides earlier):
     * 1. Base configs (config.json, config.groovy, config.yml, config.properties)
     * 2. Profile-specific configs (config-{profile}.json, etc.)
     * 3. System properties (-Dapp.xxx=yyy)
     * 4. Environment variables (USE_DISTRIBUTED, etc.)
     */
    static Map loadConfig() {
        String profile = resolveProfile()
        log.debug "Active profile: $profile"

        Map mergedConfig = [:]

        // Load base configs first
        mergedConfig = deepMerge(mergedConfig, loadJsonConfig(null))
        mergedConfig = deepMerge(mergedConfig, loadGroovyConfig(null))
        mergedConfig = deepMerge(mergedConfig, loadYamlConfig(null))
        mergedConfig = deepMerge(mergedConfig, loadPropertiesConfig(null))

        // Load profile-specific configs (override base configs)
        mergedConfig = deepMerge(mergedConfig, loadJsonConfig(profile))
        mergedConfig = deepMerge(mergedConfig, loadGroovyConfig(profile))
        mergedConfig = deepMerge(mergedConfig, loadYamlConfig(profile))
        mergedConfig = deepMerge(mergedConfig, loadPropertiesConfig(profile))

        // Add profile to config
        mergedConfig.profile = profile

        // Override with system properties
        mergedConfig = deepMerge(mergedConfig, loadSystemProperties())

        // Override with environment variables (highest priority)
        mergedConfig = deepMerge(mergedConfig, loadEnvironmentVariables())

        return mergedConfig
    }

    /**
     * Resolve the active profile from environment or system properties
     */
    private static String resolveProfile() {
        // Check environment variables first
        def envProfile = System.getenv('APP_PROFILE') ?: System.getenv('PROFILE')
        if (envProfile) {
            return envProfile
        }

        // Check system properties
        def sysProfile = System.getProperty('app.profile') ?: System.getProperty('profile')
        if (sysProfile) {
            return sysProfile
        }

        return DEFAULT_PROFILE
    }

    private static Map loadJsonConfig(String profile) {
        def filename = profile ? "/config-${profile}.json" : "/config.json"
        def configStream = ConfigLoader.class.getResourceAsStream(filename)
        if (configStream) {
            try {
                def json = new JsonSlurper().parse(configStream)
                log.debug "Loaded ${filename}"
                return flattenMap(json)
            } catch (Exception e) {
                log.debug "Error loading ${filename}: ${e.message}"
            }
        }
        return [:]
    }

    private static Map loadGroovyConfig(String profile) {
        def filename = profile ? "/config-${profile}.groovy" : "/config.groovy"
        def configUrl = ConfigLoader.class.getResource(filename)
        if (configUrl) {
            try {
                def config = new ConfigSlurper(profile ?: '').parse(configUrl)
                log.debug "Loaded ${filename}"
                return config.flatten()
            } catch (Exception e) {
                log.debug "Error loading ${filename}: ${e.message}"
            }
        }
        return [:]
    }

    private static Map loadYamlConfig(String profile) {
        def filename = profile ? "/config-${profile}.yml" : "/config.yml"
        def configStream = ConfigLoader.class.getResourceAsStream(filename)
        if (!configStream && !profile) {
            configStream = ConfigLoader.class.getResourceAsStream('/config.yaml')
            filename = '/config.yaml'
        }
        if (configStream) {
            try {
                def yaml = new Yaml()
                def data = yaml.load(configStream)
                log.debug "Loaded ${filename}"
                return flattenMap(data)
            } catch (Exception e) {
                log.debug "Error loading ${filename}: ${e.message}"
            }
        }
        return [:]
    }

    private static Map loadPropertiesConfig(String profile) {
        def filename = profile ? "/config-${profile}.properties" : "/config.properties"
        def configStream = ConfigLoader.class.getResourceAsStream(filename)
        if (configStream) {
            try {
                def props = new Properties()
                props.load(configStream)
                log.debug "Loaded ${filename}"
                return props.collectEntries { k, v -> [(k.toString()): v] }
            } catch (Exception e) {
                log.debug "Error loading ${filename}: ${e.message}"
            }
        }
        return [:]
    }

    private static Map loadSystemProperties() {
        // Look for system properties with 'app.' prefix
        def appProps = System.properties.findAll { k, v ->
            k.toString().startsWith('app.') && k.toString() != 'app.profile'
        }.collectEntries { k, v ->
            // Remove 'app.' prefix: app.distributed -> distributed
            [(k.toString().replaceFirst('app.', '')): v]
        }

        if (appProps) {
            log.debug  "Loaded ${appProps.size()} system properties"
        }
        return appProps
    }

    private static Map loadEnvironmentVariables() {
        // Map common environment variables to config keys
        def envConfig = [:]

        if (System.getenv('USE_DISTRIBUTED')) {
            envConfig.distributed = System.getenv('USE_DISTRIBUTED') == 'true'
        }
        if (System.getenv('HAZELCAST_CLUSTER_NAME')) {
            envConfig.'hazelcast.cluster.name' = System.getenv('HAZELCAST_CLUSTER_NAME')
        }
        if (System.getenv('HAZELCAST_PORT')) {
            envConfig.'hazelcast.port' = System.getenv('HAZELCAST_PORT')
        }
        if (System.getenv('DB_URL')) {
            envConfig.'database.url' = System.getenv('DB_URL')
        }
        if (System.getenv('DB_USERNAME')) {
            envConfig.'database.username' = System.getenv('DB_USERNAME')
        }
        if (System.getenv('DB_PASSWORD')) {
            envConfig.'database.password' = System.getenv('DB_PASSWORD')
        }

        if (envConfig) {
            println "Loaded ${envConfig.size()} environment variables"
        }
        return envConfig
    }

    /**
     * Deep merge two maps, with values from 'override' taking precedence
     */
    private static Map deepMerge(Map base, Map override) {
        if (!override) return base
        if (!base) return override

        Map result = [:] + base

        override.each { key, value ->
            if (value instanceof Map && result[key] instanceof Map) {
                result[key] = deepMerge((Map)result[key], (Map)value)
            } else {
                result[key] = value
            }
        }

        return result
    }

    /**
     * Flatten nested maps into dot-notation keys
     * e.g., [hazelcast: [cluster: [name: 'test']]] -> ['hazelcast.cluster.name': 'test']
     */
    private static Map flattenMap(Map map, String prefix = '') {
        Map result = [:]

        map.each { key, value ->
            def newKey = prefix ? "${prefix}.${key}" : key.toString()

            if (value instanceof Map) {
                result.putAll(flattenMap((Map)value, newKey))
            } else {
                result[newKey] = value
            }
        }

        return result
    }

    /**
     * Get a config value with dot notation and optional default
     */
    static Object get(Map config, String key, Object defaultValue = null) {
        config.getOrDefault(key, defaultValue)
    }

    /**
     * Get a config value as boolean
     */
    static boolean getBoolean(Map config, String key, boolean defaultValue = false) {
        def value = config.get(key)
        if (value == null) return defaultValue
        if (value instanceof Boolean) return value
        return value.toString().toLowerCase() in ['true', '1', 'yes', 'on']
    }

    /**
     * Get a config value as integer
     */
    static int getInt(Map config, String key, int defaultValue = 0) {
        def value = config.get(key)
        if (value == null) return defaultValue
        if (value instanceof Number) return value.intValue()
        try {
            return value.toString().toInteger()
        } catch (NumberFormatException e) {
            return defaultValue
        }
    }

    /**
     * Get a config value as String
     */
    static String getString(Map config, String key, String defaultValue = null) {
        def value = config.get(key)
        return value != null ? value.toString() : defaultValue
    }
}