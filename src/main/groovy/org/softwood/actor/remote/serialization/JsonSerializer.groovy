package org.softwood.actor.remote.serialization

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

/**
 * JSON-based message serializer.
 * 
 * <p>Human-readable, debugging-friendly serialization using Groovy's
 * built-in JSON support. Good for development and debugging.</p>
 * 
 * <h2>Characteristics</h2>
 * <ul>
 *   <li>Human-readable</li>
 *   <li>Easy to debug</li>
 *   <li>Works with curl/REST clients</li>
 *   <li>Slower than binary formats</li>
 *   <li>Larger payload size</li>
 * </ul>
 * 
 * @since 2.0.0
 */
@CompileStatic
class JsonSerializer implements MessageSerializer {
    
    private final JsonSlurper slurper = new JsonSlurper()
    
    @Override
    byte[] serialize(Object obj) throws SerializationException {
        try {
            def json = JsonOutput.toJson(obj)
            return json.bytes
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize to JSON", e)
        }
    }
    
    @Override
    Object deserialize(byte[] bytes) throws SerializationException {
        try {
            def json = new String(bytes, 'UTF-8')
            return slurper.parseText(json)
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize from JSON", e)
        }
    }
    
    @Override
    String contentType() {
        return 'application/json'
    }
    
    @Override
    boolean canSerialize(Class<?> type) {
        // JSON can serialize most types (primitives, collections, maps, beans)
        // Cannot serialize: Closures, Threads, etc.
        return !(Closure.isAssignableFrom(type) || 
                Thread.isAssignableFrom(type))
    }
}
