package dev.codexpocket.wire

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private fun waitFor(why: String, done: () -> Boolean) {
    val until = System.nanoTime() + 15_000_000_000L
    while (!done()) { check(System.nanoTime() < until) { why }; Thread.sleep(1) }
}
private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray().toString(Charsets.UTF_8)

/** Same production Kotlin source/JNI. Host fixture only, never an Android E2E claim. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "stdio") { stdioPeer(); return }
    val caller = Thread.currentThread()
    val signals = ArrayBlockingQueue<Pair<Int, DataChannelSignal>>(256)
    val failure = AtomicReference<Throwable?>()
    val opens = AtomicInteger()
    val ends = AtomicInteger()
    val offsets = arrayOf(AtomicLong(), AtomicLong())
    val holdListener = AtomicBoolean(false)
    val listenerEntered = CountDownLatch(1)
    val releaseListener = CountDownLatch(1)
    val transports = ArrayList<DataChannelTransport>()
    fun listener(index: Int) = object : TransportListener {
        override fun onOpen() { check(Thread.currentThread() !== caller); opens.incrementAndGet() }
        override fun onBinary(bytes: ByteArray) {
            check(Thread.currentThread() !== caller)
            if (index == 1 && holdListener.compareAndSet(true,false)) {
                listenerEntered.countDown()
                check(releaseListener.await(5,TimeUnit.SECONDS))
            }
            val offset = offsets[index].get()
            bytes.forEachIndexed { i, byte -> check((byte.toInt() and 255) == ((offset+i)%251).toInt()) }
            offsets[index].addAndGet(bytes.size.toLong())
        }
        override fun onFailure() { ends.incrementAndGet() }
    }
    repeat(2) { index ->
        transports += DataChannelTransport(77, listener(index), {
            check(Thread.currentThread() !== caller); check(signals.offer(index to it))
        }, bindAddress="127.0.0.1")
    }
    val controller = Thread({
        try {
            while (!Thread.currentThread().isInterrupted) {
                val (from, signal) = signals.take()
                val to = transports[1-from]
                check(when (signal) {
                    is DataChannelSignal.Description -> to.remoteDescription(signal.generation, signal.sdp, signal.type)
                    is DataChannelSignal.Candidate -> to.remoteCandidate(signal.generation, signal.candidate, signal.mid)
                })
            }
        } catch (_: InterruptedException) { } catch (t: Throwable) { failure.set(t) }
    }, "fixture-signaling").apply { isDaemon=true;start() }
    check(!transports[0].remoteCandidate(76,"invalid","0"))
    check(transports[1].start(false)); check(transports[0].start(true))
    waitFor("Kotlin open") { failure.get()?.let { throw it }; opens.get()==2 }
    holdListener.set(true)
    check(transports[0].send(ByteArray(128) { (it%251).toByte() }))
    check(listenerEntered.await(2,TimeUnit.SECONDS))
    check(transports[1].send(ByteArray(257) { (it%251).toByte() }))
    val reverseDeadline = System.nanoTime()+1_000_000_000L
    while(offsets[0].get()!=257L && System.nanoTime()<reverseDeadline) Thread.sleep(1)
    check(offsets[0].get()==257L) { "A blocked listener stalled the native sender" }
    releaseListener.countDown()
    waitFor("listener released") { offsets[1].get()==128L }
    fun transfer(from: Int, total: Int) {
        var sent=offsets[1-from].get().toInt()
        while(sent<total) {
            val n=minOf(65536,total-sent)
            val bytes=ByteArray(n) { ((sent+it)%251).toByte() }
            waitFor("Kotlin send") { failure.get()?.let { throw it }; transports[from].send(bytes) }
            // Verify send takes ownership by copying, not by retaining the caller's array.
            bytes.fill(99);sent+=n
            waitFor("Kotlin receive") { offsets[1-from].get()==sent.toLong() }
        }
    }
    transfer(0,2*1024*1024+317);transfer(1,2*1024*1024+719)
    transports[0].close(true)
    waitFor("Kotlin remote close") { ends.get()==1 }
    transports[1].close(false);controller.interrupt();controller.join()
    val preopen=DataChannelTransport(78,listener(0),{},bindAddress="127.0.0.1")
    repeat(16) { check(preopen.send(ByteArray(65536))) }
    check(!preopen.send(byteArrayOf(1)));check(!preopen.send(ByteArray(65537)))
    preopen.close(false);check(!preopen.send(byteArrayOf(1)))
    val errors=AtomicInteger()
    val bad=DataChannelTransport(79,object:TransportListener {
        override fun onOpen() { error("fixture listener throws") }
        override fun onBinary(bytes:ByteArray) { error("unexpected") }
        override fun onFailure() { errors.incrementAndGet() }
    },{},bindAddress="127.0.0.1")
    check(bad.start(false));check(bad.remoteDescription(79,"invalid-sdp","offer"))
    waitFor("Kotlin native failure") { errors.get()==1 };bad.close(false)
    (transports + listOf(preopen,bad)).forEach { check(it.awaitReleased(5000)) { "JNI teardown" } }
    println("{\"fixture\":\"kotlin_host_jni_two_peers\",\"success\":true,\"bytesForward\":2097469,\"bytesReverse\":2097871,\"phoneE2EVerified\":false,\"pocketTlsVerified\":false}")
}

private fun stdioPeer() {
    val count=AtomicLong()
    lateinit var transport:DataChannelTransport
    transport=DataChannelTransport(88,object:TransportListener {
        override fun onOpen() {}
        override fun onBinary(bytes:ByteArray) {
            val offset=count.get()
            bytes.forEachIndexed { i, byte -> check((byte.toInt() and 255)==((offset+i)%251).toInt()) }
            // Mixed fixture allows only one <=64 KiB block outstanding, so echo cannot fill TX.
            check(transport.send(bytes));count.addAndGet(bytes.size.toLong())
        }
        override fun onFailure() {}
    },{ signal ->
        when(signal) {
            is DataChannelSignal.Description -> println("D ${hex(signal.sdp.toByteArray())} ${hex(signal.type.toByteArray())}")
            is DataChannelSignal.Candidate -> println("C ${hex(signal.candidate.toByteArray())} ${hex(signal.mid.toByteArray())}")
        }
    },bindAddress="127.0.0.1")
    check(transport.start(false));println("READY")
    while(true) {
        val line=readlnOrNull() ?: break
        if(line=="QUIT")break
        val parts=line.split(' ')
        check(parts.size==3)
        check(when(parts[0]) {
            "D" -> transport.remoteDescription(88,unhex(parts[1]),unhex(parts[2]))
            "C" -> transport.remoteCandidate(88,unhex(parts[1]),unhex(parts[2]))
            else -> false
        })
    }
    transport.close(false)
    check(transport.awaitReleased(5000)) { "stdio JNI teardown" }
}
