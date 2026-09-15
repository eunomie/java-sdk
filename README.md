# Dagger Java SDK

The user experience for authoring Dagger modules in Java.

Modules created with this tool use a **self-contained** layout: the Dagger Java
SDK is vendored into the module as real, buildable source, all generated files
are committed to version control, and **no code generation runs at module load
time** — the runtime just builds and packages the module.

> This repository owns both halves of the Java SDK. The root Dang module
> (`main.dang` / `mod.dang`) owns code generation and scaffolding, and runs when
> the engine asks it to generate a scope. The module *runtime* (the SDK
> contract: building and packaging Java modules) is the build/package-only Dang
> module under `runtime/`; new modules reference it as
> `github.com/dagger/java-sdk/runtime`.

> [!IMPORTANT]
> This SDK implements the module-scope interface from
> [dagger/dagger#13992](https://github.com/dagger/dagger/pull/13992) and needs an
> engine that has it. That change merged, and `v1.0.0-beta.13` carries it; on an
> earlier engine the module loads but every call into it fails.
>
> It writes module manifests through
> [`github.com/dagger/sdk-helpers`](https://github.com/dagger/sdk-helpers), the
> manifest builder that #13992 moved out of the engine, at the `v1.0.2` tag
> rather than the `dagger.io/sdk/helpers@v1` vanity address the other SDKs moved
> to: the released engine resolves that address as a local path and then fails to
> load the workspace at all.

## Install

```sh
dagger module install github.com/dagger/java-sdk
```

The engine recognizes the SDK interface and records the module as the `java` SDK
in `dagger.toml`:

```toml
[modules.java-sdk]
source = "github.com/dagger/java-sdk"

[sdks.java]
module = "java-sdk"
```

## Create a module

```sh
dagger module init java --name my-module --path .dagger/modules/my-module
```

The engine records the module scope in `dagger.toml` and calls this SDK's
`generateScope`, which renders the template, writes `dagger-module.toml`, and
generates the SDK bindings in one step:

```
<module>/
  dagger-module.toml                                            # [runtime] source = github.com/dagger/java-sdk/runtime
  pom.xml                                                       # two-pass build; dagger.proc defaults to "none"
  src/main/java/io/dagger/modules/<pkg>/<Module>.java
  src/main/java/io/dagger/modules/<pkg>/package-info.java
  src/generated/java/io/dagger/gen/entrypoint/Entrypoint.java   # generated entrypoint
  sdk/src/main/java/...                                         # vendored SDK library
  sdk/src/processor/java/...                                    # vendored annotation processor
  sdk/src/generated/java/io/dagger/client/modules/core/...      # the core API (from the engine schema)
  sdk/src/generated/java/io/dagger/client/modules/<client>/...   # one package per client
```

The SDK settings become typed flags on `dagger module init java` and are
persisted on the scope:

```sh
dagger module init java --name my-module --template empty
```

`--template` picks a starter under `templates/`: `default` (a small working
module), `empty` (a bare object class), or `legacy`. `--lock` records the commit
each git client resolved to as a pin; without it a client added at a branch or
tag keeps following it.

Because everything is committed and the pom defaults `dagger.proc=none`, the
module builds with a plain `mvn package` (no annotation processor at build time)
— in an IDE or CI, without Dagger.

## Generate

```sh
dagger generate
```

This regenerates every module scope recorded in `dagger.toml`. The engine
narrows the set to the scopes containing your current directory, so running it
inside a module regenerates that module.

Generation runs Maven in containers this SDK controls: it builds the vendored
codegen plugin, generates the client bindings from the engine's introspection
schema, vendors the SDK library and annotation processor as source, and runs the
processor once to produce the entrypoint. It does not delegate code generation
back to the engine.

## Module scopes

A Java scope is a directory with a `pom.xml`. `findClientRoot` answers with the
nearest one at or above your current directory, which is how
`dagger module client add` and friends find the module you are standing in. A
project built with anything but Maven has no `pom.xml`, so this SDK reports no
scope for it.

## Calling the core API

Core is a client package like any other. Its types are generated into
`io.dagger.client.modules.core`, and the way in is a static method on its root
type:

```java
import static io.dagger.client.modules.core.Core.core;
import io.dagger.client.modules.core.Container;

Container base = core().container().from("alpine:3.24");
```

`core()` uses the ambient session; `core(dag)` takes one you already hold.
`io.dagger.client` itself holds only hand-written code — `Dagger`, `Session`,
`QueryBuilder` and the rest of the runtime — so nothing generated is privileged.

> [!WARNING]
> `dag().container()` no longer exists. `Dagger.dag()` returns a `Session`, not
> a generated client, and core is reached as `core()` after a static import of
> `io.dagger.client.modules.core.Core.core`. Every core type moves with it:
> `io.dagger.client.Container` becomes
> `io.dagger.client.modules.core.Container`. `Dagger.connect()` returns an
> `AutoCloseableSession` in place of `AutoCloseableClient`.

## Module clients

Module dependencies are replaced by generated module clients:

```sh
dagger module client add java <module-ref>
```

Each client's types are generated into a package of their own,
`io.dagger.client.modules.<client>`, from that client's own schema and nothing
else. The way in is a static method on the client's own root type, so one import
is the whole of the integration:

```java
import static io.dagger.client.modules.sdkhelpers.SdkHelpers.sdkHelpers;

sdkHelpers().moduleManifest().generate();
```

Core is not extended with an accessor for it. A client package is
self-contained: it reaches core types where they live, and nothing in core names
it. Pass a session explicitly when you have one — `sdkHelpers(dag)` — or let the
no-argument form use the ambient one. Core is entered the same way, which is the
whole of the difference between a client and core: none.

A module named `core` is refused, because the generated core API has that
package. Alias the target to something else.

In a module scope the client set becomes the module's dependency set. Each
client is recorded in the manifest the module has — `dagger-module.toml`, or the
`dagger.json` of a pre-1.0 module — and a client that is removed is dropped from
both the manifest and the bindings.

> [!WARNING]
> A client's types moved out of `io.dagger.client` in this release, and so did
> the way in. `dag().sdkHelpers()` becomes `sdkHelpers()` after a static import
> of `io.dagger.client.modules.sdkhelpers.SdkHelpers.sdkHelpers`, and each type
> is imported from `io.dagger.client.modules.<client>` rather than from
> `io.dagger.client`. The `<Client>Arguments` holder moves with the method, onto
> the client's root type.

## Standalone clients

A Maven project that is no Dagger module can call modules too. Run the same
command inside it:

```sh
cd my-java-app          # any directory with a pom.xml
dagger module client add java github.com/dagger/sdk-helpers@v1.0.2
dagger generate
```

`dagger generate` writes the client tree under `dagger/`, all of it SDK-owned
and regenerated whole:

```
my-java-app/
  pom.xml                                                     # gains one profile, see below
  dagger/src/main/java/io/dagger/client/**                    # the hand-written SDK runtime
  dagger/src/main/java/io/dagger/client/modules/core/**       # the core API
  dagger/src/main/java/io/dagger/client/modules/<client>/**   # one package per client
```

The bindings under `io.dagger.client.modules.<client>` are the same files a
module gets for the same client. Only what surrounds them differs.

Your own code then reads exactly as a module's does:

```java
import static io.dagger.client.modules.sdkhelpers.SdkHelpers.sdkHelpers;

public class App {
  public static void main(String[] args) throws Exception {
    System.out.println(sdkHelpers().moduleManifest().generate());
  }
}
```

Run it with a `dagger` binary on `PATH` and no wrapper command:

```sh
mvn package
java -jar target/my-java-app-1.0-SNAPSHOT.jar
```

There is no session to join, so the SDK starts one with `dagger session`, and
each client asks the engine to load its module the first time your code reaches
for it. Set `_EXPERIMENTAL_DAGGER_CLI_BIN` to point at a specific binary. The
SDK does not download a CLI; install one first.

The generated code needs Java 17, so the project's `maven.compiler.release`
(or `maven.compiler.source` and `maven.compiler.target`) has to be 17 or later.

### The one thing written into your pom

Your `pom.xml` is yours, so `dagger generate` adds exactly one element to it: a
profile with the id `dagger-clients`, carrying a comment that says what wrote
it. The profile adds `dagger/src/main/java` as a source root, along with the SDK's
own run-time dependencies. It activates on the presence of the generated tree, so deleting
`dagger/` makes it inert and deleting the profile removes the integration.

Generation refuses to touch a `dagger-clients` profile that does not carry that
comment, on the assumption that you wrote it.

### What a client is pinned to

A client added at a git ref records the commit it resolved to, and the generated
code asks for that commit. A client that is a path in your workspace records the
path, so a jar built from it only works inside that workspace; a git client is
the form that travels.

> [!WARNING]
> The client set is the *whole* dependency set. A module that recorded
> dependencies before this interface existed has no clients recorded for them,
> so the first `dagger generate` drops them. Re-register each one first:
>
> ```sh
> dagger module client add java <module-ref>
> ```
>
> Then check that each one landed in `dagger.toml` before you generate.

## Pre-1.0 modules

A module configured by `dagger.json` is migrated the first time it is generated:
its contents move into a `dagger-module.toml` and the `dagger.json` is removed,
so the two cannot disagree. The runtime it already names is preserved — a module
on the engine's builtin `java` runtime stays there, and does not silently move
onto this repository's.

## Test

```sh
dagger check
```

Checks run against two engines:

- On the released engine, everything that does not call this module: the SDK
  library's unit tests (`packager:unit-tests`), the prebuilt assets, and the
  templates.
- On an engine built from dagger/dagger#13992, the SDK interface itself.
  `engine-e-2-e:dev-sdk-check` builds that engine from the commit pinned in
  `.dagger/modules/engine-e2e` (the `engine-dev` dependency and `engineCommit`),
  installs this checkout as the `java` SDK, initializes a Java module, and calls
  it. `engine-e-2-e:sdk-contract-check` runs the `e-2-e:*` checks inside the same
  engine. Bump both pins to follow the branch.

The `e2e` module is deliberately not installed in `dagger.toml`. Every check in
it calls this module, which a released engine cannot run, so
`engine-e-2-e:sdk-contract-check` loads it by path — `dagger -m
.dagger/modules/e2e check` — inside an engine that can.
