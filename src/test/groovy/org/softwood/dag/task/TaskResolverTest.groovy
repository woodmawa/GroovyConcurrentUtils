package org.softwood.dag.task

import org.softwood.security.SecretsResolver
import spock.lang.Specification

/**
 * Tests for TaskResolver - core resolver functionality used by all tasks.
 */
class TaskResolverTest extends Specification {
    
    TaskContext ctx
    TaskResolver resolver
    
    def setup() {
        // Create real context with globals (no mocking needed)
        ctx = new TaskContext()
        ctx.globals.testKey = "testValue"
        ctx.globals.counter = 42
        ctx.globals.enabled = true
        
        def prevValue = [userId: 123, email: "test@example.com", items: ["a", "b", "c"]]
        resolver = new TaskResolver(prevValue, ctx)
    }
    
    // =========================================================================
    // Previous Value Access
    // =========================================================================
    
    def "should access previous task result"() {
        expect:
        resolver.prev.userId == 123
        resolver.prev.email == "test@example.com"
        resolver.prev.items.size() == 3
    }
    
    def "should handle null previous value"() {
        when:
        def nullResolver = new TaskResolver(null, ctx)
        
        then:
        nullResolver.prev == null
    }
    
    // =========================================================================
    // Globals Access
    // =========================================================================
    
    def "should get all globals"() {
        when:
        def globals = resolver.getGlobals()
        
        then:
        globals.testKey == "testValue"
        globals.counter == 42
        globals.enabled == true
    }
    
    def "should get specific global value"() {
        expect:
        resolver.global("testKey") == "testValue"
        resolver.global("counter") == 42
        resolver.global("enabled") == true
    }
    
    def "should get global with default value"() {
        expect:
        resolver.global("testKey", "default") == "testValue"
        resolver.global("missingKey", "default") == "default"
    }
    
    def "should set global value"() {
        when:
        resolver.setGlobal("newKey", "newValue")
        
        then:
        ctx.globals.newKey == "newValue"
    }
    
    def "should update global with closure"() {
        when:
        resolver.updateGlobal("counter") { it + 1 }
        
        then:
        ctx.globals.counter == 43
    }
    
    def "should check if global exists"() {
        expect:
        resolver.hasGlobal("testKey") == true
        resolver.hasGlobal("missingKey") == false
    }
    
    def "should remove global"() {
        when:
        def removed = resolver.removeGlobal("testKey")
        
        then:
        removed == "testValue"
        !ctx.globals.containsKey("testKey")
    }
    
    // =========================================================================
    // Template Rendering
    // =========================================================================
    
    def "should render simple template"() {
        when:
        def result = resolver.template('Hello ${prev.email}')
        
        then:
        result == "Hello test@example.com"
    }
    
    def "should render template with globals"() {
        when:
        def result = resolver.template('Counter: ${globals.counter}')
        
        then:
        result == "Counter: 42"
    }
    
    def "should render template with additional bindings"() {
        when:
        def result = resolver.template('User ${name} has ${prev.items.size()} items', [name: "Alice"])
        
        then:
        result == "User Alice has 3 items"
    }
    
    def "should handle empty template"() {
        expect:
        resolver.template('') == ""
        resolver.template(null) == ""
    }
    
    def "should render template from file"() {
        given:
        def tempFile = File.createTempFile("template", ".txt")
        tempFile.text = 'Email: ${prev.email}'
        
        when:
        def result = resolver.templateFile(tempFile.absolutePath)
        
        then:
        result == "Email: test@example.com"
        
        cleanup:
        tempFile.delete()
    }
    
    def "should throw on missing template file"() {
        when:
        resolver.templateFile("/nonexistent/template.txt")
        
        then:
        thrown(FileNotFoundException)
    }
    
    // =========================================================================
    // Credential Resolution
    // =========================================================================
    
    def "should resolve credential from globals"() {
        when:
        def value = resolver.credential("testKey")
        
        then:
        value == "testValue"
    }
    
    def "should resolve credential with default"() {
        expect:
        resolver.credential("missingKey", "default") == "default"
    }
    
    def "should throw on missing required credential"() {
        when:
        resolver.credential("missingKey")
        
        then:
        thrown(IllegalStateException)
    }
    
    def "should resolve environment variable with transformation"() {
        when:
        // Test with default value since we can't set env vars at runtime
        def value = resolver.env("test.var", "test-default")
        
        then:
        value == "test-default"
    }
    
    def "should resolve system property"() {
        given:
        System.setProperty("test.property", "property-value")
        
        when:
        def value = resolver.sysprop("test.property")
        
        then:
        value == "property-value"
        
        cleanup:
        System.clearProperty("test.property")
    }
    
    def "should check if credential exists"() {
        expect:
        resolver.hasCredential("testKey") == true
        resolver.hasCredential("missingKey") == false
    }
    
    // =========================================================================
    // Convenience Methods
    // =========================================================================
    
    def "should resolve closure value"() {
        when:
        def result = resolver.resolve { r -> r.prev.userId * 2 }
        
        then:
        result == 246
    }
    
    def "should resolve non-closure value"() {
        expect:
        resolver.resolve("plain string") == "plain string"
        resolver.resolve(42) == 42
    }
    
    def "should resolve list of values"() {
        when:
        def values = ["static", { r -> r.prev.userId }, 100]
        def results = resolver.resolveAll(values)
        
        then:
        results == ["static", 123, 100]
    }
    
    def "should resolve map values"() {
        when:
        def map = [
            static: "value",
            dynamic: { r -> r.prev.email },
            number: 42
        ]
        def results = resolver.resolveMap(map)
        
        then:
        results.static == "value"
        results.dynamic == "test@example.com"
        results.number == 42
    }
}
