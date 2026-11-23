package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class ConfigSpec {

    static final List<String> SUPPORTED_EXTENSIONS =
            ['yml', 'yaml', 'json', 'properties', 'groovy']

    String defaultProfile = 'dev'
    String profileKey = 'profile'

    List<String> profileEnvKeys = ['APP_PROFILE', 'PROFILE']
    List<String> profileSysKeys = ['app.profile', 'profile']

    String sysPropPrefix = 'app.'
    String externalFilesSysProp = 'app.config'
    String externalDirsSysProp  = 'app.configDir'

    String libraryDefaultsPath = '/org/softwood/config'
    List<String> libraryBaseNames = ['defaults']
    List<String> userBaseNames = ['config']
    List<String> externalBaseNames = ['config']

    List<EnvMapping> envMappings = []
    List<ConfigValidator> validators = []
    ConfigSchema schema = null

    static ConfigSpec defaultSpec() {
        ConfigSpec s = new ConfigSpec()
        s.envMappings << new EnvMapping('USE_DISTRIBUTED', 'distributed')
        s.envMappings << new EnvMapping('HAZELCAST_CLUSTER_NAME', 'hazelcast.cluster.name')
        s.envMappings << new EnvMapping('HAZELCAST_PORT', 'hazelcast.port')
        s.envMappings << new EnvMapping('DB_URL', 'database.url')
        s.envMappings << new EnvMapping('DB_USERNAME', 'database.username')
        s.envMappings << new EnvMapping('DB_PASSWORD', 'database.password')
        return s
    }
}
