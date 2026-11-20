package org.softwood.dag.script

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.expr.BinaryExpression
import org.codehaus.groovy.ast.expr.BooleanExpression
import org.codehaus.groovy.ast.expr.CastExpression
import org.codehaus.groovy.ast.expr.ConstantExpression
import org.codehaus.groovy.ast.expr.ElvisOperatorExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.GStringExpression
import org.codehaus.groovy.ast.expr.ListExpression
import org.codehaus.groovy.ast.expr.MapEntryExpression
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.NotExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.TernaryExpression
import org.codehaus.groovy.ast.expr.UnaryMinusExpression
import org.codehaus.groovy.ast.expr.UnaryPlusExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.runtime.InvokerHelper
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation
import org.codehaus.groovy.syntax.Types

/**
 * 1.4.0 (decision D2): a compiled rule condition. Made by {@link RestrictedCompiler#compileRule}.
 *
 * <p>Holds the checked syntax tree of one expression and evaluates it by walking that tree. No class
 * is generated and the tree is only read, so one instance is shared by every task and thread that
 * uses the same rule text.</p>
 *
 * <p>The walk does each property read, index and method call itself, and checks the object reached:</p>
 * <ul>
 *   <li>a property of a map is the entry of that name, never a method of the map;</li>
 *   <li>a property of a list is that property of each element;</li>
 *   <li>a property of any other object is its getter, refused by name for {@code class},
 *       {@code metaClass} and their relatives, and refused by type for classes, class loaders,
 *       reflection, files, paths, URLs, processes, threads, closures, scripts, anything closeable,
 *       and the task graph's own context;</li>
 *   <li>a method is one of the fixed list, on text, a list, a map or an array; {@code toString()}
 *       also on any object whose properties may be read.</li>
 * </ul>
 * <p>A refusal while running is a {@link SecurityException}. Any other failure (a null receiver,
 * operands of the wrong kind) is thrown to the caller: {@code ControlTask} fails the node.</p>
 */
@CompileStatic
final class RuleExpression {

    /** The rule as written. */
    final String source

    private final Expression root

    RuleExpression(String source, Expression root) {
        this.source = source
        this.root = root
    }

    /** Evaluate against the given names and read the result as true or false, by Groovy's rules. */
    boolean test(Map<String, Object> variables) {
        return truth(evaluate(variables))
    }

    /** Evaluate against the given names: {@code input}, {@code globals}, {@code memory}. */
    Object evaluate(Map<String, Object> variables) {
        return eval(root, variables != null ? variables : Collections.<String, Object> emptyMap())
    }

    @Override
    String toString() {
        return "RuleExpression[${source}]".toString()
    }

    // ------------------------------------------------------------------ the walk

    private static Object eval(Expression e, Map<String, Object> vars) {
        if (e instanceof ConstantExpression) {
            return ((ConstantExpression) e).value
        }
        if (e instanceof VariableExpression) {
            return vars.get(((VariableExpression) e).name)
        }
        if (e instanceof PropertyExpression) {
            PropertyExpression p = (PropertyExpression) e
            String name = RestrictedCompiler.constantName(p.property)
            if (RestrictedCompiler.isMath(p.objectExpression)) {
                return name == 'PI' ? (Object) Math.PI : (Object) Math.E
            }
            return property(eval(p.objectExpression, vars), name, p.safe)
        }
        if (e instanceof MethodCallExpression) {
            MethodCallExpression m = (MethodCallExpression) e
            String name = RestrictedCompiler.constantName(m.method)
            List<Expression> argExpressions = RestrictedCompiler.argumentsOf(m.arguments)
            if (RestrictedCompiler.isMath(m.objectExpression)) {
                return InvokerHelper.invokeStaticMethod(Math, name, evalAll(argExpressions, vars))
            }
            Object receiver = eval(m.objectExpression, vars)
            if (receiver == null && m.safe) return null
            return call(receiver, name, evalAll(argExpressions, vars))
        }
        if (e instanceof BinaryExpression) {
            return binary((BinaryExpression) e, vars)
        }
        if (e instanceof ElvisOperatorExpression) {
            ElvisOperatorExpression elvis = (ElvisOperatorExpression) e
            Object value = eval(elvis.trueExpression, vars)
            return truth(value) ? value : eval(elvis.falseExpression, vars)
        }
        if (e instanceof TernaryExpression) {
            TernaryExpression t = (TernaryExpression) e
            return truth(eval(t.booleanExpression, vars)) ? eval(t.trueExpression, vars) : eval(t.falseExpression, vars)
        }
        if (e instanceof NotExpression) {
            return !truth(eval(((NotExpression) e).expression, vars))
        }
        if (e instanceof BooleanExpression) {
            return eval(((BooleanExpression) e).expression, vars)
        }
        if (e instanceof UnaryMinusExpression) {
            return ScriptBytecodeAdapter.unaryMinus(number(eval(((UnaryMinusExpression) e).expression, vars), '-'))
        }
        if (e instanceof UnaryPlusExpression) {
            return number(eval(((UnaryPlusExpression) e).expression, vars), '+')
        }
        if (e instanceof ListExpression) {
            List<Object> list = new ArrayList<Object>()
            for (Expression item : ((ListExpression) e).expressions) list.add(eval(item, vars))
            return list
        }
        if (e instanceof MapExpression) {
            Map<Object, Object> map = new LinkedHashMap<Object, Object>()
            for (MapEntryExpression entry : ((MapExpression) e).mapEntryExpressions) {
                map.put(eval(entry.keyExpression, vars), eval(entry.valueExpression, vars))
            }
            return map
        }
        if (e instanceof GStringExpression) {
            GStringExpression g = (GStringExpression) e
            StringBuilder text = new StringBuilder()
            List<ConstantExpression> strings = g.strings
            List<Expression> values = g.values
            for (int i = 0; i < strings.size(); i++) {
                text.append(String.valueOf(strings[i].value))
                if (i < values.size()) text.append(InvokerHelper.toString(eval(values[i], vars)))
            }
            return text.toString()
        }
        if (e instanceof CastExpression) {
            CastExpression c = (CastExpression) e
            Class type = RestrictedCompiler.CAST_TYPES.get(c.type.name)
            if (type == null) throw new SecurityException("a cast to ${c.type.name} is not allowed in a rule".toString())
            Object value = eval(c.expression, vars)
            return c.coerce ? ScriptBytecodeAdapter.asType(value, type) : ScriptBytecodeAdapter.castToType(value, type)
        }
        // RestrictedCompiler.check refuses everything else, so this is not reached for a compiled rule.
        throw new SecurityException("${e.getClass().simpleName} is not allowed in a rule".toString())
    }

    private static Object[] evalAll(List<Expression> expressions, Map<String, Object> vars) {
        Object[] values = new Object[expressions.size()]
        for (int i = 0; i < values.length; i++) values[i] = eval(expressions[i], vars)
        return values
    }

    private static Object binary(BinaryExpression b, Map<String, Object> vars) {
        if (b.operation.type == Types.LEFT_SQUARE_BRACKET) {
            Object receiver = eval(b.leftExpression, vars)
            if (receiver == null && b.safe) return null
            return index(receiver, eval(b.rightExpression, vars))
        }
        String op = b.operation.text
        if (op == '&&') return truth(eval(b.leftExpression, vars)) && truth(eval(b.rightExpression, vars))
        if (op == '||') return truth(eval(b.leftExpression, vars)) || truth(eval(b.rightExpression, vars))

        Object left = eval(b.leftExpression, vars)
        Object right = eval(b.rightExpression, vars)
        switch (op) {
            case '==':  return ScriptBytecodeAdapter.compareEqual(left, right)
            case '!=':  return ScriptBytecodeAdapter.compareNotEqual(left, right)
            case '===': return left.is(right)
            case '!==': return !left.is(right)
            case '<':   return ScriptBytecodeAdapter.compareLessThan(left, right)
            case '<=':  return ScriptBytecodeAdapter.compareLessThanEqual(left, right)
            case '>':   return ScriptBytecodeAdapter.compareGreaterThan(left, right)
            case '>=':  return ScriptBytecodeAdapter.compareGreaterThanEqual(left, right)
            case '<=>': return ScriptBytecodeAdapter.compareTo(left, right)
            case '+':   return arithmetic('plus', op, left, right)
            case '-':   return arithmetic('minus', op, left, right)
            case '*':   return arithmetic('multiply', op, left, right)
            case '/':   return arithmetic('div', op, left, right)
            case '%':   return arithmetic('mod', op, left, right)
            case '**':  return arithmetic('power', op, left, right)
            case 'in':  return isIn(left, right)
            case '!in': return !isIn(left, right)
            case '==~': return ScriptBytecodeAdapter.matchRegex(text(left, op), text(right, op))
            case '=~':  return ScriptBytecodeAdapter.findRegex(text(left, op), text(right, op))   // a Matcher, true when it finds
        }
        throw new SecurityException("the operator '${op}' is not allowed in a rule".toString())
    }

    // ------------------------------------------------------------------ checked operations

    private static Object property(Object receiver, String name, boolean safe) {
        if (receiver == null) {
            if (safe) return null
            throw new NullPointerException("cannot read '${name}' of null".toString())
        }
        if (receiver instanceof Map) {
            return ((Map) receiver).get(name)
        }
        if (RestrictedCompiler.DENIED_NAMES.contains(name)) {
            throw new SecurityException("a rule cannot read '${name}'".toString())
        }
        if (receiver instanceof Collection) {
            // As Groovy does: a property the collection itself has (empty, first, last) wins;
            // any other name is read from each element.
            if (InvokerHelper.getMetaClass(receiver).hasProperty(receiver, name) != null) {
                return InvokerHelper.getProperty(receiver, name)
            }
            List<Object> each = new ArrayList<Object>()
            for (Object element : (Collection) receiver) each.add(property(element, name, true))
            return each
        }
        refuseIfOffLimits(receiver)
        return InvokerHelper.getProperty(receiver, name)
    }

    private static Object index(Object receiver, Object key) {
        if (receiver == null) {
            throw new NullPointerException('cannot index null')
        }
        if (receiver instanceof Map) {
            return ((Map) receiver).get(key)
        }
        if (key instanceof Number && (receiver instanceof List || receiver instanceof CharSequence || receiver.getClass().isArray())) {
            return InvokerHelper.invokeMethod(receiver, 'getAt', ((Number) key).intValue())
        }
        if (key instanceof CharSequence) {
            return property(receiver, key.toString(), false)
        }
        throw new SecurityException(
                "a rule cannot index a ${receiver.getClass().name} with a ${key == null ? 'null' : key.getClass().name}".toString())
    }

    private static Object call(Object receiver, String name, Object[] args) {
        if (receiver == null) {
            if (name == 'toString' && args.length == 0) return 'null'      // as Groovy does
            throw new NullPointerException("cannot call ${name}() on null".toString())
        }
        boolean plain = receiver instanceof CharSequence || receiver instanceof Collection ||
                receiver instanceof Map || receiver.getClass().isArray()
        if (!plain) {
            if (name != 'toString') {
                throw new SecurityException(
                        "a rule can call ${name}() on text, a list or a map, not on a ${receiver.getClass().name}".toString())
            }
            refuseIfOffLimits(receiver)
        }
        return InvokerHelper.invokeMethod(receiver, name, args)
    }

    private static Object arithmetic(String method, String op, Object left, Object right) {
        boolean numbers = left instanceof Number && right instanceof Number
        // Groovy's other meanings that build a new value and change nothing: text + anything,
        // list + anything, map + map, text * count.
        boolean joining = method == 'plus' &&
                (left instanceof CharSequence || left instanceof Collection || (left instanceof Map && right instanceof Map))
        boolean repeating = method == 'multiply' && left instanceof CharSequence && right instanceof Number
        if (!numbers && !joining && !repeating) {
            throw new IllegalArgumentException(
                    "'${op}' cannot be applied to ${kind(left)} and ${kind(right)} in a rule".toString())
        }
        // The array matters: a bare null argument would be read as "no arguments".
        Object[] argument = [right] as Object[]
        if (method == 'mod') {
            // Groovy 5 and later read % as remainder(); earlier versions as mod().
            try {
                return InvokerHelper.invokeMethod(left, 'remainder', argument)
            } catch (MissingMethodException ignored) {
                return InvokerHelper.invokeMethod(left, 'mod', argument)
            }
        }
        return InvokerHelper.invokeMethod(left, method, argument)
    }

    private static boolean isIn(Object value, Object container) {
        // Membership, as Groovy 6 reads 'in': an element of the collection, a key of the map.
        if (container instanceof Map) {
            return ((Map) container).containsKey(value)
        }
        if (container instanceof Collection) {
            return ((Collection) container).contains(value)
        }
        if (container == null) {
            return false
        }
        if (container.getClass().isArray()) {
            return DefaultTypeTransformation.asCollection(container).contains(value)
        }
        if (container instanceof CharSequence && value instanceof CharSequence) {
            return container.toString().contains(value.toString())
        }
        throw new IllegalArgumentException(
                "'in' needs a list, an array, a map or text on the right: got ${kind(container)}".toString())
    }

    private static Object number(Object value, String sign) {
        if (value instanceof Number) return value
        throw new IllegalArgumentException("unary '${sign}' needs a number: got ${kind(value)}".toString())
    }

    private static String text(Object value, String op) {
        if (value == null) return null
        if (value instanceof CharSequence) return value.toString()
        throw new IllegalArgumentException("'${op}' needs text on both sides: got ${kind(value)}".toString())
    }

    private static boolean truth(Object value) {
        return DefaultTypeTransformation.castToBoolean(value)
    }

    private static String kind(Object value) {
        return value == null ? 'null' : value.getClass().simpleName
    }

    // ------------------------------------------------------------------ what a rule may not navigate

    private static final List<String> OFF_LIMITS_PREFIXES = Collections.unmodifiableList([
            'java.lang.reflect.', 'java.lang.invoke.', 'java.lang.Runtime', 'java.lang.ProcessBuilder',
            'java.lang.System', 'java.lang.Module', 'java.io.', 'java.nio.', 'java.net.', 'java.security.',
            'javax.', 'sun.', 'com.sun.', 'jdk.',
            'groovy.lang.', 'groovy.util.', 'org.codehaus.groovy.', 'org.apache.groovy.',
            'org.softwood.dag.TaskGraph', 'org.softwood.dag.task.TaskContext', 'org.softwood.pool.'
    ])

    /** True for an object whose getters and toString() a rule must not reach. */
    static boolean offLimits(Object o) {
        if (o == null) return false
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character ||
                o instanceof Enum || o instanceof Collection || o instanceof Map || o instanceof Expando) {
            return false
        }
        if (o instanceof Class || o instanceof ClassLoader || o instanceof MetaClass || o instanceof Closure ||
                o instanceof Script || o instanceof Thread || o instanceof Process ||
                o instanceof File || o instanceof java.nio.file.Path || o instanceof URL ||
                o instanceof java.lang.reflect.AnnotatedElement || o instanceof java.lang.reflect.Member ||
                o instanceof java.util.concurrent.Executor || o instanceof AutoCloseable) {
            return true
        }
        String type = o.getClass().name
        for (String prefix : OFF_LIMITS_PREFIXES) {
            if (type.startsWith(prefix)) return true
        }
        return false
    }

    private static void refuseIfOffLimits(Object receiver) {
        if (offLimits(receiver)) {
            throw new SecurityException("a rule cannot read into a ${receiver.getClass().name}".toString())
        }
    }
}
