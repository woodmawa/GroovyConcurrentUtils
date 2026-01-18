package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Test config loading for ArangoDbProvider.
 */
class ArangoDbProviderConfigTest extends Specification {
    
    def "should load ArangoDbProvider from config"() {
        when:
        def provider = ArangoDbProvider.fromConfig()
        
        then:
        provider != null
        provider.host == 'localhost'
        provider.port == 8529
        provider.database == '_system'
        provider.username == 'root'
        provider.useSsl == false
        provider.timeout == 10000
        provider.maxConnections == 1
    }
    
    def "should allow config overrides"() {
        when:
        def provider = ArangoDbProvider.fromConfig([
            'database.arangodb.host': 'arangoserver',
            'database.arangodb.port': 8530,
            'database.arangodb.database': 'mydb',
            'database.arangodb.useSsl': true
        ])
        
        then:
        provider != null
        provider.host == 'arangoserver'
        provider.port == 8530
        provider.database == 'mydb'
        provider.useSsl == true
        provider.username == 'root'  // Not overridden, uses config default
    }
}
