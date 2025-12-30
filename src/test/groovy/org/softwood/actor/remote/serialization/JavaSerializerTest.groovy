package org.softwood.actor.remote.serialization

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for Java binary message serializer.
 */
@CompileDynamic
class JavaSerializerTest {
    
    private final JavaSerializer serializer = new JavaSerializer()
    
    @Test
    void test_serialize_deserialize_string() {
        def original = "Hello, Java Serialization!"
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
        assertTrue(bytes.length > 0, "Should produce bytes")
    }
    
    @Test
    void test_serialize_deserialize_numbers() {
        // Integer
        def intVal = 42
        def intBytes = serializer.serialize(intVal)
        assertEquals(intVal, serializer.deserialize(intBytes))
        
        // Long
        def longVal = 9876543210L
        def longBytes = serializer.serialize(longVal)
        assertEquals(longVal, serializer.deserialize(longBytes))
        
        // Double
        def doubleVal = 3.14159
        def doubleBytes = serializer.serialize(doubleVal)
        assertEquals(doubleVal, serializer.deserialize(doubleBytes))
    }
    
    @Test
    void test_serialize_deserialize_boolean() {
        def trueBytes = serializer.serialize(true)
        assertEquals(true, serializer.deserialize(trueBytes))
        
        def falseBytes = serializer.serialize(false)
        assertEquals(false, serializer.deserialize(falseBytes))
    }
    
    @Test
    void test_serialize_deserialize_null() {
        def bytes = serializer.serialize(null)
        assertNull(serializer.deserialize(bytes))
    }
    
    @Test
    void test_serialize_deserialize_list() {
        def original = [1, "two", 3.0, true, null]
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes) as List
        
        assertEquals(original.size(), result.size())
        assertEquals(1, result[0])
        assertEquals("two", result[1])
        assertEquals(3.0, result[2])
        assertEquals(true, result[3])
        assertNull(result[4])
    }
    
    @Test
    void test_serialize_deserialize_map() {
        def original = [
            name: "Alice",
            age: 30,
            score: 95.5,
            active: true
        ]
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes) as Map
        
        assertEquals("Alice", result.name)
        assertEquals(30, result.age)
        assertEquals(95.5, result.score)
        assertEquals(true, result.active)
    }
    
    @Test
    void test_serialize_deserialize_nested_structure() {
        def original = [
            user: [
                name: "Bob",
                tags: ["admin", "developer"]
            ],
            count: 42,
            metadata: [
                created: "2024-01-01",
                active: true
            ]
        ]
        
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes) as Map
        
        assertEquals("Bob", (result.user as Map).name)
        assertEquals(2, ((result.user as Map).tags as List).size())
        assertEquals("admin", ((result.user as Map).tags as List)[0])
        assertEquals(42, result.count)
        assertEquals(true, (result.metadata as Map).active)
    }
    
    @Test
    void test_size_comparison() {
        def data = [
            message: "This is a test message",
            numbers: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
            nested: [
                a: "value1",
                b: "value2",
                c: "value3"
            ]
        ]
        
        // Java serialization
        def javaBytes = serializer.serialize(data)
        
        // JSON (for comparison)
        def jsonSerializer = new JsonSerializer()
        def jsonBytes = jsonSerializer.serialize(data)
        
        // MessagePack (for comparison)
        def msgpackSerializer = new MessagePackSerializer()
        def msgpackBytes = msgpackSerializer.serialize(data)
        
        println "Java serialization size: ${javaBytes.length} bytes"
        println "MessagePack size: ${msgpackBytes.length} bytes"
        println "JSON size: ${jsonBytes.length} bytes"
        
        // MessagePack should be most compact
        assertTrue(msgpackBytes.length < javaBytes.length, 
            "MessagePack should be more compact than Java serialization")
        assertTrue(javaBytes.length > 0, "Should produce bytes")
    }
    
    @Test
    void test_content_type() {
        assertEquals('application/java-serialization', serializer.contentType())
    }
    
    @Test
    void test_can_serialize() {
        assertTrue(serializer.canSerialize(String))
        assertTrue(serializer.canSerialize(Integer))
        assertTrue(serializer.canSerialize(ArrayList))  // Implements Serializable
        assertTrue(serializer.canSerialize(HashMap))     // Implements Serializable
        
        assertFalse(serializer.canSerialize(Closure))
        assertFalse(serializer.canSerialize(Thread))
    }
    
    @Test
    void test_actor_message_envelope() {
        // Simulate typical actor message envelope
        def envelope = [
            actor: "myActor",
            payload: [
                command: "increment",
                value: 42
            ],
            timeout: 5000
        ]
        
        def bytes = serializer.serialize(envelope)
        def result = serializer.deserialize(bytes) as Map
        
        assertEquals("myActor", result.actor)
        assertEquals("increment", (result.payload as Map).command)
        assertEquals(42, (result.payload as Map).value)
        assertEquals(5000, result.timeout)
    }
    
    @Test
    void test_serializable_custom_class() {
        // Test with a custom Serializable class
        def person = new SerializablePerson(name: "Charlie", age: 35, tags: ["dev", "ops"])
        
        def bytes = serializer.serialize(person)
        def result = serializer.deserialize(bytes) as SerializablePerson
        
        assertEquals("Charlie", result.name)
        assertEquals(35, result.age)
        assertEquals(2, result.tags.size())
        assertEquals("dev", result.tags[0])
    }
    
    @Test
    void test_preserves_type_information() {
        // Java serialization preserves exact types
        def original = new SerializablePerson(name: "Dave", age: 40, tags: ["admin"])
        
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes)
        
        // Type is preserved
        assertTrue(result instanceof SerializablePerson)
        assertEquals(SerializablePerson, result.class)
    }
    
    @Test
    void test_performance_comparison_with_messagepack() {
        // Prepare test data
        def testData = (1..100).collect { i ->
            [
                id: i,
                name: "Person $i",
                active: i % 2 == 0,
                score: i * 1.5
            ]
        }
        
        // Java serialization
        long javaStart = System.nanoTime()
        def javaBytes = serializer.serialize(testData)
        def javaResult = serializer.deserialize(javaBytes)
        long javaTime = System.nanoTime() - javaStart
        
        // MessagePack
        def msgpackSerializer = new MessagePackSerializer()
        long msgpackStart = System.nanoTime()
        def msgpackBytes = msgpackSerializer.serialize(testData)
        def msgpackResult = msgpackSerializer.deserialize(msgpackBytes)
        long msgpackTime = System.nanoTime() - msgpackStart
        
        println "Java serialization: ${javaTime / 1000}μs, ${javaBytes.length} bytes"
        println "MessagePack:        ${msgpackTime / 1000}μs, ${msgpackBytes.length} bytes"
        println "MessagePack is ${String.format('%.1f', javaTime / (double)msgpackTime)}x faster"
        println "MessagePack is ${String.format('%.1f', javaBytes.length / (double)msgpackBytes.length)}x more compact"
        
        // Both should work correctly
        assertEquals(100, (javaResult as List).size())
        assertEquals(100, (msgpackResult as List).size())
    }
    
    /**
     * Test helper class - Serializable person
     */
    static class SerializablePerson implements Serializable {
        String name
        int age
        List<String> tags
    }
}
