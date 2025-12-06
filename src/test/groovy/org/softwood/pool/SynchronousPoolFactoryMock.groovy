package org.softwood.pool


import java.util.concurrent.ExecutorService

class SynchronousPoolFactoryMock {

    static ExecutorPool wrap(ExecutorService exec) {
        return new SynchronousPoolMock()
    }

    static Builder builder() {
        return new Builder()
    }

    static class Builder {
        String name

        Builder name(String name) {
            this.name = name
            return this
        }

        ExecutorPool build() {
            return new SynchronousPoolMock(name)
        }
    }
}
