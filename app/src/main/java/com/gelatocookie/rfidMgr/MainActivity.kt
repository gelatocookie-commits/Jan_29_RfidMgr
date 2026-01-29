package com.gelatocookie.rfidMgr

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.zebra.rfid.api3.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), RFIDManager.RFIDCallback {

    private lateinit var rfidManager: RFIDManager
    private lateinit var uiHandler: MainUIHandler
    private var uiUpdateJob: Job? = null
    private val uiRefreshInterval = 500L
    private var lastTagSnapshot: Map<String, Int> = emptyMap()
    private var tagAdapter: ArrayAdapter<String>? = null
    private var connectionStartTime: Long = 0

    private val CHANNEL_ID = "RFID_STATUS_CHANNEL"
    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        createNotificationChannel()
        setupUI()
        rfidManager = RFIDManager(this, this)
        checkAndInitRFID()
        
        val zebraSerial = getZebraSerialNumber(this)
        Log.d(TAG, "Zebra Serial: $zebraSerial")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "RFID Status"
            val descriptionText = "Notifications for RFID reader status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, message: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Permission not granted, can't show notification
            return
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            notify(System.currentTimeMillis().toInt(), builder.build())
        }
    }

    private fun getZebraSerialNumber(context: Context): String {
        val uri = Uri.parse("content://oem_info/oem.zebra.secure/build_serial")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex("build_serial")
                    if (index != -1) cursor.getString(index) else "Unknown"
                } else "Unknown"
            } ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing OEM Info", e)
            "Unknown"
        }
    }

    private fun checkAndInitRFID() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 600)
        } else {
            rfidManager.init()
        }
    }

    private fun setupUI() {
        tagAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        findViewById<ListView>(R.id.listView_RfidTags).adapter = tagAdapter

        uiHandler = object : MainUIHandler(this) {
            override val emcTextView: TextView? get() = findViewById(R.id.textTitle)
            override val beforeTextView: TextView? get() = findViewById(R.id.textrfidBefore)
            override val statusTextView: TextView? get() = findViewById(R.id.textrfid)
            override val afterTextView: TextView? get() = findViewById(R.id.textrfidAfter)
            override val adapter: ArrayAdapter<String>? get() = tagAdapter
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { rfidManager.startInventory() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { rfidManager.stopInventory() }

        uiHandler.perform(MainUIHandler.UIAction.EmcUpdate("RFID SDK Version: ${BuildConfig.VERSION_NAME}"))
    }

    private fun startUITimer() {
        if (uiUpdateJob?.isActive == true) return
        uiUpdateJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                if (lastTagSnapshot.isNotEmpty()) {
                    uiHandler.perform(MainUIHandler.UIAction.RefreshTagList(lastTagSnapshot))
                }
                delay(uiRefreshInterval)
            }
        }
    }

    private fun stopUITimer() {
        uiUpdateJob?.cancel()
        uiUpdateJob = null
    }

    override fun onEmcUpdate(message: String) = uiHandler.perform(MainUIHandler.UIAction.EmcUpdate(message))
    override fun onBeforeUpdate(message: String) = uiHandler.perform(MainUIHandler.UIAction.BeforeUpdate(message))
    
    override fun onStatusUpdate(message: String) {
        var finalMessage = message
        if (message == "Finding Reader...") {
            connectionStartTime = System.currentTimeMillis()
        } else if (message.startsWith("RFID Connected") || message.startsWith("Error") || message.startsWith("No Readers Found")) {
            if (connectionStartTime > 0) {
                val duration = System.currentTimeMillis() - connectionStartTime
                finalMessage = "$message\n(Connect time: $duration ms)"
                connectionStartTime = 0 // Reset
                Log.d(TAG, "RFID connection took $duration ms")
                showNotification("RFID Status", message)
            }
        }
        uiHandler.perform(MainUIHandler.UIAction.StatusUpdate(finalMessage))
    }

    override fun emcUpdate(message: String) = uiHandler.perform(MainUIHandler.UIAction.EmcUpdate(message))
    override fun onAfterUpdate(message: String) = uiHandler.perform(MainUIHandler.UIAction.AfterUpdate(message))
    override fun onTotalCountUpdate(total: Int) = uiHandler.perform(MainUIHandler.UIAction.TotalCount(total))
    
    override fun onTagsScanned(tagDB: Map<String, Int>) {
        lastTagSnapshot = tagDB
    }

    override fun onInventoryStarted() {
        uiHandler.perform(MainUIHandler.UIAction.ClearTags)
        uiHandler.perform(MainUIHandler.UIAction.StatusUpdate("RFID Status\nScanning..."))
        showNotification("RFID Inventory", "Scanning started...")
        startUITimer()
    }

    override fun onInventoryStopped() {
        stopUITimer()
        uiHandler.perform(MainUIHandler.UIAction.StatusUpdate("RFID Status:\nScan Stopped"))
        showNotification("RFID Inventory", "Scanning stopped.")
        uiHandler.perform(MainUIHandler.UIAction.RefreshTagList(lastTagSnapshot))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUITimer()
        rfidManager.dispose()
    }
}
