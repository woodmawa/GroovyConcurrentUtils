package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.0 (decision D2): the tables of the script profile, level 1.
 *
 * <p>A restricted {@code ScriptTask} script may name only the classes listed here, may not use the
 * method and property names listed here on anything, and at run time may not call into, read or
 * write an object of a type that is off limits. {@link ScriptCompiler} applies the first two when
 * the script is compiled; {@link ScriptGuard} applies the third, and the first again, while it runs.</p>
 *
 * <p>These lists are a reduction, not isolation.</p>
 */
@CompileStatic
final class ScriptProfile {

    private ScriptProfile() {}

    /** Packages whose classes a script may name. Exact packages: a sub-package is not included. */
    static final Set<String> ALLOWED_PACKAGES = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'java.util', 'java.util.function', 'java.util.stream', 'java.util.regex',
            'java.time', 'java.time.format', 'java.time.temporal',
            'java.math', 'java.text', 'java.nio.charset', 'java.util.concurrent.atomic',
            'groovy.json', 'groovy.xml', 'groovy.xml.slurpersupport', 'groovy.yaml'
    ]))

    /** Classes in an allowed package that a script may still not name. */
    static final Set<String> DENIED_CLASSES = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'java.util.Formatter', 'java.util.Scanner', 'java.util.Timer', 'java.util.TimerTask',
            'java.util.ServiceLoader', 'java.util.ResourceBundle', 'java.util.PropertyResourceBundle',
            'java.util.ListResourceBundle'
    ]))

    /** Further classes a script may name, one by one. */
    static final Set<String> ALLOWED_CLASSES = Collections.unmodifiableSet(new LinkedHashSet<String>([
            // java.lang value types
            'java.lang.Object', 'java.lang.String', 'java.lang.CharSequence', 'java.lang.StringBuilder',
            'java.lang.Number', 'java.lang.Integer', 'java.lang.Long', 'java.lang.Short', 'java.lang.Byte',
            'java.lang.Double', 'java.lang.Float', 'java.lang.Boolean', 'java.lang.Character',
            'java.lang.Math', 'java.lang.StrictMath', 'java.lang.Comparable', 'java.lang.Iterable',
            'java.lang.Enum', 'java.lang.Void',
            // exceptions a script may throw and catch (not Throwable, not Error)
            'java.lang.Exception', 'java.lang.RuntimeException', 'java.lang.IllegalArgumentException',
            'java.lang.IllegalStateException', 'java.lang.NumberFormatException', 'java.lang.ArithmeticException',
            'java.lang.NullPointerException', 'java.lang.IndexOutOfBoundsException',
            'java.lang.ArrayIndexOutOfBoundsException', 'java.lang.StringIndexOutOfBoundsException',
            'java.lang.UnsupportedOperationException', 'java.lang.ClassCastException',
            // the clock only: see SYSTEM_METHODS
            'java.lang.System',
            // concurrent collections
            'java.util.concurrent.ConcurrentHashMap', 'java.util.concurrent.ConcurrentLinkedQueue',
            'java.util.concurrent.ConcurrentLinkedDeque', 'java.util.concurrent.CopyOnWriteArrayList',
            'java.util.concurrent.CopyOnWriteArraySet', 'java.util.concurrent.ConcurrentSkipListMap',
            'java.util.concurrent.ConcurrentSkipListSet', 'java.util.concurrent.TimeUnit',
            // single classes from packages that are otherwise closed
            'java.net.URI', 'java.net.URLEncoder', 'java.net.URLDecoder', 'java.security.MessageDigest',
            // Groovy
            'groovy.lang.Closure', 'groovy.lang.GString', 'groovy.lang.Range', 'groovy.lang.IntRange',
            'groovy.lang.ObjectRange', 'groovy.lang.NumberRange', 'groovy.lang.EmptyRange',
            'groovy.lang.Tuple', 'groovy.lang.Tuple2', 'groovy.lang.Tuple3',
            'groovy.util.Node', 'groovy.util.NodeList', 'groovy.util.Expando',
            'groovy.transform.Field',
            // this library
            'org.softwood.promise.Promise', 'org.softwood.promise.Promises', 'org.softwood.gstream.Gstream'
    ]))

    /** The only members of java.lang.System a script may use. */
    static final Set<String> SYSTEM_METHODS = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'currentTimeMillis', 'nanoTime', 'lineSeparator']))

    /**
     * Method names a script may not call on anything, itself included. They are the ways out through
     * every object (its class, its meta class, dynamic invocation), through the script (evaluating
     * more text, its binding), through a closure's delegate, and a few by-name escapes.
     */
    static final Set<String> DENIED_METHODS = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'getClass', 'getMetaClass', 'setMetaClass', 'metaClass', 'invokeMethod', 'getProperty', 'setProperty',
            'getProperties', 'getMetaPropertyValues', 'wait', 'notify', 'notifyAll', 'finalize',
            'with', 'tap', 'use', 'identity', 'mixin', 'addShutdownHook',
            'evaluate', 'run', 'getBinding', 'setBinding',
            'setDelegate', 'rehydrate', 'setResolveStrategy', 'setDirective',
            'execute', 'toURL', 'newInstance', 'forName', 'getClassLoader', 'loadClass', 'defineClass',
            'getInteger', 'getLong', 'getBoolean'
    ]))

    /** Property and variable names a script may not read or write on anything, itself included. */
    static final Set<String> DENIED_PROPERTIES = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'class', 'metaClass', 'classLoader', 'declaringClass', 'protectionDomain',
            'properties', 'metaPropertyValues', 'binding'
    ]))

    /** The only annotation a script may carry, by the name written. */
    static final Set<String> ALLOWED_ANNOTATIONS = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'Field', 'groovy.transform.Field']))

    /** True when a script may name this class. Array types are judged by their element type. */
    static boolean allowedClassName(String name) {
        if (name == null) return false
        String n = name
        while (n.endsWith('[]')) n = n.substring(0, n.length() - 2)
        while (n.startsWith('[')) {                                  // JVM array names: [Ljava.lang.String;
            n = n.substring(1)
            if (n.startsWith('L') && n.endsWith(';')) n = n.substring(1, n.length() - 1)
            else if (n.length() == 1) return true                    // a primitive array
        }
        if (ALLOWED_CLASSES.contains(n)) return true
        if (DENIED_CLASSES.contains(n)) return false
        int inner = n.indexOf('$')
        String outer = inner > 0 ? n.substring(0, inner) : n
        if (DENIED_CLASSES.contains(outer)) return false
        if (ALLOWED_CLASSES.contains(outer)) return true
        int dot = outer.lastIndexOf('.')
        return dot > 0 && ALLOWED_PACKAGES.contains(outer.substring(0, dot))
    }
}
