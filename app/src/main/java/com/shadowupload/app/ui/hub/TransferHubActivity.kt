package com.shadowupload.app.ui.hub

import android.content.Intent
import android.os.Bundle
import com.shadowupload.app.R
import com.shadowupload.app.databinding.ActivityTransferHubBinding
import com.shadowupload.app.databinding.ItemHubTileBinding
import com.shadowupload.app.ui.clone.CloneActivity
import com.shadowupload.app.ui.common.BaseActivity
import com.shadowupload.app.ui.group.GroupActivity
import com.shadowupload.app.ui.qr.QrShareActivity
import com.shadowupload.app.ui.shake.ShakeConnectActivity
import com.shadowupload.app.ui.wifi.WifiHelpActivity

/**
 * مركز النقل — يُفتح من الزر العائم المركزي فقط.
 *
 * يحتوي الخيارات الستة المطلوبة حرفياً:
 *   1) إنشاء مجموعة
 *   2) انضمام لمجموعة
 *   3) المشاركة عبر الـ QR
 *   4) نسخ هاتف آخر بالكامل
 *   5) تحريك الهاتف للاتصال
 *   6) إعدادات الـ WiFi والمساعدة
 */
class TransferHubActivity : BaseActivity() {

    private lateinit var binding: ActivityTransferHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finishWithAnimation() }
        bindTiles()
        handleIntentMode()
    }

    private fun bindTiles() {
        tile(
            binding.tileCreateGroup, R.drawable.ic_group_create,
            R.string.hub_create_group, R.string.hub_create_group_desc
        ) {
            startActivity(
                Intent(this, GroupActivity::class.java)
                    .putExtra(GroupActivity.EXTRA_MODE, GroupActivity.MODE_CREATE)
            )
        }

        tile(
            binding.tileJoinGroup, R.drawable.ic_group_join,
            R.string.hub_join_group, R.string.hub_join_group_desc
        ) {
            startActivity(
                Intent(this, GroupActivity::class.java)
                    .putExtra(GroupActivity.EXTRA_MODE, GroupActivity.MODE_JOIN)
            )
        }

        tile(
            binding.tileQrShare, R.drawable.ic_qr,
            R.string.hub_qr_share, R.string.hub_qr_share_desc
        ) {
            startActivity(Intent(this, QrShareActivity::class.java))
        }

        tile(
            binding.tileClonePhone, R.drawable.ic_clone,
            R.string.hub_clone_phone, R.string.hub_clone_phone_desc
        ) {
            startActivity(Intent(this, CloneActivity::class.java))
        }

        tile(
            binding.tileShakeConnect, R.drawable.ic_shake,
            R.string.hub_shake_connect, R.string.hub_shake_connect_desc
        ) {
            startActivity(Intent(this, ShakeConnectActivity::class.java))
        }

        tile(
            binding.tileWifiHelp, R.drawable.ic_wifi,
            R.string.hub_wifi_help, R.string.hub_wifi_help_desc
        ) {
            startActivity(Intent(this, WifiHelpActivity::class.java))
        }
    }

    /** يربط بلاطة بخيارها */
    private fun tile(
        tileBinding: ItemHubTileBinding,
        iconRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        action: () -> Unit
    ) {
        tileBinding.ivTileIcon.setImageResource(iconRes)
        tileBinding.tvTileTitle.setText(titleRes)
        tileBinding.tvTileSubtitle.setText(subtitleRes)
        tileBinding.root.contentDescription = getString(
            R.string.a11y_tile,
            getString(titleRes),
            getString(subtitleRes)
        )
        tileBinding.root.setOnClickListener {
            com.shadowupload.app.util.UiUtils.pulse(tileBinding.ivTileIcon)
            action()
        }
    }

    /**
     * عند الدخول من شاشة الملفات:
     *  - وجود ملفات محددة → نفتح QR في وضع الإرسال.
     *  - لا ملفات → نفتح QR في وضع الاستقبال (رمز جاهز).
     */
    private fun handleIntentMode() {
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: return
        val targetMode = when {
            mode == "send" && com.shadowupload.app.ui.transfer.PendingTransfer.hasItems ->
                QrShareActivity.MODE_SEND
            mode == "receive" || mode == "send" -> QrShareActivity.MODE_RECEIVE
            else -> return
        }
        startActivity(
            Intent(this, QrShareActivity::class.java)
                .putExtra(QrShareActivity.EXTRA_MODE, targetMode)
        )
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // نغلقه بأنيميشن مخصص على كل الإصدارات
        finishWithAnimation()
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
    }
}
