package com.shadowupload.app.ui.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.data.model.TransferDirection
import com.shadowupload.app.data.model.TransferRecord
import com.shadowupload.app.databinding.ActivityHistoryBinding
import com.shadowupload.app.databinding.ItemHistoryBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.FormatUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.launch

/**
 * سجل النقل: كل العمليات السابقة مع الاتجاه والحجم والسرعة.
 */
class TransferHistoryActivity : BaseActivity() {

    private var _binding: ActivityHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: ShadowUploadApp
    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ShadowUploadApp

        binding.btnBack.setOnClickListener { finish() }
        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        binding.recyclerHistory.adapter = adapter
        binding.btnClearHistory.setOnClickListener { confirmClear() }
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            val records = app.historyRepository.allRecords()
            if (_binding == null) return@launch
            adapter.submit(records)
            binding.emptyHistory.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerHistory.visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear)
            .setMessage(R.string.me_clear_history_confirm)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                lifecycleScope.launch {
                    app.historyRepository.clearAll()
                    if (_binding != null) {
                        adapter.submit(emptyList())
                        binding.emptyHistory.visibility = View.VISIBLE
                        binding.recyclerHistory.visibility = View.GONE
                        UiUtils.snack(binding.root, R.string.history_cleared)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    /** محوّل السجل */
    private class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.HistoryHolder>() {

        private val records = mutableListOf<TransferRecord>()

        fun submit(newRecords: List<TransferRecord>) {
            records.clear()
            records.addAll(newRecords)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder {
            val rowBinding = ItemHistoryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return HistoryHolder(rowBinding)
        }

        override fun getItemCount(): Int = records.size

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            holder.bind(records[position])
        }

        class HistoryHolder(private val rowBinding: ItemHistoryBinding) :
            RecyclerView.ViewHolder(rowBinding.root) {

            fun bind(record: TransferRecord) {
                val context = rowBinding.root.context
                val isSend = record.direction == TransferDirection.SEND
                rowBinding.tvHistoryTitle.text = if (record.sampleFileName.isNotBlank()) {
                    record.sampleFileName
                } else {
                    context.getString(if (isSend) R.string.history_sent else R.string.history_received)
                }
                rowBinding.tvHistoryMeta.text = context.getString(
                    R.string.history_item_summary,
                    record.fileCount,
                    FormatUtils.formatSize(record.totalBytes)
                ) + " • " + FormatUtils.formatSpeed(record.averageSpeed)
                rowBinding.tvHistoryTime.text = FormatUtils.formatTimestamp(record.timestamp)
                rowBinding.ivDirection.setImageResource(
                    if (isSend) R.drawable.ic_send else R.drawable.ic_check
                )
                rowBinding.root.alpha = if (record.success) 1f else 0.6f
            }
        }
    }
}
