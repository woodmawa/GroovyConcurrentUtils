package org.softwood.dag.task

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import static org.junit.jupiter.api.Assertions.*

import org.softwood.dag.TaskGraph
import com.github.tomakehurst.wiremock.WireMockServer
import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig

import java.time.Duration

/**
 * Tests for HttpTask - HTTP/REST API request execution
 */
class HttpTaskTest {

    TaskContext ctx
    WireMockServer wireMock
    
    @BeforeEach
    void setup() {
        ctx = new TaskContext()
        
        // Start WireMock server
        wireMock = new WireMockServer(wireMockConfig().port(8089))
        wireMock.start()
        configureFor("localhost", 8089)
    }
    
    @AfterEach
    void cleanup() {
        wireMock.stop()
    }

    // =========================================================================
    // Basic HTTP Method Tests
    // =========================================================================

    @Test
    void testSimpleGetRequest() {
        // Mock endpoint
        stubFor(get(urlEqualTo("/users/123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody('{"id": 123, "name": "Alice"}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/users/123"
                method HttpMethod.GET
            }
        }
        
        def response = graph.run().get()
        
        assertEquals(200, response.statusCode)
        assertTrue(response.isSuccess())
        assertEquals("Alice", response.json().name)
    }

    @Test
    void testPostRequestWithJson() {
        stubFor(post(urlEqualTo("/users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody('{"id": 456, "name": "Bob"}')))
        
        def graph = TaskGraph.build {
            httpTask("create") {
                url "http://localhost:8089/users"
                method HttpMethod.POST
                json {
                    name "Bob"
                    email "bob@example.com"
                }
            }
        }
        
        def response = graph.run().get()
        
        assertEquals(201, response.statusCode)
        assertTrue(response.isSuccess())
        assertEquals(456, response.json().id)
    }

    @Test
    void testPutRequest() {
        stubFor(put(urlEqualTo("/users/123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"id": 123, "name": "Alice Updated"}')))
        
        def graph = TaskGraph.build {
            httpTask("update") {
                url "http://localhost:8089/users/123"
                method HttpMethod.PUT
                json {
                    name "Alice Updated"
                }
            }
        }
        
        def response = graph.run().get()
        
        assertEquals(200, response.statusCode)
        assertEquals("Alice Updated", response.json().name)
    }

    @Test
    void testDeleteRequest() {
        stubFor(delete(urlEqualTo("/users/123"))
            .willReturn(aResponse()
                .withStatus(204)))
        
        def graph = TaskGraph.build {
            httpTask("delete") {
                url "http://localhost:8089/users/123"
                method HttpMethod.DELETE
            }
        }
        
        def response = graph.run().get()
        
        assertEquals(204, response.statusCode)
        assertTrue(response.isSuccess())
    }

    // =========================================================================
    // Headers Tests
    // =========================================================================

    @Test
    void testCustomHeaders() {
        stubFor(get(urlEqualTo("/data"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("User-Agent", equalTo("MyApp/1.0"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"data": "test"}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/data"
                headers {
                    Accept "application/json"
                    "User-Agent" "MyApp/1.0"
                }
            }
        }
        
        def response = graph.run().get()
        assertEquals(200, response.statusCode)
    }

    // =========================================================================
    // Query Parameters Tests
    // =========================================================================

    @Test
    void testQueryParameters() {
        stubFor(get(urlPathEqualTo("/search"))
            .withQueryParam("q", equalTo("groovy"))
            .withQueryParam("page", equalTo("1"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"results": []}')))
        
        def graph = TaskGraph.build {
            httpTask("search") {
                url "http://localhost:8089/search"
                queryParams {
                    q "groovy"
                    page "1"
                }
            }
        }
        
        def response = graph.run().get()
        assertEquals(200, response.statusCode)
    }

    // =========================================================================
    // Authentication Tests
    // =========================================================================

    @Test
    void testBearerTokenAuth() {
        stubFor(get(urlEqualTo("/protected"))
            .withHeader("Authorization", equalTo("Bearer secret-token"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"data": "protected"}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/protected"
                auth {
                    bearer "secret-token"
                }
            }
        }
        
        def response = graph.run().get()
        assertEquals(200, response.statusCode)
    }

    @Test
    void testBasicAuth() {
        def credentials = "alice:password123"
        def encoded = Base64.encoder.encodeToString(credentials.getBytes("UTF-8"))
        
        stubFor(get(urlEqualTo("/protected"))
            .withHeader("Authorization", equalTo("Basic ${encoded}"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"data": "protected"}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/protected"
                auth {
                    basic "alice", "password123"
                }
            }
        }
        
        def response = graph.run().get()
        assertEquals(200, response.statusCode)
    }

    // =========================================================================
    // Error Handling Tests
    // =========================================================================

    @Test
    void testClientError404() {
        stubFor(get(urlEqualTo("/notfound"))
            .willReturn(aResponse()
                .withStatus(404)
                .withBody("Not Found")))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/notfound"
            }
        }
        
        def exception = assertThrows(Exception) {
            graph.run().get()
        }
        
        // Should be HttpClientErrorException (4xx)
        assertTrue(exception.message.contains("404"))
    }

    @Test
    void testServerError500() {
        stubFor(get(urlEqualTo("/error"))
            .willReturn(aResponse()
                .withStatus(500)
                .withBody("Internal Server Error")))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/error"
                // Disable retries for this test
                maxRetries = 0
            }
        }
        
        def exception = assertThrows(Exception) {
            graph.run().get()
        }
        
        // Check the full exception chain (message + causes)
        def fullMessage = exception.toString()
        def cause = exception.cause
        while (cause != null) {
            fullMessage += " " + cause.toString()
            cause = cause.cause
        }
        
        assertTrue(fullMessage.contains("500") || fullMessage.contains("Internal Server Error"),
            "Exception chain should mention 500 or Internal Server Error, got: ${fullMessage}")
    }

    // =========================================================================
    // Response Parsing Tests
    // =========================================================================

    @Test
    void testJsonResponseParsing() {
        stubFor(get(urlEqualTo("/data"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody('{"users": [{"name": "Alice"}, {"name": "Bob"}]}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch") {
                url "http://localhost:8089/data"
            }
        }
        
        def response = graph.run().get()
        
        def json = response.json()
        assertEquals(2, json.users.size())
        assertEquals("Alice", json.users[0].name)
        assertEquals("Bob", json.users[1].name)
    }

    // =========================================================================
    // TaskGraph Integration Tests
    // =========================================================================

    @Test
    void testHttpTaskInChain() {
        stubFor(get(urlEqualTo("/users/123"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody('{"id": 123, "name": "Alice"}')))
        
        def graph = TaskGraph.build {
            httpTask("fetch-user") {
                url "http://localhost:8089/users/123"
            }
            
            serviceTask("process") {
                action { ctx, prev ->
                    ctx.promiseFactory.createPromise("Processed: ${prev.json().name}".toString())
                }
            }
            
            chainVia("fetch-user", "process")
        }
        
        def result = graph.run().get()
        assertEquals("Processed: Alice", result)
    }

    // =========================================================================
    // Factory and Type Tests
    // =========================================================================

    @Test
    void testFactoryCreation() {
        def task = TaskFactory.createHttpTask("http1", "HTTP Task", ctx)
        
        assertNotNull(task)
        assertEquals("http1", task.id)
        assertTrue(task instanceof HttpTask)
    }

    @Test
    void testTaskTypeEnum() {
        def task = TaskFactory.createTask(TaskType.HTTP, "http1", "HTTP", ctx)
        
        assertNotNull(task)
        assertTrue(task instanceof HttpTask)
    }

    @Test
    void testFriendlyNames() {
        def names = ["http", "httptask", "rest", "api"]
        
        names.each { name ->
            def type = TaskType.fromString(name)
            assertEquals(TaskType.HTTP, type)
        }
    }
}
