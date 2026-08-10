/**
 * Writes the Maven settings.xml every build uses.
 *
 * Releases and snapshots are deliberately separate repositories rather than one
 * group. nexus.bwmp.dev's maven-public contains releases and central only, so
 * naming maven-snapshots explicitly here is what lets CI consume an unreleased
 * Keystone — while a public consumer building from source, who has no such
 * settings file, structurally cannot resolve one by accident.
 *
 * No credential is written. ${env.NEXUS_USER} and ${env.NEXUS_PASS} are
 * expanded by Maven at run time, and only exist inside the withCredentials
 * blocks wrapped around the deploy steps.
 */
def call(Map config = [:]) {
    String host = config.get('host', 'https://nexus.bwmp.dev')
    String serverId = config.get('serverId', 'nexus-site')

    writeFile file: '.jenkins-settings.xml', text: """<settings>
  <servers>
    <server>
      <id>${serverId}</id>
      <username>\${env.NEXUS_USER}</username>
      <password>\${env.NEXUS_PASS}</password>
    </server>
  </servers>
  <profiles>
    <profile>
      <id>bwmp-nexus</id>
      <repositories>
        <repository>
          <id>bwmp-nexus</id>
          <url>${host}/repository/maven-public/</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>false</enabled></snapshots>
        </repository>
        <repository>
          <id>bwmp-nexus-snapshots</id>
          <url>${host}/repository/maven-snapshots/</url>
          <releases><enabled>false</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>bwmp-nexus</activeProfile>
  </activeProfiles>
</settings>
"""
}
