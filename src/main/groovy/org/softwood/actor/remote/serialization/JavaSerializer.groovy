package org.softwood.actor.remote.serialization

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Binary message serializer using Java's native serialization.
 * 
 * <p>Simple, reliable binary serialization. Works with any Serializable object.
 * This is a fallback option - MessagePackSerializer is recommended for better performance.</p>
 * 
 * <h2>Characteristics</h2>
 * <ul>
 *   <li>Binary format</li>
 *   <li>Type-safe</li>
 *   <li>Supports complex object graphs</li>
 *   <li>JVM-only (not cross-language)</li>
 *   <li>Requires Serializable interface</li>
 *   <li>Slower than MessagePack</li>
 * </ul>
 * 
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Fallback when MessagePack isn't available</li>
 *   <li>Testing and debugging</li>
 *   <li>Serializing complex Java objects with custom serialization logic</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> For production remote actor messaging, use MessagePackSerializer instead.</p>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class JavaSerializer implements MessageSerializer {
    
    @Override
    byte[] serialize(Object obj) throws SerializationException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            ObjectOutputStream oos = new ObjectOutputStream(baos)
            
            oos.writeObject(obj)
            oos.flush()
            oos.close()
            
            return baos.toByteArray()
            
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize with Java serialization", e)
        }
    }
    
    @Override
    Object deserialize(byte[] bytes) throws SerializationException {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(bytes)
            ObjectInputStream ois = new ObjectInputStream(bais)
            
            Object obj = ois.readObject()
            ois.close()
            
            return obj
            
        } catch (Exception e) {
            throw new SerializationException("Failed to deserialize with Java serialization", e)
        }
    }
    
    @Override
    String contentType() {
        return 'application/java-serialization'
    }
    
    @Override
    boolean canSerialize(Class<?> type) {
        // Can serialize anything that implements Serializable
        // Cannot serialize: Closures, Threads, open resources
        return Serializable.isAssignableFrom(type) &&
                !(Closure.isAssignableFrom(type) || Thread.isAssignableFrom(type))
    }
}
