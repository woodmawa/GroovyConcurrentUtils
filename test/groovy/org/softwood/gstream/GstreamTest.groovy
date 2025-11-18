        @Test
        void testGetStream() {
            def gstream = Gstream.of(1, 2, 3)
            def javaStream = gstream.getStream()

            assertNotNull(javaStream)
            assertTrue(javaStream instanceof java.util.stream.Stream)
        }

        // New feature tests

        @Test
        void testMaxWithComparator() {
            def result = Gstream.of(5, 2, 8, 1, 9, 3)
                    .max { a, b -> a <=> b }

            assertTrue(result.isPresent())
            assertEquals(9, result.get())
        }

        @Test
        void testMinWithComparator() {
            def result = Gstream.of(5, 2, 8, 1, 9, 3)
                    .min { a, b -> a <=> b }

            assertTrue(result.isPresent())
            assertEquals(1, result.get())
        }

        @Test
        void testMaxWithComparatorEmpty() {
            def result = Gstream.empty()
                    .max { a, b -> a <=> b }

            assertFalse(result.isPresent())
        }

        @Test
        void testPartitioningBy() {
            def result = Gstream.of(1, 2, 3, 4, 5, 6)
                    .partitioningBy { it % 2 == 0 }

            assertEquals([1, 3, 5], result[false])
            assertEquals([2, 4, 6], result[true])
        }

        @Test
        void testToMapWithKeyAndValueMappers() {
            def people = [
                    [name: 'Alice', age: 30],
                    [name: 'Bob', age: 25],
                    [name: 'Charlie', age: 35]
            ]

            def result = Gstream.from(people)
                    .toMap({ it.name }, { it.age })

            assertEquals(30, result['Alice'])
            assertEquals(25, result['Bob'])
            assertEquals(35, result['Charlie'])
        }

        @Test
        void testToMapWithKeyMapperOnly() {
            def words = ["apple", "banana", "cherry"]
            def result = Gstream.from(words)
                    .toMap { it.length() }

            assertEquals("apple", result[5])
            assertEquals("banana", result[6])
            assertEquals("cherry", result[6]) // overwrites banana
        }

        @Test
        void testToMapWithMergeFunction() {
            def items = [
                    [category: 'A', value: 10],
                    [category: 'B', value: 20],
                    [category: 'A', value: 30]
            ]

            def result = Gstream.from(items)
                    .toMap(
                            { it.category },
                            { it.value },
                            { a, b -> a + b }
                    )

            assertEquals(40, result['A'])
            assertEquals(20, result['B'])
        }

        @Test
        void testToCollection() {
            def result = Gstream.of(1, 2, 3, 4, 5)
                    .toCollection { new LinkedHashSet() }

            assertTrue(result instanceof LinkedHashSet)
            assertEquals([1, 2, 3, 4, 5] as Set, result)
        }

        @Test
        void testToArray() {
            def result = Gstream.of(1, 2, 3, 4, 5)
                    .toArray()

            assertTrue(result instanceof Object[])
            assertEquals(5, result.length)
            assertEquals([1, 2, 3, 4, 5], result as List)
        }

        @Test
        void testConcat() {
            def stream1 = Gstream.of(1, 2, 3)
            def stream2 = Gstream.of(4, 5, 6)
            def result = Gstream.concat(stream1, stream2)
                    .toList()

            assertEquals([1, 2, 3, 4, 5, 6], result)
        }

        @Test
        void testConcatWithOperations() {
            def stream1 = Gstream.of(1, 2, 3)
            def stream2 = Gstream.of(4, 5, 6)
            def result = Gstream.concat(stream1, stream2)
                    .filter { it % 2 == 0 }
                    .map { it * 10 }
                    .toList()

            assertEquals([20, 40, 60], result)
        }

        @Test
        void testUnordered() {
            def result = Gstream.of(1, 2, 3, 4, 5)
                    .unordered()
                    .distinct()
                    .toList()

            // Result may be in any order, but should contain all elements
            assertEquals(5, result.size())
            assertTrue(result.containsAll([1, 2, 3, 4, 5]))
        }

        @Test
        void testOnCloseAndClose() {
            def closeHandlerCalled = false
            def stream = Gstream.of(1, 2, 3)
                    .onClose { closeHandlerCalled = true }

            assertFalse(closeHandlerCalled)

            stream.toList() // Consume stream
            stream.close()  // Explicitly close

            assertTrue(closeHandlerCalled)
        }

        @Test
        void testMultipleCloseHandlers() {
            def handlers = []
            def stream = Gstream.of(1, 2, 3)
                    .onClose { handlers << 'first' }
                    .onClose { handlers << 'second' }
                    .onClose { handlers << 'third' }

            stream.toList()
            stream.close()

            // Handlers run in reverse order
            assertEquals(['third', 'second', 'first'], handlers)
        }

        @Test
        void testBuilderBasic() {
            def builder = Gstream.builder()
            builder.add(1)
            builder.add(2)
            builder.add(3, 4, 5)

            def result = builder.build()
                    .toList()

            assertEquals([1, 2, 3, 4, 5], result)
        }

        @Test
        void testBuilderWithOperations() {
            def builder = Gstream.builder()
            builder.add(1, 2, 3, 4, 5, 6)
            builder.filter { it % 2 == 0 }
            builder.map { it * 10 }

            def result = builder.build()
                    .toList()

            assertEquals([20, 40, 60], result)
        }

        @Test
        void testBuilderWithFrom() {
            def builder = Gstream.builder()
            builder.from([10, 20, 30, 40, 50])
            builder.filter { it > 25 }

            def result = builder.build()
                    .toList()

            assertEquals([30, 40, 50], result)
        }

        @Test
        void testBuilderWithParallel() {
            def builder = Gstream.builder()
            builder.from(1..100)
            builder.parallel(true)

            def stream = builder.build()

            assertTrue(stream.isParallel())
        }

        @Test
        void testBuilderChaining() {
            def result = Gstream.builder()
                    .add(1, 2, 3, 4, 5)
                    .filter { it > 2 }
                    .map { it * 2 }
                    .sorted()
                    .build()
                    .toList()

            assertEquals([6, 8, 10], result)
        }

        @Test
        void testDSLBasic() {
            def result = Gstream.build {
                add 1, 2, 3, 4, 5
            }.toList()

            assertEquals([1, 2, 3, 4, 5], result)
        }

        @Test
        void testDSLWithOperations() {
            def result = Gstream.build {
                add 1, 2, 3, 4, 5, 6
                filter { it % 2 == 0 }
                map { it * 10 }
            }.toList()

            assertEquals([20, 40, 60], result)
        }

        @Test
        void testDSLWithFrom() {
            def result = Gstream.build {
                from([10, 20, 30, 40, 50])
                filter { it > 25 }
                sorted()
            }.toList()

            assertEquals([30, 40, 50], result)
        }

        @Test
        void testDSLWithRange() {
            def result = Gstream.build {
                from(1..10)
                filter { it % 2 == 0 }
            }.toList()

            assertEquals([2, 4, 6, 8, 10], result)
        }

        @Test
        void testDSLWithParallel() {
            def result = Gstream.build {
                from(1..100)
                parallel true
                filter { it % 2 == 0 }
            }.count()

            assertEquals(50L, result)
        }

        @Test
        void testDSLComplexPipeline() {
            def result = Gstream.build {
                from(1..20)
                filter { it > 5 }
                map { it * 2 }
                filter { it < 30 }
                distinct()
                sorted()
                limit 5
            }.toList()

            assertEquals([12, 14, 16, 18, 20], result)
        }

        @Test
        void testDSLWithOf() {
            def result = Gstream.build {
                of 1, 2, 3, 4, 5
                map { it * 2 }
            }.toList()

            assertEquals([2, 4, 6, 8, 10], result)
        }

        @Test
        void testDSLNestedOperations() {
            def result = Gstream.build {
                add 1, 2, 3, 4, 5, 6, 7, 8, 9, 10
                filter { it % 2 == 0 }
                map { it * it }
                filter { it > 20 }
                sorted { a, b -> b <=> a }
                limit 3
            }.toList()

            assertEquals([100, 64, 36], result)
        }

        @Test
        void testBuilderWithSkipAndLimit() {
            def result = Gstream.builder()
                    .from(1..10)
                    .skip(3)
                    .limit(5)
                    .build()
                    .toList()

            assertEquals([4, 5, 6, 7, 8], result)
        }

        @Test
        void testDSLWithDistinctAndSorted() {
            def result = Gstream.build {
                add 5, 2, 8, 2, 1, 9, 1, 3
                distinct()
                sorted()
            }.toList()

            assertEquals([1, 2, 3, 5, 8, 9], result)
        }
    }
