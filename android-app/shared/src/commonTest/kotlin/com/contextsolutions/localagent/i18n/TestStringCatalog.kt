package com.contextsolutions.localagent.i18n

import com.contextsolutions.localagent.language.PreferredLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [StringCatalog] pinned to the English floor ([Strings.ENGLISH]) for tests
 * that construct catalog-consuming classes (notifiers, the task queue) directly.
 * Resolving English keeps the pre-i18n assertions (e.g. "Job finished") intact.
 */
fun englishStringCatalog(): StringCatalog = object : StringCatalog {
    override val active: StateFlow<Strings> = MutableStateFlow(Strings.ENGLISH)
    override fun stringsFor(language: PreferredLanguage): Strings = Strings.ENGLISH
}
