package org.softwood.actor.remote.serialization

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Java serialization for remote actor messages.
 *
 * <h2>Deserialisation is filtered (GCU 1.2.1)</h2>
 * <p>Java deserialisation of bytes from another process can execute code through classes that happen
 * to be on the classpath. This serializer therefore reads through an {@link ObjectInputFilter} and
 * accepts only:</p>
 * <ul>
 *   <li>JDK value types under {@code java.lang.}, {@code java.util.}, {@code java.time.} and
 *       {@code java.math.}, and arrays of accepted types;</li>
 *   <li>Groovy's interpolated string ({@code GString}), by exact class name, since it turns up in
 *       ordinary message maps. No other Groovy runtime class is accepted: closures and method
 *       pointers are exactly what a deserialisation attack is built from;</li>
 *   <li>classes whose name starts with one of the package prefixes given to the constructor.</li>
 * </ul>
 * <p>Anything else is rejected and {@link #deserialize} throws {@link SerializationException}. An
 * application that sends its own message classes names their packages:</p>
 * <pre>
 * def serializer = new JavaSerializer(['com.myapp.messages.'])
 * </pre>
 * <p>Graph depth, reference count and array length are capped as well. Prefer the JSON or MessagePack
 * serializers where they are enough.</p>
 *
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class JavaSerializer implements MessageSerializer {

    private static final List<String> JDK_PREFIXES =
            Collections.unmodifiableList(['java.lang.', 'java.util.', 'java.time.', 'java.math.'])

    /** Accepted by exact name only: a prefix here would let the rest of the Groovy runtime in. */
    private static final Set<String> EXACT_CLASSES = Collections.unmodifiableSet(
            ['groovy.lang.GString', 'org.codehaus.groovy.runtime.GStringImpl'] as Set<String>)

    private static final long MAX_DEPTH = 64
    private static final long MAX_REFERENCES = 100_000
    private static final long MAX_ARRAY_LENGTH = 1_000_000

    private final List<String> allowedPrefixes

    /** Accepts JDK value types only. */
    JavaSerializer() {
        this(Collections.<String> emptyList())
    }

    /**
     * @param allowedPackagePrefixes extra class-name prefixes to accept, e.g. {@code 'com.myapp.messages.'}
     */
    JavaSerializer(Collection<String> allowedPackagePrefixes) {
        List<String> all = new ArrayList<String>(JDK_PREFIXES)
        if (allowedPackagePrefixes != null) {
            for (String p : allowedPackagePrefixes) {
                if (p != null && !p.trim().isEmpty()) {
                    all.add(p.trim())
                }
            }
        }
        this.allowedPrefixes = Collections.unmodifiableList(all)
    }

    List<String> getAllowedPrefixes() {
        return allowedPrefixes
    }

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
            ois.setObjectInputFilter(new AllowListFilter(allowedPrefixes))
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
        return Serializable.isAssignableFrom(type) &&
                !(Closure.isAssignableFrom(type) || Thread.isAssignableFrom(type))
    }

    /** Rejects every class that is not explicitly allowed, and oversized graphs. */
    @CompileStatic
    private static class AllowListFilter implements ObjectInputFilter {

        private final List<String> prefixes

        AllowListFilter(List<String> prefixes) {
            this.prefixes = prefixes
        }

        @Override
        ObjectInputFilter.Status checkInput(ObjectInputFilter.FilterInfo info) {
            if (info.depth() > MAX_DEPTH || info.references() > MAX_REFERENCES
                    || info.arrayLength() > MAX_ARRAY_LENGTH) {
                return ObjectInputFilter.Status.REJECTED
            }

            Class<?> type = info.serialClass()
            if (type == null) {
                // A back-reference or a limits-only check: nothing new is being instantiated.
                return ObjectInputFilter.Status.UNDECIDED
            }
            while (type.isArray()) {
                type = type.getComponentType()
            }
            if (type.isPrimitive()) {
                return ObjectInputFilter.Status.ALLOWED
            }

            String className = type.getName()
            if (EXACT_CLASSES.contains(className)) {
                return ObjectInputFilter.Status.ALLOWED
            }
            for (String prefix : prefixes) {
                if (className.startsWith(prefix)) {
                    return ObjectInputFilter.Status.ALLOWED
                }
            }
            return ObjectInputFilter.Status.REJECTED
        }
    }
}
