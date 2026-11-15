package com.enlace.app

import com.enlace.app.modules.actions.ActionsWidget
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the widget arranges the PC's buttons into titled rows. */
class WidgetTest {
    private fun action(id: String, group: String? = null) =
        """{"id":"$id","label":"$id","icon":"dot"${if (group != null) ""","group":"$group"""" else ""}}"""

    @Test fun groupsKeepTheFileOrderAndLooseButtonsGoLast() {
        val list = JSONArray("[" + listOf(
            action("lock"), action("lamp", "Luz"), action("play"), action("dim", "Luz"), action("song", "Música"),
        ).joinToString(",") + "]")
        val groups = ActionsWidget.groups(list)
        assertEquals(listOf("Luz", "Música", "Compu"), groups.map { it.title })
        assertEquals(listOf(listOf("lamp", "dim"), listOf("song"), listOf("lock", "play")), groups.map { g -> g.buttons.map { it.id } })
    }

    @Test fun withoutGroupsThereIsOneUntitledRowOfAtMostSixButtons() {
        val list = JSONArray("[" + (1..8).joinToString(",") { action("a$it") } + "]")
        val groups = ActionsWidget.groups(list)
        assertEquals(listOf(""), groups.map { it.title })
        assertEquals(6, groups[0].buttons.size)
        assertEquals(0, ActionsWidget.groups(JSONArray()).size)
    }
}
