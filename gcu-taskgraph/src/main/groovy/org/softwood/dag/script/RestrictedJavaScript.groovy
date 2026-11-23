package org.softwood.dag.script

import org.openjdk.nashorn.api.scripting.ClassFilter
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory

import javax.script.ScriptEngine

/**
 * 1.4.0 (decision D2): the JavaScript engine a restricted {@code ScriptTask} uses.
 *
 * <p>Nashorn, started with no Java access: {@code --no-java} removes {@code Java}, {@code Packages}
 * and their relatives, and a class filter that exposes no class at all backs it up and, in Nashorn,
 * also switches off reflection from script code. A script works with the values in its bindings and
 * with JavaScript's own objects.</p>
 *
 * <p>Kept in a class of its own so that nothing else in the library needs Nashorn on the class path.
 * A new engine is made for every run: engines are not shared between tasks or runs.</p>
 */
class RestrictedJavaScript {

    /** The language names that mean JavaScript. */
    static final Set<String> NAMES = ['javascript', 'js', 'ecmascript', 'nashorn'] as Set<String>

    static ScriptEngine newEngine(ClassLoader loader) {
        ClassFilter nothing = { String className -> false } as ClassFilter
        return new NashornScriptEngineFactory().getScriptEngine(['--no-java'] as String[], loader, nothing)
    }
}
