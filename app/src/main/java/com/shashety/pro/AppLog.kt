package com.shashety.pro

import android.util.Log

object AppLog {
    private const val TAG = "ShashetyPro"
    fun info(message: String) = Log.i(TAG, message)
    fun error(message: String, error: Throwable? = null) = Log.e(TAG, message, error)
}
