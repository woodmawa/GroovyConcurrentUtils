package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.0: thrown inside a restricted script when its thread has been interrupted: because the
 * script ran past its {@code scriptTimeout}, or because the graph was cancelled.
 *
 * <p>The restricted compiler puts an interrupt check at the start of every loop pass, method and
 * closure. It throws this, and it is an Error on purpose: a script may catch {@code Exception}
 * but may not name {@code Error} or {@code Throwable}, so a script cannot swallow its own
 * interruption and carry on.</p>
 */
@CompileStatic
class ScriptInterruptedError extends Error {

    ScriptInterruptedError(String message) {
        super(message)
    }
}
