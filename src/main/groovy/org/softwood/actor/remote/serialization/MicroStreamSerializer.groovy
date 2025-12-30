package org.softwood.actor.remote.serialization

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Binary message serializer using Java serialization.
 * 
 * <p>Simple, reliable binary serialization. Works with any Serializable object.
 * Named MicroStreamSerializer for backward compatibility.</p>
 * 
 * <h2>Characteristics</h2>
 * <ul>
 *   <li>Binary format (compact)</li>
 *   <li>Type-safe</li>
 *   <li>Supports complex object graphs</li>
 *   <li>JVM-only (not cross-language)</li>
 *   <li>Requires Serializable interface</li>
 * </ul>
 * 
 * <h2>Note</h2>
 * <p>This implementation uses Java serialization.
 * For higher performance, consider using MessagePackSerializer instead.</p>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class MicroStreamSerializer implements MessageSerializer {
    
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
            throw new SerializationException("Failed to serialize", e)
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
            throw new SerializationException("Failed to deserialize", e)
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
