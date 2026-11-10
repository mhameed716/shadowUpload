package com.shadowupload.app.ui.group

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.shadowupload.app.R
import com.shadowupload.app.ShadowUploadApp
import com.shadowupload.app.core.net.HandshakeToken
import com.shadowupload.app.databinding.ActivityGroupBinding
import com.shadowupload.app.databinding.ItemMemberBinding
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.util.NetworkUtils
import com.shadowupload.app.util.UiUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * إنشاء مجموعة أو الانضمام إليها.
 *
 * المنطق:
 *  - المضيف يحجز المنفذ ويولّد رمز جلسة من 6 أرقام، ويعرض عنوانه.
 *  - الضيف يدخل الرمز فيُتحقق منه محلياً ثم يبدأ الاتصال بعنوان المضيف.
 *
 * كل ذلك بدون أي خادم: الرمز مجرد مفتاح اتفاق محلي، والتواصل يجري
 * مباشرة على الشبكة المحلية.
 */
class GroupActivity : BaseActivity() {

    private var _binding: ActivityGroupBinding? = null
    private val binding get() = _binding!!
    private lateinit var app: ShadowUploadApp
    private var hosting = false
    private var tickerJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ShadowUploadApp

        val mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_CREATE
        configureUi(mode)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSecondaryAction.setOnClickListener { leaveGroup() }
    }

    private fun configureUi(mode: String) {
        if (mode == MODE_CREATE) {
            binding.tvGroupTitle.setText(R.string.group_create_title)
            binding.boxGroupName.visibility = View.VISIBLE
            binding.etGroupCode.isEnabled = false
            binding.etGroupCode.setText(HandshakeToken.generateNumericCode())
            binding.btnPrimaryAction.setText(R.string.group_start_hosting)
            binding.btnPrimaryAction.setOnClickListener { createGroup() }
            binding.tvCodeLabel.setText(R.string.group_code_label)
            binding.etGroupName.setText(
                getString(R.string.group_prefix) + " " + app.preferences.deviceName
            )
        } else {
            binding.tvGroupTitle.setText(R.string.join_title)
            binding.boxGroupName.visibility = View.GONE
            binding.etGroupCode.isEnabled = true
            binding.etGroupCode.setText("")
            binding.tvCodeLabel.setText(R.string.join_enter_code)
            binding.btnPrimaryAction.setText(R.string.join_button)
            binding.btnPrimaryAction.setOnClickListener { joinGroup() }
        }
    }

    // ================= إنشاء مجموعة =================

    private fun createGroup() {
        val code = binding.etGroupCode.text?.toString()?.trim().orEmpty()
        if (code.length < 4) {
            UiUtils.snack(binding.root, R.string.group_invalid_code)
            return
        }
        hosting = true
        // بدء خادم الاستقبال فعلياً على المنفذ المحدد
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !com.shadowupload.app.util.PermissionUtils.isGranted(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            )
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS
            )
        }
        com.shadowupload.app.service.TransferService.startReceiving(this)
        binding.tvHostingState.visibility = View.VISIBLE
        binding.tvHostingState.text = getString(
            R.string.group_host_ip,
            "${NetworkUtils.localIpAddress() ?: "—"}:${app.preferences.receivePort}"
        )
        binding.btnPrimaryAction.setText(R.string.group_stop_hosting)
        binding.btnPrimaryAction.setOnClickListener { stopHosting() }
        binding.btnSecondaryAction.visibility = View.VISIBLE
        UiUtils.snack(binding.root, R.string.group_created_success)
    }

    private fun stopHosting() {
        if (hosting) com.shadowupload.app.service.TransferService.stop(this)
        hosting = false
        binding.tvHostingState.visibility = View.GONE
        binding.btnSecondaryAction.visibility = View.GONE
        binding.btnPrimaryAction.setText(R.string.group_start_hosting)
        binding.btnPrimaryAction.setOnClickListener { createGroup() }
    }

    // ================= الانضمام =================

    private fun joinGroup() {
        val code = binding.etGroupCode.text?.toString()?.trim().orEmpty()
        if (code.length < 4) {
            UiUtils.snack(binding.root, R.string.group_invalid_code)
            return
        }
        binding.btnPrimaryAction.isEnabled = false
        binding.btnPrimaryAction.setText(R.string.join_connecting)
        val localIp = NetworkUtils.localIpAddress()
        if (localIp == null) {
            binding.btnPrimaryAction.isEnabled = true
            binding.btnPrimaryAction.setText(R.string.join_button)
            UiUtils.snack(binding.root, R.string.wifi_not_connected)
            return
        }
        // التحقق محلي من صيغة الرمز، ثم البحث عن المضيف في الشبكة
        tickerJob = lifecycleScope.launch {
            val scanner = com.shadowupload.app.core.net.LanScanner(app.preferences.receivePort)
            var found: com.shadowupload.app.core.net.LanScanner.DiscoveredPeer? = null
            scanner.scanOnce(
                onFound = { peer -> if (found == null) found = peer },
                onFinished = { }
            )
            var waited = 0
            while (found == null && waited < 9_000) {
                delay(250)
                waited += 250
            }
            if (_binding == null) return@launch
            binding.btnPrimaryAction.isEnabled = true
            binding.btnPrimaryAction.setText(R.string.join_button)
            val peer = found
            if (peer != null) {
                UiUtils.snack(binding.root, R.string.group_joined_success)
                binding.tvHostingState.visibility = View.VISIBLE
                binding.tvHostingState.text = getString(R.string.transfer_peer_found, peer.name)
                binding.btnSecondaryAction.visibility = View.VISIBLE
                showMembers(listOf(peer.name to peer.ip))
            } else {
                UiUtils.snack(binding.root, R.string.join_failed)
            }
        }
    }

    private fun showMembers(members: List<Pair<String, String>>) {
        binding.tvMembersTitle.visibility = View.VISIBLE
        binding.tvMembersTitle.text = getString(R.string.group_members, members.size)
        binding.recyclerMembers.visibility = View.VISIBLE
        binding.recyclerMembers.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerMembers.adapter = MembersAdapter(members)
    }

    private fun leaveGroup() {
        stopHosting()
        UiUtils.snack(binding.root, R.string.group_left)
        finish()
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        _binding = null
        super.onDestroy()
    }

    /** محوّل بسيط للأعضاء */
    private class MembersAdapter(
        private val members: List<Pair<String, String>>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<MembersAdapter.MemberHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MemberHolder {
            val rowBinding = ItemMemberBinding.inflate(
                android.view.LayoutInflater.from(parent.context), parent, false
            )
            return MemberHolder(rowBinding)
        }

        override fun getItemCount(): Int = members.size

        override fun onBindViewHolder(holder: MemberHolder, position: Int) {
            val (name, ip) = members[position]
            holder.rowBinding.tvMemberName.text = name
            holder.rowBinding.tvMemberIp.text = ip
        }

        class MemberHolder(val rowBinding: ItemMemberBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(rowBinding.root)
    }

    override fun onStop() {
        super.onStop()
        // إيقاف الاستضافة عند مغادرة الشاشة لتفادي خادم معلّق
        if (isFinishing && hosting) {
            com.shadowupload.app.service.TransferService.stop(this)
            hosting = false
        }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CREATE = "create"
        const val MODE_JOIN = "join"
        private const val REQUEST_NOTIFICATIONS = 2077
    }
}
