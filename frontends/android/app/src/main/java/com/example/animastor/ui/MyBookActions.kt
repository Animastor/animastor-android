package com.example.animastor.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.animastor.R
import com.example.animastor.databinding.DialogDeleteVbookBinding
import com.example.animastor.repository.MyBookAction
import kotlinx.coroutines.launch

/**
 * «Мои книги» — the one confirmation + execution of a book action, shared by
 * the Library list, the File-screen 3.1 card and the editor's 3.1 state (web
 * parity: features/myBooks/ConfirmBookAction.tsx + myBooksStore.runBookAction).
 *
 * The confirmation names the book. The server answers for the caller's own
 * workspace only (403 otherwise). When the book is the one open on this
 * device, the client side goes too: «delete content» resets the player and
 * the progress (the book stays open, its structure is intact); «delete book»
 * closes the book in both ViewModels.
 */
object MyBookActions {

    fun confirmText(ctx: Context, action: MyBookAction, title: String): String = when (action) {
        MyBookAction.DELETE_CONTENT -> ctx.getString(R.string.my_books_delete_content_confirm, title)
        MyBookAction.DELETE_BOOK -> ctx.getString(R.string.my_books_delete_book_confirm, title)
    }

    private fun label(ctx: Context, action: MyBookAction): String = when (action) {
        MyBookAction.DELETE_CONTENT -> ctx.getString(R.string.my_books_delete_content)
        MyBookAction.DELETE_BOOK -> ctx.getString(R.string.my_books_delete_book)
    }

    fun confirm(
        activity: FragmentActivity,
        bookId: String,
        title: String,
        action: MyBookAction,
        onDone: () -> Unit = {},
    ) {
        val body = DialogDeleteVbookBinding.inflate(LayoutInflater.from(activity))
        body.dialogMessage.text = confirmText(activity, action, title)
        AppDialogs.action(
            ctx = activity,
            title = label(activity, action),
            content = body.root,
            cancelText = activity.getString(R.string.dialog_cancel),
            actionText = label(activity, action),
            destructive = true,
        ) { dlg ->
            dlg.dismiss()
            run(activity, bookId, action, onDone)
        }.show()
    }

    private fun run(activity: FragmentActivity, bookId: String, action: MyBookAction, onDone: () -> Unit) {
        val vm = ViewModelProvider(activity, GenerateViewModel.factory)[GenerateViewModel::class.java]
        val playback = ViewModelProvider(activity, PlaybackViewModel.factory)[PlaybackViewModel::class.java]
        activity.lifecycleScope.launch {
            runCatching {
                val isOpen = vm.bookId == bookId
                when (action) {
                    MyBookAction.DELETE_CONTENT -> {
                        vm.repository.clearBookCache(bookId)
                        if (isOpen) {
                            vm.repository.clearCache()
                            clearLocalMediaFiles(activity)
                            playback.closeBook()
                            vm.resetProgressState()
                        }
                    }
                    MyBookAction.DELETE_BOOK -> {
                        vm.repository.deleteBook(bookId)
                        if (isOpen) {
                            vm.repository.clearCache()
                            clearLocalMediaFiles(activity)
                            vm.closeBook()
                            playback.closeBook()
                        }
                        vm.clearUnsupportedBook(bookId)
                    }
                }
            }.onSuccess {
                Toast.makeText(
                    activity,
                    if (action == MyBookAction.DELETE_CONTENT) R.string.my_books_content_deleted else R.string.my_books_book_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                onDone()
            }.onFailure { e ->
                Toast.makeText(activity, activity.getString(R.string.my_books_action_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Local temp media of the player (chunk MP3, video, scene audio) — as the old Settings buttons did. */
    private fun clearLocalMediaFiles(ctx: Context) {
        ctx.cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("chunk-") || file.name.startsWith("video-") || file.name.startsWith("scene_audio-")) {
                file.delete()
            }
        }
    }
}
