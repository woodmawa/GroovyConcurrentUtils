package org.softwood.dag.script

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ModuleNode
import org.codehaus.groovy.ast.expr.AnnotationConstantExpression
import org.codehaus.groovy.ast.expr.AttributeExpression
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ClosureListExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.DeclarationExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NamedArgumentListExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.ExpressionStatement
import org.codehaus.groovy.ast.stmt.Statement
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.ErrorCollector
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.syntax.Types

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 1.4.0 (decision D2): the restricted compiler for text that a flow supplies and GCU evaluates.
 *
 * <p>This class holds the <b>rule profile</b>, used by {@code ControlTask}: a rule condition is one
 * expression over the names it is given ({@code input}, {@code globals}, {@code memory}).</p>
 *
 * <p>A rule is parsed to a syntax tree and checked against an allow-list; it is never compiled to a
 * class and never run by the Groovy runtime as a script. {@link RuleExpression} walks the tree and
 * performs every property read, index and method call itself, so each one is checked again against
 * the object actually reached. What the profile allows:</p>
 * <ul>
 *   <li>literals, list and map literals, strings with {@code ${}} values;</li>
 *   <li>{@code == != === !== < <= > >= <=>}, {@code && || !}, {@code + - * / % **}, {@code in}, {@code !in},
 *       {@code ==~}, {@code =~}, the conditional and Elvis operators;</li>
 *   <li>property and index access, with {@code ?.};</li>
 *   <li>the methods {@code size isEmpty contains containsKey startsWith endsWith matches toString
 *       toLowerCase toUpperCase trim}, and the functions and constants of {@code Math};</li>
 *   <li>a cast or {@code as} to a number type, {@code String} or {@code Boolean}.</li>
 * </ul>
 * <p>Everything else is refused when the rule is compiled: {@code new}, imports, closures, loops,
 * assignment, definitions, any other method, any other name, and the properties {@code class},
 * {@code metaClass} and their relatives.</p>
 *
 * <p><b>This is a reduction, not isolation.</b> A rule can still call the getters and
 * {@code toString()} of the objects it is handed, and a pattern given to {@code matches} can be slow.
 * Text from a party you do not trust belongs in a separate process.</p>
 */
@CompileStatic
final class RestrictedCompiler {

    /** The names a rule may read. */
    static final Set<String> RULE_VARIABLES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(['input', 'globals', 'memory']))

    /** The methods a rule may call on text, a list or a map ({@code toString} on anything it may read). */
    static final Set<String> RULE_METHODS = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'size', 'isEmpty', 'contains', 'containsKey', 'startsWith', 'endsWith', 'matches',
            'toString', 'toLowerCase', 'toUpperCase', 'trim']))

    /** Property names refused whatever the receiver, when the rule is compiled and again when it runs. */
    static final Set<String> DENIED_NAMES = Collections.unmodifiableSet(new LinkedHashSet<String>([
            'class', 'metaClass', 'classLoader', 'declaringClass', 'protectionDomain',
            'properties', 'metaPropertyValues']))

    /** Operators a rule may use between two operands. Index access is handled apart. */
    static final Set<String> RULE_OPERATORS = Collections.unmodifiableSet(new LinkedHashSet<String>([
            '==', '!=', '===', '!==', '<', '<=', '>', '>=', '<=>', '&&', '||',
            '+', '-', '*', '/', '%', '**', 'in', '!in', '==~', '=~']))

    /** The types a rule may cast to, by the name written in the rule. */
    static final Map<String, Class> CAST_TYPES = castTypes()

    /** Every public static method of java.lang.Math. */
    static final Set<String> MATH_FUNCTIONS = mathFunctions()

    static final Set<String> MATH_CONSTANTS = Collections.unmodifiableSet(new LinkedHashSet<String>(['PI', 'E']))

    /** A rule longer than this is refused: the tree walk is recursive. */
    static final int MAX_RULE_LENGTH = 2000

    private static final int MAX_CACHED_RULES = 2000

    private static final ConcurrentHashMap<String, RuleExpression> RULES = new ConcurrentHashMap<String, RuleExpression>()

    private RestrictedCompiler() {}

    /**
     * Compile a rule condition under the rule profile. The same text gives the same compiled rule:
     * a rule is parsed and checked once and then reused by every task and every run.
     *
     * @throws RuleCompilationException when the text does not parse or is outside the profile
     */
    static RuleExpression compileRule(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new RuleCompilationException('is empty')
        }
        RuleExpression cached = RULES.get(source)
        if (cached != null) return cached

        RuleExpression compiled = new RuleExpression(source, parseRule(source))
        if (RULES.size() >= MAX_CACHED_RULES) RULES.clear()
        RuleExpression earlier = RULES.putIfAbsent(source, compiled)
        return earlier != null ? earlier : compiled
    }

    /**
     * Compile a script under the script profile, level 1: the second profile of this compiler,
     * used by {@code ScriptTask}. See {@link ScriptCompiler} for what it allows and how it is held.
     *
     * @param baseClass the script base class; its public methods are callable from the script by name
     * @param parent    the class loader the script's own loader delegates to
     * @throws ScriptCompilationException when the script does not compile or is outside the profile
     */
    static Class<? extends Script> compileScript(String source, Class<? extends Script> baseClass, ClassLoader parent) {
        return ScriptCompiler.compile(source, baseClass, parent)
    }

    // ------------------------------------------------------------------ parsing

    private static Expression parseRule(String source) {
        if (source.length() > MAX_RULE_LENGTH) {
            throw new RuleCompilationException("is longer than ${MAX_RULE_LENGTH} characters".toString())
        }
        ModuleNode module = parse(source)

        if (module.imports || module.starImports || module.staticImports || module.staticStarImports) {
            throw new RuleCompilationException('uses an import: not allowed in a rule')
        }
        if (module.methods) {
            throw new RuleCompilationException('defines a method: not allowed in a rule')
        }
        for (ClassNode c : module.classes) {
            if (!c.isScript()) throw new RuleCompilationException('defines a class: not allowed in a rule')
        }
        List<Statement> statements = module.statementBlock.statements
        if (statements.size() != 1 || !(statements[0] instanceof ExpressionStatement)) {
            throw new RuleCompilationException('is not a single expression')
        }
        Expression root = ((ExpressionStatement) statements[0]).expression
        check(root)
        return root
    }

    /**
     * Source text to a syntax tree and no further: no resolution, no transformation, no class.
     * Annotations such as @Grab and @ASTTest are therefore never acted on.
     */
    private static ModuleNode parse(String source) {
        try {
            CompilerConfiguration config = new CompilerConfiguration()
            SourceUnit unit = new SourceUnit('rule', source, config,
                    new GroovyClassLoader(RestrictedCompiler.classLoader), new ErrorCollector(config))
            unit.parse()
            unit.completePhase()
            unit.convert()
            if (unit.errorCollector.hasErrors() || unit.AST == null) {
                throw new RuleCompilationException('does not parse')
            }
            return unit.AST
        } catch (RuleCompilationException e) {
            throw e
        } catch (Exception | AssertionError e) {
            throw new RuleCompilationException('does not parse')
        }
    }

    // ------------------------------------------------------------------ the allow-list

    private static void check(Expression e) {
        if (e instanceof AnnotationConstantExpression) {
            throw refused('an annotation')
        }
        if (e instanceof ConstantExpression) {
            Object v = ((ConstantExpression) e).value
            if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Character) return
            throw refused('a constant of type ' + v.getClass().name)
        }
        if (e instanceof VariableExpression) {
            String name = ((VariableExpression) e).name
            if (RULE_VARIABLES.contains(name)) return
            throw new RuleCompilationException(
                    ("reads '${name}': a rule is given only " + RULE_VARIABLES.join(', ')).toString())
        }
        if (e instanceof AttributeExpression) {
            throw refused('direct field access (.@)')
        }
        if (e instanceof PropertyExpression) {
            PropertyExpression p = (PropertyExpression) e
            if (p.spreadSafe) throw refused('the spread operator')
            String name = constantName(p.property)
            if (isMath(p.objectExpression)) {
                if (!MATH_CONSTANTS.contains(name)) throw refused('Math.' + name)
                return
            }
            if (DENIED_NAMES.contains(name)) throw refused("the property '" + name + "'")
            check(p.objectExpression)
            return
        }
        if (e instanceof MethodCallExpression) {
            MethodCallExpression m = (MethodCallExpression) e
            if (m.spreadSafe) throw refused('the spread operator')
            if (m.implicitThis) throw refused('a call with no receiver')
            String name = constantName(m.method)
            if (isMath(m.objectExpression)) {
                if (!MATH_FUNCTIONS.contains(name)) throw refused('Math.' + name + '()')
            } else {
                if (!RULE_METHODS.contains(name)) throw refused('the method ' + name + '()')
                check(m.objectExpression)
            }
            for (Expression arg : argumentsOf(m.arguments)) check(arg)
            return
        }
        if (e instanceof DeclarationExpression) {
            throw refused('a declaration')
        }
        if (e instanceof BinaryExpression) {
            BinaryExpression b = (BinaryExpression) e
            boolean index = b.operation.type == Types.LEFT_SQUARE_BRACKET
            if (!index && !RULE_OPERATORS.contains(b.operation.text)) {
                throw refused("the operator '" + b.operation.text + "'")
            }
            check(b.leftExpression)
            check(b.rightExpression)
            return
        }
        if (e instanceof TernaryExpression) {       // the Elvis operator is one of these
            TernaryExpression t = (TernaryExpression) e
            check(t.booleanExpression)
            check(t.trueExpression)
            check(t.falseExpression)
            return
        }
        if (e instanceof BooleanExpression) {       // so is 'not'
            check(((BooleanExpression) e).expression)
            return
        }
        if (e instanceof UnaryMinusExpression) {
            check(((UnaryMinusExpression) e).expression)
            return
        }
        if (e instanceof UnaryPlusExpression) {
            check(((UnaryPlusExpression) e).expression)
            return
        }
        if (e instanceof ClosureListExpression) {
            throw refused('a statement list')
        }
        if (e instanceof ListExpression) {
            for (Expression item : ((ListExpression) e).expressions) check(item)
            return
        }
        if (e instanceof NamedArgumentListExpression) {
            throw refused('named arguments')
        }
        if (e instanceof MapExpression) {
            for (MapEntryExpression entry : ((MapExpression) e).mapEntryExpressions) {
                check(entry.keyExpression)
                check(entry.valueExpression)
            }
            return
        }
        if (e instanceof GStringExpression) {
            for (Expression value : ((GStringExpression) e).values) check(value)
            return
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e
            if (!CAST_TYPES.containsKey(c.type.name)) throw refused('a cast to ' + c.type.name)
            check(c.expression)
            return
        }
        if (e instanceof ConstructorCallExpression) {
            throw refused("'new'")
        }
        if (e instanceof ClosureExpression) {
            throw refused('a closure')
        }
        throw refused(describe(e))
    }

    static boolean isMath(Expression e) {
        return e instanceof VariableExpression && ((VariableExpression) e).name == 'Math'
    }

    static String constantName(Expression e) {
        if (e instanceof ConstantExpression && ((ConstantExpression) e).value instanceof String) {
            return (String) ((ConstantExpression) e).value
        }
        throw refused('a computed property or method name')
    }

    static List<Expression> argumentsOf(Expression arguments) {
        List<Expression> list = arguments instanceof TupleExpression ?
                ((TupleExpression) arguments).expressions : [arguments]
        for (Expression arg : list) {
            if (arg instanceof NamedArgumentListExpression || arg instanceof MapEntryExpression) {
                throw refused('named arguments')
            }
        }
        return list
    }

    private static String describe(Expression e) {
        String name = e.getClass().simpleName
        return name.endsWith('Expression') ? name.substring(0, name.length() - 'Expression'.length()) : name
    }

    private static RuleCompilationException refused(String what) {
        return new RuleCompilationException('uses ' + what + ': not allowed in a rule')
    }

    // ------------------------------------------------------------------ tables

    private static Map<String, Class> castTypes() {
        Map<String, Class> types = new LinkedHashMap<String, Class>()
        types.put('int', Integer); types.put('Integer', Integer); types.put('java.lang.Integer', Integer)
        types.put('long', Long); types.put('Long', Long); types.put('java.lang.Long', Long)
        types.put('double', Double); types.put('Double', Double); types.put('java.lang.Double', Double)
        types.put('float', Float); types.put('Float', Float); types.put('java.lang.Float', Float)
        types.put('boolean', Boolean); types.put('Boolean', Boolean); types.put('java.lang.Boolean', Boolean)
        types.put('BigDecimal', BigDecimal); types.put('java.math.BigDecimal', BigDecimal)
        types.put('BigInteger', BigInteger); types.put('java.math.BigInteger', BigInteger)
        types.put('String', String); types.put('java.lang.String', String)
        return Collections.unmodifiableMap(types)
    }

    private static Set<String> mathFunctions() {
        Set<String> names = new TreeSet<String>()
        for (Method m : Math.getMethods()) {
            if (Modifier.isStatic(m.modifiers)) names.add(m.name)
        }
        return Collections.unmodifiableSet(names)
    }
}
