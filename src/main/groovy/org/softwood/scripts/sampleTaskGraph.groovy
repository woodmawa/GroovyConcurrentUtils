package org.softwood.scripts

import org.softwood.dag.TaskGraph
import org.softwood.dag.JoinStrategy
import org.softwood.dag.task.ServiceTask
import org.softwood.promise.Promises

// Service classes
class UserService {
    def fetch(userId) {
        println "UserService: Fetching user $userId..."
        Thread.sleep(100)
        return [id: userId, name: "User-${userId}", email: "user${userId}@example.com"]
    }
}

class OrderService {
    def fetch(userId) {
        println "OrderService: Fetching orders for user $userId..."
        Thread.sleep(150)
        return [[orderId: 1, amount: 100], [orderId: 2, amount: 250]]
    }
}

class InvoiceService {
    def fetch(userId) {
        println "InvoiceService: Fetching invoices for user $userId..."
        Thread.sleep(120)
        return [[invoiceId: 'INV-001', total: 100], [invoiceId: 'INV-002', total: 250]]
    }
}

def graph = TaskGraph.build {

    globals {
        tenantId = "acme"
        baseUrl  = "https://api.example.com"
        userId   = 42

        // Register services in globals
        userService = new UserService()
        orderService = new OrderService()
        invoiceService = new InvoiceService()
    }

    task("loadUser", ServiceTask) {
        maxRetries 3

        action { ctx, prevOpt ->
            def userId = ctx.globals.userId
            Promises.async {
                ctx.globals.userService.fetch(userId)
            }
        }
    }

    task("loadOrders", ServiceTask) {
        dependsOn "loadUser"

        action { ctx, prevOpt ->
            def userPromise = prevOpt.orElse(null)
            Promises.async {
                def user = userPromise?.get()
                ctx.globals.orderService.fetch(user.id)
            }
        }
    }

    task("loadInvoices", ServiceTask) {
        dependsOn "loadUser"

        action { ctx, prevOpt ->
            def userPromise = prevOpt.orElse(null)
            Promises.async {
                def user = userPromise?.get()
                ctx.globals.invoiceService.fetch(user.id)
            }
        }
    }

    fork("fanOut") {
        from "loadUser"
        to "loadOrders", "loadInvoices"
    }

    join("summary", JoinStrategy.ALL_COMPLETED) {
        from "loadOrders", "loadInvoices"

        action { ctx, promises ->
            // FIXED: Return the Promise from Promises.async, not just the map
            return Promises.async {
                def orders   = promises[0].get()
                def invoices = promises[1].get()

                // Return the aggregated result
                [
                        totalOrders: orders.size(),
                        totalInvoices: invoices.size(),
                        orderTotal: orders.sum { it.amount },
                        invoiceTotal: invoices.sum { it.total }
                ]
            }
        }
    }

    onTaskEvent { event ->
        println "[${event.taskId}] ${event.type} at ${event.timestamp}"
    }
}

def resultPromise = graph.run()
resultPromise.onComplete { summary ->
    println "Workflow completed: $summary"
}

Thread.sleep(5000)