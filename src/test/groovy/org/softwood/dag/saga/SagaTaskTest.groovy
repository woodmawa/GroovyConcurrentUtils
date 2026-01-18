package org.softwood.dag.saga

import org.softwood.dag.TaskGraph
import org.softwood.dag.task.sql.JdbcSqlProvider
import spock.lang.Specification

/**
 * Comprehensive tests for SagaTask with compensations.
 */
class SagaTaskTest extends Specification {
    
    JdbcSqlProvider provider
    
    def setup() {
        // Create in-memory H2 database with unique name per test
        def dbName = "sagatest_${System.currentTimeMillis()}_${(Math.random() * 10000).toInteger()}"
        provider = new JdbcSqlProvider(
            url: "jdbc:h2:mem:${dbName};DB_CLOSE_DELAY=-1",
            driverClassName: "org.h2.Driver",
            username: "sa",
            password: ""
        )
        provider.initialize()
        
        // Create test schema
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS orders (
                id INT PRIMARY KEY,
                status VARCHAR(50),
                total DECIMAL(10,2)
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS inventory (
                product_id INT PRIMARY KEY,
                quantity INT
            )
        """, [])
        
        provider.executeUpdate("""
            CREATE TABLE IF NOT EXISTS payments (
                id INT PRIMARY KEY,
                order_id INT,
                amount DECIMAL(10,2),
                status VARCHAR(50)
            )
        """, [])
        
        // Insert test data
        provider.executeUpdate("INSERT INTO inventory VALUES (1, 100)", [])
    }
    
    def cleanup() {
        if (provider) {
            provider.close()
        }
    }
    
    // =========================================================================
    // Success Path Tests
    // =========================================================================
    
    def "should execute all saga steps successfully"() {
        given:
        def executionLog = []
        
        when:
        def graph = TaskGraph.build {
            saga("checkout") {
                compensatable(serviceTask("step1") {
                    action { ctx, prev ->
                        executionLog << "step1"
                        ctx.promiseFactory.createPromise([step: 1])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step1"
                    }
                }
                
                compensatable(serviceTask("step2") {
                    action { ctx, prev ->
                        executionLog << "step2"
                        ctx.promiseFactory.createPromise([step: 2])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step2"
                    }
                }
                
                compensatable(serviceTask("step3") {
                    action { ctx, prev ->
                        executionLog << "step3"
                        ctx.promiseFactory.createPromise([step: 3])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step3"
                    }
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        result.completedSteps == 3
        executionLog == ["step1", "step2", "step3"]
    }
    
    def "should execute saga with SQL transactions"() {
        when:
        def graph = TaskGraph.build {
            saga("order-processing") {
                // Step 1: Create order
                compensatable(sqlTask("create-order") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("INSERT INTO orders (id, status, total) VALUES (?, ?, ?)")
                        stmt.setInt(1, 1)
                        stmt.setString(2, "pending")
                        stmt.setBigDecimal(3, 100.00)
                        stmt.executeUpdate()
                        stmt.close()
                        return [orderId: 1, total: 100.00]
                    }
                }) {
                    compensate { result ->
                        // Delete order
                        provider.executeUpdate("DELETE FROM orders WHERE id = ?", [result.orderId])
                    }
                }
                
                // Step 2: Reserve inventory
                compensatable(sqlTask("reserve-inventory") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?")
                        stmt.setInt(1, 5)
                        stmt.setInt(2, 1)
                        stmt.executeUpdate()
                        stmt.close()
                        return [reserved: 5]
                    }
                }) {
                    compensate { result ->
                        // Release inventory
                        provider.executeUpdate("UPDATE inventory SET quantity = quantity + ? WHERE product_id = ?", [result.reserved, 1])
                    }
                }
                
                // Step 3: Confirm order
                compensatable(sqlTask("confirm-order") {
                    provider this.provider
                    update "UPDATE orders SET status = ? WHERE id = ?"
                    params { prev -> ["confirmed", 1] }
                }) {
                    compensate { result ->
                        provider.executeUpdate("UPDATE orders SET status = ? WHERE id = ?", ["cancelled", 1])
                    }
                }
            }
        }
        
        def result = graph.run().get()
        
        then:
        result.success
        
        when:
        def order = provider.queryForMap("SELECT * FROM orders WHERE id = ?", [1])
        def inventory = provider.queryForMap("SELECT * FROM inventory WHERE product_id = ?", [1])
        
        then:
        order.status == "confirmed"
        inventory.quantity == 95  // 100 - 5
    }
    
    // =========================================================================
    // Compensation Tests
    // =========================================================================
    
    def "should compensate all completed steps on failure"() {
        given:
        def executionLog = []
        
        when:
        def graph = TaskGraph.build {
            saga("failing-saga") {
                compensatable(serviceTask("step1") {
                    action { ctx, prev ->
                        executionLog << "step1"
                        ctx.promiseFactory.createPromise([step: 1])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step1"
                    }
                }
                
                compensatable(serviceTask("step2") {
                    action { ctx, prev ->
                        executionLog << "step2"
                        ctx.promiseFactory.createPromise([step: 2])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step2"
                    }
                }
                
                compensatable(serviceTask("step3-fails") {
                    action { ctx, prev ->
                        executionLog << "step3-fails"
                        throw new RuntimeException("Step 3 failed")
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step3"
                    }
                }
            }
        }
        
        graph.run().get()
        
        then:
        def ex = thrown(Exception)
        ex.message.contains("Step 3 failed") || ex.cause?.message?.contains("Step 3 failed")
        
        and:
        executionLog == ["step1", "step2", "step3-fails", "compensate-step2", "compensate-step1"]
    }
    
    def "should compensate SQL transactions on failure"() {
        when:
        def graph = TaskGraph.build {
            saga("failing-order") {
                // Step 1: Create order (succeeds)
                compensatable(sqlTask("create-order") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("INSERT INTO orders (id, status, total) VALUES (?, ?, ?)")
                        stmt.setInt(1, 2)
                        stmt.setString(2, "pending")
                        stmt.setBigDecimal(3, 200.00)
                        stmt.executeUpdate()
                        stmt.close()
                        return [orderId: 2]
                    }
                }) {
                    compensate { result ->
                        provider.executeUpdate("DELETE FROM orders WHERE id = ?", [result.orderId])
                    }
                }
                
                // Step 2: Reserve inventory (succeeds)
                compensatable(sqlTask("reserve-inventory") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?")
                        stmt.setInt(1, 10)
                        stmt.setInt(2, 1)
                        stmt.executeUpdate()
                        stmt.close()
                        return [reserved: 10]
                    }
                }) {
                    compensate { result ->
                        provider.executeUpdate("UPDATE inventory SET quantity = quantity + ? WHERE product_id = ?", [result.reserved, 1])
                    }
                }
                
                // Step 3: Payment (fails)
                compensatable(serviceTask("process-payment") {
                    action { ctx, prev ->
                        throw new RuntimeException("Payment declined")
                    }
                }) {
                    compensate { result ->
                        // Would refund payment
                    }
                }
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
        
        when:
        def order = provider.queryForMap("SELECT * FROM orders WHERE id = ?", [2])
        def inventory = provider.queryForMap("SELECT * FROM inventory WHERE product_id = ?", [1])
        
        then:
        order == null  // Order was compensated (deleted)
        inventory.quantity == 100  // Inventory was compensated (restored)
    }
    
    // =========================================================================
    // Non-Compensatable Steps
    // =========================================================================
    
    def "should allow non-compensatable steps"() {
        given:
        def executionLog = []
        
        when:
        def graph = TaskGraph.build {
            saga("mixed-saga") {
                compensatable(serviceTask("step1") {
                    action { ctx, prev ->
                        executionLog << "step1"
                        ctx.promiseFactory.createPromise([step: 1])
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step1"
                    }
                }
                
                nonCompensatable(serviceTask("step2-logging") {
                    action { ctx, prev ->
                        executionLog << "step2-logging"
                        ctx.promiseFactory.createPromise([logged: true])
                    }
                })
                
                compensatable(serviceTask("step3-fails") {
                    action { ctx, prev ->
                        executionLog << "step3-fails"
                        throw new RuntimeException("Fail")
                    }
                }) {
                    compensate { result ->
                        executionLog << "compensate-step3"
                    }
                }
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
        
        and:
        executionLog == ["step1", "step2-logging", "step3-fails", "compensate-step1"]
        // Note: step2-logging is NOT compensated
    }
    
    // =========================================================================
    // Callback Tests
    // =========================================================================
    
    def "should invoke callbacks during saga execution"() {
        given:
        def callbacks = []
        
        when:
        def graph = TaskGraph.build {
            saga("callback-saga") {
                compensatable(serviceTask("step1") {
                    action { ctx, prev ->
                        ctx.promiseFactory.createPromise([step: 1])
                    }
                }) {
                    compensate { result ->
                        callbacks << "compensate-step1"
                    }
                }
                
                compensatable(serviceTask("step2-fails") {
                    action { ctx, prev ->
                        throw new RuntimeException("Fail")
                    }
                }) {
                    compensate { result -> }
                }
                
                onStepSuccess { step, result ->
                    callbacks << "success-${step.id}"
                }
                
                onStepFailure { step, error ->
                    callbacks << "failure-${step.id}"
                }
                
                onSagaComplete { results ->
                    callbacks << "saga-complete"
                }
                
                onSagaFailed { error, compensatedSteps ->
                    callbacks << "saga-failed-${compensatedSteps.size()}"
                }
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
        callbacks.size() == 4
        callbacks[0] == "success-step1"
        callbacks[1] == "failure-step2-fails"
        callbacks[2] == "compensate-step1"
        callbacks[3] == "saga-failed-1"
    }
    
    // =========================================================================
    // Complex Scenario Test
    // =========================================================================
    
    def "should handle complex multi-step saga with late failure"() {
        when:
        def graph = TaskGraph.build {
            saga("complex-order") {
                // Step 1: Create order (succeeds)
                compensatable(sqlTask("create-order") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("INSERT INTO orders (id, status, total) VALUES (?, ?, ?)")
                        stmt.setInt(1, 3)
                        stmt.setString(2, "pending")
                        stmt.setBigDecimal(3, 300.00)
                        stmt.executeUpdate()
                        stmt.close()
                        return [orderId: 3]
                    }
                }) {
                    compensate { result ->
                        provider.executeUpdate("DELETE FROM orders WHERE id = ?", [result.orderId])
                    }
                }
                
                // Step 2: Reserve inventory (succeeds)
                compensatable(sqlTask("reserve-inventory") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?")
                        stmt.setInt(1, 15)
                        stmt.setInt(2, 1)
                        stmt.executeUpdate()
                        stmt.close()
                        return [reserved: 15]
                    }
                }) {
                    compensate { result ->
                        provider.executeUpdate("UPDATE inventory SET quantity = quantity + ? WHERE product_id = ?", [result.reserved, 1])
                    }
                }
                
                // Step 3: Process payment (succeeds)
                compensatable(sqlTask("process-payment") {
                    provider this.provider
                    withTransaction { conn ->
                        def stmt = conn.prepareStatement("INSERT INTO payments (id, order_id, amount, status) VALUES (?, ?, ?, ?)")
                        stmt.setInt(1, 1)
                        stmt.setInt(2, 3)
                        stmt.setBigDecimal(3, 300.00)
                        stmt.setString(4, "completed")
                        stmt.executeUpdate()
                        stmt.close()
                        return [paymentId: 1]
                    }
                }) {
                    compensate { result ->
                        provider.executeUpdate("UPDATE payments SET status = ? WHERE id = ?", ["refunded", result.paymentId])
                    }
                }
                
                // Step 4: Final validation (fails)
                compensatable(serviceTask("validate") {
                    action { ctx, prev ->
                        throw new RuntimeException("Validation failed")
                    }
                }) {
                    compensate { result -> }
                }
            }
        }
        
        graph.run().get()
        
        then:
        thrown(Exception)
        
        when:
        def order = provider.queryForMap("SELECT * FROM orders WHERE id = ?", [3])
        def inventory = provider.queryForMap("SELECT * FROM inventory WHERE product_id = ?", [1])
        def payment = provider.queryForMap("SELECT * FROM payments WHERE id = ?", [1])
        
        then:
        order == null  // Compensated (deleted)
        inventory.quantity == 100  // Compensated (restored)
        payment.status == "refunded"  // Compensated
    }
}
