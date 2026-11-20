package org.softwood.dag.script

import groovy.transform.CompileStatic

/**
 * 1.4.0: a rule condition that the restricted compiler will not accept: it does not parse, or it
 * uses something outside the rule profile. Raised when the rule is compiled, which a TaskGraph does
 * while it is validated and before any task runs.
 */
@CompileStatic
class RuleCompilationException extends IllegalArgumentException {

    RuleCompilationException(String message) {
        super(message)
    }
}
