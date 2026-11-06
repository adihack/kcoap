package dev.kcoap.sample.android

import android.app.Application
import dev.kcoap.crypto.CryptoBackend

/**
 * The app's process. What kcoap needs once per process lives here rather than in [MainActivity], which
 * Android may create several times, and run twice at once in two tasks.
 */
class SampleApplication : Application() {
    /** This flavor's crypto backend (CryptoSetup.kt). */
    internal val crypto: CryptoBackend by lazy { cryptoBackend() }

    /**
     * The one OSCORE association, with its sequence numbers under `noBackupFilesDir`: Auto Backup skips that
     * directory, and a sequence number brought back from a backup reuses nonces (RFC 8613 Appendix B.1.1).
     */
    internal val oscore: OscoreAssociation by lazy { OscoreAssociation(crypto, noBackupFilesDir) }
}
