package cat.narezany.patcher.data

import android.content.Context
import android.content.pm.PackageManager

/** What is on the phone under the name a mod installs as. */
sealed class Installed {
    /** Nothing. */
    data object Nothing : Installed()

    /** The mod; which version, when it says. Releases before the patcher do not. */
    data class Ours(val version: String?) : Installed()

    /**
     * The app as its own maker signed it. Android will not put the mod over
     * it: it has to be removed first, and its accounts signed in to again.
     */
    data object Theirs : Installed()

    companion object {
        private const val VERSION = "cat.narezany.margyt.version"

        fun of(context: Context, packageName: String): Installed {
            val manager = context.packageManager
            val info = try {
                manager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            } catch (missing: PackageManager.NameNotFoundException) {
                return Nothing
            }
            val said = info.metaData?.get(VERSION)?.toString()
            if (!said.isNullOrEmpty()) return Ours(said)
            // a release from before it said: known by being signed as this app is
            return if (manager.checkSignatures(context.packageName, packageName)
                    == PackageManager.SIGNATURE_MATCH) Ours(null) else Theirs
        }
    }
}

/** Where a mod stands for this person: what they have and what they could have. */
data class Standing(val mod: Mod, val installed: Installed, val latest: Release?) {
    val outdated: Boolean
        get() {
            val have = installed as? Installed.Ours ?: return false
            val newest = latest ?: return false
            return have.version == null || Versions.compare(newest.version, have.version) > 0
        }

    companion object {
        fun of(context: Context, mod: Mod, beta: Boolean) =
            Standing(mod, Installed.of(context, mod.packageName), mod.latest(beta))
    }
}
