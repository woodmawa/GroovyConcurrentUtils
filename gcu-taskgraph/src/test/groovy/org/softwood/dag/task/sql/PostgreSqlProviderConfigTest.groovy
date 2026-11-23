package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Test config loading for PostgreSqlProvider.
 */
class PostgreSqlProviderConfigTest extends Specification {
    
    def "should load PostgreSqlProvider from config"() {
        when:
        def provider = PostgreSqlProvider.fromConfig()
        
        then:
        provider != null
        provider.host == 'localhost'
        provider.port == 5432
        provider.database == 'postgres'
        provider.username == 'postgres'
        provider.schema == 'public'
        provider.poolSize == 1
        provider.maxPoolSize == 10
    }
    
    def "should allow config overrides"() {
        when:
        def provider = PostgreSqlProvider.fromConfig([
            'database.postgres.host': 'customhost',
            'database.postgres.port': 5433,
            'database.postgres.database': 'customdb'
        ])
        
        then:
        provider != null
        provider.host == 'customhost'
        provider.port == 5433
        provider.database == 'customdb'
        provider.username == 'postgres'  // Not overridden, uses config default
    }
    
    def "should build URL from host, port, database"() {
        when:
        def provider = PostgreSqlProvider.fromConfig()
        
        then:
        provider.url == 'jdbc:postgresql://localhost:5432/postgres'
    }
}
