package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for FirestoreProvider configuration loading.
 */
class FirestoreProviderConfigTest extends Specification {

    def "should allow config overrides for projectId"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project-123'
        ])

        then:
        provider.projectId == 'test-project-123'
        provider.@initialized
    }

    def "should use default database ID if not specified"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project'
        ])

        then:
        provider.databaseId == '(default)'
        provider.@initialized
    }

    def "should allow config override for database ID"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project',
                'database.firestore.databaseId': 'custom-db'
        ])

        then:
        provider.databaseId == 'custom-db'
        provider.@initialized
    }

    def "should allow config override for credentials path"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project',
                'database.firestore.credentialsPath': '/path/to/credentials.json'
        ])

        then:
        provider.credentialsPath == '/path/to/credentials.json'
        provider.@initialized
    }

    def "should allow config override for emulator host"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project',
                'database.firestore.emulatorHost': 'localhost:8080'
        ])

        then:
        provider.emulatorHost == 'localhost:8080'
        provider.@initialized
    }

    def "should allow config overrides for timeouts"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project',
                'database.firestore.connectionTimeout': 15000,
                'database.firestore.requestTimeout': 45000
        ])

        then:
        provider.connectionTimeout == 15000
        provider.requestTimeout == 45000
        provider.@initialized
    }

    def "should handle multiple config overrides"() {
        when:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'my-gcp-project',
                'database.firestore.databaseId': 'prod-db',
                'database.firestore.credentialsPath': '/etc/gcp/sa.json',
                'database.firestore.connectionTimeout': 20000
        ])

        then:
        provider.projectId == 'my-gcp-project'
        provider.databaseId == 'prod-db'
        provider.credentialsPath == '/etc/gcp/sa.json'
        provider.connectionTimeout == 20000
        provider.@initialized
    }

    def "should work in stub mode after config loading"() {
        given:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'test-project'
        ])

        when:
        def result = provider.find("users", [:], null, null)

        then:
        result != null
        result.isEmpty()
    }

    def "should handle emulator configuration for local testing"() {
        given:
        def provider = FirestoreProvider.fromConfig([
                'database.firestore.projectId': 'demo-project',
                'database.firestore.emulatorHost': 'localhost:8080'
        ])

        when:
        def id = provider.insertOne("test-collection", [name: "Test"])

        then:
        id != null
        id instanceof String
    }
}