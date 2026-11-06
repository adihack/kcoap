/**
 * Who makes kcoap and where it lives: written into every POM (`kcoap.publishing`) and into the footer of
 * the API reference (`kcoap.docs` and `kcoap.publishing`). The license comes from gradle.properties
 * (`kcoap.license.name`, `kcoap.license.url`), the repository from `kcoap.repository`.
 */
object KcoapProject {
    /** The project's website, the POM's `url`. */
    const val WEBSITE: String = "https://kcoap.dev"

    /** The maintainer, the POM's only developer. */
    const val MAINTAINER_ID: String = "adihack"
    const val MAINTAINER_NAME: String = "Adrian Sołtys"
    const val MAINTAINER_EMAIL: String = "adrian.soltys@seelso.net"
    const val MAINTAINER_URL: String = "https://github.com/adihack"

    /** The footer of every page of the API reference (Dokka's HTML `footerMessage`). */
    const val DOKKA_FOOTER: String =
        "© 2026 Adrian Sołtys and the kcoap contributors · Apache License 2.0 · kcoap™ is a trademark of Adrian Sołtys"
}
