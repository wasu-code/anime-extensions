package kuchihige.utils

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

/**
 * Returns the first filter in this [AnimeFilterList] that matches the given type [T].
 *
 * Example:
 * ```
 * val authorFilter = filters.get<AuthorFilter>()
 * ```
 *
 * @throws NoSuchElementException if no filter of type [T] exists in the list.
 */
inline fun <reified T> AnimeFilterList.get(): T =
    first { it is T } as T
