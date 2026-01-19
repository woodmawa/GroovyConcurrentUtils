package org.softwood.dag.task.sql

import spock.lang.Specification

/**
 * Tests for Google Cloud SQL provider configuration loading.
 */
class GoogleCloudSqlProviderConfigTest extends Specification {

    def "should allow config overrides for instance connection name"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'my-project:us-central1:my-instance',
                'database.googlecloudsql.database': 'testdb'
        ])

        then:
        provider.instanceConnectionName == 'my-project:us-central1:my-instance'
        provider.@initialized
    }

    def "should use default database type if not specified"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb'
        ])

        then:
        provider.databaseType == 'postgresql'
        provider.@initialized
    }

    def "should allow config override for database type"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.databaseType': 'mysql',
                'database.googlecloudsql.database': 'testdb'
        ])

        then:
        provider.databaseType == 'mysql'
        provider.@initialized
    }

    def "should allow config override for username and password"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb',
                'database.googlecloudsql.username': 'myuser',
                'database.googlecloudsql.password': 'mypass'
        ])

        then:
        provider.username == 'myuser'
        provider.password == 'mypass'
        provider.@initialized
    }

    def "should allow config override for IAM authentication"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb',
                'database.googlecloudsql.enableIamAuth': true
        ])

        then:
        provider.enableIamAuth == true
        provider.@initialized
    }

    def "should allow config override for connection pooling"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb',
                'database.googlecloudsql.poolSize': 10,
                'database.googlecloudsql.maxPoolSize': 30
        ])

        then:
        provider.poolSize == 10
        provider.maxPoolSize == 30
        provider.@initialized
    }

    def "should allow config override for timeouts"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb',
                'database.googlecloudsql.connectionTimeout': 60,
                'database.googlecloudsql.idleTimeout': 1200
        ])

        then:
        provider.connectionTimeout == 60
        provider.idleTimeout == 1200
        provider.@initialized
    }

    def "should handle multiple config overrides"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'my-project:us-west1:prod-instance',
                'database.googlecloudsql.databaseType': 'postgresql',
                'database.googlecloudsql.database': 'production',
                'database.googlecloudsql.username': 'produser',
                'database.googlecloudsql.password': 'prodpass',
                'database.googlecloudsql.schema': 'app',
                'database.googlecloudsql.poolSize': 15,
                'database.googlecloudsql.logQueries': true
        ])

        then:
        provider.instanceConnectionName == 'my-project:us-west1:prod-instance'
        provider.databaseType == 'postgresql'
        provider.database == 'production'
        provider.username == 'produser'
        provider.password == 'prodpass'
        provider.schema == 'app'
        provider.poolSize == 15
        provider.logQueries == true
        provider.@initialized
    }

    def "should work in stub mode after config loading"() {
        given:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb'
        ])

        when:
        def result = provider.query("SELECT * FROM users")

        then:
        result != null
        result.isEmpty()
    }

    def "should support direct TCP/IP configuration via host and port"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.host': '1.2.3.4',
                'database.googlecloudsql.port': 5432,
                'database.googlecloudsql.databaseType': 'postgresql',
                'database.googlecloudsql.database': 'testdb'
        ])

        then:
        provider.host == '1.2.3.4'
        provider.port == 5432
        provider.@initialized
    }

    def "should allow config for credentials path"() {
        when:
        def provider = GoogleCloudSqlProvider.fromConfig([
                'database.googlecloudsql.instanceConnectionName': 'project:region:instance',
                'database.googlecloudsql.database': 'testdb',
                'database.googlecloudsql.credentialsPath': '/path/to/credentials.json'
        ])

        then:
        provider.credentialsPath == '/path/to/credentials.json'
        provider.@initialized
    }
}
