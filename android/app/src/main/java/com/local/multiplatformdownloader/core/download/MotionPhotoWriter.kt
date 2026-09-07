package com.local.multiplatformdownloader.core.download

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/** Writes a Google Motion Photo container without transcoding either source. */
internal object MotionPhotoWriter {
    fun compose(image: File, video: File, output: File) {
        require(image.isFile && image.length() >= 4) { "实况照片的静态图片不存在" }
        require(video.isFile && video.length() >= 8) { "实况照片的动态视频不存在" }
        require(isJpeg(image)) { "Motion Photo 只能封装 JPEG 静态图片" }
        require(isMp4(video)) { "Motion Photo 只能封装 MP4 动态视频" }
        require(image.canonicalPath != output.canonicalPath) { "合成文件不能覆盖原始图片" }
        require(video.canonicalPath != output.canonicalPath) { "合成文件不能覆盖原始视频" }

        output.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "无法创建 Motion Photo 缓存目录" }
        }
        val xmp = xmpPacket(video.length())
        val payload = XMP_HEADER + xmp.toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 65_535) { "Motion Photo 元数据过大" }

        val temp = File(output.parentFile, "${output.name}.part")
        temp.delete()
        try {
            BufferedOutputStream(FileOutputStream(temp)).use { destination ->
                destination.write(0xFF)
                destination.write(0xD8)
                destination.write(0xFF)
                destination.write(0xE1)
                val segmentLength = payload.size + 2
                destination.write((segmentLength ushr 8) and 0xFF)
                destination.write(segmentLength and 0xFF)
                destination.write(payload)

                BufferedInputStream(image.inputStream()).use { source ->
                    check(source.read() == 0xFF && source.read() == 0xD8) { "JPEG 文件头无效" }
                    source.copyTo(destination)
                }
                BufferedInputStream(video.inputStream()).use { it.copyTo(destination) }
            }
            output.delete()
            check(temp.renameTo(output)) { "无法完成 Motion Photo 原子写入" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }
    }

    private fun isJpeg(file: File): Boolean = file.inputStream().use { input ->
        input.read() == 0xFF && input.read() == 0xD8
    }

    private fun isMp4(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(12)
        val count = input.read(header)
        count >= 8 && header.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))
    }

    private fun xmpPacket(videoLength: Long): String =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
 GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
 GCamera:MotionPhotoPresentationTimestampUs="-1"
 GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
 GCamera:MicroVideoOffset="$videoLength"/>
<rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/"
 xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
<Container:Directory><rdf:Seq>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
<rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li>
</rdf:Seq></Container:Directory>
</rdf:Description></rdf:RDF></x:xmpmeta>""".trimIndent()

    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
}
