# bwmp Jenkins shared library

The build pipeline every bwmp Maven plugin uses, in one place.

A plugin's whole `Jenkinsfile` becomes:

```groovy
@Library('bwmp') _

mavenPlugin()
```

## Installing it in Jenkins

> Manage Jenkins → System → **Global Pipeline Libraries** → Add

| Field | Value |
|---|---|
| Name | `bwmp` |
| Default version | `main` |
| Retrieval method | Modern SCM → Git |
| Project repository | `https://github.com/bwmp-dev/jenkins-library` |
| Load implicitly | **off** |
| Allow default version to be overridden | on |

**Leave "Load implicitly" off.** With it on, every job on the controller loads this library whether it wants it or not, and a syntax error here breaks builds that have nothing to do with Maven. The `@Library('bwmp') _` line is one line per repo and worth it.

"Allow default version to be overridden" lets a single repo pin a version — `@Library('bwmp@some-branch')` — which is how you test a library change without breaking every build at once. Use it.

## `mavenPlugin(...)`

Everything is optional; a single-module plugin needs only `mavenPlugin()`.

| Parameter | Default | Meaning |
|---|---|---|
| `jdk` | `Java 25` | Jenkins JDK tool name. Must be >= the newest class file version on the compile classpath — paper-api 26.x is Java 25 — regardless of the `--release` level the project targets. |
| `maven` | `3.8.1` | Jenkins Maven tool name; `null` to use `mvn` from the agent's PATH |
| `nexusCredentials` | `nexus-deploy` | Jenkins credential ID for the Nexus deploy account |
| `artifacts` | `**/target/*.jar` | what to archive |
| `excludes` | `**/original-*.jar` | shade's pre-shading copy, never wanted |
| `verify` | none | jar layout assertions, see below |
| `deploy` | `true` | set false for a plugin that should never publish |
| `releaseBranch` | `main\|master` | branches that deploy snapshots |

**JDK 21 is the default even though these emit Java 17 bytecode.** `--release 17` fixes the *output* version; compiling *against* paper-api 26.x needs a compiler new enough to read its class files.

### What it does

| Trigger | Result |
|---|---|
| Any branch | build, test, verify jar layout, archive |
| `main` | as above, plus deploy `<version>-SNAPSHOT` to `maven-snapshots` |
| Tag | as above, plus deploy the release to `maven-releases` |

Snapshots get their version rewritten in the workspace before deploying. release-please leaves poms at the **last released** version between releases — there is no `-SNAPSHOT` in git — so deploying as-is would try to republish something already public.

## `verifyJarLayout(...)`

The stage that earns its place. Shading failures are invisible to the compiler: a relocation that silently did not happen, or one that moved too much, produces a jar that builds cleanly and dies at runtime with a `NoClassDefFoundError` naming a class that looks entirely correct.

```groovy
verify: [
    jar:       'sigil-plugin/target/Sigil-*.jar',
    relocated: ['dev/bwmp/sigil/libs/keystone/'],   // must be present
    absent:    ['net/kyori/', 'dev/bwmp/keystone/'], // must NOT be present
    present:   ['dev/bwmp/sigil/SigilPlugin.class']  // must survive shading
]
```

`present` exists because of a real bug: **shade matches relocation patterns by raw string prefix**, so a consumer package sharing a prefix with a relocated one gets moved too. That is how `dev.bwmp.keystonetest` once ended up relocated by a pattern meant for `dev.bwmp.keystone`, leaving `plugin.yml` pointing at a class that no longer existed.

## Requirements

A Jenkins credential holding the Nexus deploy account, with the ID given in `nexusCredentials` (default `nexus-deploy`). Until it exists, builds compile, test and archive normally and only the deploy stages fail — deliberately, so a missing credential is visible rather than a silently skipped publish.

## Changing this library

It is loaded by every plugin, so a mistake here breaks all of them at once. Test on a branch first:

```groovy
@Library('bwmp@my-change') _
```

in one repo, confirm it builds, then merge.
