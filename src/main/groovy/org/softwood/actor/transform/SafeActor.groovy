// ═════════════════════════════════════════════════════════════
// SafeActor.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor.transform

import org.codehaus.groovy.transform.GroovyASTTransformationClass

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * Annotation for safer actor code with Groovy closures.
 * 
 * <p>This annotation applies AST transformations to help avoid common
 * Groovy closure pitfalls in actor code. It provides automatic workarounds
 * for issues that occur in deeply nested closures.</p>
 * 
 * <h2>What it Does</h2>
 * <ul>
 *   <li>Warns about .each {} usage in actor creation</li>
 *   <li>Suggests ctx.spawnForEach() as alternative</li>
 * </ul>
 * 
 * <h2>Example</h2>
 * <pre>
 * \@SafeActor
 * class FileScanner {
 *     def scan(File dir, ActorSystem system) {
 *         def coordinator = system.actor {
 *             onMessage { msg, ctx ->
 *                 // Warnings shown for problematic patterns
 *             }
 *         }
 *     }
 * }
 * </pre>
 * 
 * <h2>When to Use</h2>
 * <ul>
 *   <li>On classes that create actors with nested closures</li>
 *   <li>On scripts that use complex actor patterns</li>
 *   <li>When you want automatic warnings for problematic patterns</li>
 * </ul>
 * 
 * @since 1.0.0
 */
@Retention(RetentionPolicy.SOURCE)
@Target([ElementType.TYPE, ElementType.METHOD])
@GroovyASTTransformationClass(classes = [SafeActorASTTransformation])
@interface SafeActor {
    /**
     * Whether to warn about .each usage in actor creation.
     * @return true to enable (default)
     */
    boolean warnAboutEach() default true
    
    /**
     * Whether to add logging of transformations.
     * @return true to enable
     */
    boolean verbose() default false
}
