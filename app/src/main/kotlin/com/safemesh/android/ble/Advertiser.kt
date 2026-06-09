package com.safemesh.android.ble

import android.annotation.SuppressLint
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log

/**
 * Advertise the SafeMesh service UUID + local_name = `SM-<nick8>`.
 *
 * IMPORTANT: do NOT add ServiceData here — Linux experiments showed that
 * combining a 128-bit Service UUID + Service Data + a local name overflows
 * the 31-byte legacy advertisement and BlueZ rejects it (the Linux CLI
 * "Failed to register advertisement" regression). Identity is exchanged
 * over control gossip after GATT link-up instead.
 */
class Advertiser(private val advertiser: BluetoothLeAdvertiser) {
    private var callback: AdvertiseCallback? = null

    @SuppressLint("MissingPermission")
    fun start(localName: String) {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        // Two ADVs: one with the full service UUID + name (so peers see us).
        // The local name goes in the response payload to keep the primary AD
        // small enough for the 31-byte legacy limit even with a 128-bit UUID.
        val advData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Uuids.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResp = AdvertiseData.Builder()
            .setIncludeDeviceName(true)  // BlueZ reads this for the `name` field
            .build()

        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed: $errorCode")
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "advertising started: $settingsInEffect")
            }
        }
        callback = cb
        // Set the device name so the scan response carries SM-<nick8>.
        try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.name = localName
        } catch (_: SecurityException) { /* permission may be denied */ }
        advertiser.startAdvertising(settings, advData, scanResp, cb)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        callback?.let { advertiser.stopAdvertising(it) }
        callback = null
    }

    companion object { private const val TAG = "SM/Adv" }
}
