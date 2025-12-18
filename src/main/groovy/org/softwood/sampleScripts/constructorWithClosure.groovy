package org.softwood.sampleScripts

import groovy.transform.ToString

class Test {
    int num
    Closure clos

    Test (int num, Closure clos ) {
        this.num = num
        this.clos = clos
    }

    void status () {
        println "number is $num "
        if (clos) {
            println "delegate: ${clos.delegate}"
            println "call clos " + clos()
        } else {
            println "closure was not set "
        }
    }

}

//def test = new Test (5) {it -> println "hello william "}

//test.status()

@ToString
class Person {
    String name
    int age
    String email

    static Person create(String name, @DelegatesTo(Person) Closure config) {
        def person = new Person(name: name)
        person.with(config)
        return person
    }

    static Person create(@DelegatesTo(Person) Closure config) {
        def person = new Person()
        person.with(config)
        return person
    }


}

// Multiple styles now work:
def person1 = Person.create("Alice") {
    age = 30
    email = "alice@example.com"
}

def person2 = Person.create {
    name = "Bob"
    age = 25
}

println "${person1} and ${person2} "