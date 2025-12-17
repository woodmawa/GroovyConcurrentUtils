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
 * - Restart limits and backoff
 * - Different directives for different exceptions
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
                    ctx.state.counter++
                    println "Processing: $msg (attempt ${ctx.state.counter})"
                    
                    if (msg == 'fail') {
                        throw new RuntimeException('Simulated failure!')
                    }
                    
                    ctx.reply("Done: $msg")
                }
            }
            
            println worker.ask('task1')
            println worker.ask('task2')
            
            // This will fail and restart
            worker.tell('fail')
            Thread.sleep(200)
            
            // Should work after restart (counter reset to 0)
            println worker.ask('task3')
            println()
            
            // Example 2: Custom strategy with different directives
            println "=" * 60
            println "Example 2: Custom Strategy"
            println "=" * 60
            
            def processor = system.actor {
                name 'smart-processor'
                state retryCount: 0
                
                supervisionStrategy { throwable ->
                    switch(throwable) {
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
                    ctx.state.retryCount++
                    println "Processing: $msg (retry ${ctx.state.retryCount})"
                    
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
            
            println processor.ask('good-input')
            
            // This will RESUME (ignore error)
            processor.tell('bad-input')
            Thread.sleep(100)
            println processor.ask('after-bad-input')
            
            // This will RESTART (clear state)
            processor.tell('io-error')
            Thread.sleep(200)
            println processor.ask('after-io-error')
            println()
            
            // Example 3: Restart limits and exponential backoff
            println "=" * 60
            println "Example 3: Restart Limits + Backoff"
            println "=" * 60
            
            def flaky = system.actor {
                name 'flaky-service'
                state failures: 0
                
                supervisionStrategy(
                    maxRestarts: 3,
                    withinDuration: Duration.ofSeconds(5),
                    useExponentialBackoff: true,
                    initialBackoff: Duration.ofMillis(100),
                    maxBackoff: Duration.ofMillis(1000)
                ) { throwable ->
                    SupervisorDirective.RESTART
                }
                
                onMessage { msg, ctx ->
                    ctx.state.failures++
                    println "Attempt ${ctx.state.failures}: Processing $msg"
                    
                    // Fail first 2 times, then succeed
                    if (ctx.state.failures <= 2) {
                        throw new RuntimeException("Temporary failure #${ctx.state.failures}")
                    }
                    
                    ctx.reply("Success after ${ctx.state.failures} attempts!")
                }
            }
            
            // First call will fail twice, then succeed
            println flaky.ask('resilient-task', Duration.ofSeconds(5))
            println()
            
            // Example 4: Actor that exceeds restart limits
            println "=" * 60
            println "Example 4: Exceeding Restart Limits"
            println "=" * 60
            
            def doomed = system.actor {
                name 'doomed-actor'
                
                supervisionStrategy(
                    maxRestarts: 2,
                    withinDuration: Duration.ofSeconds(2)
                ) { throwable ->
                    SupervisorDirective.RESTART
                }
                
                onMessage { msg, ctx ->
                    println "Processing $msg - then failing..."
                    throw new RuntimeException('Always fails')
                }
            }
            
            // Trigger multiple failures
            3.times {
                doomed.tell("doomed-task-$it")
                Thread.sleep(100)
            }
            
            Thread.sleep(500)
            println "Doomed actor stopped: ${doomed.isStopped()}"
            println()
            
            println "=" * 60
            println "All examples completed!"
            println "=" * 60
            
        } finally {
            system.shutdown()
        }
    }
}
