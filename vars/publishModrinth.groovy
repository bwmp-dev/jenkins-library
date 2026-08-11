/**
 * Uploads one built jar to Modrinth as a new version.
 *
 *     publishModrinth(
 *         projectId:     'sigil',
 *         versionNumber: '1.0.2',
 *         file:          'sigil-plugin/target/Sigil-*.jar',
 *         loaders:       ['bukkit', 'spigot', 'paper', 'purpur', 'folia'],
 *         gameVersions:  ['1.21.4', '1.21.5'],
 *         changelog:     releaseNotes('v1.0.2')
 *     )
 *
 * The Modrinth project must already exist. Projects are created by hand and a
 * new one is not publicly visible until it passes review, so this publishes
 * versions - it does not onboard a plugin.
 *
 * curl rather than a Jenkins plugin: there is no maintained Modrinth step, and
 * this Jenkins has neither pipeline-utility-steps nor http_request installed.
 */
def call(Map config) {
    String projectId     = config.projectId
    String versionNumber = config.versionNumber
    String fileGlob      = config.file
    String versionName   = config.get('name', versionNumber)
    List   loaders       = config.get('loaders', [])
    List   gameVersions  = config.get('gameVersions', [])
    // Only tag builds reach this, so 'release' is the only sane default.
    String versionType   = config.get('versionType', 'release')
    boolean featured     = config.get('featured', true)
    String credId        = config.get('credentials', 'modrinth-token')
    String changelog     = config.get('changelog', '')
    // Modrinth requires a uniquely-identifying User-Agent and asks that it
    // carry contact information, so they can get in touch rather than block.
    String userAgent     = config.get('userAgent',
                                      'bwmp-dev/jenkins-library (+https://github.com/bwmp-dev)')

    if (!projectId)     { error 'publishModrinth: projectId is required' }
    if (!versionNumber) { error 'publishModrinth: versionNumber is required' }
    if (!fileGlob)      { error 'publishModrinth: file is required' }

    // Built with JsonOutput rather than assembled in shell. The changelog is
    // arbitrary markdown from CHANGELOG.md - it contains quotes, backticks,
    // newlines and brackets, and hand-quoting that into a shell heredoc is how
    // you end up with a corrupted request body on the one release whose notes
    // happened to contain a quote.
    String dataJson = groovy.json.JsonOutput.toJson([
        project_id    : projectId,
        version_number: versionNumber,
        name          : versionName,
        changelog     : changelog,
        game_versions : gameVersions,
        loaders       : loaders,
        version_type  : versionType,
        featured      : featured,
        dependencies  : [],
        file_parts    : ['file'],
        primary_file  : 'file'
    ])

    writeFile file: '.modrinth-version.json', text: dataJson

    withCredentials([string(credentialsId: credId, variable: 'MODRINTH_TOKEN')]) {
        sh """
            set -e
            # set +x for the rest of this script: Jenkins runs sh with tracing,
            # which would echo the Authorization header. credentials-binding
            # masks the value in the log as well, but one layer of protection
            # for a token that can publish releases is not enough.
            set +x

            JAR=\$(ls ${fileGlob} 2>/dev/null | grep -v original | head -1)
            if [ -z "\$JAR" ]; then
                echo "No jar matched ${fileGlob}"
                exit 1
            fi

            # Modrinth rejects a duplicate version_number with a 400, which
            # would turn a re-run of an already-published tag red. Re-running a
            # tag build is a normal thing to do, so treat an existing version as
            # done rather than as a failure.
            EXISTING=\$(curl -sS \\
                -H "User-Agent: ${userAgent}" \\
                "https://api.modrinth.com/v2/project/${projectId}/version" \\
                | grep -c '"version_number":"${versionNumber}"' || true)
            if [ "\$EXISTING" != "0" ]; then
                echo "Modrinth already has ${projectId} ${versionNumber} - skipping upload."
                rm -f .modrinth-version.json
                exit 0
            fi

            echo "Uploading \$JAR to Modrinth project ${projectId} as ${versionNumber}"
            HTTP=\$(curl -sS -o modrinth-response.json -w '%{http_code}' \\
                -X POST https://api.modrinth.com/v2/version \\
                -H "Authorization: \$MODRINTH_TOKEN" \\
                -H "User-Agent: ${userAgent}" \\
                -F 'data=@.modrinth-version.json;type=application/json' \\
                -F file=@"\$JAR")

            rm -f .modrinth-version.json
            if [ "\$HTTP" != "200" ] && [ "\$HTTP" != "201" ]; then
                echo "Modrinth upload failed: HTTP \$HTTP"
                cat modrinth-response.json
                rm -f modrinth-response.json
                exit 1
            fi
            rm -f modrinth-response.json
            echo "Published ${projectId} ${versionNumber} to Modrinth."
        """
    }
}
