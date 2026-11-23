package org.softwood.actor.remote.serialization

/**
 * Pluggable serialization strategy for remote actor communication.
 * 
 * <p>Allows different transports to use different serialization formats
 * without coupling transport logic to serialization logic.</p>
 * 
 * <h2>Implementations</h2>
 * <ul>
 *   <li><strong>JSON</strong> - Human-readable, debugging-friendly</li>
 *   <li><strong>MessagePack</strong> - Compact binary JSON</li>
 *   <li><strong>MicroStream</strong> - High-performance binary</li>
 *   <li><strong>Kryo</strong> - Fast JVM serialization</li>
 * </ul>
 * 
 * @since 2.0.0
 */
interface MessageSerializer {
    
    /**
     * Serializes an object to bytes.
     * 
     * @param obj object to serialize
     * @return serialized bytes
     * @throws SerializationException if serialization fails
     */
    byte[] serialize(Object obj) throws SerializationException
    
    /**
     * Deserializes bytes to an object.
     * 
     * @param bytes serialized bytes
     * @return deserialized object
     * @throws SerializationException if deserialization fails
     */
    Object deserialize(byte[] bytes) throws SerializationException
    
    /**
     * Returns the content type / format identifier.
     * E.g., "application/json", "application/msgpack", "application/microstream"
     * 
     * @return content type string
     */
    String contentType()
    
    /**
     * Checks if this serializer can handle the given type.
     * 
     * @param type class to check
     * @return true if can serialize this type
     */
    boolean canSerialize(Class<?> type)
}
