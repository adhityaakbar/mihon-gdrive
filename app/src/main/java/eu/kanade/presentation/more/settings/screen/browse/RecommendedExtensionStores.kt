package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.runtime.Immutable
import dev.icerock.moko.resources.StringResource
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.i18n.MR

/**
 * Curated third-party extension stores offered as one-tap additions so users can read
 * from online sources besides Google Drive.
 *
 * [matchKey] identifies the store regardless of which index URL ends up stored, since the
 * service may rewrite `index.min.json` to `repo.json` or follow `index_v2`.
 */
@Immutable
data class RecommendedExtensionStore(
    val name: String,
    val indexUrl: String,
    val githubUrl: String,
    val description: StringResource,
    private val matchKey: String,
) {
    fun isAdded(stores: List<ExtensionStore>): Boolean =
        stores.any { it.indexUrl.contains(matchKey, ignoreCase = true) }
}

val RECOMMENDED_EXTENSION_STORES = listOf(
    RecommendedExtensionStore(
        name = "Keiyoushi",
        indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
        githubUrl = "https://github.com/keiyoushi/extensions",
        description = MR.strings.extensionStoresScreen_recommended_keiyoushi,
        matchKey = "keiyoushi/extensions",
    ),
    RecommendedExtensionStore(
        name = "Yūzōnō",
        indexUrl = "https://raw.githubusercontent.com/yuzono/manga-repo/repo/index.min.json",
        githubUrl = "https://github.com/yuzono/manga-repo",
        description = MR.strings.extensionStoresScreen_recommended_yuzono,
        matchKey = "yuzono/manga-repo",
    ),
)
