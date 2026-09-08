package com.mkvolk.staticserver

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.core.graphics.toColorInt
import android.graphics.Color
import android.telephony.TelephonyManager
import android.view.View
import android.widget.Button
import androidx.annotation.RequiresPermission
import org.w3c.dom.Text

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "server_prefs"
        private const val PREF_FOLDER_URI = "folder_uri"
        private const val PORT = 8080
    }

    private lateinit var dataText: TextView
    private lateinit var hotSpotText: TextView
    private lateinit var folderText: TextView
    private lateinit var statusText: TextView
    private lateinit var urlText: TextView
    private var selectedFolderUri: Uri? = null

    private val folderPicker =
        registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->

            if (uri == null) {
                return@registerForActivityResult
            }

            try {

                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                contentResolver.takePersistableUriPermission(uri, flags)

            } catch (_: Exception) {
                // Some providers may not support persistable permissions.
                Toast.makeText(
                    this,
                    "Error with URI Permissions",
                    Toast.LENGTH_SHORT
                ).show()
            }

            selectedFolderUri = uri

            // Save URI to Preferences
            getPreferences().edit()
                .putString(PREF_FOLDER_URI,uri.toString())
                .apply()

            Toast.makeText(
                this,
                "Folder was selected",
                Toast.LENGTH_SHORT
            ).show()

            // Change UI select folder
            folderText.text =  "Folder: " + getFolderName( uri ) + "/"
            folderText.backgroundTintList = ColorStateList.valueOf(("#D1FFFB".toColorInt()))

        }

    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            // Nothing else required.
        }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onCreate(savedInstanceState: Bundle? ) {

        super.onCreate(savedInstanceState)

        //Grasp and set UI from res/layout/activity_main.xml
        setContentView( R.layout.activity_main_2 )

        folderText = findViewById(R.id.folderText)
        statusText = findViewById(R.id.statusText)
        urlText = findViewById(R.id.urlText)

        dataText = findViewById(R.id.main_mobile_data)
        hotSpotText = findViewById(R.id.main_hotspot)

        val selectFolderButton =
            findViewById<Button>(R.id.selectFolderButton)

        val startButton =
            findViewById<Button>(
                R.id.startButton
            )

        val stopButton =
            findViewById<Button>(
                R.id.stopButton
            )

        val qrButton =
            findViewById<Button>(
                R.id.qrButton
            )

        val infoButton =
            findViewById<Button>(
                R.id.main_info_button
            )

        //Setup Saved data
        loadSavedFolder()
        loadServerState()

        requestNotificationPermission()

        // Start the foreground service to maintain notification
        startServerService()

        selectFolderButton.setOnClickListener {
            folderPicker.launch(null)
        }

        // Try to select the folder with the textView instead
        folderText.setOnClickListener {
            folderPicker.launch(null)
        }

        startButton.setOnClickListener {

            if (selectedFolderUri == null) {

                Toast.makeText( this,
                    "Select a website folder first",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }
            //Change server_state to start
            getPreferences().edit()
                .putString("server_state","start")
                .apply()

            startServer()
        }

        stopButton.setOnClickListener {
            //Change server_state to stop
            getPreferences().edit()
                .putString("server_state","stop")
                .apply()

            stopServer()
        }

        qrButton.setOnClickListener {

            if (!isServerRunning()) {

                Toast.makeText(
                    this,
                    "Start the server first",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            showQrCode()
        }

        infoButton.setOnClickListener {
            showInfo()
        }

        updateUi()
    }

    fun getFolderName(uri: Uri?): String? {
        return uri?.lastPathSegment
            ?.let {Uri.decode(it)}
            ?.filter { it.isLetterOrDigit()}
            ?.takeIf { it.isNotEmpty() }
    }

    fun getLastAlphanumericPart(uri: Uri): String? {
        return Uri.decode(uri.toString())
            .split(Regex("[^A-Za-z0-9]+"))
            .lastOrNull { it.isNotEmpty() }
    }

    private fun getPreferences() =
        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

    private fun loadSavedFolder() {

        //Fetch folder_uri from preferences
        val saved = getPreferences()
                .getString( PREF_FOLDER_URI, null)

        if (saved != null) {

            try {
                selectedFolderUri =
                    Uri.parse(saved)

                // Change UI
                folderText.text = getFolderName(selectedFolderUri)
                folderText.backgroundTintList = ColorStateList.valueOf(("#D1FFFB".toColorInt()))

            } catch (_: Exception) {
                selectedFolderUri = null
            }
        }

    }

    private fun loadServerState(){

        //Fetch server_state from saved preferences
        val state = getPreferences()
            .getString( "server_state", null)

        if (state != null) {

            try {

                if(state == "start"){
                    changeUIStart()
                }else{
                    changeUIStop()
                }


            } catch (_: Exception) {
                //This means its first time opening the app
            }
        }
    }

    private fun requestNotificationPermission() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            if (
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun startServerService() {

        val intent =
            Intent( this, StaticServerService::class.java
            )

        ContextCompat.startForegroundService(this, intent)
    }

    private fun startServer() {

        //Get URI
        val uri = selectedFolderUri
            ?: return

        // Start Server
        val intent =
            Intent( this, StaticServerService::class.java
            ).apply {

                action = StaticServerService.ACTION_START

                putExtra( StaticServerService.EXTRA_FOLDER_URI, uri.toString())
            }

        ContextCompat.startForegroundService(this,intent)

        changeUIStart()

        /*
        // Change UI
        statusText.text = "Status: Running"
        updateUrl()
        displayQrCode()

        val container = findViewById<LinearLayout>(R.id.main_container)
        container.backgroundTintList = ColorStateList.valueOf(("#C8FFF2".toColorInt()))
        */
    }

    private fun stopServer() {

        val intent = Intent(this, StaticServerService::class.java).apply {
                action = StaticServerService.ACTION_STOP
            }

        startService(intent)

        changeUIStop()
        /*
        // Change UI
        statusText.text = "Status: Stopped"
        urlText.text = "URL: —"

        val imageViewEmbed = findViewById<ImageView>(R.id.image_show)
        imageViewEmbed.setImageResource(R.drawable.arf)

        val container = findViewById<LinearLayout>(R.id.main_container)
        container.backgroundTintList = ColorStateList.valueOf(("#D3D3D3".toColorInt()))
        */
    }


    private fun isServerRunning(): Boolean {
        return statusText.text
            .toString()
            .contains( "Running", ignoreCase = true)
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    private fun updateUi() {
        statusText.text = "Status: Stopped"
        urlText.text = "URL: —"

        //Setup information alerts

        if (isDataEnabled()){
            dataText.visibility = View.VISIBLE
        }else{
            dataText.visibility = View.INVISIBLE
        }
        if (!isHotspotEnabled()){
            hotSpotText.visibility = View.INVISIBLE
        }else{
            hotSpotText.visibility = View.VISIBLE
        }
    }

    private fun getLocalIpAddress(): String? {

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()

            while (interfaces.hasMoreElements()) {

                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                val addresses = networkInterface.inetAddresses

                while (addresses.hasMoreElements()) {

                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    /**
     * getServerUrl()
     * This function concatenates the output of getLoalIpAddress() and PORT into a string
     * Returns: String
     * */
    private fun getServerUrl(): String? {

        val ip = getLocalIpAddress() ?: return null

        return "http://$ip:$PORT/"
    }

    private fun updateUrl() {

        val url = getServerUrl()

        if (url == null) {

            urlText.text =
                "URL: Unable to determine local IP"

        } else {

            urlText.text =
                "URL: $url"
        }
    }

    private fun displayQrCode() {

        val url = getServerUrl()

        if (url == null) {

            Toast.makeText(
                this,
                "Unable to determine phone's local IP",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val bitmap =
            generateQrCode(url, 900, 900)

        //Embed QR code in the viewer
        val imageViewEmbed = findViewById<ImageView>(R.id.image_show)
        imageViewEmbed.setImageBitmap(bitmap)
    }

    private fun showQrCode() {

        val url = getServerUrl()

        if (url == null) {

            Toast.makeText( this,
                "Unable to determine phone's local IP",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val bitmap =
            generateQrCode( url, 900, 900)

        //Embed QR code in the viewer
        val imageViewEmbed = findViewById<ImageView>(R.id.image_show)
        imageViewEmbed.setImageBitmap(bitmap)

        val imageView =
            ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                setPadding(32,32,32,32)
            }

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16,16,16,16)
            }

        val urlView =
            TextView(this).apply {
                text = url
                textSize = 16f
                setPadding(16,0,16,16)
            }

        container.addView(
            imageView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        container.addView(
            urlView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle("Scan to open website")
            .setView(container)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showInfo() {

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16,16,16,16)
            }

        val infoText =
            TextView(this).apply {
                text = getString(R.string.main_info_text)
                textSize = 16f
                setPadding(16,0,16,16)
            }

        val urlText =
            TextView(this).apply {
                text = "Github Repository"
                textSize = 16f
                setPadding(16, 0, 16, 16)
                setTextColor(Color.BLUE)
                setOnClickListener {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/MKVolk/PocketApp"))
                    )
                }
            }


        container.addView(
            infoText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            urlText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.main_info_title)
            .setView(container)
            .setPositiveButton(R.string.main_info_close, null)
            .show()
    }

    private fun generateQrCode(text: String,width: Int,height: Int
    ): Bitmap {

        val matrix =
            MultiFormatWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                width,
                height
            )

        val bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (x in 0 until width) {
            for (y in 0 until height) {

                bitmap.setPixel( x, y,
                    if (matrix[x, y]) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                )
            }
        }

        return bitmap
    }

    private fun changeUIStop(){
        statusText.text = "Status: Stopped"
        urlText.text = "URL: —"

        val imageViewEmbed = findViewById<ImageView>(R.id.image_show)
        imageViewEmbed.setImageResource(R.drawable.arf)

        val container = findViewById<LinearLayout>(R.id.main_container)
        container.backgroundTintList = ColorStateList.valueOf(("#D3D3D3".toColorInt()))
    }

    private fun changeUIStart(){
        statusText.text = "Status: Running"
        updateUrl()
        displayQrCode()

        val container = findViewById<LinearLayout>(R.id.main_container)
        container.backgroundTintList = ColorStateList.valueOf(("#C8FFF2".toColorInt()))

        //TODO: Show the URL
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do nothing when activity is closed
    }

    private fun isHotspotEnabled(): Boolean {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // getWifiApState() is hidden from the public SDK, so reflection is required.
            try {
                val method = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true

                val state = method.invoke(wifiManager) as Int

                state == 12 || state == 13
            } catch (e: Exception) {
                false
            }
        } else {
            try {
                val method = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                method.invoke(wifiManager) as Boolean
            } catch (e: Exception) {
                false
            }
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.MODIFY_PHONE_STATE, Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.READ_BASIC_PHONE_STATE, Manifest.permission.READ_PHONE_STATE])
    private fun isDataEnabled(): Boolean {
        val telephonyManager =
            getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                telephonyManager.isDataEnabled
            } catch (e: SecurityException) {
                false
            }
        } else {
            try {
                val method =
                    telephonyManager.javaClass.getDeclaredMethod("getDataEnabled")
                method.isAccessible = true
                method.invoke(telephonyManager) as Boolean
            } catch (e: Exception) {
                false
            }
        }
    }


    /*
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        //Save folderText color
        outState.putInt("folderText_color", folderText.backgroundTintList) //Fix type mismatch

        //Save the container LinearLayout color
        val container = findViewById<LinearLayout>(R.id.main_container)
        outState.putInt("container_color", container.backgroundTintList) //Fix type mismatch

        //Sava the Bitmap image from image_show
        val imageViewEmbed = findViewById<ImageView>(R.id.image_show)
        outState.putInt("image-show_image", imageViewEmbed.backgroundTintList) //Fix type mismatch


    }*/

}
