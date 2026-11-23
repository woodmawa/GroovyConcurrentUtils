package org.softwood.dag.task.objectstorage

import groovy.transform.Immutable

@Immutable
class ContainerOptions {
    static final ContainerOptions DEFAULT = new ContainerOptions()

    String region
    Map<String, String> metadata = [:]
    boolean publicRead = false
}