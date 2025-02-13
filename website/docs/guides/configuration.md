---
title: Configuration
sidebar_position: 7
---

import {ChainedSnippets} from "../../src/components/MarkdownComponents.js";

`scala-cli` can be configured in two ways:
- on the command-line
- directly in `.scala` and `.sc` files

Parameters on the command line take precedence over parameters in sources.
That way, you can quickly override parameters from the command line.

:::warning
The configuration options and syntax in `.scala` (and `.sc`) files is likely to evolve in the future.
:::

## Command-line

Pass `--help` to any sub-command of `scala-cli` to list its options:
```bash
scala-cli --help
scala-cli package --help
```

As an example of command line configuration, one thing you can do with `scala-cli` command line options is to specify the Scala version:
```scala title=Test.scala
@main def test = println("test")
```

<ChainedSnippets>

```bash
scala-cli --scala 3.0.0 Test.scala
```

```text
test
```

</ChainedSnippets>

Another thing you can do is to specify dependencies:

<ChainedSnippets>

```bash
scala-cli --dependency org.typelevel::cats-core:2.9.0 Test.scala
```

```text
test
```

</ChainedSnippets>

The reference documentation lists [all of the available options](/docs/reference/cli-options.md).


## In .scala and .sc files

Configuration information can also be put in `.scala` and `.sc` files using special imports, and the `using` directive.

### Using directives

Scala CLI can be configured inside `.scala` files.
This is achieved by specifying `using` directives inside comments at the top of a `.scala` file, before any `package` or `import` statement:

```scala compile
//> using scala "2.13"
//> using platform "scala-js"
//> using options "-Xasync"

// package and import statements follow here ...
```

The reference documentation lists [all available using directives](/docs/reference/directives.md#using-directives).

### Special imports

Dependencies can be added right from `.scala` and `.sc` files with [`using` directives](#using-directives):

```scala compile
//> using lib "com.lihaoyi::upickle:1.4.0"
//> using lib "com.lihaoyi::pprint:0.6.6"
import ujson._
```

Both `import $ivy` and `import $dep` are not supported.
