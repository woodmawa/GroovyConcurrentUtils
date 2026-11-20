package org.softwood.dag.script

import groovy.transform.CompileStatic
import groovy.transform.ThreadInterrupt
import org.codehaus.groovy.ast.ASTNode
import org.codehaus.groovy.ast.AnnotatedNode
import org.codehaus.groovy.ast.AnnotationNode
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassCodeVisitorSupport
import org.codehaus.groovy.ast.ClassHelper
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.DynamicVariable
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.ImportNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.expr.ArgumentListExpression
import org.codehaus.groovy.ast.expr.ArrayExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.EmptyExpression
import org.codehaus.groovy.ast.expr.SpreadExpression
import org.codehaus.groovy.ast.expr.SpreadMapExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.MethodPointerExpression
import org.codehaus.groovy.ast.expr.PostfixExpression
import org.codehaus.groovy.ast.expr.PrefixExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.CatchStatement
import org.codehaus.groovy.ast.stmt.ForStatement
import org.codehaus.groovy.ast.stmt.SynchronizedStatement
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilationFailedException
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.MultipleCompilationErrorsException
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.control.messages.ExceptionMessage
import org.codehaus.groovy.control.messages.SyntaxErrorMessage
import org.codehaus.groovy.syntax.Types

import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 1.4.0 (decision D2): compiles a {@code ScriptTask} script under the script profile, level 1.
 *
 * <p>Unlike a rule, a script has statements, loops and closures, so it is compiled to a class. Three
 * things happen on the way:</p>
 * <ol>
 *   <li><b>Before anything is resolved or transformed</b>: the only annotation accepted is
 *       {@code @Field}; no class definitions, no static imports, no import aliases, no import of a
 *       class outside the profile. This is what keeps {@code @Grab} and {@code @ASTTest} from ever
 *       being acted on.</li>
 *   <li><b>After class names are resolved</b>: every class the script names must be in
 *       {@link ScriptProfile}; no method or property name from its deny lists; no computed method
 *       or property name; no method pointer, {@code .@}, {@code super} or {@code synchronized}; a
 *       class is used only through its public static members. Then the receiver of every call,
 *       property access, index and {@code <<} is wrapped in a {@link ScriptGuard} check, which
 *       looks at the object actually reached when the script runs.</li>
 *   <li>An interrupt check is added to every loop pass, method and closure, throwing
 *       {@link ScriptInterruptedError}, which a script cannot catch.</li>
 * </ol>
 *
 * <p>The checks are this class's own compilation customizers. It is a reduction, not isolation:
 * a script can call any public method of the objects the flow puts in its bindings, and anything
 * that reaches an object of a type no list names can use it.</p>
 */
@CompileStatic
final class ScriptCompiler {

    private ScriptCompiler() {}

    /**
     * @param source    the script
     * @param baseClass the script base class; its public methods are callable from the script by name
     * @param parent    the class loader the script's own loader delegates to
     * @throws ScriptCompilationException when the script does not compile or is outside the profile
     */
    static Class<? extends Script> compile(String source, Class<? extends Script> baseClass, ClassLoader parent) {
        if (source == null || source.trim().isEmpty()) {
            throw new ScriptCompilationException('is empty')
        }
        CompilerConfiguration config = new CompilerConfiguration()
        config.scriptBaseClass = (baseClass != null ? baseClass : Script).name
        config.disabledGlobalASTTransformations = new HashSet<String>(['groovy.grape.GrabAnnotationTransformation'])

        Map<String, Object> interrupt = new HashMap<String, Object>()
        interrupt.put('thrown', new ClassExpression(ClassHelper.make(ScriptInterruptedError)))
        config.addCompilationCustomizers(
                new EarlyCheck(),
                new ProfileCheck(),
                new ASTTransformationCustomizer(interrupt, ThreadInterrupt))

        GroovyClassLoader loader = new GroovyClassLoader(parent != null ? parent : ScriptCompiler.classLoader, config)
        try {
            return (Class<? extends Script>) loader.parseClass(source)
        } catch (ScriptCompilationException e) {
            throw e
        } catch (MultipleCompilationErrorsException e) {
            throw translate(e)
        } catch (CompilationFailedException e) {
            throw new ScriptCompilationException('does not compile: ' + oneLine(e.message))
        }
    }

    private static ScriptCompilationException translate(MultipleCompilationErrorsException e) {
        String syntax = null
        for (Object message : e.errorCollector.errors) {
            if (message instanceof ExceptionMessage) {
                Throwable t = ((ExceptionMessage) message).cause
                while (t != null) {
                    if (t instanceof ScriptCompilationException) return (ScriptCompilationException) t
                    t = t.cause
                }
            } else if (message instanceof SyntaxErrorMessage && syntax == null) {
                syntax = ((SyntaxErrorMessage) message).cause?.message
            }
        }
        return new ScriptCompilationException('does not compile: ' + oneLine(syntax != null ? syntax : e.message))
    }

    private static String oneLine(String text) {
        if (text == null) return 'no detail'
        String flat = text.replaceAll('\\s+', ' ').trim()
        return flat.length() <= 240 ? flat : flat.substring(0, 237) + '...'
    }

    private static ScriptCompilationException refused(String what, ASTNode at) {
        String where = at != null && at.lineNumber > 0 ? " (line ${at.lineNumber})" : ''
        return new ScriptCompilationException("uses ${what}: not allowed in a script${where}".toString())
    }

    // ------------------------------------------------------------------ 1: before resolution

    private static final class EarlyCheck extends CompilationCustomizer {

        EarlyCheck() { super(CompilePhase.CONVERSION) }

        @Override
        void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            if (!classNode.isScript()) {
                throw refused('a class definition', classNode)
            }
            ModuleNode module = source.AST
            if (module.staticImports || module.staticStarImports) {
                throw refused('a static import', null)
            }
            for (ImportNode i : module.imports) {
                String name = i.className
                String simple = name.substring(name.lastIndexOf('.') + 1)
                if (i.alias != null && i.alias != simple) throw refused('an import alias', i)
                if (!ScriptProfile.allowedClassName(name)) throw refused('the class ' + name, i)
                annotations(i)
            }
            for (ImportNode i : module.starImports) {
                String pkg = i.packageName
                if (pkg.endsWith('.')) pkg = pkg.substring(0, pkg.length() - 1)
                if (!ScriptProfile.ALLOWED_PACKAGES.contains(pkg)) throw refused('the package ' + pkg, i)
                annotations(i)
            }
            if (module.package != null) annotations(module.package)
            for (MethodNode m : module.methods) {
                if (m.name == 'main' || m.name == 'run') throw refused('a method named ' + m.name, m)
            }

            ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() { return source }

                @Override
                void visitAnnotations(AnnotatedNode node) {
                    annotations(node)
                    super.visitAnnotations(node)
                }
            }
            // Only what the script wrote: its statements and its methods. The class's own generated
            // members (constructors, main) carry the compiler's annotations and are not the script's.
            annotations(classNode)
            if (module.statementBlock != null) module.statementBlock.visit(visitor)
            for (MethodNode m : module.methods) {
                visitor.visitMethod(m)
            }
        }

        private static void annotations(AnnotatedNode node) {
            for (AnnotationNode a : node.annotations) {
                // The compiler itself marks the script class with @BaseScript when a script base class
                // is configured. A script cannot annotate its own class: where a script can write
                // @BaseScript (a declaration, an import, the package) it is refused like any other.
                if (node instanceof ClassNode && a.classNode.name == 'groovy.transform.BaseScript') continue
                if (!ScriptProfile.ALLOWED_ANNOTATIONS.contains(a.classNode.name)) {
                    throw refused('the annotation @' + a.classNode.name, a)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 2: after resolution

    private static final class ProfileCheck extends CompilationCustomizer {

        ProfileCheck() { super(CompilePhase.CANONICALIZATION) }

        @Override
        void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
            if (!classNode.isScript()) {
                throw refused('a class definition', classNode)
            }
            new Rewriter(source, classNode).rewrite()
        }
    }

    private static final class Rewriter extends ClassCodeExpressionTransformer {

        private static final ClassNode GUARD = ClassHelper.make(ScriptGuard)

        private final SourceUnit source
        private final ClassNode script

        Rewriter(SourceUnit source, ClassNode script) {
            this.source = source
            this.script = script
        }

        @Override
        protected SourceUnit getSourceUnit() { return source }

        void rewrite() {
            for (FieldNode f : script.fields) {
                requireType(f.type, f)
                visitField(f)
            }
            for (MethodNode m : new ArrayList<MethodNode>(script.methods)) {
                if (m.name == 'main' && m.isStatic()) continue       // generated: it starts the script
                requireType(m.returnType, m)
                parameters(m.parameters, m)
                if (m.code != null) m.code.visit(this)
            }
        }

        private void parameters(Parameter[] parameters, ASTNode at) {
            if (parameters == null) return
            for (Parameter p : parameters) {
                requireType(p.type, at)
                if (p.hasInitialExpression()) p.setInitialExpression(transform(p.initialExpression))
            }
        }

        // ---- statements that need a look

        @Override
        void visitCatchStatement(CatchStatement statement) {
            requireType(statement.variable.type, statement)
            super.visitCatchStatement(statement)
        }

        @Override
        void visitForLoop(ForStatement loop) {
            if (loop.variable != null) requireType(loop.variable.type, loop)
            Expression over = loop.collectionExpression
            if (over != null && !(over instanceof ClosureListExpression) && !(over instanceof EmptyExpression)) {
                // for (x in something): what is iterated is checked first
                loop.setCollectionExpression(passable(transform(over), 'a for loop'))
                if (loop.loopBlock != null) loop.loopBlock.visit(this)
            } else {
                super.visitForLoop(loop)
            }
        }

        @Override
        void visitSynchronizedStatement(SynchronizedStatement statement) {
            throw refused('synchronized', statement)
        }

        // ---- expressions

        @Override
        Expression transform(Expression e) {
            if (e == null) return null
            if (e instanceof ClassExpression) {
                requireType(e.type, e)
                return e
            }
            if (e instanceof ConstructorCallExpression) {
                ConstructorCallExpression c = (ConstructorCallExpression) e
                if (c.isSpecialCall()) throw refused('a constructor call on this or super', e)
                requireType(c.type, e)
                return e.transformExpression(this)
            }
            if (e instanceof MethodPointerExpression) {
                throw refused('a method pointer or method reference', e)
            }
            if (e instanceof AttributeExpression) {
                throw refused('direct field access (.@)', e)
            }
            if (e instanceof PropertyExpression) {
                return property((PropertyExpression) e, false)
            }
            if (e instanceof MethodCallExpression) {
                return call((MethodCallExpression) e)
            }
            if (e instanceof StaticMethodCallExpression) {
                StaticMethodCallExpression s = (StaticMethodCallExpression) e
                if (ScriptProfile.DENIED_METHODS.contains(s.method)) throw refused('the method ' + s.method + '()', e)
                if (s.ownerType != script) {
                    requireType(s.ownerType, e)
                    requireStatic(s.ownerType, s.method, true, e)
                }
                return e.transformExpression(this)
            }
            if (e instanceof VariableExpression) {
                VariableExpression v = (VariableExpression) e
                if (v.isSuperExpression()) throw refused('super', e)
                if (!v.isThisExpression()) {
                    if (v.accessedVariable instanceof DynamicVariable && ScriptProfile.DENIED_PROPERTIES.contains(v.name)) {
                        throw refused("the name '" + v.name + "'", e)
                    }
                    requireType(v.originType, e)
                }
                return v
            }
            if (e instanceof CastExpression) {
                CastExpression c = (CastExpression) e
                requireType(c.type, e)
                CastExpression n = new CastExpression(c.type, passable(transform(c.expression), 'a cast'), c.isIgnoringAutoboxing())
                n.setCoerce(c.isCoerce())
                n.setStrict(c.isStrict())
                n.setSourcePosition(c)
                return n
            }
            if (e instanceof SpreadExpression) {
                SpreadExpression n = new SpreadExpression(passable(transform(((SpreadExpression) e).expression), 'the spread operator'))
                n.setSourcePosition(e)
                return n
            }
            if (e instanceof SpreadMapExpression) {
                SpreadMapExpression n = new SpreadMapExpression(passable(transform(((SpreadMapExpression) e).expression), 'the spread operator'))
                n.setSourcePosition(e)
                return n
            }
            if (e instanceof DeclarationExpression && ((DeclarationExpression) e).isMultipleAssignmentDeclaration()) {
                DeclarationExpression d = (DeclarationExpression) e
                DeclarationExpression n = new DeclarationExpression(transform(d.leftExpression), d.operation,
                        passable(transform(d.rightExpression), 'a multiple assignment'))
                n.setSourcePosition(d)
                return n
            }
            if (e instanceof ArrayExpression) {
                requireType(((ArrayExpression) e).elementType, e)
                return e.transformExpression(this)
            }
            if (e instanceof ClosureExpression) {
                ClosureExpression c = (ClosureExpression) e
                parameters(c.parameters, e)
                if (c.code != null) c.code.visit(this)
                return c
            }
            if (e instanceof PostfixExpression) {
                PostfixExpression p = (PostfixExpression) e
                PostfixExpression n = new PostfixExpression(left(p.expression), p.operation)
                n.setSourcePosition(p)
                return n
            }
            if (e instanceof PrefixExpression) {
                PrefixExpression p = (PrefixExpression) e
                PrefixExpression n = new PrefixExpression(p.operation, left(p.expression))
                n.setSourcePosition(p)
                return n
            }
            if (e instanceof BinaryExpression && e.getClass() == BinaryExpression) {
                return binary((BinaryExpression) e)
            }
            return e.transformExpression(this)
        }

        /** An expression that is assigned to. */
        private Expression left(Expression e) {
            if (e instanceof PropertyExpression && !(e instanceof AttributeExpression)) {
                return property((PropertyExpression) e, true)
            }
            return transform(e)
        }

        private Expression binary(BinaryExpression b) {
            String op = b.operation.text
            BinaryExpression n
            if (b.operation.type == Types.LEFT_SQUARE_BRACKET) {
                n = new BinaryExpression(guard('indexable', transform(b.leftExpression)), b.operation, transform(b.rightExpression))
                n.setSafe(b.isSafe())
            } else if (Types.ofType(b.operation.type, Types.ASSIGNMENT_OPERATOR)) {
                if (op == '<<=' || op == '>>=' || op == '>>>=') throw refused("the operator '" + op + "'", b)
                Expression value = transform(b.rightExpression)
                if (b.leftExpression instanceof TupleExpression) value = passable(value, 'a multiple assignment')
                n = new BinaryExpression(left(b.leftExpression), b.operation, value)
            } else if (op == '<<' || op == '>>' || op == '>>>' || op == '|') {
                n = new BinaryExpression(
                        guard('checkedOp', transform(b.leftExpression), new ConstantExpression(op)),
                        b.operation, transform(b.rightExpression))
            } else {
                n = new BinaryExpression(transform(b.leftExpression), b.operation, transform(b.rightExpression))
            }
            n.setSourcePosition(b)
            return n
        }

        private Expression property(PropertyExpression p, boolean write) {
            String name = constantName(p.property, 'property', p)
            if (ScriptProfile.DENIED_PROPERTIES.contains(name)) throw refused("the property '" + name + "'", p)
            Expression object = p.objectExpression

            if (object instanceof ClassExpression) {
                requireType(object.type, p)
                if (write) throw refused('assignment to a static member', p)
                requireStatic(object.type, name, false, p)
                return p
            }
            if (object instanceof VariableExpression && ((VariableExpression) object).isSuperExpression()) {
                throw refused('super', p)
            }
            if (object instanceof VariableExpression && ((VariableExpression) object).isThisExpression()) {
                return p                                              // the script's own: a binding variable
            }
            Expression checked = write ?
                    guard('checkedSet', transform(object), new ConstantExpression(name)) :
                    guard('checkedGet', transform(object), new ConstantExpression(name), new ConstantExpression(p.isSpreadSafe(), true))
            PropertyExpression n = new PropertyExpression(checked, p.property, p.isSafe())
            n.setSpreadSafe(p.isSpreadSafe())
            n.setSourcePosition(p)
            return n
        }

        private Expression call(MethodCallExpression m) {
            String name = constantName(m.method, 'method', m)
            if (ScriptProfile.DENIED_METHODS.contains(name)) throw refused('the method ' + name + '()', m)
            Expression object = m.objectExpression
            Expression receiver

            if (object instanceof ClassExpression) {
                requireType(object.type, m)
                requireStatic(object.type, name, true, m)
                receiver = object
            } else if (object instanceof VariableExpression && ((VariableExpression) object).isSuperExpression()) {
                throw refused('super', m)
            } else if (object instanceof VariableExpression && ((VariableExpression) object).isThisExpression()) {
                receiver = object                                     // the script's own methods, its base class's, a bound closure
            } else {
                receiver = guard('checked', transform(object), new ConstantExpression(name),
                        new ConstantExpression(m.isSpreadSafe(), true))
            }
            MethodCallExpression n = new MethodCallExpression(receiver, m.method, transform(m.arguments))
            n.setSafe(m.isSafe())
            n.setSpreadSafe(m.isSpreadSafe())
            n.setImplicitThis(m.isImplicitThis())
            n.setGenericsTypes(m.genericsTypes)
            n.setSourcePosition(m)
            return n
        }

        // ---- helpers

        private static String constantName(Expression e, String kind, ASTNode at) {
            if (e instanceof ConstantExpression && ((ConstantExpression) e).value instanceof String) {
                return (String) ((ConstantExpression) e).value
            }
            throw refused('a computed ' + kind + ' name', at)
        }

        private static Expression passable(Expression value, String what) {
            return guard('passable', value, new ConstantExpression(what))
        }

        private static Expression guard(String method, Expression... arguments) {
            return new StaticMethodCallExpression(GUARD, method, new ArgumentListExpression(arguments))
        }

        private void requireType(ClassNode type, ASTNode at) {
            if (type == null) return
            ClassNode t = type
            while (t.isArray()) t = t.componentType
            if (ClassHelper.isPrimitiveType(t) || t == script || t.name == 'java.lang.Object') return
            if (!ScriptProfile.allowedClassName(t.name)) {
                throw refused('the class ' + t.name, at)
            }
        }

        /** A class may be used only through a public static member it really has. */
        private static void requireStatic(ClassNode type, String name, boolean method, ASTNode at) {
            String simple = type.nameWithoutPackage
            if (type.name == 'java.lang.System') {
                if (method && ScriptProfile.SYSTEM_METHODS.contains(name)) return
                throw refused('System.' + name, at)
            }
            Class c = null
            try {
                c = type.typeClass
            } catch (Throwable ignored) {
                c = null
            }
            if (c != null) {
                if (method) {
                    for (Method m : c.methods) {
                        if (m.name == name && Modifier.isStatic(m.modifiers)) return
                    }
                } else {
                    for (Field f : c.fields) {
                        if (f.name == name && Modifier.isStatic(f.modifiers)) return
                    }
                    String cap = name.length() > 0 ? name.substring(0, 1).toUpperCase() + name.substring(1) : name
                    for (Method m : c.methods) {
                        if (Modifier.isStatic(m.modifiers) && m.parameterCount == 0 &&
                                (m.name == 'get' + cap || m.name == 'is' + cap)) return
                    }
                }
            }
            throw refused(simple + '.' + name + (method ? '()' : '') + ', which is not a public static member of ' + simple, at)
        }
    }
}
