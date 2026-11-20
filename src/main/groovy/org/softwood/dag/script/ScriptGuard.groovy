package org.softwood.dag.script

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation

/**
 * 1.4.0 (decision D2): the run-time half of the script profile.
 *
 * <p>{@link ScriptCompiler} rewrites a restricted script so that the receiver of every method call,
 * property read, property write, index and {@code <<} passes through one of these methods first:
 * {@code a.b()} becomes {@code ScriptGuard.checked(a, 'b', false).b()}. The method looks at the
 * object actually reached and either returns it unchanged or throws a {@link SecurityException}.
 * The call itself is still made by Groovy, so a script keeps Groovy's meaning for everything it is
 * allowed to do.</p>
 *
 * <p>What a receiver may be:</p>
 * <ul>
 *   <li>text, numbers, booleans, enums, lists, sets, maps, arrays, closures, iterators and map entries;</li>
 *   <li>an instance of a class the profile lets a script name ({@link ScriptProfile#allowedClassName});</li>
 *   <li>any other object that is not off limits: that is, the objects the flow put in the script's
 *       bindings. A script may call their public methods.</li>
 * </ul>
 * <p>Off limits: a class used as a value, class loaders, reflection, files, paths, URLs, processes,
 * threads, anything closeable, and the packages listed in {@link RuleExpression#offLimits}.</p>
 *
 * <p>These methods are public because compiled scripts call them. They are not an API.</p>
 */
@CompileStatic
final class ScriptGuard {

    private ScriptGuard() {}

    /** The receiver of a method call. With spread, every element is a receiver. */
    static Object checked(Object receiver, String method, boolean spread) {
        if (receiver == null) return null
        if (spread) {
            for (Object element : elementsOf(receiver)) requireReceiver(element, method + '()')
            return receiver
        }
        requireReceiver(receiver, method + '()')
        if (method == 'parse') {
            String type = receiver.getClass().name
            if (type.startsWith('groovy.json.') || type.startsWith('groovy.xml.') || type.startsWith('groovy.yaml.')) {
                throw refused("parse() on a ${type} reads a file or an address: use parseText()")
            }
        }
        return receiver
    }

    /** The receiver of a property read. A property the list does not have is read from each element. */
    static Object checkedGet(Object receiver, String property, boolean spread) {
        if (receiver == null) return null
        if (receiver instanceof Map && !spread) return receiver
        if (spread || (receiver instanceof Collection &&
                InvokerHelper.getMetaClass(receiver).hasProperty(receiver, property) == null)) {
            for (Object element : elementsOf(receiver)) checkedGet(element, property, false)
            return receiver
        }
        requireReceiver(receiver, property)
        return receiver
    }

    /** The receiver of a property write. */
    static Object checkedSet(Object receiver, String property) {
        if (receiver == null) return null
        if (receiver instanceof Map) return receiver
        if (receiver instanceof Closure) {
            throw refused("a script cannot set '${property}' on a closure")
        }
        requireReceiver(receiver, property)
        return receiver
    }

    /** The receiver of an index, read or written. An object's properties are not reached by index. */
    static Object indexable(Object receiver) {
        if (receiver == null) return null
        if (receiver instanceof Map || receiver instanceof Collection || receiver instanceof CharSequence ||
                receiver.getClass().isArray() || allowedType(receiver.getClass())) {
            return receiver
        }
        throw refused("a script cannot index a ${receiver.getClass().name}: read its properties by name")
    }

    /** The left operand of an operator that writes to or pipes its receiver: << >> >>> |. */
    static Object checkedOp(Object receiver, String operator) {
        if (receiver == null) return null
        requireReceiver(receiver, operator)
        return receiver
    }

    /**
     * A value about to be iterated, spread, cast or taken apart by a multiple assignment. Groovy
     * turns some objects into their contents when it does that (a File into its lines), with no
     * method call for the other checks to see.
     */
    static Object passable(Object value, String what) {
        if (value == null || value instanceof Class) return value
        requireReceiver(value, what)
        return value
    }

    // ------------------------------------------------------------------ the rule

    private static void requireReceiver(Object receiver, String what) {
        if (receiver == null) return
        if (receiver instanceof Class) {
            throw refused("a script cannot use ${what} on a class held as a value (${((Class) receiver).name})")
        }
        if (plain(receiver)) return
        if (allowedType(receiver.getClass())) return
        if (RuleExpression.offLimits(receiver)) {
            throw refused("a script cannot use ${what} on a ${receiver.getClass().name}")
        }
    }

    /**
     * True when the class, or a class it extends, is one a script may name. The class actually
     * reached is often an internal one (a charset, a time zone, a caught exception): what it
     * extends says what it is. Interfaces are not considered.
     */
    private static boolean allowedType(Class type) {
        for (Class c = type; c != null && c != Object; c = c.superclass) {
            if (ScriptProfile.allowedClassName(c.name)) return true
        }
        return false
    }

    private static boolean plain(Object o) {
        return o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character ||
                o instanceof Enum || o instanceof Collection || o instanceof Map || o.getClass().isArray() ||
                o instanceof Closure || o instanceof Script || o instanceof Iterator || o instanceof Map.Entry
    }

    private static Collection elementsOf(Object receiver) {
        if (receiver instanceof Collection) return (Collection) receiver
        if (receiver.getClass().isArray()) return DefaultTypeTransformation.asCollection(receiver)
        if (receiver instanceof Iterator) return Collections.emptyList()    // consumed by the call itself: its elements are checked when used
        return Collections.singletonList(receiver)
    }

    private static SecurityException refused(String message) {
        return new SecurityException(message.toString())
    }
}
