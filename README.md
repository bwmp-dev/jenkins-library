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
| `repoServerId` | `nexus-site` | Maven server id; must match the `<server>` id in the generated settings.xml |
| `snapshotRepo` | `.../maven-snapshots/` | snapshot deploy URL, passed as `altDeploymentRepository` |
| `releaseRepo` | `.../maven-releases/` | release deploy URL, passed as `altDeploymentRepository` |
| `artifacts` | `**/target/*.jar` | what to archive |
| `excludes` | `**/original-*.jar` | shade's pre-shading copy, never wanted |
| `verify` | none | jar layout assertions, see below |
| `modrinth` | none | publish tag builds to Modrinth, see below |
| `discord` | `true` | set false to silence notifications for one repo |
| `discordDevCredentials` | `discord-webhook-dev` | Secret text credential holding the snapshot-channel webhook URL |
| `discordReleaseCredentials` | `discord-webhook-release` | Secret text credential holding the release-channel webhook URL |
| `deploy` | `true` | set false for a plugin that should never publish |
| `releaseBranch` | `main\|master` | branches that deploy snapshots |

**JDK 25 is the default even though these emit Java 17 bytecode.** `--release 17` fixes the *output* version and the platform API; it does not lower the highest class file version javac will read off the classpath. paper-api 26.1.2 ships class file version 69 (Java 25), so a Java 21 compiler fails with `class file has wrong version 69.0, should be 65.0`.

## Modrinth

Opt-in per repo. Omit `modrinth` and the stage never runs — which is the right
setting for Keystone, a library shaded into its consumers rather than a plugin
anyone installs.

```groovy
mavenPlugin(
    verify: [ jar: 'sigil-plugin/target/Sigil-*.jar', /* ... */ ],
    modrinth: [
        projectId:    'sigil',                                        // slug or id
        loaders:      ['bukkit', 'spigot', 'paper', 'purpur', 'folia'],
        gameVersions: ['1.21.4', '1.21.5', '1.21.6'],
        // file: defaults to verify.jar, so it is usually redundant
    ]
)
```

Only **tag** builds publish, and always as `version_type: release` — snapshots
off `main` never reach Modrinth. The version number is the tag with any leading
`v` stripped, and the changelog is that version's section of `CHANGELOG.md`, the
same text the Discord release embed uses.

`gameVersions` is an explicit list on purpose. Modrinth's tag API could expand a
minimum version into "everything newer", but claiming support for a Minecraft
version the moment it exists is a claim you have not tested — this way widening
support stays a deliberate edit.

Needs a **Secret text** credential `modrinth-token` (override with
`credentials:`) holding a PAT with the `VERSION_CREATE` scope. Modrinth wants the
raw token in `Authorization`, not a `Bearer` prefix, and requires a
uniquely-identifying `User-Agent`; both are handled in `publishModrinth`.

Re-running an already-published tag is a no-op: the existing version is detected
and the upload skipped, rather than failing on Modrinth's duplicate-version 400.

The project must already exist on Modrinth. New projects are created by hand and
are not publicly visible until they pass review, so this publishes versions — it
does not onboard a plugin.

**Discord posts from `post { cleanup }`, not `post { always }`.** `always` runs *before* `success`, so a message sent there reports `SUCCESS` on a build that `archiveArtifacts` then fails — which has happened. `cleanup` is the only condition guaranteed to run last, so `currentBuild.currentResult` there matches what the user sees.

Tag builds post to the release channel, builds on `main`/`master` post to the dev channel, and PR or feature-branch builds post nothing — they neither publish a snapshot nor cut a release. Notification failure is caught and logged: a missing webhook credential never turns a green build red, so the library is safe to roll out before the credentials exist.

**Deploy targets come from here, not from `<distributionManagement>`.** They describe this Jenkins, not the source tree — only some of the plugins declare them, and `keystone-parent` deliberately must not, since consuming plugins inherit from it. The library passes `-DaltDeploymentRepository` instead, which overrides any pom that does declare one.

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
