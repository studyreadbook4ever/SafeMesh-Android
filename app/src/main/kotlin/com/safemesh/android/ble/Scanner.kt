package com.safemesh.android.ble

import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 3-seconds-ON / 15-seconds-OFF duty-cycle scanner — mirrors Rust
 * `ble::ScanDuty`. Filters by SafeMesh service UUID. Forwards each scan
 * result to the supplied callback.
 *
 * Bitchat keeps scanning continuously and modulates by power state instead,
 * but the project spec calls for the Linux-style 3/15 duty cycle here.
 *
 * NOTE: we deliberately do NOT pause discovery while connections are
 * established (the established connection isn't tied to LE scan). The
 * connect-safe extension (don't stop scan while a connect is in flight)
 * lives in the caller — for this mockup we keep it simple.
 */
class Scanner(
    private val scanner: BluetoothLeScanner,
    private val scope: CoroutineScope,
    private val onResult: (ScanResult) -> Unit
) {
    private var loopJob: Job? = null
    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            onResult(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(onResult)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
        }
    }

    fun start() {
        loopJob?.cancel()
        loopJob = scope.launch { dutyLoop() }
    }

    fun stop() {
        loopJob?.cancel(); loopJob = null
        stopScan()
    }

    @SuppressLint("MissingPermission")
    private suspend fun dutyLoop() {
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(Uuids.SERVICE)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        while (scope.isActive) {
            try { scanner.startScan(filters, settings, cb) } catch (t: Throwable) {
                Log.w(TAG, "startScan threw: $t")
            }
            delay(SCAN_ON_MS)
            stopScan()
            delay(SCAN_OFF_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { scanner.stopScan(cb) } catch (_: Throwable) {}
    }

    companion object {
        const val SCAN_ON_MS: Long = 3_000
        const val SCAN_OFF_MS: Long = 15_000
        private const val TAG = "SM/Scan"
    }
}
