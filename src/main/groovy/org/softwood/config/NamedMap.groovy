package org.softwood.config

import groovy.transform.CompileStatic

@CompileStatic
class NamedMap {

    final String name
    final Map<String, Object> map

    NamedMap(String name, Map<String, Object> map) {
        this.name = name
        this.map = map
    }
}
