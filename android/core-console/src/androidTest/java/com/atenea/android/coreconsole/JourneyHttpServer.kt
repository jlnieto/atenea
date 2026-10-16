package com.atenea.android.coreconsole

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

internal data class JourneyHttpRequest(val method: String, val path: String)
internal data class JourneyHttpResponse(val status: Int, val body: String)

/** Loopback-only fixture; no dependency/toolchain manifest changes and no production server. */
internal class JourneyHttpServer(private val dispatch: (JourneyHttpRequest) -> JourneyHttpResponse) {
    private val listener=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))
    private val threads=Executors.newCachedThreadPool { task -> Thread(task,"atenea-journey-fixture").apply { isDaemon=true } }
    fun url(path: String)="http://127.0.0.1:${listener.localPort}$path"
    fun start() {
        threads.execute {
            while (!listener.isClosed) {
                val connection=try { listener.accept() } catch (_: IOException) { break }
                threads.execute {
                    try { respond(connection) } catch (_: IOException) { connection.close() }
                }
            }
        }
    }
    private fun respond(connection: Socket) {
        connection.use { socket ->
            socket.soTimeout=30_000
            val input=socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request=input.readLine()?.split(' ',limit=3) ?: return
            require(request.size==3)
            val headers=mutableMapOf<String,String>()
            var headerBytes=0
            while (true) {
                val line=input.readLine() ?: return
                if (line.isEmpty()) break
                headerBytes+=line.length;require(headerBytes<=16_384)
                val separator=line.indexOf(':');require(separator>0)
                headers[line.substring(0,separator).lowercase()]=line.substring(separator+1).trim()
            }
            fun discard(length: Int) {
                require(length in 0..65_536)
                repeat(length) { check(input.read()!=-1) }
            }
            if (headers["transfer-encoding"]?.lowercase()=="chunked") {
                while (true) {
                    val size=input.readLine().substringBefore(';').toInt(16)
                    if (size==0) { input.readLine();break }
                    discard(size);check(input.readLine().isEmpty())
                }
            } else discard(headers["content-length"]?.toInt() ?: 0)
            val response=dispatch(JourneyHttpRequest(request[0],request[1]))
            val bytes=response.body.toByteArray(Charsets.UTF_8)
            val output=socket.getOutputStream()
            output.write(("HTTP/1.1 ${response.status} Fixture\r\nContent-Type: application/json; charset=utf-8\r\n"+
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes);output.flush()
        }
    }
    fun shutdown() { listener.close();threads.shutdownNow() }
}
