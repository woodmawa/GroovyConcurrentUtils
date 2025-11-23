package org.softwood.scripts

import org.softwood.pool.ConcurrentPool

import java.util.concurrent.CompletableFuture

ConcurrentPool pool = new ConcurrentPool()
//check for boolean return in the CF
CompletableFuture cf = pool.execute ({println "hello world with passed with  $it"; return true}, 10 )

def result = cf.get()
println "result : $result"

result = pool.withPool {
    println "hello world using with "; return 10
}
println "result (with)  : ${result.get()}"
