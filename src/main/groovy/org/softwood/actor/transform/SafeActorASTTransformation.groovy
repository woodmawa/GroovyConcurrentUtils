// ═════════════════════════════════════════════════════════════
// SafeActorASTTransformation.groovy
// ═════════════════════════════════════════════════════════════
package org.softwood.actor.transform

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.transform.ASTTransformation
import org.codehaus.groovy.transform.GroovyASTTransformation

/**
 * AST transformation for \@SafeActor annotation.
 * 
 * <p>This transformation makes actor code safer by warning about
 * problematic patterns in Groovy closures.</p>
 * 
 * @since 1.0.0
 */
@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
class SafeActorASTTransformation implements ASTTransformation {
    
    private boolean warnAboutEach = true
    private boolean verbose = false
    
    @Override
    void visit(ASTNode[] nodes, SourceUnit source) {
        if (nodes.length != 2) return
        if (!(nodes[0] instanceof AnnotationNode)) return
        if (!(nodes[1] instanceof AnnotatedNode)) return
        
        AnnotationNode annotation = (AnnotationNode) nodes[0]
        AnnotatedNode annotated = (AnnotatedNode) nodes[1]
        
        // Read annotation parameters
        readParameters(annotation)
        
        if (verbose) {
            println "@SafeActor transformation starting on ${annotated.class.simpleName}"
        }
        
        // Apply transformations based on node type
        if (annotated instanceof ClassNode) {
            transformClass((ClassNode) annotated, source)
        } else if (annotated instanceof MethodNode) {
            transformMethod((MethodNode) annotated, source)
        }
    }
    
    private void readParameters(AnnotationNode annotation) {
        warnAboutEach = getMemberValue(annotation, 'warnAboutEach', true)
        verbose = getMemberValue(annotation, 'verbose', false)
    }
    
    private boolean getMemberValue(AnnotationNode annotation, String name, boolean defaultValue) {
        Expression expr = annotation.getMember(name)
        if (expr instanceof ConstantExpression) {
            return ((ConstantExpression) expr).value as boolean
        }
        return defaultValue
    }
    
    private void transformClass(ClassNode classNode, SourceUnit source) {
        // Transform all methods in the class
        classNode.methods.each { method ->
            transformMethod(method, source)
        }
    }
    
    private void transformMethod(MethodNode method, SourceUnit source) {
        if (method.code instanceof BlockStatement) {
            transformBlock((BlockStatement) method.code, source, 0)
        }
    }
    
    private void transformBlock(BlockStatement block, SourceUnit source, int depth) {
        block.statements.each { statement ->
            transformStatement(statement, source, depth)
        }
    }
    
    private void transformStatement(Statement statement, SourceUnit source, int depth) {
        if (statement instanceof ExpressionStatement) {
            transformExpression(((ExpressionStatement) statement).expression, source, depth)
        } else if (statement instanceof BlockStatement) {
            transformBlock((BlockStatement) statement, source, depth + 1)
        } else if (statement instanceof IfStatement) {
            IfStatement ifStmt = (IfStatement) statement
            transformStatement(ifStmt.ifBlock, source, depth)
            if (ifStmt.elseBlock) {
                transformStatement(ifStmt.elseBlock, source, depth)
            }
        } else if (statement instanceof WhileStatement) {
            transformStatement(((WhileStatement) statement).loopBlock, source, depth)
        } else if (statement instanceof ForStatement) {
            transformStatement(((ForStatement) statement).loopBlock, source, depth)
        }
    }
    
    private void transformExpression(Expression expr, SourceUnit source, int depth) {
        // Warn about .each usage
        if (warnAboutEach && expr instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) expr
            if (call.methodAsString == 'each' && depth > 2) {
                source.addWarning(
                    "@SafeActor: Consider using traditional for loops instead of .each in deeply nested actor code",
                    call.lineNumber,
                    call.columnNumber
                )
            }
            
            // Check for system.actor calls inside .each
            if (call.methodAsString == 'actor' && depth > 3) {
                source.addWarning(
                    "@SafeActor: Creating actors inside closures may cause scoping issues. Consider ctx.spawn() or ctx.spawnForEach()",
                    call.lineNumber,
                    call.columnNumber
                )
            }
        }
        
        // Recurse into closure expressions
        if (expr instanceof ClosureExpression) {
            ClosureExpression closure = (ClosureExpression) expr
            if (closure.code instanceof BlockStatement) {
                transformBlock((BlockStatement) closure.code, source, depth + 1)
            }
        }
        
        // Recurse into method calls
        if (expr instanceof MethodCallExpression) {
            MethodCallExpression call = (MethodCallExpression) expr
            if (call.arguments instanceof ArgumentListExpression) {
                ArgumentListExpression args = (ArgumentListExpression) call.arguments
                args.expressions.each { argExpr ->
                    transformExpression(argExpr, source, depth)
                }
            }
        }
    }
}
