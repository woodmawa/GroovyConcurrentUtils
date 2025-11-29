package org.softwood.scripts

import org.softwood.dag.TaskGraph
import org.softwood.dag.JoinStrategy
import org.softwood.dag.task.ServiceTask
import org.softwood.promise.Promises

def graph = TaskGraph.build {

    globals {
        tenantId = "acme"
        baseUrl  = "https://api.example.com"
    }

    task("loadUser", ServiceTask) {
        maxRetries 3
        scheduleAt(LocalDateTime.now().plusSeconds(5))  // delayed start

        action { ctx, prevOpt ->
            def userId = ctx.globals.userId ?: 42
            Promises.async {
                // remote call
                fetchUser(userId)
            }
        }
    }

    task("loadOrders", ServiceTask) {
        dependsOn "loadUser"

        action { ctx, prevOpt ->
            def userPromise = prevOpt.orElse(null)  // previous task result
            Promises.async {
                def user = userPromise?.get()
                fetchOrders(user.id)
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
            Promises.async {
                def orders   = promises[0].get()
                def invoices = promises[1].get()
                combineSummary(orders, invoices)
            }
        }
    }

    onTaskEvent { event ->
        println "[${event.taskId}] ${event.type} at ${event.timestamp}"
    }
}

def resultPromise = graph.run()
resultPromise.onComplete { summary ->
    println "Workflow summary: $summary"
}
