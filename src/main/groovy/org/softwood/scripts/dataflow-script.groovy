package org.softwood.scripts

import org.softwood.dataflow.DataflowVariable

def value =10
DataflowVariable df = new DataflowVariable (value)

println df.get()