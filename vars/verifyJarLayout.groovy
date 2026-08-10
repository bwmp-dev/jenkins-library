/**
 * Asserts what is and is not inside a built jar.
 *
 * Shading failures are invisible to the compiler. A relocation that silently
 * did not happen, or one that moved too much, produces a jar that builds
 * cleanly and dies at runtime with a NoClassDefFoundError naming a class that
 * looks entirely correct. This is the only stage that can catch that.
 *
 *   verifyJarLayout(
 *       jar:       'sigil-plugin/target/Sigil-*.jar',
 *       relocated: ['dev/bwmp/sigil/libs/keystone/'],  // must be present
 *       absent:    ['net/kyori/', 'dev/bwmp/keystone/'], // must NOT be present
 *       present:   ['dev/bwmp/sigil/SigilPlugin.class']  // must survive shading
 *   )
 */
def call(Map config) {
    String jarGlob = config.get('jar', '**/target/*.jar')
    List relocated = config.get('relocated', [])
    List absent    = config.get('absent', [])
    List present   = config.get('present', [])

    // Built as one script so the whole assertion runs in a single shell and
    // reports every failure it can, rather than stopping at the first.
    String script = """
        set -e
        JAR=\$(ls ${jarGlob} 2>/dev/null | grep -v original | head -1)
        if [ -z "\$JAR" ]; then
            echo "No jar matched ${jarGlob}"
            exit 1
        fi
        echo "Checking \$JAR"
        LISTING=\$(unzip -l "\$JAR")
        FAILED=0
    """

    absent.each { pattern ->
        script += """
        if echo "\$LISTING" | grep -q ' ${pattern}'; then
            echo "FAIL: '${pattern}' must not be in the jar (shading did not relocate it)"
            echo "\$LISTING" | grep ' ${pattern}' | head -5
            FAILED=1
        fi
        """
    }

    relocated.each { pattern ->
        script += """
        if ! echo "\$LISTING" | grep -q '${pattern}'; then
            echo "FAIL: expected relocated classes at '${pattern}' but found none"
            FAILED=1
        fi
        """
    }

    present.each { pattern ->
        script += """
        if ! echo "\$LISTING" | grep -q '${pattern}'; then
            echo "FAIL: '${pattern}' is missing - it was probably relocated by mistake."
            echo "      shade matches relocation patterns by raw string prefix, so a"
            echo "      package sharing a prefix with a relocated one gets moved too."
            FAILED=1
        fi
        """
    }

    script += """
        [ "\$FAILED" = "0" ] || exit 1
        echo "Jar layout OK."
    """

    sh script
}
