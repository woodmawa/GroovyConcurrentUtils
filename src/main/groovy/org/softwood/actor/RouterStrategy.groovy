package org.softwood.actor

import groovy.transform.CompileStatic

@CompileStatic
enum RouterStrategy {
    ROUND_ROBIN,
    RANDOM,
    BROADCAST
}
