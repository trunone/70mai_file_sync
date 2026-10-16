package com.lz1bgb.camhttp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.lz1bgb.camhttp.databinding.ItemRemoteFileBinding

data class RemoteFileItem(
    val fileInfo: FileInfo,
    var isSelected: Boolean = false
)

class RemoteFilesAdapter(
    private val onSelectionChanged: (selectedCount: Int) -> Unit
) : RecyclerView.Adapter<RemoteFilesAdapter.RemoteFileViewHolder>() {

    private val items = mutableListOf<RemoteFileItem>()

    fun submitList(newList: List<FileInfo>) {
        items.clear()
        items.addAll(newList.map { RemoteFileItem(it) })
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        items.forEach { it.isSelected = true }
        notifyDataSetChanged()
        onSelectionChanged(getSelectedCount())
    }

    fun deselectAll() {
        items.forEach { it.isSelected = false }
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun getSelectedFiles(): List<FileInfo> {
        return items.filter { it.isSelected }.map { it.fileInfo }
    }

    fun getSelectedCount(): Int {
        return items.count { it.isSelected }
    }

    fun isAllSelected(): Boolean {
        return items.isNotEmpty() && items.all { it.isSelected }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteFileViewHolder {
        val binding = ItemRemoteFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RemoteFileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RemoteFileViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RemoteFileViewHolder(private val binding: ItemRemoteFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RemoteFileItem) {
            binding.tvFileName.text = item.fileInfo.name
            binding.tvFilePath.text = item.fileInfo.path

            // Format file size nicely if valid number
            val sizeBytes = item.fileInfo.size.toLongOrNull()
            binding.tvFileSize.text = if (sizeBytes != null) {
                when {
                    sizeBytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
                    sizeBytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                    sizeBytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", sizeBytes / 1024.0)
                    else -> "$sizeBytes B"
                }
            } else {
                item.fileInfo.size
            }

            binding.cbSelect.isChecked = item.isSelected

            binding.root.setOnClickListener {
                item.isSelected = !item.isSelected
                binding.cbSelect.isChecked = item.isSelected
                onSelectionChanged(getSelectedCount())
            }
        }
    }
}
