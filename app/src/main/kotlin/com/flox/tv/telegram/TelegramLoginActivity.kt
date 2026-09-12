package com.flox.tv.telegram

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import com.flox.tv.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class TelegramLoginActivity : Activity() {
    private lateinit var qr: ImageView
    private lateinit var state: TextView
    private lateinit var password: EditText
    private lateinit var submit: Button

    private val listener: (Telegram.Auth) -> Unit = { a -> runOnUiThread { render(a) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_login)
        qr = findViewById(R.id.login_qr)
        state = findViewById(R.id.login_state)
        password = findViewById(R.id.login_password)
        submit = findViewById(R.id.login_submit)
        submit.setOnClickListener { Telegram.sendPassword(password.text.toString()) }
        if (!Telegram.configured) {
            state.text = getString(R.string.telegram_not_configured)
            return
        }
        Telegram.start(this)
        Telegram.addAuthListener(listener)
    }

    override fun onDestroy() {
        Telegram.removeAuthListener(listener)
        super.onDestroy()
    }

    private fun render(a: Telegram.Auth) {
        val needsPassword = a == Telegram.Auth.Password
        password.visibility = if (needsPassword) View.VISIBLE else View.GONE
        submit.visibility = password.visibility
        qr.visibility = if (a is Telegram.Auth.Qr) View.VISIBLE else View.INVISIBLE
        when (a) {
            is Telegram.Auth.Qr -> { qr.setImageBitmap(encode(a.link)); state.text = getString(R.string.telegram_scan) }
            Telegram.Auth.Password -> { state.text = getString(R.string.telegram_password); password.requestFocus() }
            Telegram.Auth.Ready -> { setResult(RESULT_OK); finish() }
            is Telegram.Auth.Failed -> state.text = a.message.uppercase()
            else -> state.text = getString(R.string.state_loading)
        }
    }

    private fun encode(text: String): Bitmap {
        val size = resources.getDimensionPixelSize(R.dimen.qr_size)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
