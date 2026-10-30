package tech.devline.scropy_ui.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AdbReliabilityTest {
    private fun stream() = AdbStream(1).apply {
        onCloseCallback = {}
        sendOkay = {}
        writeToDevice = {}
    }

    @Test(timeout = 2000) fun timeoutClosesABlockedStreamAndReportsFailure() {
        val stream = stream()
        try {
            withAdbDeadline(50, { stream.close() }) { stream.inputStream.read() }
            fail("Must time out, not report EOF as success")
        } catch (_: SocketTimeoutException) { assertTrue(stream.isClosed()) }
    }

    @Test fun aCompletedOperationDoesNotCloseItsResourceLater() {
        val aborted = CountDownLatch(1)
        assertEquals("OK", withAdbDeadline(50, { aborted.countDown() }) { "OK" })
        assertFalse(aborted.await(100, TimeUnit.MILLISECONDS))
    }

    @Test fun aNormalFailurePreservesItsOriginalReason() {
        val error = IOException("Permission denied")
        try { withAdbDeadline(1000, {}) { throw error }; fail("Must fail") }
        catch (e: IOException) { assertSame(error, e) }
    }

    @Test(timeout = 2000) fun remoteClosePreservesFinalOutputAndRepeatedEofDoesNotBlock() {
        val stream = stream()
        stream.onData("OK".toByteArray())
        stream.onClose()
        assertEquals('O'.code, stream.inputStream.read())
        assertEquals('K'.code, stream.inputStream.read())
        assertEquals(-1, stream.inputStream.read())
        assertEquals(-1, stream.inputStream.read())
    }

    @Test(timeout = 2000) fun localCloseUnblocksAnOpenAcknowledgementWait() {
        val stream = stream()
        val waiting = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val thread = Thread {
            waiting.countDown()
            try { stream.waitReady(10_000); fail("Closed stream must fail") }
            catch (_: IOException) { ended.countDown() }
        }
        thread.start()
        waiting.await()
        stream.close()
        assertTrue(ended.await(1, TimeUnit.SECONDS))
        thread.join()
    }

    @Test fun verifiesAndTrimsSerialOnEveryConnection() {
        assertEquals("AURA-1", checkedDeviceSerial("AURA-1\n", "AURA-1"))
        assertEquals("AURA-2", checkedDeviceSerial("AURA-2", null))
    }

    @Test(expected = DeviceIdentityException::class)
    fun rejectsAnotherMachineAtTheSameIp() { checkedDeviceSerial("AURA-2", "AURA-1") }

    @Test(expected = DeviceIdentityException::class)
    fun rejectsAnUnverifiableSerial() { checkedDeviceSerial(" \n", null) }

    @Test fun migratesUsbSavedDevicesWithoutLosingTheirOriginalSerial() {
        assertEquals("AURA-1", savedDeviceSerial(null, "usb-wifi-AURA-1"))
        assertEquals("AURA-2", savedDeviceSerial("AURA-2", "usb-wifi-AURA-1"))
        assertNull(savedDeviceSerial(null, "legacy-manual-device"))
    }

    @Test(expected = DeviceIdentityException::class)
    fun legacyManualEntriesRequireExplicitRegistrationInsteadOfSilentlyBindingAnotherAura() {
        requireSavedSerial(null)
    }

    @Test fun connectionCloseNotifiesExactlyOnceAndLateSubscribersSeeTheLoss() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val transport = object : AdbTransport {
            override fun read(len: Int): ByteArray? = null
            override fun write(data: ByteArray) = true
            override fun shutdown() = Unit
            override fun close() = Unit
        }
        val connection = AdbConnection(transport, keys.private, byteArrayOf())
        var callbacks = 0
        connection.setDisconnectListener { callbacks++ }
        connection.close(); connection.close()
        assertEquals(1, callbacks)
        assertFalse(connection.isAlive())
        connection.setDisconnectListener { callbacks++ }
        assertEquals(2, callbacks)
    }

    @Test fun intentionalCleanupCanDetachTheDisconnectCallback() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val connection = AdbConnection(object : AdbTransport {
            override fun read(len: Int): ByteArray? = null
            override fun write(data: ByteArray) = true
            override fun shutdown() = Unit
            override fun close() = Unit
        }, keys.private, byteArrayOf())
        var callbacks = 0
        connection.setDisconnectListener { callbacks++ }
        connection.setDisconnectListener(null)
        connection.close()
        assertEquals(0, callbacks)
    }

    @Test(timeout = 2000) fun readerEofNotifiesTheUiWithoutWaitingForAnotherButtonPress() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val eof = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = AdbConnection(object : AdbTransport {
            override fun read(len: Int): ByteArray? { eof.await(); return null }
            override fun write(data: ByteArray) = true
            override fun shutdown() { eof.countDown() }
            override fun close() = Unit
        }, keys.private, byteArrayOf())
        connection.setDisconnectListener { disconnected.countDown() }
        val readLoop = AdbConnection::class.java.getDeclaredMethod("readLoop").apply { isAccessible = true }
        val reader = Thread { readLoop.invoke(connection) }
        reader.start()
        eof.countDown()
        assertTrue(disconnected.await(1, TimeUnit.SECONDS))
        assertFalse(connection.isAlive())
        reader.join()
    }
}
