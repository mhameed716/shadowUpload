package com.shadowupload.app.ui.files.category

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.shadowupload.app.R
import com.shadowupload.app.data.model.FileCategory
import com.shadowupload.app.data.model.FileItem
import com.shadowupload.app.databinding.ItemFileBinding
import com.shadowupload.app.util.FormatUtils

/**
 * محوّل قائمة الملفات.
 *
 * يستخدم DiffUtil لتحديث سلس بلا إعادة رسم كامل — وهو عامل مهم لسلاسة
 * التمرير على قوائم تضم آلاف العناصر على أجهزة API 21.
 */
class FileListAdapter(
    private val onItemClick: (FileItem) -> Unit,
    private val onItemLongClick: (FileItem) -> Boolean,
    private val isSelected: (FileItem) -> Boolean,
    private val selectionActive: () -> Boolean
) : RecyclerView.Adapter<FileListAdapter.FileViewHolder>() {

    private val items = mutableListOf<FileItem>()

    fun submit(newItems: List<FileItem>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos].id == newItems[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldItems[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    /** إعادة رسم حالة التحديد (تُستدعى بعد كل نقرة) */
    fun refreshSelectionState() {
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val binding = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            holder.bindSelection(items[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class FileViewHolder(
        private val binding: ItemFileBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FileItem) {
            binding.tvItemName.text = item.name
            binding.tvItemMeta.text = buildMeta(item)
            binding.ivItemIcon.setImageResource(iconFor(item))
            bindSelection(item)

            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onItemLongClick(item) }
        }

        /** يُحدّث مربع التحديد والشفافية فقط (بلا إعادة رسم كامل) */
        fun bindSelection(item: FileItem) {
            val active = selectionActive()
            val selected = isSelected(item)
            binding.cbSelected.visibility = if (active) View.VISIBLE else View.GONE
            binding.cbSelected.isChecked = selected
            binding.root.alpha = if (active && !selected) 0.7f else 1f
        }

        private fun buildMeta(item: FileItem): String = when {
            item.isDirectory -> binding.root.context.getString(R.string.file_folder)
            item.category == FileCategory.APPS ->
                "${FormatUtils.formatSize(item.size)} • ${item.packageName.orEmpty()}"
            else -> "${FormatUtils.formatSize(item.size)} • ${
                FormatUtils.formatTimestamp(item.lastModified)
            }"
        }

        private fun iconFor(item: FileItem): Int = when {
            item.isDirectory -> R.drawable.ic_folder
            item.category == FileCategory.APPS -> R.drawable.ic_apps
            item.category == FileCategory.VIDEOS -> R.drawable.ic_videos
            item.category == FileCategory.PHOTOS -> R.drawable.ic_photos
            item.category == FileCategory.AUDIO -> R.drawable.ic_audio
            item.category == FileCategory.DOCUMENTS -> R.drawable.ic_documents
            else -> R.drawable.ic_documents
        }
    }

    private companion object {
        const val PAYLOAD_SELECTION = "payload_selection"
    }
}
