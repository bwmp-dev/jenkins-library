/**
 * The build every bwmp Maven plugin uses.
 *
 * Usage in a plugin's Jenkinsfile:
 *
 *     @Library('bwmp') _
 *     mavenPlugin(
 *         jdk: 'Java 25',
 *         artifacts: 'sigil-plugin/target/Sigil-*.jar,sigil-api/target/sigil-api-*.jar',
 *         verify: [
 *             relocated: ['dev/bwmp/sigil/libs/keystone/', 'dev/bwmp/sigil/libs/kyori/'],
 *             absent:    ['net/kyori/', 'dev/bwmp/keystone/'],
 *             present:   ['dev/bwmp/sigil/SigilPlugin.class'],
 *             jar:       'sigil-plugin/target/Sigil-*.jar'
 *         ]
 *     )
 *
 * Every parameter has a default that suits a single-module plugin, so a simple
 * project needs only `mavenPlugin()`.
 */
def call(Map config = [:]) {

    // ---- defaults ---------------------------------------------------------
    // JDK 25 rather than 17: these projects emit Java 17 bytecode via
    // --release, but compiling against paper-api 26.x needs a compiler new
    // enough to READ its class files, and paper-api 26.1.2 ships class file
    // version 69 (Java 25). --release only constrains the bytecode javac
    // writes and the platform API it exposes; it does not lower the maximum
    // class file version javac will accept off the classpath, so the compiler
    // must be >= the newest dependency, independent of the target level.
    //
    // Symptom when this is too old: "bad class file ... paper-api-*.jar / class
    // file has wrong version 69.0, should be 65.0" (65 = Java 21).
    String jdkTool      = config.get('jdk', 'Java 25')
    // The name of a Jenkins Maven tool. Defaults to the one configured on
    // jenkins.luminescent.dev, whose name is its version number.
    //
    // A Jenkins tool is NOT on PATH automatically - the environment block below
    // is what puts it there. Pass `maven: null` to instead use whatever `mvn`
    // the agent already has on PATH; that is only safe if you know one is
    // installed, and `mvn: not found` is the usual first-run failure otherwise.
    //
    // containsKey rather than config.get('maven', '3.8.1'): Groovy's two-arg
    // Map.get substitutes the default when the VALUE is null, not only when the
    // key is absent, which would silently ignore an explicit `maven: null` and
    // make the escape hatch above a lie.
    String mavenTool    = config.containsKey('maven') ? config.maven : '3.8.1'
    String nexusCredId  = config.get('nexusCredentials', 'nexus-deploy')
    // Deploy targets are supplied here rather than read from each project's
    // <distributionManagement>, because they are a property of this CI setup,
    // not of the source tree. Two concrete reasons:
    //
    //   1. Half the bwmp plugins declare distributionManagement and half do
    //      not, so relying on it makes deployment work per-repo by accident.
    //   2. keystone-parent is deliberately standalone - consuming plugins
    //      inherit from it - so putting bwmp's Nexus URLs in it would hand a
    //      deploy target to every downstream consumer of a public framework.
    //
    // The id MUST match the <server> id writeNexusSettings writes, or Maven
    // finds no credentials for the repository and the upload 401s.
    String repoServerId = config.get('repoServerId', 'nexus-site')
    String snapshotRepo = config.get('snapshotRepo', 'https://nexus.bwmp.dev/repository/maven-snapshots/')
    String releaseRepo  = config.get('releaseRepo',  'https://nexus.bwmp.dev/repository/maven-releases/')
    // Two webhooks, not one: tag builds announce to the release channel and
    // main-branch builds to the dev channel, so release announcements are not
    // buried under snapshot traffic from ten repos. Both are Secret text
    // credentials holding the webhook URL.
    // Modrinth publishing is opt-in per repo: absent projectId, the stage is
    // skipped entirely. Keystone in particular must never appear there - it is
    // a library shaded into its consumers, not a plugin anyone installs.
    Map    modrinth           = config.get('modrinth', [:])
    boolean notifyDiscord     = config.get('discord', true)
    String discordDevCred     = config.get('discordDevCredentials', 'discord-webhook-dev')
    String discordReleaseCred = config.get('discordReleaseCredentials', 'discord-webhook-release')
    String artifacts    = config.get('artifacts', '**/target/*.jar')
    String excludes     = config.get('excludes', '**/original-*.jar')
    Map    verify       = config.get('verify', null)
    boolean deploy      = config.get('deploy', true)
    String releaseBranch = config.get('releaseBranch', 'main|master')

    pipeline {
        agent any

        tools {
            jdk jdkTool
        }

        triggers {
            githubPush()
        }

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
            disableConcurrentBuilds()
        }

        environment {
            MAVEN_ARGS = '-B -ntp -s .jenkins-settings.xml'
            // Only resolves the tool when one was named; otherwise PATH is left
            // exactly as the agent has it.
            PATH = "${mavenTool ? tool(mavenTool) + '/bin:' : ''}${env.PATH}"
        }

        stages {
            stage('Maven settings') {
                steps {
                    // Needed even for projects with no Nexus dependency,
                    // because Maven resolves a PARENT pom before reading the
                    // project's own <repositories>. Anything inheriting
                    // keystone-parent can only find it via settings.xml, the
                    // local repository, or relativePath.
                    //
                    // No secret is written: the credential references are
                    // ${env.*}, which Maven expands at run time, and those
                    // variables exist only inside the withCredentials blocks
                    // around the deploy steps.
                    // Passed explicitly so the server id and the id in
                    // altDeploymentRepository cannot drift apart.
                    writeNexusSettings(serverId: repoServerId)
                }
            }

            stage('Build') {
                steps {
                    sh 'mvn $MAVEN_ARGS clean verify'
                }
            }

            stage('Verify jar') {
                when { expression { verify != null } }
                steps {
                    // Compilation cannot tell you a jar is laid out wrongly.
                    // Both of the worst bugs these plugins have hit - shade
                    // relocating a consumer's own classes, and a listener that
                    // was never registered - compiled perfectly and only failed
                    // at runtime.
                    verifyJarLayout(verify)
                }
            }

            stage('Deploy snapshot') {
                when {
                    allOf {
                        expression { deploy }
                        not { buildingTag() }
                        branch pattern: releaseBranch, comparator: 'REGEXP'
                    }
                }
                steps {
                    withCredentials([usernamePassword(
                            credentialsId: nexusCredId,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS')]) {
                        // release-please leaves the poms at the last released
                        // version between releases, so deploying as-is would
                        // try to republish something already public. Suffixing
                        // here keeps -SNAPSHOT out of git entirely.
                        //
                        // The case guard makes that idempotent. A project that
                        // has not had its first release yet still has
                        // -SNAPSHOT in the poms, and appending unconditionally
                        // produced `0.1.0-SNAPSHOT-SNAPSHOT` - which Maven
                        // treats as a snapshot and happily deploys, so the
                        // damage is a junk version in Nexus rather than a
                        // build failure. Check, do not assume.
                        //
                        // ::default:: is the layout token maven-deploy-plugin
                        // 2.7 requires in altDeploymentRepository; omitting it
                        // fails to parse.
                        sh """
                            set -e
                            VERSION=\$(mvn \$MAVEN_ARGS -q -DforceStdout help:evaluate -Dexpression=project.version)
                            case "\$VERSION" in
                                *-SNAPSHOT)
                                    echo "Poms are already at \$VERSION - not re-suffixing."
                                    ;;
                                *)
                                    mvn \$MAVEN_ARGS versions:set -DnewVersion="\${VERSION}-SNAPSHOT" \\
                                        -DprocessAllModules=true -DgenerateBackupPoms=false
                                    ;;
                            esac
                            mvn \$MAVEN_ARGS deploy -DskipTests \\
                                -DaltDeploymentRepository='${repoServerId}::default::${snapshotRepo}'
                        """
                    }
                }
            }

            stage('Deploy release') {
                when {
                    allOf {
                        expression { deploy }
                        buildingTag()
                    }
                }
                steps {
                    withCredentials([usernamePassword(
                            credentialsId: nexusCredId,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS')]) {
                        sh "mvn \$MAVEN_ARGS deploy -DskipTests " +
                           "-DaltDeploymentRepository='${repoServerId}::default::${releaseRepo}'"
                    }
                }
            }

            // After the Maven deploy, not before. If Modrinth rejects the
            // upload the build goes red, but the artifact is already in Nexus
            // and every consumer can resolve it - the failure costs you a
            // storefront listing, not the release.
            stage('Publish to Modrinth') {
                when {
                    allOf {
                        buildingTag()
                        expression { modrinth?.projectId }
                    }
                }
                steps {
                    script {
                        publishModrinth(modrinth + [
                            versionNumber: env.TAG_NAME.replaceFirst(/^v/, ''),
                            file:          modrinth.get('file', verify?.jar),
                            changelog:     releaseNotes(env.TAG_NAME)
                        ])
                    }
                }
            }
        }

        post {
            success {
                archiveArtifacts(
                    artifacts: artifacts,
                    excludes: excludes,
                    fingerprint: true,
                    onlyIfSuccessful: true
                )
            }
            always {
                junit testResults: '**/target/surefire-reports/TEST-*.xml', allowEmptyResults: true
            }
            // cleanup, NOT always. Declarative runs post conditions in a fixed
            // order and `always` comes before `success`, so wiping the
            // workspace there deletes every jar before archiveArtifacts can
            // see one. The symptom is
            //
            //     'archiveArtifacts: No artifacts found that match the file
            //      pattern "**/target/*.jar". Configuration error?'
            //
            // on a build whose every stage passed, which reads like a bad glob
            // and is not one - the glob is fine, the files are already gone.
            // `cleanup` is the only post condition guaranteed to run last.
            cleanup {
                // Discord goes here rather than in `always` for the same
                // reason. archiveArtifacts runs in `success`, AFTER `always` -
                // so a notification sent from `always` would report SUCCESS on
                // a build that archiving then failed. That is not theoretical;
                // it is exactly what build #4 did. `cleanup` runs last, so
                // currentBuild.currentResult here is the result the user sees.
                script {
                    String hook = null
                    if (env.TAG_NAME) {
                        hook = discordReleaseCred
                    } else if (env.BRANCH_NAME ==~ /^(?:${releaseBranch})$/) {
                        hook = discordDevCred
                    }
                    // PR and feature-branch builds deliberately stay silent:
                    // they neither publish a snapshot nor cut a release, so
                    // there is nothing to announce.

                    // tokenize, not split('/')[1]: JOB_NAME is
                    // "<org>/<repo>/<branch>" under an organisation folder, but
                    // a plain multibranch job has fewer segments and indexing
                    // blindly would throw. Second-from-last is the repo in both.
                    List jobPath = env.JOB_NAME.tokenize('/')
                    String repoName = jobPath.size() > 1 ? jobPath[-2] : jobPath[0]

                    // Body text. Read from the workspace, which still exists
                    // because cleanWs() runs after this - that ordering is load
                    // bearing, not incidental.
                    //
                    // Both are best-effort and separately guarded: a build that
                    // failed before checkout has no git history and no
                    // CHANGELOG.md, and neither is a reason to lose the
                    // notification that told you it failed.
                    String body = ''
                    if (notifyDiscord && hook) {
                        try {
                            if (env.TAG_NAME) {
                                // The same notes Modrinth publishes, resolved
                                // in one place - see vars/releaseNotes.groovy.
                                body = releaseNotes(env.TAG_NAME)
                            } else {
                                body = sh(returnStdout: true,
                                          script: 'git log -1 --pretty=format:%s%n%n%b').trim()
                            }
                        } catch (err) {
                            echo "Could not read notification body: ${err.message}"
                        }
                        // Discord rejects an embed description over 4096
                        // characters outright, so a long changelog would lose
                        // the whole message rather than the tail of it.
                        if (body.length() > 1500) {
                            body = body.take(1500) + '\n…'
                        }
                    }

                    if (notifyDiscord && hook) {
                        // Never fail a build over a notification. A missing or
                        // revoked webhook credential must not turn a green
                        // build red - and because this is fail-soft, the
                        // library can be rolled out before the credentials
                        // exist rather than after.
                        try {
                            withCredentials([string(credentialsId: hook,
                                                    variable: 'DISCORD_WEBHOOK')]) {
                                discordSend(
                                    webhookURL: env.DISCORD_WEBHOOK,
                                    title: env.TAG_NAME
                                        ? "${repoName} ${env.TAG_NAME} released"
                                        : "${repoName} ${env.BRANCH_NAME} ${env.BUILD_DISPLAY_NAME}",
                                    description: (env.TAG_NAME
                                        ? "Published to ${releaseRepo}"
                                        : "Snapshot from ${env.BRANCH_NAME}")
                                        + (body ? "\n\n${body}" : ''),
                                    link: env.BUILD_URL,
                                    result: currentBuild.currentResult,
                                    // Off for releases: the changelog above is
                                    // the curated version of the same commits,
                                    // and printing both says everything twice.
                                    // On for dev, where a push can batch several
                                    // commits and the body only shows the head.
                                    showChangeset: !env.TAG_NAME,
                                    enableArtifactsList: true,
                                    customUsername: 'bwmp CI'
                                )
                            }
                        } catch (err) {
                            echo "Discord notification skipped: ${err.message}"
                        }
                    }
                }
                // Removed explicitly rather than relying on cleanWs, so a
                // workspace left behind by an aborted build is not a leak.
                sh 'rm -f .jenkins-settings.xml'
                cleanWs()
            }
        }
    }
}
