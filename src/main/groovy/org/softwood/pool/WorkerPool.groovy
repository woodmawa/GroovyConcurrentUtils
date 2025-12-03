package org.softwood.pool

interface WorkerPool {
    void execute(Runnable r)
    void submit(Runnable r)
}