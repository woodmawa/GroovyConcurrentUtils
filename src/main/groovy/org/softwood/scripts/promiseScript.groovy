package org.softwood.scripts

import org.softwood.promise.Promise
import org.softwood.promise.Promises

Promise p = Promises.newPromise()

println p.dump()

p.accept (10)
println "promise set to " + p.get()