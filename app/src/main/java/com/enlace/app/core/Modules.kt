package com.enlace.app.core

import android.view.View
import com.enlace.app.MainActivity
import com.enlace.app.modules.actions.ActionsModule
import com.enlace.app.modules.mic.MicModule
import com.enlace.app.modules.share.ShareModule

/**
 * A feature of the link. Each one lives in its own package under modules/.
 *
 * To add one: create the package, implement this, and add it to [Modules.all]
 * (plus its manifest entries if it has a service or activity). The PC side is
 * a file in pc/src/enlace/modules/. A module the PC does not have is simply
 * not shown. See docs/modules.md.
 */
interface Module {
    /** The same name the PC module uses; it travels in every frame. */
    val name: String

    /** The module's card on the main screen, or null if it has nothing to show there. */
    fun card(activity: MainActivity): View?

    /** The main screen became visible again; refresh what the card shows. */
    fun refresh(activity: MainActivity) {}
}

object Modules {
    val all: List<Module> = listOf(MicModule, ShareModule, ActionsModule)
    val names: List<String> get() = all.map { it.name }
}
