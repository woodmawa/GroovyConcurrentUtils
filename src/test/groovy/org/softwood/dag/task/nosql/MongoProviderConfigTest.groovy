package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Test config loading for MongoProvider.
 */
class MongoProviderConfigTest extends Specification {
    
    def "should load MongoProvider from config"() {
        when:
        def provider = MongoProvider.fromConfig()
        
        then:
        provider != null
        provider.connectionString == 'mongodb://localhost:27017'
        provider.databaseName == 'test'
    }
    
    def "should allow config overrides"() {
        when:
        def provider = MongoProvider.fromConfig([
            'database.mongodb.connectionString': 'mongodb://prodserver:27017',
            'database.mongodb.database': 'production'
        ])
        
        then:
        provider != null
        provider.connectionString == 'mongodb://prodserver:27017'
        provider.databaseName == 'production'
    }
}
