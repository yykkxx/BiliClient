package com.RobinNotBad.BiliClient.util

import com.RobinNotBad.BiliClient.model.DanmakuElem
import com.RobinNotBad.BiliClient.model.DmSegMobileReply

/**
 * Protobuf 解析器（Kotlin 重构版，原 ProtobufParser.java）
 *
 * 用于解析 Bilibili 新版弹幕接口（seg.so）返回的 protobuf 数据。
 *
 * 相比原 Java 实现：
 * 1. 改为下标游标直接读取 ByteArray，不再为每个字段创建 ByteArrayInputStream，
 *    一个视频几千条弹幕时可显著减少对象分配与 GC 压力；
 * 2. 越界/字段异常统一抛 ProtobufException，便于上层识别“这一段数据坏了”并重试，
 *    而不是静默吞掉导致弹幕整段丢失。
 *
 * parseDmSegMobileReply 保持 @JvmStatic，Java 调用方式不变。
 */
object ProtobufParser {

    /** protobuf 数据异常 */
    class ProtobufException(message: String) : java.io.IOException(message)

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED32 = 5

    /** 单个字段长度上限（1MB），防御坏数据导致超大数组分配 */
    private const val MAX_FIELD_LENGTH = 1 shl 20

    /**
     * 解析弹幕分段响应
     *
     * @param data protobuf 格式的字节数据
     * @return 弹幕分段响应对象
     */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun parseDmSegMobileReply(data: ByteArray?): DmSegMobileReply {
        val reply = DmSegMobileReply()
        if (data == null || data.isEmpty()) return reply

        val cursor = Cursor(data)
        while (cursor.hasRemaining()) {
            val tag = cursor.readVarint()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val length = cursor.readVarint()
                val elemData = cursor.readBytes(length)
                reply.elems.add(parseDanmakuElem(elemData))
            } else {
                cursor.skipField(wireType)
            }
        }
        return reply
    }

    /**
     * 安全解析：数据异常时返回 null 而不抛异常，便于上层重试或回退。
     */
    @JvmStatic
    fun parseDmSegMobileReplySafe(data: ByteArray?): DmSegMobileReply? = try {
        parseDmSegMobileReply(data)
    } catch (e: Exception) {
        null
    }

    /**
     * 解析单条弹幕元素
     */
    @JvmStatic
    @Throws(java.io.IOException::class)
    fun parseDanmakuElem(data: ByteArray): DanmakuElem {
        val elem = DanmakuElem()
        val cursor = Cursor(data)

        while (cursor.hasRemaining()) {
            val tag = cursor.readVarint()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> if (wireType == WIRE_VARINT) elem.id = cursor.readVarint64()
                2 -> if (wireType == WIRE_VARINT) elem.progress = cursor.readVarint()
                3 -> if (wireType == WIRE_VARINT) elem.mode = cursor.readVarint()
                4 -> if (wireType == WIRE_VARINT) elem.fontsize = cursor.readVarint()
                5 -> if (wireType == WIRE_VARINT) elem.color = cursor.readVarint()
                6 -> if (wireType == WIRE_LENGTH_DELIMITED) elem.midHash = cursor.readString()
                7 -> if (wireType == WIRE_LENGTH_DELIMITED) elem.content = cursor.readString()
                8 -> if (wireType == WIRE_VARINT) elem.ctime = cursor.readVarint64()
                9 -> if (wireType == WIRE_VARINT) elem.weight = cursor.readVarint()
                10 -> if (wireType == WIRE_LENGTH_DELIMITED) elem.action = cursor.readString()
                11 -> if (wireType == WIRE_VARINT) elem.pool = cursor.readVarint()
                12 -> if (wireType == WIRE_LENGTH_DELIMITED) elem.idStr = cursor.readString()
                13 -> if (wireType == WIRE_VARINT) elem.attr = cursor.readVarint()
                14 -> if (wireType == WIRE_LENGTH_DELIMITED) elem.animation = cursor.readString()
                else -> cursor.skipField(wireType)
            }
        }
        return elem
    }

    /**
     * 字节游标。所有读取都做边界检查，坏数据立即抛异常而不是读到脏值。
     */
    private class Cursor(private val data: ByteArray) {
        private var pos = 0

        fun hasRemaining(): Boolean = pos < data.size

        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (shift >= 32) throw ProtobufException("Varint too long")
                if (pos >= data.size) throw ProtobufException("Unexpected end of stream")
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }

        fun readVarint64(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (shift >= 64) throw ProtobufException("Varint too long")
                if (pos >= data.size) throw ProtobufException("Unexpected end of stream")
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return result
        }

        fun readBytes(length: Int): ByteArray {
            if (length < 0 || length > MAX_FIELD_LENGTH) {
                throw ProtobufException("Illegal field length: $length")
            }
            if (pos + length > data.size) throw ProtobufException("Unexpected end of stream")
            val out = data.copyOfRange(pos, pos + length)
            pos += length
            return out
        }

        fun readString(): String {
            val length = readVarint()
            if (length < 0 || length > MAX_FIELD_LENGTH) {
                throw ProtobufException("Illegal string length: $length")
            }
            if (pos + length > data.size) throw ProtobufException("Unexpected end of stream")
            val s = String(data, pos, length, Charsets.UTF_8)
            pos += length
            return s
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint64()
                WIRE_FIXED64 -> skip(8)
                WIRE_LENGTH_DELIMITED -> skip(readVarint())
                WIRE_FIXED32 -> skip(4)
                else -> throw ProtobufException("Unknown wire type: $wireType")
            }
        }

        private fun skip(count: Int) {
            if (count < 0 || pos + count > data.size) throw ProtobufException("Unexpected end of stream")
            pos += count
        }
    }
}
