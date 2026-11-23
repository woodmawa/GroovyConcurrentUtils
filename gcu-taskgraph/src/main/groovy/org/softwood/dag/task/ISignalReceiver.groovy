package org.softwood.dag.task

/**
 * Interface for objects that can receive signals.
 * 
 * <p>This interface enables both SignalTask and BusinessRuleTask
 * to participate in the global signal registry system using a common contract.</p>
 * 
 * <h3>Implementors:</h3>
 * <ul>
 *   <li>{@link SignalTask} - Direct signal waiting/sending task</li>
 *   <li>{@link BusinessRuleTask.SignalListener} - Business rule signal trigger</li>
 * </ul>
 * 
 * @since 1.0
 */
interface ISignalReceiver {
    
    /**
     * Receive a signal with associated data.
     * 
     * <p>This method is called by the global signal mechanism when a matching
     * signal is sent. Implementations should handle the signal asynchronously
     * and complete any associated promises/tasks.</p>
     * 
     * @param signalData the signal payload containing:
     *                   - signalName: name of the signal
     *                   - sentAt: timestamp when signal was sent
     *                   - any additional user-provided payload data
     */
    void receiveSignal(Map signalData)
}
