package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.0: a script that the restricted compiler will not accept: it does not compile, or it uses
 * something outside the script profile. Raised when the script is compiled, which a TaskGraph does
 * while it is validated and before any task runs.
 */
@CompileStatic
class ScriptCompilationException extends IllegalArgumentException {

    ScriptCompilationException(String message) {
        super(message)
    }
}
