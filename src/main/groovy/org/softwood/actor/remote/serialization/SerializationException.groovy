package org.softwood.actor.remote.serialization

/**
 * Exception thrown when serialization or deserialization fails.
 */
class SerializationException extends RuntimeException {
    
    SerializationException(String message) {
        super(message)
    }
    
    SerializationException(String message, Throwable cause) {
        super(message, cause)
    }
}
