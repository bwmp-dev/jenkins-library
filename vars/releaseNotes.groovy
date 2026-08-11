/**
 * The CHANGELOG.md section for one version, as plain markdown.
 *
 *     releaseNotes('v1.0.2')   // or '1.0.2' - a leading v is stripped
 *
 * Lives here rather than inline because two callers need the same text: the
 * Discord release embed and the Modrinth version changelog. Two copies of the
 * awk below would drift, and the failure mode is silent - you get a release
 * announced with the wrong notes, which nothing flags.
 *
 * Requires a workspace with the repository checked out. Returns '' when there
 * is no CHANGELOG.md, rather than failing: a missing changelog should cost you
 * the release notes, not the release.
 */
def call(String version) {
    String ver = version.replaceFirst(/^v/, '')

    // The section for THIS version. release-please writes
    // "## [1.0.2](...compare/v1.0.1...v1.0.2)", so the heading for 1.0.2 also
    // contains the string 1.0.1 - a substring match hands you the wrong
    // release's notes.
    //
    // The version is compared with ==, never interpolated into a regex. An
    // earlier draft built the pattern "^## \\[?" ver "\\]?" and it matched
    // every heading: passing through Groovy, then the shell, then awk's string
    // parser left a single backslash, awk read "\[" as a plain "[", and the
    // pattern became an optional character class matching any "## " line.
    // Three layers of escaping is not worth defending, so only static regexes
    // appear below.
    String section = sh(returnStdout: true, script: """
        [ -f CHANGELOG.md ] || exit 0
        awk -v ver="${ver}" '
            /^## / {
                if (found) exit
                h = \$0
                sub(/^## /, "", h)
                sub(/^\\[/, "", h)
                n = index(h, "]")
                if (n == 0) n = index(h, " ")
                v = (n > 0) ? substr(h, 1, n - 1) : h
                if (v == ver) { found = 1; next }
            }
            found
        ' CHANGELOG.md
    """).trim()

    // Fallback: the newest section. A tag build has its own release at the top
    // of the file anyway, so this only matters if the heading format moves.
    if (!section) {
        section = sh(returnStdout: true, script: """
            [ -f CHANGELOG.md ] || exit 0
            awk '/^## /{n++} n==1 && !/^## /' CHANGELOG.md
        """).trim()
    }

    return section
}
