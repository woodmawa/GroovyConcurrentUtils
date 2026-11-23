package org.softwood.dag.task

import groovy.transform.Canonical

/**
 * Statistics for a tracked metric.
 */
@Canonical
class Stats {
    String name
    int count = 0
    double sum = 0.0
    double min = 0.0
    double max = 0.0
    double average = 0.0
    double median = 0.0
    
    @Override
    String toString() {
        if (count == 0) {
            return "${name}: [no data]"
        }
        return "${name}: count=${count}, sum=${sum}, avg=${average}, min=${min}, max=${max}, median=${median}"
    }
}
