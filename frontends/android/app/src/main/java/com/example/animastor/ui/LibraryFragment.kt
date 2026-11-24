package com.example.animastor.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.example.animastor.R
import com.example.animastor.databinding.FragmentLibraryBinding
import com.example.animastor.repository.AuthStore
import com.example.animastor.repository.LibraryTab
import com.example.animastor.repository.MyBookAction
import com.example.animastor.repository.MyBooksModel
import com.example.animastor.repository.RecentBook
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Library — two tabs (2026-11-22, web parity: pages/LibraryPage.tsx,
 * /library?tab=mine|public):
 *  - «Мои книги»: the books of the user's workspace, recently opened first
 *    (GET /api/v1/books). Each: title, format, date, «Открыть», a menu with
 *    «Удалить сгенерированный контент» / «Удалить книгу» (confirmed, the
 *    confirmation names the book). VBook 3.1: «устаревший формат», only
 *    «Удалить книгу». Empty list: «Создать книгу» / «Открыть файл».
 *  - «Общая библиотека»: "coming soon" stub (the public WebView is gone).
 *
 * ONE screen for every shortcut — the File «Библиотека» card, the Settings
 * «Мои книги» row and the account menu all go through
 * [MainActivity.openLibrary] / [MainActivity.openMyBooks].
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {

    companion object {
        const val TAG = "LibraryFragment"
        private const val ARG_TAB = "tab"

        fun newInstance(tab: LibraryTab): LibraryFragment = LibraryFragment().apply {
            arguments = Bundle().apply { putString(ARG_TAB, tab.key) }
        }
    }

    private var binding: FragmentLibraryBinding? = null
    private val viewModel: GenerateViewModel by activityViewModels { GenerateViewModel.factory }
    private var tab: LibraryTab = LibraryTab.MINE
    private var books: List<RecentBook> = emptyList()
    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentLibraryBinding.bind(view)
        tab = LibraryTab.from(savedInstanceState?.getString(ARG_TAB) ?: arguments?.getString(ARG_TAB))

        binding?.toolbar?.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
        binding?.libraryTabs?.let { tabs ->
            tabs.getTabAt(tab.ordinal)?.select()
            tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) {
                    tab = LibraryTab.entries[t.position]
                    render()
                }
                override fun onTabUnselected(t: TabLayout.Tab) {}
                override fun onTabReselected(t: TabLayout.Tab) {}
            })
        }
        render()
    }

    /** [MainActivity.openLibrary] on an already open Library: switch the tab. */
    fun selectTab(target: LibraryTab) {
        tab = target
        binding?.libraryTabs?.getTabAt(target.ordinal)?.select()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ARG_TAB, tab.key)
    }

    private fun render() {
        when (tab) {
            LibraryTab.MINE -> {
                if (!AuthStore.state.value.authenticated) {
                    showMessage(getString(R.string.my_books_login_required), null)
                } else {
                    renderBooks()
                    load()
                }
            }
            LibraryTab.PUBLIC -> {
                loadJob?.cancel()
                binding?.libraryProgress?.visibility = View.GONE
                showMessage(getString(R.string.coming_soon), getString(R.string.library_public_soon))
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        binding?.libraryProgress?.visibility = View.VISIBLE
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { viewModel.repository.fetchMyBooks() }
            binding?.libraryProgress?.visibility = View.GONE
            if (tab != LibraryTab.MINE) return@launch
            result.onSuccess {
                books = MyBooksModel.sort(it)
                renderBooks(loaded = true)
            }.onFailure { e ->
                showError(e.message ?: "")
            }
        }
    }

    private fun content(): LinearLayout? = binding?.libraryContent?.also { it.removeAllViews() }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun color(attr: Int, v: View): Int = MaterialColors.getColor(v, attr)

    private fun showMessage(title: String, body: String?) {
        val root = content() ?: return
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(32), dp(8), dp(16))
        }
        col.addView(TextView(requireContext()).apply {
            text = title; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, this))
        })
        if (body != null) col.addView(TextView(requireContext()).apply {
            text = body; textSize = 14f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, this))
        })
        root.addView(col)
    }

    private fun showError(message: String) {
        val root = content() ?: return
        root.addView(TextView(requireContext()).apply {
            text = getString(R.string.my_books_load_failed, message); textSize = 14f
            setTextColor(color(com.google.android.material.R.attr.colorError, this))
        })
        root.addView(outlinedButton(getString(R.string.my_books_retry)) { load() })
    }

    private fun outlinedButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun renderBooks(loaded: Boolean = false) {
        val root = content() ?: return
        if (books.isEmpty()) {
            if (!loaded) return // the first load is in flight — the progress bar says so
            renderEmpty(root)
            return
        }
        for (book in books) root.addView(bookCard(book))
    }

    private fun renderEmpty(root: LinearLayout) {
        val col = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(32), dp(8), dp(16))
            tag = "my-books-empty"
        }
        col.addView(TextView(requireContext()).apply {
            text = getString(R.string.my_books_empty_title); textSize = 16f; gravity = Gravity.CENTER
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface, this))
        })
        col.addView(TextView(requireContext()).apply {
            text = getString(R.string.my_books_empty_desc); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, this))
        })
        col.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.my_books_create)
            setOnClickListener { (requireActivity() as MainActivity).runOnFileScreen { it.startCreateBook() } }
        })
        col.addView(outlinedButton(getString(R.string.my_books_open_file)) {
            (requireActivity() as MainActivity).runOnFileScreen { it.launchImport() }
        })
        root.addView(col)
    }

    private fun bookCard(book: RecentBook): View {
        val ctx = requireContext()
        val legacy = MyBooksModel.isLegacy(book)
        val title = MyBooksModel.title(book.book_id, book.title)

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) }
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(4), dp(12))
        }
        val info = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(ctx).apply {
            text = title; textSize = 15f; maxLines = 2
            setTextColor(color(
                if (legacy) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface, this))
        })
        val meta = buildList {
            add(MyBooksModel.formatLabel(book))
            if (legacy) add(getString(R.string.my_books_legacy))
            MyBooksModel.moment(book)?.let { m ->
                val date = DateUtils.formatDateTime(ctx, m.at,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
                add(getString(if (m.opened) R.string.my_books_opened_at else R.string.my_books_updated_at, date))
            }
        }
        info.addView(TextView(ctx).apply {
            text = meta.joinToString(" · "); textSize = 12f; setPadding(0, dp(4), 0, 0)
            setTextColor(color(
                if (legacy) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant, this))
        })
        row.addView(info)

        if (!legacy) {
            row.addView(MaterialButton(ctx).apply {
                text = getString(R.string.my_books_open)
                setOnClickListener { open(book) }
            })
        }
        row.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_more_vert)
            contentDescription = getString(R.string.my_books_menu)
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { v -> showMenu(v, book, title) }
        })
        card.addView(row)
        return card
    }

    private fun showMenu(anchor: View, book: RecentBook, title: String) {
        val popup = PopupMenu(requireContext(), anchor)
        MyBooksModel.actions(book).forEachIndexed { i, action ->
            val item = popup.menu.add(0, i, i, getString(
                if (action == MyBookAction.DELETE_CONTENT) R.string.my_books_delete_content else R.string.my_books_delete_book))
            if (action == MyBookAction.DELETE_CONTENT) item.isEnabled = MyBooksModel.contentActionEnabled(book)
        }
        val actions = MyBooksModel.actions(book)
        popup.setOnMenuItemClickListener { item ->
            MyBookActions.confirm(requireActivity(), book.book_id, title, actions[item.itemId]) {
                if (actions[item.itemId] == MyBookAction.DELETE_BOOK) {
                    books = books.filterNot { it.book_id == book.book_id }
                    renderBooks(loaded = true)
                }
                if (isAdded) load()
            }
            true
        }
        popup.show()
    }

    private fun open(book: RecentBook) {
        val activity = requireActivity() as MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            binding?.libraryProgress?.visibility = View.VISIBLE
            val result = viewModel.openBookById(book.book_id)
            binding?.libraryProgress?.visibility = View.GONE
            when (result) {
                is GenerateViewModel.OpenResult.Play -> activity.leaveOverlaysAnd { activity.switchToPlayTab() }
                is GenerateViewModel.OpenResult.Generate -> activity.leaveOverlaysAnd { activity.switchToGenerateTab() }
                // The File screen shows the 3.1 state with «Удалить книгу».
                is GenerateViewModel.OpenResult.Unsupported -> activity.leaveOverlaysAnd { activity.switchToFileTab() }
                is GenerateViewModel.OpenResult.Failed ->
                    Toast.makeText(activity, getString(R.string.my_books_action_failed, result.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        binding = null
        super.onDestroyView()
    }
}
