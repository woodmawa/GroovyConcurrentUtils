package org.softwood.dataflow

import groovy.util.logging.Slf4j
import org.codehaus.groovy.runtime.InvokerInvocationException

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Convenience class that makes working with DataFlowVariables more comfortable.
 * <p>
 * A DataFlows instance is a bean with properties of type DataFlowVariable.
 * Property access is relayed to the access methods of DataFlowVariable.
 * Each property is initialized lazily the first time it is accessed.
 * Non-String named properties can be also accessed using array-like indexing syntax
 * This allows a rather compact usage of DataFlowVariables like
 * </p>
 * <pre>
 * final df = new Dataflows()
 * start { df[0] = df.x + df.y }
 * start { df.x = 10 }
 * start { df.y = 5 }
 * assert 15 == df[0]
 * </pre>
 *
 * @author Will Woodman
 * Date: 25-03-2025
 */
@Slf4j
class Dataflows extends GroovyObjectSupport {

    private final DataflowVariable NEW_DATA_FLOW = new DataflowVariable()

    private ConcurrentMap<Object, DataflowVariable> variables = null
    private Object lock  = new Object()

    Dataflows() {
        variables = new ConcurrentHashMap<Object, DataflowVariable>()
    }

    /**
     * Removes a DFV from the map and binds it to null, if it has not been bound yet
     *
     * @param name The name of the DFV to remove.
     * @return A DFV is exists, or null
     */
    public DataflowVariable<Object> remove(final Object name) {
        final DataflowVariable<Object> df = variables.remove(name)

        if (df != null) df.set(null)

        return df
    }


    /**
     * Checks whether a certain key is contained in the map. Doesn't check, whether the variable has already been bound.
     *
     * @param name The name of the DFV to check.
     * @return A DFV is exists, or null
     */
    public boolean contains(final Object name) {
        return variables.containsKey(name)
    }

    /**
     * Binds the value to the DataflowVariable that is associated with the property "name".
     *
     * @param newValue a scalar or a DataflowVariable that may block on value access
     * @see DataflowVariable#setValue
     */
    @Override
    public void setProperty(final String property, final Object newValue) {
        if (newValue instanceof DataflowVariable) {

            log.debug "setProperty: updated $property with $newValue DF "
            //update the map entry with the DF provided
            variables.put(property, newValue)
        }
        else {
            log.debug "setProperty: $property with $newValue"
            //otherwise ensure property is in the map with return the default DF and set it with the value
            ensureMapContainsDFV(property).setValue (newValue)

        }
    }

    /**
     * @return the value of the DataflowVariable associated with the property "name".
     * May block if the value is not scalar.
     * @see DataflowVariable#value
     */
    @Override
    public Object getProperty(final String property) {
        try {
            log.debug "getProperty: $property on dataFlows, return the DFV "
            DataflowVariable df = ensureMapContainsDFV(property)

            //dont block just return the DFV
            return df
        } catch (InterruptedException e) {
            throw new InvokerInvocationException(e)
        }
    }

    /**
     * Retrieves the DFV associated with the given index
     *
     * @param index The index to find a match for
     * @return the value of the DataflowVariable associated with the property "index".
     * May block if the value is not scalar.
     * @throws InterruptedException If the thread gets interrupted
     * @see DataflowVariable#getValue
     */
    Object getAt(final int index) throws InterruptedException {
        log.info "getAt: using index $index and wait on the DFE"
        return ensureMapContainsDFV(index).getValue()
    }

    /**
     * Binds the value to the DataflowVariable that is associated with the property "index".
     *
     * @param index The index to associate the value with
     * @param value a scalar or a DataflowVariable that may block on value access
     * @see DataflowVariable#setValue
     */
    void putAt(final Object index, final Object value) {
        log.info "putAt: using index $index put $value into the DFE"
        ensureMapContainsDFV(index).setValue(value)
    }

    /**
     * meta programming called for any method ref on DataFlows
     * intercepts call on DataFlows to grab method name, and put
     * into map with that name as key and a DFV variable future as the value
     * Invokes the given method.  Looks at the args list and if an closure
     * sets up the whenBound  to call it and pass it the value of the DFV to process
     *
     * otherwise fires missingMethod hook if the args array is not a closure
     * Allows for invoking whenBound() on the dataflow variables.
     * <pre>
     * def df = new Dataflows()
     * df.var {*     println "Variable bound to $it"
     * }* </pre>
     *
     * @param name the name of the method to call (the variable name)
     * @param args the arguments to use for the method call (a closure to invoke when a value is bound)
     * @return the result of invoking the method (void)
     */
    def invokeMethod(final String name, final Object args) {
        log.info  "dataflows: invoke method with $name"
        final DataflowVariable<Object> df = ensureMapContainsDFV(name)
        if (args instanceof Object[] && ((Object[]) args).length == 1 && ((Object[]) args)[0] instanceof Closure) {
            df.whenBound((Closure) ((Object[]) args)[0])
            return this
        } else
            throw new MissingMethodException(name, Dataflows.class, (Object[]) args)
    }

    def propertyMissing (final String name) {
        log.info  "dataflows read: propertyMissing called for $name "
        DataflowVariable df

        df =  ensureMapContainsDFV (name)

        if (df.isBound())
            return df.getValue()
        else
            return df.getValue()//df.toFuture()

    }

    //handle setting the value into the DF mapped with this property name as key
    def propertyMissing (final String name, value ) {
        log.info  "dataflows set: propertyMissing called for $name with value $value, setting the DFV"

        DataflowVariable df =  ensureMapContainsDFV (name)
        df.setValue(value)
        def val = df.getValue()
        log.debug  "dataflows set: DFV is $val"
        val
    }


    /**
     * Convenience method to play nicely with Groovy object iteration methods.
     * The iteration restrictions of ConcurrentHashMap concerning parallel access and
     * ConcurrentModificationException apply.
     *
     * @return iterator over the stored key:DataflowVariable value pairs
     */
    public Iterator<Map.Entry<Object, DataflowVariable<Object>>> iterator() {
        return variables.entrySet().iterator()
    }

    /**
     * The idea is following:
     * <ul>
     *   <li>we try to putIfAbsent dummy DFV in to map</li>
     *   <li>if something real already there we are done</li>
     * </ul>
     * <p>
     * <p>
     *
     * @param name The key to ensure has a DFV bound to it
     * @return DataflowVariable corresponding to name
     */
    private DataflowVariable<Object> ensureMapContainsDFV(final Object name) {
        DataflowVariable<Object> df = variables.putIfAbsent(name, NEW_DATA_FLOW)
        if (df == null || df == NEW_DATA_FLOW) {
            df = new DataflowVariable<Object>()
            variables.put(name, df)
        }
        return df
    }

    DataflowVariable<Object> getDataFlowVariable (final index) {
        def df = ensureMapContainsDFV (index)
    }
}
