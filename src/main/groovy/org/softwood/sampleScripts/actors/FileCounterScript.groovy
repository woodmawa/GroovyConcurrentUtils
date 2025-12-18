package org.softwood.sampleScripts.actors

import org.softwood.actor.ActorSystem

import java.time.Duration

/**
 * Demonstrates using actors to recursively count files by type in a directory tree.
 * 
 * This example shows:
 * - Actor-based directory traversal
 * - Parallel processing of subdirectories
 * - State aggregation across multiple actors
 * - Coordinating asynchronous results
 */

def system = new ActorSystem('file-counter-system')

try {
    // Coordinator actor that aggregates results from directory scanners
    def coordinator = system.actor {
        name 'coordinator'
        state counts: [:].withDefault { 0 }, 
              pendingWorkers: 0,
              startTime: System.currentTimeMillis()
        
        onMessage { msg, ctx ->
            if (msg.type == 'scan') {
                // Initial scan request
                println "🔍 Starting scan of: ${msg.directory}"
                ctx.state.startTime = System.currentTimeMillis()
                
                // Create a scanner actor for the root directory
                def scanner = system.actor {
                    name "scanner-${msg.directory.name}"
                    
                    onMessage { scanMsg, scanCtx ->
                        if (scanMsg.type == 'scanDir') {
                            def dir = new File(scanMsg.path)
                            
                            if (!dir.exists() || !dir.isDirectory()) {
                                ctx.tell(coordinator, [type: 'workerDone'])
                                return
                            }
                            
                            def files = dir.listFiles() ?: []
                            def subdirs = []
                            def localCounts = [:].withDefault { 0 }
                            
                            // Process files and identify subdirectories
                            files.each { file ->
                                if (file.isFile()) {
                                    def extension = file.name.contains('.') ? 
                                        file.name.substring(file.name.lastIndexOf('.') + 1).toLowerCase() : 
                                        'no-extension'
                                    localCounts[extension]++
                                } else if (file.isDirectory()) {
                                    subdirs << file
                                }
                            }
                            
                            // Send local results back to coordinator
                            if (localCounts) {
                                ctx.tell(coordinator, [type: 'results', counts: localCounts, dir: dir.name])
                            }
                            
                            // Spawn child actors for subdirectories
                            if (subdirs) {
                                ctx.tell(coordinator, [type: 'addWorkers', count: subdirs.size()])
                                
                                subdirs.each { subdir ->
                                    def childScanner = system.actor {
                                        name "scanner-${subdir.name}-${System.currentTimeMillis()}"
                                        
                                        onMessage { childMsg, childCtx ->
                                            if (childMsg.type == 'scanDir') {
                                                // Recursively scan subdirectory
                                                scanCtx.self.tell(childMsg)
                                            }
                                        }
                                    }
                                    
                                    childScanner.tell([type: 'scanDir', path: subdir.absolutePath])
                                }
                            }
                            
                            // Mark this worker as done
                            ctx.tell(coordinator, [type: 'workerDone'])
                        }
                    }
                }
                
                ctx.state.pendingWorkers = 1
                scanner.tell([type: 'scanDir', path: msg.directory.absolutePath])
                
            } else if (msg.type == 'results') {
                // Aggregate results from a scanner
                msg.counts.each { ext, count ->
                    ctx.state.counts[ext] += count
                }
                println "📊 Results from ${msg.dir}: ${msg.counts}"
                
            } else if (msg.type == 'addWorkers') {
                // Track additional workers that were spawned
                ctx.state.pendingWorkers += msg.count
                
            } else if (msg.type == 'workerDone') {
                // Worker completed
                ctx.state.pendingWorkers--
                
                if (ctx.state.pendingWorkers == 0) {
                    // All workers done - print final results
                    def elapsed = System.currentTimeMillis() - ctx.state.startTime
                    println "\n" + "="*60
                    println "📁 FINAL RESULTS (completed in ${elapsed}ms)"
                    println "="*60
                    
                    def sortedCounts = ctx.state.counts.sort { -it.value }
                    def totalFiles = sortedCounts.values().sum() ?: 0
                    
                    sortedCounts.each { ext, count ->
                        def percentage = totalFiles > 0 ? (count / totalFiles * 100).round(1) : 0
                        println String.format("  %-20s: %,6d files (%5.1f%%)", 
                            ".${ext}", count, percentage)
                    }
                    
                    println "-"*60
                    println String.format("  %-20s: %,6d files", "TOTAL", totalFiles)
                    println "="*60
                    
                    ctx.reply([done: true, counts: ctx.state.counts, totalFiles: totalFiles])
                }
            } else if (msg.type == 'getStatus') {
                ctx.reply([
                    pendingWorkers: ctx.state.pendingWorkers,
                    currentCounts: ctx.state.counts
                ])
            }
        }
    }
    
    // Choose directory to scan (modify as needed)
    def targetDir = new File(System.getProperty("user.home"))
    targetDir = targetDir.append( "/IdeaProjects")
    // Or scan current project:
    // def targetDir = new File("C:\\Users\\willw\\IdeaProjects\\GroovyConcurrentUtils")
    
    println """
╔══════════════════════════════════════════════════════════════╗
║           ACTOR-BASED FILE COUNTER EXAMPLE                    ║
╚══════════════════════════════════════════════════════════════╝

This example demonstrates:
  • Parallel directory traversal using actors
  • Recursive subdirectory scanning
  • Distributed state aggregation
  • Coordination of asynchronous workers

Target directory: ${targetDir.absolutePath}
"""
    
    // Start the scan and wait for completion
    def result = coordinator.askSync([type: 'scan', directory: targetDir], Duration.ofMinutes(2))
    
    println "\n✅ Scan complete! Found ${result.totalFiles} files across ${result.counts.size()} file types."
    
} finally {
    println "\n🛑 Shutting down actor system..."
    system.shutdown()
}
