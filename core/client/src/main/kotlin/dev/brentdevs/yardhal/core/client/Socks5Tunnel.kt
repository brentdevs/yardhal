package dev.brentdevs.yardhal.core.client

import java.io.IOException
import java.io.InputStream
import java.net.IDN
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

public data class Socks5Config(
    public val host: String,
    public val port: Int,
    public val username: String? = null,
    public val password: String? = null,
) {
    override fun toString(): String =
        "Socks5Config(host=$host, port=$port, authenticated=${username != null || password != null})"
}

public class Socks5Exception(
    public val authenticationRejected: Boolean,
    message: String,
) : IOException(message)

public object Socks5Tunnel {
    public fun connect(
        destinationHost: String,
        destinationPort: Int,
        proxy: Socks5Config,
        timeoutMillis: Int,
    ): Socket {
        require(timeoutMillis > 0) { "SOCKS5 timeout must be positive" }
        require(proxy.host.isNotBlank()) { "SOCKS5 proxy host must not be blank" }
        require(proxy.port in 1..65535) { "SOCKS5 proxy port is out of range" }
        require(destinationPort in 1..65535) { "SOCKS5 destination port is out of range" }
        val destination = destinationName(destinationHost)
        val authenticated = proxy.username != null || proxy.password != null
        val username = proxy.username?.toByteArray(Charsets.UTF_8)
        val password = proxy.password?.toByteArray(Charsets.UTF_8)
        try {
            if (authenticated && (username == null || password == null || username.size !in 1..255 || password.size !in 1..255)) {
                throw Socks5Exception(true, "SOCKS5 authentication requires a username and password of 1 to 255 bytes each")
            }
            val deadlineNanos = System.nanoTime() + timeoutMillis.toLong() * 1_000_000
            val socket = Socket()
            var connected = false
            try {
                val endpoint = InetSocketAddress(proxy.host, proxy.port)
                socket.connect(endpoint, remainingMillis(deadlineNanos))
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val reply = ByteArray(255)
                val method = if (authenticated) 2 else 0
                output.write(byteArrayOf(5, 1, method.toByte()))
                output.flush()
                readExactly(socket, input, reply, 2, deadlineNanos)
                if (reply[0].toInt() != 5) {
                    throw Socks5Exception(false, "SOCKS5 proxy returned an invalid negotiation version")
                }
                if (reply[1].toInt() != method) {
                    throw Socks5Exception(
                        authenticated,
                        if (authenticated) "SOCKS5 proxy did not accept the required username/password authentication"
                        else "SOCKS5 proxy did not accept anonymous authentication",
                    )
                }
                if (username != null && password != null) {
                    val request = ByteArray(3 + username.size + password.size)
                    request[0] = 1
                    request[1] = username.size.toByte()
                    username.copyInto(request, 2)
                    request[2 + username.size] = password.size.toByte()
                    password.copyInto(request, 3 + username.size)
                    try {
                        output.write(request)
                        output.flush()
                    } finally {
                        request.fill(0)
                    }
                    readExactly(socket, input, reply, 2, deadlineNanos)
                    if (reply[0].toInt() != 1) {
                        throw Socks5Exception(true, "SOCKS5 proxy returned an invalid authentication version")
                    }
                    if (reply[1].toInt() != 0) {
                        throw Socks5Exception(true, "SOCKS5 proxy rejected the username/password credentials")
                    }
                }
                val request = ByteArray(7 + destination.length)
                request[0] = 5
                request[1] = 1
                request[3] = 3
                request[4] = destination.length.toByte()
                for (index in destination.indices) {
                    request[5 + index] = destination[index].code.toByte()
                }
                request[5 + destination.length] = (destinationPort ushr 8).toByte()
                request[6 + destination.length] = destinationPort.toByte()
                output.write(request)
                output.flush()
                readExactly(socket, input, reply, 4, deadlineNanos)
                if (reply[0].toInt() != 5) {
                    throw Socks5Exception(false, "SOCKS5 proxy returned an invalid connection reply version")
                }
                if (reply[2].toInt() != 0) {
                    throw Socks5Exception(false, "SOCKS5 proxy returned an invalid reserved reply field")
                }
                val result = reply[1].toInt() and 0xff
                val addressLength = when (reply[3].toInt() and 0xff) {
                    1 -> 4
                    3 -> {
                        readExactly(socket, input, reply, 1, deadlineNanos)
                        val length = reply[0].toInt() and 0xff
                        if (length == 0) {
                            throw Socks5Exception(false, "SOCKS5 proxy returned an empty bound hostname")
                        }
                        length
                    }
                    4 -> 16
                    else -> throw Socks5Exception(false, "SOCKS5 proxy returned an unsupported reply address type")
                }
                readExactly(socket, input, reply, addressLength, deadlineNanos)
                readExactly(socket, input, reply, 2, deadlineNanos)
                if (result != 0) {
                    throw Socks5Exception(false, replyFailure(result))
                }
                remainingMillis(deadlineNanos)
                socket.soTimeout = 0
                connected = true
                return socket
            } finally {
                if (!connected) {
                    runCatching { socket.close() }
                }
            }
        } finally {
            username?.fill(0)
            password?.fill(0)
        }
    }

    private fun destinationName(host: String): String {
        if (host.isBlank() || host.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }) {
            throw Socks5Exception(false, "SOCKS5 destination hostname is invalid")
        }
        val ascii = try {
            IDN.toASCII(host)
        } catch (_: IllegalArgumentException) {
            throw Socks5Exception(false, "SOCKS5 destination hostname is invalid")
        }
        if (ascii.length !in 1..255 || ascii.any { it.code > 0x7f }) {
            throw Socks5Exception(false, "SOCKS5 destination hostname must be 1 to 255 bytes")
        }
        return ascii
    }

    private fun remainingMillis(deadlineNanos: Long): Int {
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) {
            throw SocketTimeoutException("SOCKS5 proxy negotiation timed out")
        }
        return ((remainingNanos + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readExactly(
        socket: Socket,
        input: InputStream,
        buffer: ByteArray,
        length: Int,
        deadlineNanos: Long,
    ) {
        var offset = 0
        while (offset < length) {
            socket.soTimeout = remainingMillis(deadlineNanos)
            val count = input.read(buffer, offset, length - offset)
            if (count < 0) {
                throw Socks5Exception(false, "SOCKS5 proxy closed the connection during negotiation")
            }
            offset += count
        }
    }

    private fun replyFailure(result: Int): String = when (result) {
        1 -> "SOCKS5 proxy reported a general failure"
        2 -> "SOCKS5 proxy rules do not allow this connection"
        3 -> "SOCKS5 proxy could not reach the destination network"
        4 -> "SOCKS5 proxy could not reach the destination host"
        5 -> "SOCKS5 destination refused the connection"
        6 -> "SOCKS5 connection time to live expired"
        7 -> "SOCKS5 proxy does not support the CONNECT command"
        8 -> "SOCKS5 proxy does not support the destination address type"
        else -> "SOCKS5 proxy returned an unknown connection error"
    }
}
