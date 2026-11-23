package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Test config loading for H2SqlProvider.
 */
class H2SqlProviderConfigTest extends Specification {
    
    def "should load H2SqlProvider from config"() {
        when:
        def provider = H2SqlProvider.fromConfig()
        
        then:
        provider != null
        provider.databaseName == 'test'
        provider.inMemory == true
        provider.username == 'sa'
        provider.url.contains('jdbc:h2:mem:test')
    }
    
    def "should allow config overrides"() {
        when:
        def provider = H2SqlProvider.fromConfig([
            'database.h2.databaseName': 'customdb',
            'database.h2.inMemory': false
        ])
        
        then:
        provider != null
        provider.databaseName == 'customdb'
        provider.inMemory == false
        provider.url.contains('customdb')
    }
    
    def "should work with SqlTask DSL"() {
        when:
        def provider = H2SqlProvider.fromConfig()
        
        // Create test table
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS test_users (
                id INT PRIMARY KEY,
                name VARCHAR(100),
                age INT
            )
        """, [])
        
        // Insert test data
        provider.executeUpdate("INSERT INTO test_users VALUES (?, ?, ?)", [1, "Alice", 30])
        
        // Query
        def results = provider.query("SELECT * FROM test_users WHERE age > ?", [18])
        
        then:
        results != null
        results.size() == 1
        results[0].name == "Alice"
        
        cleanup:
        provider?.close()
    }
}
