package org.softwood.dag.task

import org.softwood.dag.TaskGraph
import org.softwood.promise.Promise
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Comprehensive tests for FileTask functionality.
 */
class FileTaskTest extends Specification {
    
    @TempDir
    Path tempDir
    
    TaskGraph graph
    
    def cleanup() {
        graph = null
    }
    
    // =========================================================================
    // File Discovery Tests
    // =========================================================================
    
    def "test file discovery from directory with pattern"() {
        given: "a directory with various files"
        createTestFile("app.log", "log content")
        createTestFile("error.log", "error log")
        createTestFile("data.csv", "csv data")
        createTestFile("readme.txt", "readme")
        
        and: "a task graph with file task"
        graph = TaskGraph.build {
            fileTask("find-logs") {
                sources {
                    directory(tempDir.toString()) {
                        pattern '*.log'
                    }
                }
                
                execute { ctx ->
                    ctx.files
                }
            }
        }
        
        when: "task executes"
        def promise = graph.start()
        def result = promise.get()
        
        then: "only .log files are discovered"
        result.data.size() == 2
        result.data.every { it instanceof File }
        result.data*.name.sort() == ['app.log', 'error.log']
    }
    
    def "test eachFile with GDK delegation"() {
        given: "log files with error lines"
        createTestFile("app.log", """INFO: Application started
ERROR: Connection failed
INFO: Retrying
ERROR: Timeout occurred
INFO: Success""")
        
        and: "a task graph with file task using eachFile"
        graph = TaskGraph.build {
            fileTask("count-errors") {
                sources {
                    directory(tempDir.toString()) {
                        pattern '*.log'
                    }
                }
                
                eachFile { ctx ->
                    // 'this' is File via delegation - can use GDK methods!
                    def errorCount = 0
                    eachLine { line ->
                        if (line.contains('ERROR')) {
                            errorCount++
                        }
                    }
                    
                    ctx.emit([
                        file: name,
                        size: size(),
                        errors: errorCount
                    ])
                }
            }
        }
        
        when: "task executes"
        def promise = graph.start()
        def result = promise.get()
        
        then: "errors are counted using GDK eachLine"
        result.data.size() == 1
        result.data[0].file == 'app.log'
        result.data[0].errors == 2
        result.data[0].size > 0
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private File createTestFile(String relativePath, String content) {
        def file = tempDir.resolve(relativePath).toFile()
        file.parentFile?.mkdirs()
        file.text = content
        return file
    }
}
