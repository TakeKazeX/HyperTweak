package com.takekazex.hypertweak.util

/**
 * Orders the restart picker without making a checkbox tap move its row.
 *
 * The caller supplies the list in its stable base order and only re-runs this function when the
 * dialog's target set or automatically detected pending set changes. Thus manually checked rows
 * remain where they were during the current dialog session.
 */
internal fun smartRestartScopeOrder(
    packages: List<String>,
    restartCounts: Map<String, Int>,
    automaticallySelectedPackages: Set<String>
): List<String> = packages
    .withIndex()
    .sortedWith(
        compareByDescending<IndexedValue<String>> {
            restartCounts[it.value]?.coerceAtLeast(0) ?: 0
        }
            .thenBy { if (it.value in automaticallySelectedPackages) 0 else 1 }
            .thenBy { it.index }
    )
    .map { it.value }
