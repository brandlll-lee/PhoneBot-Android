package dev.phonebot.android.phone.adb

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class AdbProtocolTest {
    @Test fun pairingMatchesDeviceProtocolVector() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val alice = PairingAuth.client(hex("32363532333543c733885a3d925d056813df66ca344f584bfd964293f3df5a0b9167052474dde398d060254abcf40d4839936842d7c75fb5540a9107b7d9f04540dde3d2ebc2"),
            hex("ae27b884a0b2f1809bb7f294f5ab3d5287be454dc3b45a0547c5b32645f0d33a3118ef09d0e950cf9a9afbb627a5ec990ab98c2e02d63ca5ad15a7225346fcc8"))
        assertArrayEquals(hex("507f1708d35b116d592f2f9b20613cbd0bad6ba31057e90f648cd1a484bb6f33"), alice.message)
        alice.initCipher(hex("98848b90a5e5b6da50385722057ab92674dcea0a616af47f5aedf1f7450fe3cb"))
        assertArrayEquals(hex("99add9c6d77865c60c8affc3e635d8fcbb7fae6b1fe6cca32c9a58fb1cd2d2b77a0466f1cb050db0a8f42302fce2d052b632db7366"),
            alice.encrypt(hex("f9086b7b081ca7800d42e4f26954c59008c849903dd990c6187241da1bf14f5d1f13e150b4")))
    }

    @Test fun packetsCheckLengthMagicAndChecksum() {
        val packet = AdbMessage(AdbMessage.WRTE, 3, 7, "中文".toByteArray()).encode()
        val decoded = AdbMessage.read(DataInputStream(ByteArrayInputStream(packet)), verifyChecksum = true)!!
        assertEquals(3, decoded.arg0)
        assertArrayEquals("中文".toByteArray(), decoded.data)
        val broken = packet.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(java.io.IOException::class.java) { AdbMessage.read(DataInputStream(ByteArrayInputStream(broken)), verifyChecksum = true) }
        assertThrows(java.io.IOException::class.java) { AdbMessage.read(DataInputStream(ByteArrayInputStream(packet)), maxData = 1) }
    }

    @Test fun pairingKeysAgreeAndWrongCodeCannotDecrypt() {
        fun exchange(password: String): Pair<ByteArray, ByteArray> {
            val alice = Spake2(Spake2.Role.ALICE, "client".toByteArray(), "server".toByteArray())
            val bob = Spake2(Spake2.Role.BOB, "server".toByteArray(), "client".toByteArray())
            val a = alice.generateMessage("123456".toByteArray())
            val b = bob.generateMessage(password.toByteArray())
            return alice.processMessage(b) to bob.processMessage(a)
        }
        val (a, b) = exchange("123456")
        assertArrayEquals(a, b)
        val plain = "paired host key".toByteArray()
        assertArrayEquals(plain, PairingCipher(b).decrypt(PairingCipher(a).encrypt(plain)))
        val wrong = exchange("654321")
        assertThrows(javax.crypto.AEADBadTagException::class.java) { PairingCipher(wrong.second).decrypt(PairingCipher(wrong.first).encrypt(plain)) }
    }

    @Test fun identityPersistsAsCiphertextAndCertificateMatchesKey() {
        val directory = Files.createTempDirectory(Files.createDirectories(java.nio.file.Path.of("build", "adb-tests")), "identity-").toFile()
        try {
            val file = java.io.File(directory, "identity.bin")
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val store = WrappedKeyFile(file) { key }
            val first = AdbIdentity.loadOrCreate(store, "phonebot@test")
            val second = AdbIdentity.loadOrCreate(store, "phonebot@test")
            assertArrayEquals(first.keyPair.public.encoded, second.keyPair.public.encoded)
            assertFalse(file.readBytes().contentEquals(first.keyPair.private.encoded))
            first.certificate.verify(first.keyPair.public)
            store.clear()
            assertNull(store.read())
        } finally { directory.deleteRecursively() }
    }

    @Test fun closingSocketCancelsConnectionAndPairingHandshakes() {
        val tls = AdbTls(AdbIdentity.generate("phonebot@test"), KeyingMaterialExporter { _, _, _ -> error("handshake incomplete") })
        for (pairing in listOf(false, true)) ServerSocket(0).use { server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val socket = AtomicReference<Socket>()
            val peer = thread {
                server.accept().use { accepted.countDown(); release.await(10, TimeUnit.SECONDS) }
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<Boolean> {
                    try {
                        if (pairing) AdbPairing(tls).pair("127.0.0.1", server.localPort, "123456", onSocket = socket::set)
                        else AdbConnection.open("127.0.0.1", server.localPort, tls, onSocket = socket::set).close()
                        false
                    } catch (_: java.io.IOException) { true }
                }
                assertTrue(accepted.await(5, TimeUnit.SECONDS))
                socket.get().close()
                assertTrue(result.get(2, TimeUnit.SECONDS))
            } finally { release.countDown(); worker.shutdownNow(); peer.join(2000) }
        }
    }

    @Test fun wirelessConnectionRequiresTls() {
        val tls = AdbTls(AdbIdentity.generate("phonebot@test"), KeyingMaterialExporter { _, _, _ -> error("TLS required") })
        ServerSocket(0).use { server ->
            val peer = thread {
                server.accept().use {
                    AdbMessage.read(DataInputStream(it.inputStream))
                    it.outputStream.write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, 4096, "device::\u0000".toByteArray()).encode())
                }
            }
            val failure = assertThrows(AdbConnectionException::class.java) { AdbConnection.open("127.0.0.1", server.localPort, tls) }
            assertEquals(AdbConnectionException.Reason.TLS_FAILED, failure.reason)
            peer.join(2000)
        }
    }
}
