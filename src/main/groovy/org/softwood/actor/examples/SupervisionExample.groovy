package org.softwood.actor.examples

import org.softwood.actor.ActorSystem
import org.softwood.actor.supervision.SupervisionStrategy
import org.softwood.actor.supervision.SupervisorDirective

import java.time.Duration

/**
 * Example demonstrating actor supervision and resilience.
 * 
 * Shows:
 * - Automatic restart on failure
 * - Custom supervision strategies
 * - Different directives for different exceptions
 * - Restart state preservation vs clearing
 */
class SupervisionExample {
    
    static void main(String[] args) {
        def system = new ActorSystem('supervision-demo')
        
        try {
            // Example 1: Simple restart on failure
            println "=" * 60
            println "Example 1: Always Restart"
            println "=" * 60
            
            def worker = system.actor {
                name 'simple-worker'
                supervisionStrategy SupervisionStrategy.restartAlways()
                state counter: 0
                
                onMessage { msg, ctx ->
                    def count = ctx.state.get('counter', 0) as int
                    count++
                    ctx.state.put('counter', count)
                    println "Processing: $msg (attempt ${count})"
                    
                    if (msg == 'fail') {
                        throw new RuntimeException('Simulated failure!')
                    }
                    
                    ctx.reply("Done: $msg")
                }
            }
            
            println worker.askSync('task1', Duration.ofSeconds(2))
            println worker.askSync('task2', Duration.ofSeconds(2))
            
            // This will fail and restart
            worker.tell('fail')
            Thread.sleep(500)  // Give time for restart
            
            // Should work after restart (counter reset to 0)
            println worker.askSync('task3', Duration.ofSeconds(2))
            println()
            
            // Example 2: Custom strategy with different directives
            println "=" * 60
            println "Example 2: Custom Strategy by Exception Type"
            println "=" * 60
            
            def processor = system.actor {
                name 'smart-processor'
                state retryCount: 0
                
                // Custom strategy that returns different directives based on exception type
                supervisionStrategy { throwable ->
                    switch(throwable.class) {
                        case IllegalArgumentException:
                            println "  → Strategy: RESUME (ignore bad input)"
                            return SupervisorDirective.RESUME
                        case IOException:
                            println "  → Strategy: RESTART (retry I/O errors)"
                            return SupervisorDirective.RESTART
                        default:
                            println "  → Strategy: STOP (fatal error)"
                            return SupervisorDirective.STOP
                    }
                }
                
                onMessage { msg, ctx ->
                    def count = ctx.state.get('retryCount', 0) as int
                    count++
                    ctx.state.put('retryCount', count)
                    println "Processing: $msg (count ${count})"
                    
                    switch(msg) {
                        case 'bad-input':
                            throw new IllegalArgumentException('Invalid input')
                        case 'io-error':
                            throw new IOException('Network timeout')
                        case 'fatal':
                            throw new RuntimeException('Fatal error')
                        default:
                            ctx.reply("OK: $msg")
                    }
                }
            }
            
            println processor.askSync('good-input', Duration.ofSeconds(2))
            
            // This will RESUME (ignore error, keep processing)
            processor.tell('bad-input')
            Thread.sleep(200)
            println processor.askSync('after-bad-input', Duration.ofSeconds(2))
            
            // This will RESTART (clear state)
            processor.tell('io-error')
            Thread.sleep(500)
            println processor.askSync('after-io-error', Duration.ofSeconds(2))
            println()
            
            // Example 3: Resume strategy preserves state
            println "=" * 60
            println "Example 3: Resume Preserves State"
            println "=" * 60
            
            def counter = system.actor {
                name 'resilient-counter'
                state count: 0, errors: 0
                supervisionStrategy SupervisionStrategy.resumeAlways()
                
                onMessage { msg, ctx ->
                    if (msg == 'increment') {
                        def count = ctx.state.get('count', 0) as int
                        ctx.state.put('count', count + 1)
                        ctx.reply(ctx.state.get('count'))
                    } else if (msg == 'error') {
                        def errors = ctx.state.get('errors', 0) as int
                        ctx.state.put('errors', errors + 1)
                        throw new RuntimeException("Error #${ctx.state.get('errors')}")
                    } else if (msg == 'status') {
                        ctx.reply([
                            count: ctx.state.get('count', 0),
                            errors: ctx.state.get('errors', 0)
                        ])
                    }
                }
            }
            
            println "Count: " + counter.askSync('increment', Duration.ofSeconds(2))
            println "Count: " + counter.askSync('increment', Duration.ofSeconds(2))
            
            // Trigger error (will resume, keeping state)
            counter.tell('error')
            Thread.sleep(200)
            
            println "Count: " + counter.askSync('increment', Duration.ofSeconds(2))
            println "Status: " + counter.askSync('status', Duration.ofSeconds(2))
            println()
            
            // Example 4: Stop strategy terminates actor
            println "=" * 60
            println "Example 4: Stop Strategy"
            println "=" * 60
            
            def fragile = system.actor {
                name 'fragile-actor'
                supervisionStrategy SupervisionStrategy.stopAlways()
                
                onMessage { msg, ctx ->
                    if (msg == 'fail') {
                        throw new RuntimeException('Fatal error - will stop!')
                    }
                    ctx.reply("OK: $msg")
                }
            }
            
            println fragile.askSync('task1', Duration.ofSeconds(2))
            
            // This will stop the actor
            fragile.tell('fail')
            Thread.sleep(100)  // Brief wait
            
            println "Actor stopped: ${fragile.isStopped()}"
            // Note: Actor may still be terminating (cleaning up mailbox loop)
            // terminated will be true once mailbox loop fully exits
            println()
            
            // Example 5: Restart strategy clears state
            println "=" * 60
            println "Example 5: Restart Strategy Clears State"
            println "=" * 60
            
            def restartingActor = system.actor {
                name 'restarting-actor'
                state attempts: 0
                supervisionStrategy SupervisionStrategy.restartAlways()
                
                onMessage { msg, ctx ->
                    def attempts = ctx.state.get('attempts', 0) as int
                    attempts++
                    ctx.state.put('attempts', attempts)
                    println "Processing $msg (attempt ${attempts})"
                    
                    if (msg.type == 'fail-once') {
                        throw new RuntimeException('Intentional failure to trigger restart')
                    }
                    
                    ctx.reply("Processed $msg (attempt ${attempts})")
                }
            }
            
            // First message succeeds
            println restartingActor.askSync([type: 'success'], Duration.ofSeconds(2))
            
            // This will fail and restart (state cleared back to 0)
            restartingActor.tell([type: 'fail-once'])
            Thread.sleep(300)
            
            // After restart, attempts is back to 1 (state was cleared)
            println restartingActor.askSync([type: 'success'], Duration.ofSeconds(2))
            println()
            
            // Example 6: Restart with limits
            println "=" * 60
            println "Example 6: Restart with Limits"
            println "=" * 60
            
            def limited = system.actor {
                name 'limited-restarts'
                state failCount: 0
                
                // Max 3 restarts within 5 seconds
                supervisionStrategy(
                    maxRestarts: 3,
                    withinDuration: Duration.ofSeconds(5)
                ) { throwable ->
                    SupervisorDirective.RESTART
                }
                
                onMessage { msg, ctx ->
                    def failCount = ctx.state.get('failCount', 0) as int
                    println "Processing attempt, state failCount: ${failCount}"
                    
                    if (msg == 'keep-failing') {
                        ctx.state.put('failCount', failCount + 1)
                        throw new RuntimeException("Failure #${ctx.state.get('failCount')}")
                    }
                    
                    ctx.reply("OK")
                }
            }
            
            // Trigger multiple failures
            (1..5).each { i ->
                if (!limited.isStopped()) {
                    limited.tell('keep-failing')
                    Thread.sleep(200)
                } else {
                    println "Actor stopped after ${i-1} failures (restart limit reached)"
                }
            }
            
            Thread.sleep(500)
            println "Actor stopped after exceeding restart limit: ${limited.isStopped()}"
            println()
            
            println "=" * 60
            println "All examples completed!"
            println "=" * 60
            
        } catch (Exception e) {
            println "Error in example: ${e.message}"
            e.printStackTrace()
        } finally {
            system.shutdown()
        }
    }
}
