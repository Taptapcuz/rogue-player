package com.rogueplayer.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/**
 * Search entry point for the car YouTube screen.
 *
 * A navigation surface has no keyboard, and Android Auto provides no IMG for a
 * WebView rendered on a VirtualDisplay — so the page's own search box cannot be
 * typed into from the car. The supported way to get text is an Android Auto search
 * surface, which sites on a head unit using its built-in dictation (and a typing
 * widget where the hardware supports it). This screen collects the query and hands
 * the finished string back to [CarWebScreen.runSearch], which loads
 * `m.youtube.com/results?search_query=...` into the projected page.
 */
class SearchScreen(
    private val ctx: CarContext,
    private val onSearch: (String) -> Unit,
) : Screen(ctx) {

    private var query = ""

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            query = searchText
            invalidate()
        }

        override fun onSearchSubmitted(searchText: String) {
            submit(searchText)
        }
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().addItem(
            if (query.isBlank()) {
                Row.Builder()
                    .setTitle("Type or speak a YouTube search")
                    .build()
            } else {
                Row.Builder()
                    .setTitle(query)
                    .addText("Search YouTube")
                    .setOnClickListener { submit(query) }
                    .build()
            }
        ).build()

        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(query)
            .setSearchHint("Search YouTube")
            .setItemList(list)
            .setShowKeyboardByDefault(true)
            .build()
    }

    /** Run the search and leave the search surface; the web screen behind loads results. */
    private fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        Log.i(TAG, "search: $q")
        onSearch(q)
        // Pop on the next loop so we don't tear the host's template down from inside
        // its own callback.
        handler.post { screenManager.pop() }
    }

    private companion object {
        const val TAG = "CarSearchScreen"
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
    }
}
