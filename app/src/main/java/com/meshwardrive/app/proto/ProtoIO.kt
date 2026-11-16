package com.meshwardrive.app.proto

import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * Codificador/decodificador protobuf mínimo (formato binario estándar).
 * Solo implementa lo que necesitamos para hablar con un nodo Meshtastic,
 * así evitamos el plugin de protobuf y la descarga de los .proto.
 */

const val WIRE_VARINT = 0
const val WIRE_FIXED64 = 1
const val WIRE_LEN = 2
const val WIRE_FIXED32 = 5

class ProtoException(msg: String) : IOException(msg)

class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** uint32 / enum. Se omite si es 0 (proto3). */
    fun uint32(field: Int, value: Int): ProtoWriter {
        if (value != 0) {
            tag(field, WIRE_VARINT)
            rawVarint(value.toLong() and 0xFFFFFFFFL)
        }
        return this
    }

    /** int32 con signo (los negativos ocupan 10 bytes, como en protobuf). */
    fun int32(field: Int, value: Int): ProtoWriter {
        if (value != 0) {
            tag(field, WIRE_VARINT)
            rawVarint(value.toLong())
        }
        return this
    }

    fun bool(field: Int, value: Boolean): ProtoWriter {
        if (value) {
            tag(field, WIRE_VARINT)
            rawVarint(1)
        }
        return this
    }

    /** fixed32 / sfixed32, little endian. */
    fun fixed32(field: Int, value: Int): ProtoWriter {
        if (value != 0) {
            tag(field, WIRE_FIXED32)
            out.write(value and 0xFF)
            out.write((value ushr 8) and 0xFF)
            out.write((value ushr 16) and 0xFF)
            out.write((value ushr 24) and 0xFF)
        }
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN)
        rawVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun string(field: Int, value: String): ProtoWriter {
        if (value.isNotEmpty()) bytes(field, value.toByteArray(Charsets.UTF_8))
        return this
    }

    /** Submensaje: se escribe siempre (aunque esté vacío) porque suele ser un oneof. */
    fun message(field: Int, msg: ProtoWriter): ProtoWriter = bytes(field, msg.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()
}

class ProtoReader(private val buf: ByteArray) {
    private var pos = 0

    fun hasMore(): Boolean = pos < buf.size

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= buf.size) throw ProtoException("varint truncado")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoException("varint demasiado largo")
    }

    fun readTag(): Int {
        val t = readVarint().toInt()
        if (t ushr 3 == 0) throw ProtoException("tag inválido")
        return t
    }

    fun readFixed32(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readBytes(): ByteArray {
        val len = readVarint()
        if (len < 0 || len > (buf.size - pos)) throw ProtoException("longitud inválida")
        val out = buf.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    fun skip(wire: Int) {
        when (wire) {
            WIRE_VARINT -> readVarint()
            WIRE_FIXED64 -> { need(8); pos += 8 }
            WIRE_LEN -> readBytes()
            WIRE_FIXED32 -> { need(4); pos += 4 }
            else -> throw ProtoException("wire type $wire no soportado")
        }
    }

    private fun need(n: Int) {
        if (buf.size - pos < n) throw ProtoException("mensaje truncado")
    }

    // Lectores tolerantes: si el wire type no coincide, se salta el campo.
    fun vint(wire: Int): Long = if (wire == WIRE_VARINT) readVarint() else { skip(wire); 0L }
    fun fx32(wire: Int): Int = if (wire == WIRE_FIXED32) readFixed32() else { skip(wire); 0 }
    fun float(wire: Int): Float = Float.fromBits(fx32(wire))
    fun len(wire: Int): ByteArray? = if (wire == WIRE_LEN) readBytes() else { skip(wire); null }
    fun str(wire: Int): String? = len(wire)?.toString(Charsets.UTF_8)

    inline fun forEachField(block: (field: Int, wire: Int) -> Unit) {
        while (hasMore()) {
            val t = readTag()
            block(t ushr 3, t and 7)
        }
    }
}
