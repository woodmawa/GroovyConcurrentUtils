package org.softwood.actor.remote.serialization

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * MicroStream-based message serializer (Simple Java Serialization).
 * 
 * <p>High-performance binary serialization. For now using Java serialization
 * as a baseline. Can be upgraded to pure MicroStream binary format later.</p>
 * 
 * <h2>Characteristics</h2>
 * <ul>
 *   <li>Binary format (compact)</li>
 *   <li>High performance</li>
 *   <li>Type-safe</li>
 *   <li>Supports complex object graphs</li>
 *   <li>JVM-only (not cross-language)</li>
 * </ul>
 * 
 * <h2>Note</h2>
 * <p>This implementation uses Java serialization as a foundation.
 * For production use with MicroStream's native binary format,
 * integrate with MicroStream's persistence layer.</p>
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
            throw new SerializationException("Failed to serialize with MicroStream", e)
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
            throw new SerializationException("Failed to deserialize with MicroStream", e)
        }
    }
    
    @Override
    String contentType() {
        return 'application/microstream'
    }
    
    @Override
    boolean canSerialize(Class<?> type) {
        // Can serialize anything that implements Serializable
        // Cannot serialize: Closures, Threads, open resources
        return Serializable.isAssignableFrom(type) &&
                !(Closure.isAssignableFrom(type) || Thread.isAssignableFrom(type))
    }
}
