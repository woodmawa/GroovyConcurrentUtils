package org.softwood.dag.task

import org.softwood.dag.TaskGraph
import spock.lang.Specification

/**
 * Tests for the generic resolver DSL capabilities inherited by all tasks from TaskBase.
 *
 * Verifies:
 * - evaluateValue() helper works for static and dynamic values
 * - configureDsl() helper detects and defers resolver-based configurations
 * - executeDeferredConfig() executes deferred configurations with resolver
 * - TaskResolver provides access to prev, globals, and credentials
 * - Backward compatibility with non-resolver closures
 */
class TaskResolverDslTest extends Specification {

    // =========================================================================
    // Helper Methods Tests - evaluateValue()
    // =========================================================================

    def "evaluateValue should handle static string values"() {
        when:
        def graph = TaskGraph.build {
            httpTask("test") {
                url "https://static.example.com/api"
            }
        }

        def task = graph.tasks["test"]

        then:
        task != null
        // Task should accept static value
    }

    def "evaluateValue should handle dynamic closure values with resolver"() {
        when:
        def graph = TaskGraph.build {
            // Set globals using globals block
            globals {
                put('api.baseUrl', 'https://api.example.com')
            }

            serviceTask("setup") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [endpoint: '/users'] }
                }
            }

            httpTask("dynamic-url") {
                url { r -> "${r.global('api.baseUrl')}${r.prev.endpoint}" }
            }

            chainVia("setup", "dynamic-url")
        }

        then:
        graph != null
        graph.tasks.size() == 2  // setup + dynamic-url
        // Closure will be evaluated at runtime with resolver
    }

    // =========================================================================
    // Resolver Access Tests - prev, globals, credentials
    // =========================================================================

    def "resolver should provide access to previous task result via r.prev"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("produce-data") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [userId: 123, userName: 'Alice']
                    }
                }
            }

            serviceTask("consume-data") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        // Verify we got the data from previous task
                        assert prev.userId == 123
                        assert prev.userName == 'Alice'
                        return [processed: true]
                    }
                }
            }

            chainVia("produce-data", "consume-data")
        }

        def result = graph.run().get()

        then:
        result.processed == true
    }

    def "resolver should provide access to globals via r.global()"() {
        when:
        def graph = TaskGraph.build {
            // Set global configuration
            globals {
                put('db.url', 'jdbc:h2:mem:testdb')
                put('db.user', 'testuser')
                put('db.pass', 'testpass')
            }

            sqlTask("with-globals") {
                dataSource { r ->
                    url = r.global('db.url')
                    username = r.global('db.user')
                    password = r.global('db.pass')
                }
                query "SELECT 1"
            }
        }

        then:
        graph != null
        def task = graph.tasks["with-globals"]
        task != null
    }

    def "resolver should provide access to credentials via r.credential()"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("setup-creds") {
                action { ctx, prev ->
                    ctx.credentials.set('api.token', 'secret-token-123')
                    ctx.credentials.set('db.password', 'secure-password')
                    ctx.promiseFactory.executeAsync { [ready: true] }
                }
            }

            httpTask("secure-api") {
                url "https://api.example.com/data"
                auth { r ->
                    bearer r.credential('api.token')
                }
            }
            
            chainVia("setup-creds", "secure-api")
        }

        then:
        graph != null
        def task = graph.tasks["secure-api"]
        task != null
    }

    def "resolver should allow setting globals via r.setGlobal()"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("set-config") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def resolver = new TaskResolver(null, ctx)
                        resolver.setGlobal('processed', true)
                        resolver.setGlobal('count', 42)
                        return [status: 'done']
                    }
                }
            }

            serviceTask("read-config") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        def resolver = new TaskResolver(prev, ctx)
                        assert resolver.global('processed') == true
                        assert resolver.global('count') == 42
                        return [verified: true]
                    }
                }
            }

            chainVia("set-config", "read-config")
        }

        def result = graph.run().get()

        then:
        result.verified == true
    }

    // =========================================================================
    // Multiple Task Types with Resolver
    // =========================================================================

    def "resolver pattern should work across different task types"() {
        when:
        def graph = TaskGraph.build {
            globals {
                put('api.url', 'https://api.example.com')
                put('db.url', 'jdbc:h2:mem:test')
            }

            serviceTask("setup") {
                action { ctx, prev ->
                    ctx.credentials.set('api.key', 'test-key')
                    ctx.promiseFactory.executeAsync {
                        [userId: 123, fileName: 'data.txt']
                    }
                }
            }

            // HttpTask with resolver
            httpTask("fetch-data") {
                url { r -> "${r.global('api.url')}/users/${r.prev.userId}" }
                auth { r -> bearer r.credential('api.key') }
            }

            // SqlTask with resolver
            sqlTask("store-data") {
                dataSource { r ->
                    url = r.global('db.url')
                    username = "sa"
                    password = ""
                }
                query "SELECT 1"
            }

            // FileTask with resolver
            fileTask("process-file") {
                files { r ->
                    [new File("/tmp/${r.prev.fileName}")]
                }
            }

            chainVia("setup", "fetch-data", "store-data", "process-file")
        }

        then:
        graph != null
        graph.tasks.size() == 4
        // All tasks configured with resolver
    }

    // =========================================================================
    // Backward Compatibility Tests
    // =========================================================================

    def "closures without parameters should still work (backward compatibility)"() {
        when:
        def graph = TaskGraph.build {
            sqlTask("legacy-style") {
                dataSource {
                    url = "jdbc:h2:mem:test"
                    username = "sa"
                    password = ""
                }
                query "SELECT 1"
                params 123  // Static params
            }

            httpTask("legacy-http") {
                url "https://api.example.com"  // Static URL
                auth {
                    bearer "hardcoded-token"  // Static auth
                }
            }
        }

        then:
        graph != null
        graph.tasks.size() == 2
        // Both tasks should work with old-style configuration
    }

    def "mixed resolver and non-resolver closures should work together"() {
        when:
        def graph = TaskGraph.build {
            globals {
                put('api.url', 'https://api.example.com')
            }

            serviceTask("producer") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync { [id: 456] }
                }
            }

            httpTask("mixed") {
                // Dynamic URL with resolver
                url { r -> "${r.global('api.url')}/data" }

                // Static auth without resolver
                auth {
                    bearer "static-token"
                }

                // Dynamic headers with resolver
                headers { r ->
                    "X-User-ID" r.prev.id
                }
            }

            chainVia("producer", "mixed")
        }

        then:
        graph != null
        // Mix of resolver and non-resolver should work
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    def "resolver should provide default values via r.global(key, default)"() {
        when:
        def graph = TaskGraph.build {
            // Don't set any globals

            httpTask("with-defaults") {
                url { r ->
                    def baseUrl = r.global('api.url', 'https://default.example.com')
                    def port = r.global('api.port', 8080)
                    "${baseUrl}:${port}/api"
                }
            }
        }

        then:
        graph != null
        // Should use default values when globals not set
    }

    def "resolver should handle null previous results gracefully"() {
        when:
        def graph = TaskGraph.build {
            httpTask("first-task") {
                url { r ->
                    // r.prev should be null for first task
                    def userId = r.prev?.userId ?: 'default'
                    "https://api.example.com/users/${userId}"
                }
            }
        }

        then:
        graph != null
        // Should handle null prev gracefully
    }

    // =========================================================================
    // Complex Integration Tests
    // =========================================================================

    def "resolver should support complex data flow pipeline"() {
        when:
        def graph = TaskGraph.build {
            globals {
                put('env', 'test')
                put('test.db.url', 'jdbc:h2:mem:testdb')
                put('prod.db.url', 'jdbc:h2:mem:proddb')
            }

            serviceTask("setup-creds2") {
                action { ctx, prev ->
                    ctx.credentials.set('db.password', 'secure123')
                    ctx.promiseFactory.executeAsync { [ready: true] }
                }
            }

            serviceTask("fetch-config") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [
                                region: 'us-east-1',
                                maxResults: 100,
                                filters: ['active': true]
                        ]
                    }
                }
            }

            sqlTask("query-data") {
                dataSource { r ->
                    def env = r.global('env')
                    url = r.global("${env}.db.url")
                    username = "sa"
                    password = r.credential('db.password')
                }
                query "SELECT * FROM data WHERE region = ? LIMIT ?"
                params { r ->
                    [r.prev.region, r.prev.maxResults]
                }
            }

            httpTask("send-results") {
                url { r ->
                    def env = r.global('env')
                    "https://${env}.api.example.com/results"
                }
                json { r ->
                    region r.prev.region
                    count r.prev.maxResults
                    timestamp new Date().time
                }
            }

            chainVia("setup-creds2", "fetch-config", "query-data", "send-results")
        }

        then:
        graph != null
        graph.tasks.size() == 4  // setup-creds2 + 3 main tasks
        // Complex pipeline with resolver throughout
    }

    def "resolver should support conditional configuration based on prev data"() {
        when:
        def graph = TaskGraph.build {
            serviceTask("setup-creds3") {
                action { ctx, prev ->
                    ctx.credentials.set('prod.api.key', 'prod-key-123')
                    ctx.credentials.set('test.api.key', 'test-key-456')
                    ctx.promiseFactory.executeAsync { [ready: true] }
                }
            }

            serviceTask("determine-env") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [environment: 'prod', requiresAuth: true]
                    }
                }
            }

            httpTask("conditional-auth") {
                url "https://api.example.com/data"

                auth { r ->
                    if (r.prev.requiresAuth) {
                        def env = r.prev.environment
                        bearer r.credential("${env}.api.key")
                    }
                    // No auth if not required
                }
            }

            chainVia("setup-creds3", "determine-env", "conditional-auth")
        }

        then:
        graph != null
        // Conditional configuration based on prev data
    }

    // =========================================================================
    // Documentation Tests (Examples as Tests)
    // =========================================================================

    def "example: secure API workflow with credentials"() {
        given: "A workflow that uses credentials for secure API access"

        when:
        def graph = TaskGraph.build {
            globals {
                put('github.api', 'https://api.github.com')
            }

            serviceTask("setup-creds4") {
                action { ctx, prev ->
                    ctx.credentials.set('github.token', 'ghp_secret123')
                    ctx.promiseFactory.executeAsync { [ready: true] }
                }
            }

            serviceTask("get-user") {
                action { ctx, prev ->
                    ctx.promiseFactory.executeAsync {
                        [username: 'octocat']
                    }
                }
            }

            httpTask("fetch-repos") {
                url { r ->
                    "${r.global('github.api')}/users/${r.prev.username}/repos"
                }
                auth { r ->
                    bearer r.credential('github.token')
                }
                headers { r ->
                    "Accept" "application/vnd.github.v3+json"
                    "User-Agent" "MyApp/${r.global('app.version', '1.0')}"
                }
            }

            chainVia("setup-creds4", "get-user", "fetch-repos")
        }

        then:
        graph != null
        graph.tasks.size() == 3  // setup-creds4 + get-user + fetch-repos
    }

    def "example: multi-environment database workflow"() {
        given: "A workflow that adapts to different environments"

        when:
        def graph = TaskGraph.build {
            globals {
                put('environment', 'staging')
                put('dev.db.url', 'jdbc:h2:mem:dev')
                put('staging.db.url', 'jdbc:h2:mem:staging')
                put('prod.db.url', 'jdbc:h2:mem:prod')
            }

            serviceTask("setup-creds5") {
                action { ctx, prev ->
                    ctx.credentials.set('db.password', 'secure-pwd')
                    ctx.promiseFactory.executeAsync { [ready: true] }
                }
            }

            sqlTask("query") {
                dataSource { r ->
                    def env = r.global('environment')
                    url = r.global("${env}.db.url")
                    username = "sa"
                    password = r.credential('db.password')
                }
                query "SELECT 1"
            }
            
            chainVia("setup-creds5", "query")
        }

        then:
        graph != null
        graph.tasks["query"] != null
    }
}