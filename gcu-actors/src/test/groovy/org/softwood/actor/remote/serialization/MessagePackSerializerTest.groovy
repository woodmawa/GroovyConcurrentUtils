package org.softwood.actor.remote.serialization

import groovy.transform.CompileDynamic
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/**
 * Tests for MessagePack serializer.
 */
@CompileDynamic
class MessagePackSerializerTest {
    
    private final MessagePackSerializer serializer = new MessagePackSerializer()
    
    @Test
    void test_serialize_deserialize_string() {
        def original = "Hello, MessagePack!"
        def bytes = serializer.serialize(original)
        def result = serializer.deserialize(bytes)
        
        assertEquals(original, result)
        // MessagePack adds small header, so very short strings might be same size
        // But for larger payloads it's much more compact
        assertTrue(bytes.length > 0, "MessagePack should produce bytes")
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
        
        // Double - compare as doubles since MessagePack returns Double
        def doubleVal = 3.14159
        def doubleBytes = serializer.serialize(doubleVal)
        assertEquals(doubleVal, (serializer.deserialize(doubleBytes) as Double), 0.00001)
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
        assertEquals(3.0, (result[2] as Double), 0.00001)  // Compare as double
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
        assertEquals(95.5, (result.score as Double), 0.00001)  // Compare as double
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
    void test_messagepack_smaller_than_json() {
        def data = [
            message: "This is a test message",
            numbers: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
            nested: [
                a: "value1",
                b: "value2",
                c: "value3"
            ]
        ]
        
        // MessagePack
        def msgpackBytes = serializer.serialize(data)
        
        // JSON (for comparison)
        def jsonSerializer = new JsonSerializer()
        def jsonBytes = jsonSerializer.serialize(data)
        
        println "MessagePack size: ${msgpackBytes.length} bytes"
        println "JSON size: ${jsonBytes.length} bytes"
        println "MessagePack is ${((1 - msgpackBytes.length / jsonBytes.length) * 100).round()}% smaller"
        
        assertTrue(msgpackBytes.length < jsonBytes.length, 
            "MessagePack should be smaller than JSON")
    }
    
    @Test
    void test_content_type() {
        assertEquals('application/msgpack', serializer.contentType())
    }
    
    @Test
    void test_can_serialize() {
        assertTrue(serializer.canSerialize(String))
        assertTrue(serializer.canSerialize(Integer))
        assertTrue(serializer.canSerialize(List))
        assertTrue(serializer.canSerialize(Map))
        
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
}
