package org.softwood.dag.task

/**
 * Classifies the side-effect profile of a task, used to determine
 * whether automatic retry is safe.
 *
 * <ul>
 *   <li><b>PURE</b>       – No observable side effects; safe to retry unconditionally.</li>
 *   <li><b>IDEMPOTENT</b> – Has side effects but produces the same outcome when repeated
 *                           with the same inputs; safe to retry.</li>
 *   <li><b>NON_IDEMPOTENT</b> – Side effects accumulate or differ on repetition;
 *                               escalate to human before retry.</li>
 * </ul>
 *
 * Declared per-task via {@code effectClass} property; derived for scopes
 * by taking the most conservative value across member tasks.
 */
enum EffectClass {
    PURE,
    IDEMPOTENT,
    NON_IDEMPOTENT

    /**
     * Returns the more conservative of two EffectClass values.
     * Used when deriving a scope's effect class from its member tasks.
     */
    static EffectClass mostConservative(EffectClass a, EffectClass b) {
        // Ordinal order: PURE(0) < IDEMPOTENT(1) < NON_IDEMPOTENT(2)
        a.ordinal() >= b.ordinal() ? a : b
    }
}
