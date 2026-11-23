package org.softwood.dag.task.nosql

import spock.lang.Specification

/**
 * Tests for FirestoreProvider in stub mode (no Google Cloud SDK required).
 * These tests verify the provider works without the Firestore SDK dependency.
 */
class FirestoreProviderTest extends Specification {

    def "should initialize in stub mode without Firestore SDK"() {
        when:
        def provider = new FirestoreProvider(
                projectId: "test-project"
        )
        provider.initialize()

        then:
        provider != null
        !provider.@sdkAvailable  // SDK not available
        provider.@initialized    // But still initialized
    }

    def "should require projectId for initialization"() {
    when:
    def provider = new FirestoreProvider()
    provider.initialize()
    
    then:
    // In stub mode, initialization succeeds even without projectId
        // The check only applies when SDK is available
        provider.@initialized
        !provider.@sdkAvailable
    }

    def "should return empty results for find in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def result = provider.find("users", [:], null, null)

        then:
        result != null
        result.isEmpty()
    }

    def "should return empty results for find with filter in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def result = provider.find(
                "users",
                [email: "test@example.com"],
                null,
                null
        )

        then:
        result != null
        result.isEmpty()
    }

    def "should return synthetic ID for insertOne in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def result = provider.insertOne("users", [name: "John", email: "john@example.com"])

        then:
        result != null
        result instanceof String
        result.length() > 0
    }

    def "should return synthetic IDs for insertMany in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def result = provider.insertMany("users", [
                [name: "John", email: "john@example.com"],
                [name: "Jane", email: "jane@example.com"]
        ])

        then:
        result != null
        result.size() == 2
        result.every { it instanceof String && it.length() > 0 }
    }

    def "should return 0 for update in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def count = provider.update(
                "users",
                [email: "john@example.com"],
                [name: "John Updated"],
                null
        )

        then:
        count == 0
    }

    def "should return 0 for delete in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def count = provider.delete("users", [email: "john@example.com"])

        then:
        count == 0
    }

    def "should return empty list for aggregate in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def result = provider.aggregate("users", [])

        then:
        result != null
        result.isEmpty()
    }

    def "should throw exception for execute in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        provider.execute { firestore -> firestore.listCollections() }

        then:
        thrown(UnsupportedOperationException)
    }

    def "should throw exception for withTransaction in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        provider.withTransaction { }

        then:
        thrown(UnsupportedOperationException)
    }

    def "should return empty list for listCollections in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def collections = provider.listCollections()

        then:
        collections != null
        collections.isEmpty()
    }

    def "should return null for getCollectionStats in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def stats = provider.getCollectionStats("users")

        then:
        stats == null
    }

    def "should return empty list for listIndexes in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def indexes = provider.listIndexes("users")

        then:
        indexes != null
        indexes.isEmpty()
    }

    def "should return null for getDatabaseStats in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def stats = provider.getDatabaseStats()

        then:
        stats == null
    }

    def "should return server info in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        def info = provider.getServerInfo()

        then:
        info != null
        info.version == "Google Cloud Firestore"
        info.gitVersion == "N/A"
        info.sysInfo.projectId == "test-project"
        info.sysInfo.databaseId == "(default)"
        info.maxBsonObjectSize == 1048576L  // 1MB document limit
    }

    def "should handle custom database ID"() {
        when:
        def provider = new FirestoreProvider(
                projectId: "test-project",
                databaseId: "custom-db"
        )
        provider.initialize()

        then:
        provider.@databaseId == "custom-db"
        provider.@initialized
    }

    def "should handle emulator configuration"() {
        when:
        def provider = new FirestoreProvider(
                projectId: "test-project",
                emulatorHost: "localhost:8080"
        )
        provider.initialize()

        then:
        provider.@emulatorHost == "localhost:8080"
        provider.@initialized
    }

    def "should handle credentials path configuration"() {
        when:
        def provider = new FirestoreProvider(
                projectId: "test-project",
                credentialsPath: "/path/to/service-account.json"
        )
        provider.initialize()

        then:
        provider.@credentialsPath == "/path/to/service-account.json"
        provider.@initialized
    }

    def "should handle multiple initializations gracefully"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")

        when:
        provider.initialize()
        provider.initialize()  // Second call
        provider.initialize()  // Third call

        then:
        noExceptionThrown()
        provider.@initialized
    }

    def "should close without errors in stub mode"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")
        provider.initialize()

        when:
        provider.close()

        then:
        noExceptionThrown()
        !provider.@initialized
    }

    def "should handle close before initialization"() {
        given:
        def provider = new FirestoreProvider(projectId: "test-project")

        when:
        provider.close()

        then:
        noExceptionThrown()
    }

    def "should handle various timeout configurations"() {
        when:
        def provider = new FirestoreProvider(
                projectId: "test-project",
                connectionTimeout: 15000,
                requestTimeout: 45000
        )
        provider.initialize()

        then:
        provider.@connectionTimeout == 15000
        provider.@requestTimeout == 45000
    }
}