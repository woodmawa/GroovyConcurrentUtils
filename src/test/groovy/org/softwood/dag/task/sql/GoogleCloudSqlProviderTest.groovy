package org.softwood.dag.task.sql

import spock.lang.Specification
import spock.lang.Shared

/**
 * Tests for Google Cloud SQL provider.
 *
 * <p><strong>Note:</strong> These tests run in stub mode by default (no drivers required).</p>
 */
class GoogleCloudSqlProviderTest extends Specification {

    @Shared
    GoogleCloudSqlProvider provider

    def setupSpec() {
        provider = new GoogleCloudSqlProvider(
                instanceConnectionName: "my-project:us-central1:my-instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "testuser",
                password: "testpass"
        )
        provider.initialize()
    }

    def cleanupSpec() {
        provider?.close()
    }

    def "should initialize in stub mode without JDBC driver"() {
        expect:
        provider != null
        provider.getProviderType().contains("Google Cloud SQL")
        provider.getProviderType().contains("postgresql")
    }

    def "should support PostgreSQL database type"() {
        given:
        def pgProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass"
        )

        when:
        pgProvider.initialize()

        then:
        pgProvider.@databaseType == "postgresql"
        pgProvider.@initialized
    }

    def "should support MySQL database type"() {
        given:
        def mysqlProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "mysql",
                database: "testdb",
                username: "root",
                password: "pass"
        )

        when:
        mysqlProvider.initialize()

        then:
        mysqlProvider.@databaseType == "mysql"
        mysqlProvider.@initialized
    }

    def "should support SQL Server database type"() {
        given:
        def sqlserverProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "sqlserver",
                database: "testdb",
                username: "sqlserver",
                password: "pass"
        )

        when:
        sqlserverProvider.initialize()

        then:
        sqlserverProvider.@databaseType == "sqlserver"
        sqlserverProvider.@initialized
    }

    def "should support direct TCP/IP connection"() {
        given:
        def directProvider = new GoogleCloudSqlProvider(
                host: "1.2.3.4",
                port: 5432,
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass"
        )

        when:
        directProvider.initialize()

        then:
        directProvider.@host == "1.2.3.4"
        directProvider.@port == 5432
        directProvider.@initialized
    }

    def "should return empty results for queries in stub mode"() {
        when:
        def result = provider.query("SELECT * FROM users WHERE age > ?", [18])

        then:
        result != null
        result instanceof List
        result.isEmpty()
    }

    def "should return 0 for updates in stub mode"() {
        when:
        def rowsAffected = provider.executeUpdate("INSERT INTO users (name, age) VALUES (?, ?)", ["Alice", 30])

        then:
        rowsAffected == 0
    }

    def "should return null for queryForMap in stub mode"() {
        when:
        def result = provider.queryForMap("SELECT * FROM users WHERE id = ?", [1])

        then:
        result == null
    }

    def "should return null for queryForObject in stub mode"() {
        when:
        def result = provider.queryForObject("SELECT COUNT(*) FROM users")

        then:
        result == null
    }

    def "should handle queries without parameters"() {
        when:
        def result = provider.query("SELECT * FROM users")

        then:
        result != null
        result.isEmpty()
    }

    def "should handle updates without parameters"() {
        when:
        def rowsAffected = provider.executeUpdate("DELETE FROM temp_table")

        then:
        rowsAffected == 0
    }

    def "should return empty list for getTables in stub mode"() {
        when:
        def tables = provider.getTables(null, "public", null, ["TABLE"])

        then:
        tables != null
        tables.isEmpty()
    }

    def "should return empty list for getColumns in stub mode"() {
        when:
        def columns = provider.getColumns(null, "public", "users", null)

        then:
        columns != null
        columns.isEmpty()
    }

    def "should return empty list for getIndexes in stub mode"() {
        when:
        def indexes = provider.getIndexes(null, "public", "users", false)

        then:
        indexes != null
        indexes.isEmpty()
    }

    def "should return null for getPrimaryKeys in stub mode"() {
        when:
        def pk = provider.getPrimaryKeys(null, "public", "users")

        then:
        pk == null
    }

    def "should return empty list for getForeignKeys in stub mode"() {
        when:
        def fks = provider.getForeignKeys(null, "public", "users")

        then:
        fks != null
        fks.isEmpty()
    }

    def "should return empty list for getSchemas in stub mode"() {
        when:
        def schemas = provider.getSchemas()

        then:
        schemas != null
        schemas.isEmpty()
    }

    def "should return empty list for getCatalogs in stub mode"() {
        when:
        def catalogs = provider.getCatalogs()

        then:
        catalogs != null
        catalogs.isEmpty()
    }

    def "should return null for getDatabaseInfo in stub mode"() {
        when:
        def info = provider.getDatabaseInfo()

        then:
        info == null
    }

    def "should throw exception for execute in stub mode"() {
        when:
        provider.execute { connection ->
            // Try to use connection
            connection.createStatement()
        }

        then:
        thrown(UnsupportedOperationException)
    }

    def "should throw exception for withTransaction in stub mode"() {
        when:
        provider.withTransaction { connection ->
            // Try transaction
        }

        then:
        thrown(UnsupportedOperationException)
    }

    def "should support IAM authentication configuration"() {
        given:
        def iamProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user@project.iam",
                password: "",
                enableIamAuth: true
        )

        when:
        iamProvider.initialize()

        then:
        iamProvider.@enableIamAuth == true
        iamProvider.@initialized
    }

    def "should support connection pooling configuration"() {
        given:
        def pooledProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass",
                poolSize: 5,
                maxPoolSize: 20
        )

        when:
        pooledProvider.initialize()

        then:
        pooledProvider.@poolSize == 5
        pooledProvider.@maxPoolSize == 20
        pooledProvider.@initialized
    }

    def "should handle multiple initializations gracefully"() {
        given:
        def multiProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass"
        )

        when:
        multiProvider.initialize()
        multiProvider.initialize()  // Second init
        multiProvider.initialize()  // Third init

        then:
        noExceptionThrown()
        multiProvider.@initialized
    }

    def "should close without errors in stub mode"() {
        given:
        def closeProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass"
        )
        closeProvider.initialize()

        when:
        closeProvider.close()

        then:
        noExceptionThrown()
        !closeProvider.@initialized
    }

    def "should handle close before initialization"() {
        given:
        def uninitProvider = new GoogleCloudSqlProvider(
                instanceConnectionName: "project:region:instance",
                databaseType: "postgresql",
                database: "testdb",
                username: "user",
                password: "pass"
        )

        when:
        uninitProvider.close()

        then:
        noExceptionThrown()
    }
}
