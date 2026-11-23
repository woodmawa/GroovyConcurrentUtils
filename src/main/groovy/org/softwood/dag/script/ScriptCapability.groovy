package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.1: an object the library binds into a restricted script when the graph grants a capability
 * ({@code files}, {@code http}, {@code sql}, {@code process}).
 *
 * <p>{@link ScriptGuard} lets a script call only the methods named by {@link #offeredMethods()} on
 * such an object, and refuses every property read or write, index and operator on it. Groovy lets a
 * caller read a private field through property syntax and call a private method by name, so without
 * this a script could reach what a capability holds: before 1.4.1 it could add a local service to
 * {@code http}'s allowance, or raise {@code files}' size limit.</p>
 */
@CompileStatic
interface ScriptCapability {

    /** The name the script knows the object by, for messages. */
    String capabilityName()

    /** The method names a script may call. */
    Set<String> offeredMethods()
}
