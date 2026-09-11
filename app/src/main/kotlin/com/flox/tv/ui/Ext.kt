package com.flox.tv.ui

import android.content.Context
import android.view.View

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(v: Int): Int = context.dp(v)
