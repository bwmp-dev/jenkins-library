/**
 * The build every bwmp Maven plugin uses.
 *
 * Usage in a plugin's Jenkinsfile:
 *
 *     @Library('bwmp') _
 *     mavenPlugin(
 *         jdk: 'Java 21',
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
    // JDK 21 rather than 17: these projects emit Java 17 bytecode via
    // --release, but compiling against paper-api 26.x needs a compiler new
    // enough to read its class files.
    String jdkTool      = config.get('jdk', 'Java 21')
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
                    writeNexusSettings()
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
                        sh '''
                            VERSION=$(mvn $MAVEN_ARGS -q -DforceStdout help:evaluate -Dexpression=project.version)
                            mvn $MAVEN_ARGS versions:set -DnewVersion="${VERSION}-SNAPSHOT" \
                                -DprocessAllModules=true -DgenerateBackupPoms=false
                            mvn $MAVEN_ARGS deploy -DskipTests
                        '''
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
                        sh 'mvn $MAVEN_ARGS deploy -DskipTests'
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
                // Removed explicitly rather than relying on cleanWs, so a
                // workspace left behind by an aborted build is not a leak.
                sh 'rm -f .jenkins-settings.xml'
                cleanWs()
            }
        }
    }
}
