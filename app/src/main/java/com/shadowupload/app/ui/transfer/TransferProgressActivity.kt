package com.shadowupload.app.ui.transfer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.lifecycle.lifecycleScope
import com.shadowupload.app.R
import com.shadowupload.app.data.model.TransferProgress
import com.shadowupload.app.data.model.TransferState
import com.shadowupload.app.databinding.ActivityTransferProgressBinding
import com.shadowupload.app.service.TransferService
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.FormatUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * شاشة تقدم النقل: تعرض سرعة لحظية، النسبة، والوقت المتبقي،
 * وتستمع لخدمة النقل عبر LiveData.
 */
class TransferProgressActivity : BaseActivity() {

    private var _binding: ActivityTransferProgressBinding? = null
    private val binding get() = _binding!!

    private var service: TransferService? = null
    private var bound = false
    private var uiJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? TransferService.LocalBinder ?: return
            service = local.service
            bound = true
            observeProgress()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityTransferProgressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancelTransfer.setOnClickListener {
            service?.cancelCurrentTransfer()
            finish()
        }
        binding.pbTransfer.max = 100
        bindService()
        startFallbackTicker()
    }

    private fun bindService() {
        val intent = Intent(this, TransferService::class.java)
        runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }
    }

    /** مراقبة تقدم الخدمة */
    private fun observeProgress() {
        val transferService = service ?: return
        transferService.progress.observe(this) { progress ->
            if (_binding == null || progress == null) return@observe
            render(progress)
        }
    }

    /** رسم التقدم */
    private fun render(progress: TransferProgress) {
        val percent = progress.percent
        binding.pbTransfer.progress = percent.toInt()
        binding.tvPercent.text = FormatUtils.formatPercent(percent)
        binding.tvSpeed.text = FormatUtils.formatSpeed(progress.bytesPerSecond)
        binding.tvEta.text = getString(R.string.transfer_eta, FormatUtils.formatDuration(progress.etaMillis))
        binding.tvTransferFile.text = progress.currentFileName.ifBlank {
            getString(R.string.transfer_preparing)
        }
        binding.tvTransferState.setText(
            when (progress.state) {
                TransferState.CONNECTING -> R.string.transfer_connecting
                TransferState.HANDSHAKING -> R.string.qr_handshake
                TransferState.WAITING_PEER -> R.string.transfer_waiting_peer
                TransferState.VERIFYING -> R.string.settings_verify_checksum
                TransferState.COMPLETED -> R.string.transfer_done
                TransferState.FAILED -> R.string.transfer_failed
                TransferState.CANCELLED -> R.string.transfer_cancelled
                else -> R.string.transfer_title
            }
        )
        binding.tvTransferDetail.text = if (progress.totalFiles > 0) {
            getString(
                R.string.transfer_file_of,
                progress.currentFileIndex,
                progress.totalFiles
            ) + " • " + FormatUtils.formatSize(progress.bytesDone) + " / " +
                FormatUtils.formatSize(progress.bytesTotal)
        } else {
            progress.message
        }
        if (progress.state == TransferState.COMPLETED) {
            binding.btnCancelTransfer.setText(R.string.action_done)
            binding.btnCancelTransfer.setOnClickListener { finish() }
        }
    }

    /** مؤشر اهتزازي إن لم تكن الخدمة مربوطة بعد */
    private fun startFallbackTicker() {
        uiJob = lifecycleScope.launch {
            var shown = 0
            while (_binding != null && !bound) {
                delay(120)
                shown = (shown + 7) % 100
                binding.pbTransfer.progress = shown
            }
        }
    }

    override fun onDestroy() {
        uiJob?.cancel()
        if (bound) runCatching { unbindService(connection) }
        bound = false
        _binding = null
        super.onDestroy()
    }
}
