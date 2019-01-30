package com.revenuecat.sample

import android.content.Context
import android.content.Intent

fun Context.startCatsActivity() {
    startActivity(Intent(this, CatsActivity::class.java))
}
fun Context.startUpsellActivity() {
    startActivity(Intent(this, UpsellActivity::class.java))
}