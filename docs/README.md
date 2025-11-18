# Gstream - Groovy Stream Wrapper

A fluent, Groovy-friendly wrapper around Java Streams that combines the power of Java's Stream API with Groovy's expressive closure syntax.

## Table of Contents

1. [Overview](#overview)
2. [Features](#features)
3. [Quick Start](#quick-start)
4. [Internal Architecture](#internal-architecture)
5. [Creating Streams](#creating-streams)
6. [Intermediate Operations](#intermediate-operations)
7. [Terminal Operations](#terminal-operations)
8. [Parallel Streams](#parallel-streams)
9. [Groovy Collections Integration](#groovy-collections-integration)
10. [Advanced Examples](#advanced-examples)
11. [API Reference](#api-reference)

## Overview

Gstream provides a type-safe, compile-static wrapper around Java Streams that feels natural in Groovy. It bridges the gap between Java's functional programming features and Groovy's idiomatic syntax, allowing you to write fluent, expressive data processing pipelines.

### Key Benefits

- **Type-Safe**: Full compile-time type checking with `@CompileStatic`
- **Groovy-Friendly**: Native support for Groovy closures instead of Java functional interfaces
- **Seamless Integration**: Falls back to Groovy collection methods via `methodMissing`
- **Parallel Processing**: Built-in support for parallel streams
- **Range Support**: Direct support for Groovy ranges (`1..100`)

## Features

✅ Fluent API with method chaining  
✅ Full Java Stream API coverage with Groovy closures  
✅ Parallel stream support  
✅ Range support (`1..100`)  
✅ Groovy Collections fallback (`sum()`, `max()`, `min()`, etc.)  
✅ Type-safe with `@CompileStatic`  
✅ Both `filter()` and `findAll()` for Groovy idioms

## Quick Start
