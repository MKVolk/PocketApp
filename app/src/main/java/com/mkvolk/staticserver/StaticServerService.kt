package com.mkvolk.staticserver

//import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class StaticServerService : Service() {

    companion object {
        const val ACTION_START = "com.mkvolk.staticserver.START"
        const val ACTION_STOP = "com.mkvolk.staticserver.STOP"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val PORT = 8080
        private const val CHANNEL_ID = "local_static_server"
        private const val NOTIFICATION_ID = 1001
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile
    private var serverRunning = false

    private var rootUri: Uri? = null

    private val executor = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Start as a foreground service immediately.
        updateNotification("Server stopped")

        startForegroundServiceCompat()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_START -> {
                val uriString = intent.getStringExtra(EXTRA_FOLDER_URI)

                if (uriString != null) {
                    rootUri = Uri.parse(uriString)
                }
                startServer()
            }

            ACTION_STOP -> {
                stopServer()
            }
        }

        return START_STICKY
    }

    private fun startForegroundServiceCompat() {

        val notification = createNotification("Server stopped")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startServer() {

        // Do nothing if server already running
        if (serverRunning) {
            return
        }

        if (rootUri == null) {
            updateNotification("Server stopped — no folder selected")
            return
        }

        try {

            serverSocket = ServerSocket(PORT)
            serverRunning = true
            updateNotification("Server running on port $PORT")

            serverThread = Thread {

                while (serverRunning) {

                    try {

                        val socket = serverSocket?.accept()

                        if (socket != null) {
                            executor.execute {
                                handleClient(socket)
                            }
                        }

                    } catch (_: Exception) {

                        if (serverRunning) {
                            updateNotification("Server error")
                        }
                    }
                }

            }.apply {
                start()
            }

        } catch (e: Exception) {
            serverRunning = false
            updateNotification(
                "Server failed: ${e.message ?: "unknown error"}"
            )
        }
    }

    private fun stopServer() {

        serverRunning = false

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        serverSocket = null

        updateNotification("Server stopped")
    }

    private fun handleClient(socket: Socket) {

        socket.use {

            try {

                socket.soTimeout = 10_000

                val reader = BufferedReader(
                    InputStreamReader(
                        socket.getInputStream(),
                        StandardCharsets.US_ASCII
                    )
                )

                val requestLine = reader.readLine()
                    ?: return

                // Read and discard HTTP headers.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                val parts = requestLine.split(" ")

                if (parts.size < 2) {
                    sendError(socket, 400, "Bad Request")
                    return
                }

                val method = parts[0]
                val rawPath = parts[1]

                if (method != "GET" && method != "HEAD") {
                    sendError(
                        socket,
                        405,
                        "Method Not Allowed"
                    )
                    return
                }

                val pathOnly = rawPath.substringBefore("?")

                val decodedPath = try {
                    URLDecoder.decode(
                        pathOnly,
                        StandardCharsets.UTF_8.name()
                    )
                } catch (_: Exception) {
                    sendError(
                        socket,
                        400,
                        "Bad Request"
                    )
                    return
                }

                // Protect against path traversal.
                if (
                    decodedPath.contains("..") ||
                    decodedPath.contains("\\")
                ) {
                    sendError(
                        socket,
                        403,
                        "Forbidden"
                    )
                    return
                }

                var cleanPath = decodedPath

                if (cleanPath.startsWith("/")) {
                    cleanPath = cleanPath.substring(1)
                }

                val root = rootUri?.let {
                    DocumentFile.fromTreeUri(
                        this,
                        it
                    )
                }

                if (root == null) {
                    sendError(
                        socket,
                        500,
                        "No website folder selected"
                    )
                    return
                }

                var file: DocumentFile = root
                //var file = root

                if (cleanPath.isNotEmpty()) {

                    val components = cleanPath
                        .split("/")
                        .filter { it.isNotEmpty() }

                    for (component in components) {

                        val nextFile = file.findFile(component)

                        if (nextFile == null) {
                            sendError(
                                socket,
                                404,
                                "Not Found"
                            )
                            return
                        }

                        file = nextFile
                    }
                    //meow
                }

                // If URL points to a directory, try index.html.
                if (file.isDirectory) {

                    val index = file.findFile("index.html")

                    if (index != null && index.isFile) {
                        file = index
                    } else {
                        sendDirectoryListing(
                            socket,
                            file,
                            decodedPath
                        )
                        return
                    }
                }

                if (!file.isFile) {
                    sendError(
                        socket,
                        404,
                        "Not Found"
                    )
                    return
                }

                serveFile(
                    socket,
                    file,
                    method == "HEAD"
                )

            } catch (_: Exception) {
                // Client disconnected or malformed request.
            }
        }
    }

    private fun serveFile(
        socket: Socket,
        file: DocumentFile,
        headOnly: Boolean
    ) {

        val contentType = getMimeType(
            file.name ?: ""
        )

        val length = file.length()

        val output = socket.getOutputStream()

        val headers = StringBuilder()

        headers.append("HTTP/1.1 200 OK\r\n")
        headers.append("Content-Type: $contentType\r\n")

        if (length >= 0) {
            headers.append("Content-Length: $length\r\n")
        }

        headers.append("Connection: close\r\n")
        headers.append("Cache-Control: no-cache\r\n")
        headers.append("\r\n")

        output.write(
            headers.toString()
                .toByteArray(StandardCharsets.UTF_8)
        )

        if (headOnly) {
            output.flush()
            return
        }

        val input: InputStream? =
            try {
                contentResolver.openInputStream(
                    file.uri
                )
            } catch (_: Exception) {
                null
            }

        if (input == null) {
            sendError(socket, 500, "Unable to open file")
            return
        }

        input.use { inputStream ->

            val buffer = ByteArray(8192)

            while (true) {

                val count = inputStream.read(buffer)

                if (count <= 0) {
                    break
                }

                output.write( buffer, 0, count)
            }
        }

        output.flush()
    }

    private fun sendDirectoryListing(
        socket: Socket,
        directory: DocumentFile,
        currentPath: String
    ) {

        val html = buildString {

            append("<!DOCTYPE html>")
            append("<html><head>")
            append("<meta charset=\"UTF-8\">")
            append("<title>Directory</title>")
            append("</head><body>")

            append("<h1>Directory: ")
            append(escapeHtml(currentPath))
            append("</h1>")

            append("<ul>")

            directory.listFiles()
                .sortedBy { it.name?.lowercase() }
                .forEach { file ->

                    val name = file.name ?: return@forEach

                    append("<li>")
                    append("<a href=\"")

                    if (currentPath.endsWith("/")) {
                        append(currentPath)
                    } else {
                        append(currentPath)
                        append("/")
                    }

                    append(
                        Uri.encode(name)
                    )

                    if (file.isDirectory) {
                        append("/")
                    }

                    append("\">")

                    append(
                        escapeHtml(name)
                    )

                    append("</a>")
                    append("</li>")
                }

            append("</ul>")
            append("</body></html>")
        }

        val bytes = html.toByteArray(
            StandardCharsets.UTF_8
        )

        val output = socket.getOutputStream()

        val headers =
            "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"

        output.write(
            headers.toByteArray(StandardCharsets.UTF_8)
        )

        output.write(bytes)
        output.flush()
    }

    private fun sendError(
        socket: Socket,
        statusCode: Int,
        message: String
    ) {

        val body = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>$statusCode $message</title>
            </head>
            <body>
                <h1>$statusCode $message</h1>
            </body>
            </html>
        """.trimIndent()

        val bytes = body.toByteArray(
            StandardCharsets.UTF_8
        )

        val output = socket.getOutputStream()

        val headers =
            "HTTP/1.1 $statusCode $message\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"

        output.write(
            headers.toByteArray(StandardCharsets.UTF_8)
        )

        output.write(bytes)
        output.flush()
    }

    private fun getMimeType(
        filename: String
    ): String {

        return when (
            filename.substringAfterLast(
                ".",
                ""
            ).lowercase()
        ) {

            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "pdf" -> "application/pdf"
            "wasm" -> "application/wasm"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"

            else ->
                "application/octet-stream"
        }
    }

    private fun escapeHtml(
        value: String
    ): String {

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            //Small icon Arf
            //.setSmallIcon(R.drawable.ic_menu_upload )
            .setSmallIcon(R.drawable.arf)
            .setContentTitle("Local Web Server")
            .setContentText(status)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(
        status: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            createNotification(status)
        )
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local Web Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Status of the local web server"
            }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopServer()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
